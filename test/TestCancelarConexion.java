import com.beanit.iec61850bean.*;
import com.iednavigator.IEC61850Client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cancelar una conexión en curso (botón Cancelar de la barra de contexto).
 *
 * La asociación y la lectura del modelo de iec61850bean son bloqueantes; se cortan cerrando el
 * socket desde otro hilo (IEC61850Client.cancelarConexion). Casos:
 *   1. Un equipo que acepta TCP y nunca contesta: se cancela durante la asociación.
 *   2. Un proxy que deja pasar la asociación y después se traga todo: se cancela en plena
 *      lectura del modelo, sin que connect() intente las rutas de respaldo.
 *   3. Sin cancelar, el mismo equipo mudo termina por timeout y no figura como cancelado.
 *   4. Después de cancelar, una conexión normal funciona (modelo, lectura, keep-alive).
 *   5. No quedan hilos colgados en associate() ni en retrieveModel().
 *
 * Uso: java -cp "classes;lib\*;test" TestCancelarConexion
 */
public class TestCancelarConexion {
    static int ok = 0, mal = 0;
    static void chk(String que, boolean c) {
        System.out.println((c ? "OK    " : "FALLA ") + que);
        if (c) ok++; else mal++;
    }

    public static void main(String[] a) throws Exception {
        int puertoServidor = 23102, puertoMudo = 23103, puertoProxy = 23104;
        List<ServerModel> models = SclParser.parse("test/test_bay_control.cid");
        ServerSap sap = new ServerSap(puertoServidor, 0, null, models.get(0), null);
        sap.startListening(new ServerEventListener() {
            public List<ServiceError> write(List<BasicDataAttribute> bdas) { return null; }
            public void serverStoppedListening(ServerSap s) { }
        });

        // 1. Equipo mudo: acepta y nunca contesta
        ServerSocket mudo = new ServerSocket(puertoMudo);
        Thread aceptador = new Thread(() -> {
            try { while (true) mudo.accept(); } catch (IOException fin) { }
        });
        aceptador.setDaemon(true);
        aceptador.start();

        IEC61850Client c1 = new IEC61850Client();
        c1.setConnectionTimeoutMs(15000);
        long t1 = cancelarA(c1, puertoMudo, 500, null);
        chk("1. equipo mudo: cancelado en " + t1 + " ms (timeout 15 s)", t1 >= 0 && t1 < 3000);
        chk("1. figura como cancelada", c1.fueCancelada());

        // 2. Proxy que pasa la asociación (COTP CC y AARE) y después se traga todo
        Tragador proxy = new Tragador(puertoProxy, puertoServidor, 2);   // COTP CC y AARE
        proxy.start();
        Thread.sleep(200);
        IEC61850Client c2 = new IEC61850Client();
        c2.setConnectionTimeoutMs(15000);
        AtomicBoolean asociado = new AtomicBoolean();
        c2.setAlAsociar(() -> asociado.set(true));
        long t2 = cancelarA(c2, puertoProxy, 800, asociado);
        chk("2. avisó que empezaba a leer el modelo", asociado.get());
        chk("2. cancelado en plena lectura del modelo en " + t2 + " ms", t2 >= 0 && t2 < 3000);
        chk("2. figura como cancelada", c2.fueCancelada());
        chk("2. sin modelo a medias", c2.getServerModel() == null);
        proxy.cerrar();

        // 3. Sin cancelar: timeout normal
        IEC61850Client c3 = new IEC61850Client();
        c3.setConnectionTimeoutMs(1500);
        long ini = System.currentTimeMillis();
        String err = null;
        try { c3.connect("127.0.0.1", puertoMudo); } catch (IOException e) { err = e.getMessage(); }
        long t3 = System.currentTimeMillis() - ini;
        chk("3. sin cancelar termina por timeout en " + t3 + " ms: " + err, err != null && !err.equals("CANCELADA"));
        chk("3. no figura como cancelada", !c3.fueCancelada());

        // 4. Después de cancelar, el mismo cliente se conecta normalmente
        boolean conectado = c1.connect("127.0.0.1", puertoServidor);
        chk("4. conecta después de cancelar", conectado && c1.isConnected() && c1.getServerModel() != null);
        chk("4. y ya no figura como cancelada", !c1.fueCancelada());
        c1.disconnect();

        // 5. Hilos colgados
        Thread.sleep(1500);
        int colgados = 0;
        for (java.util.Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            for (StackTraceElement f : e.getValue()) {
                String m = f.getClassName() + "." + f.getMethodName();
                if (m.contains("ClientSap.associate") || m.contains("ClientAssociation.retrieveModel")) {
                    colgados++;
                    System.out.println("   colgado: " + e.getKey().getName() + " en " + m);
                    break;
                }
            }
        }
        chk("5. sin hilos colgados en associate/retrieveModel", colgados == 0);

        mudo.close();
        sap.stop();
        System.out.println(ok + " pasaron, " + mal + " fallaron");
        if (mal == 0) System.out.println("TODO OK");
        System.exit(mal == 0 ? 0 : 1);
    }

    /** Conecta en otro hilo y cancela a los {@code ms} (o cuando {@code esperar} se pone en true). */
    static long cancelarA(IEC61850Client c, int puerto, int ms, AtomicBoolean esperar) throws Exception {
        AtomicInteger resultado = new AtomicInteger(0);   // 1 cancelada, 2 otro error, 3 conectó
        long[] fin = {0};
        Thread t = new Thread(() -> {
            try {
                c.connect("127.0.0.1", puerto);
                resultado.set(3);
            } catch (IOException e) {
                resultado.set("CANCELADA".equals(e.getMessage()) ? 1 : 2);
                if (resultado.get() == 2) System.out.println("   error: " + e.getMessage());
            }
            fin[0] = System.currentTimeMillis();
        });
        t.start();
        if (esperar != null) {
            long lim = System.currentTimeMillis() + 5000;
            while (!esperar.get() && System.currentTimeMillis() < lim) Thread.sleep(20);
        }
        Thread.sleep(ms);
        long ini = System.currentTimeMillis();
        c.cancelarConexion();
        t.join(10000);
        if (resultado.get() != 1) {
            System.out.println("   resultado " + resultado.get() + " (1 = cancelada)");
            return -1;
        }
        return fin[0] - ini;
    }

    /** Reenvía en los dos sentidos; a partir de la respuesta número {@code pasan}+1 se traga todo. */
    static class Tragador extends Thread {
        final int escuchaPuerto, destino, pasan;
        volatile ServerSocket escucha;
        volatile Socket entrada, salida;
        Tragador(int escuchaPuerto, int destino, int pasan) {
            this.escuchaPuerto = escuchaPuerto; this.destino = destino; this.pasan = pasan;
            setDaemon(true);
        }
        void cerrar() {
            try { if (entrada != null) entrada.close(); } catch (IOException ignorada) { }
            try { if (salida != null) salida.close(); } catch (IOException ignorada) { }
            try { if (escucha != null) escucha.close(); } catch (IOException ignorada) { }
        }
        @Override public void run() {
            try {
                escucha = new ServerSocket(escuchaPuerto);
                entrada = escucha.accept();
                salida = new Socket("127.0.0.1", destino);
                bombear(entrada, salida, Integer.MAX_VALUE);
                bombear(salida, entrada, pasan);
            } catch (IOException fin) { }
        }
        void bombear(Socket de, Socket a, int limite) {
            Thread t = new Thread(() -> {
                byte[] buf = new byte[8192];
                int n, vistos = 0;
                try {
                    InputStream in = de.getInputStream();
                    OutputStream out = a.getOutputStream();
                    while ((n = in.read(buf)) != -1) {
                        if (++vistos > limite) continue;   // se traga, sin cerrar nada
                        out.write(buf, 0, n);
                        out.flush();
                    }
                } catch (IOException fin) { }
            });
            t.setDaemon(true);
            t.start();
        }
    }
}
