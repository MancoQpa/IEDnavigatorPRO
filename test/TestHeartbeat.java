import com.beanit.iec61850bean.*;
import com.iednavigator.IEC61850Client;

import java.io.*;
import java.net.*;
import java.util.List;
import java.util.concurrent.atomic.*;

/**
 * Reproduce la caída silenciosa de un IED y comprueba que el latido la detecte.
 *
 * El caso de campo: el equipo desaparece SIN cerrar la asociación —reinicio, cable,
 * pérdida de camino—, así que no llega ni FIN ni RST y el hilo lector de la librería se
 * queda esperando. Si además el polling no está leyendo nada, nada toca el socket y la
 * interfaz sigue mostrando "Conectado" indefinidamente.
 *
 * Para reproducirlo se interpone un proxy TCP que, a una orden, DEJA DE REENVIAR sin
 * cerrar ninguno de los dos sockets. Cerrarlos sería el caso fácil: la librería se entera
 * sola. Lo que interesa es el caso en que no se entera nadie.
 *
 * Uso:  java -cp classes;lib\*;test TestHeartbeat
 */
public class TestHeartbeat {

    private static int fallas = 0;

    static void chk(String titulo, boolean ok, String detalle) {
        if (!ok) fallas++;
        System.out.printf("%-52s %-28s %s%n", titulo, detalle, ok ? "OK" : "<<< FALLA");
    }

    public static void main(String[] args) throws Exception {
        int puertoServidor = 10302, puertoProxy = 10303;

        List<ServerModel> models = SclParser.parse("test/test_bay_control.cid");
        ServerSap sap = new ServerSap(puertoServidor, 0, null, models.get(0), null);
        sap.startListening(new ServerEventListener() {
            @Override public List<ServiceError> write(List<BasicDataAttribute> b) { return null; }
            @Override public void serverStoppedListening(ServerSap s) { }
        });

        AgujeroNegro proxy = new AgujeroNegro(puertoProxy, puertoServidor);
        proxy.start();

        final AtomicInteger cierres = new AtomicInteger();
        final AtomicReference<String> motivo = new AtomicReference<>("");

        IEC61850Client cli = new IEC61850Client();
        cli.setConnectionTimeoutMs(3000);          // para no esperar 10 s al agujero negro
        cli.setValueChangeListener(new IEC61850Client.ValueChangeListener() {
            @Override public void onValueChanged(String r, String v, String t) { }
            @Override public void onError(String r, String e) { }
            @Override public void onConnectionClosed(String m) { cierres.incrementAndGet(); motivo.set(m); }
            @Override public void onLog(String m) { }
        });

        cli.connect("127.0.0.1", puertoProxy);
        chk("conexión establecida a través del proxy", cli.isConnected(), "conectado");

        // ── Enlace sano ────────────────────────────────────────────────────────
        chk("latido con el enlace sano", cli.heartbeat(), "true");
        chk("latido repetido (nodo ya resuelto)", cli.heartbeat(), "true");
        chk("no se notificó ningún cierre", cierres.get() == 0, "cierres=" + cierres.get());

        // ── El IED desaparece sin cerrar nada ──────────────────────────────────
        proxy.tragar();
        System.out.println("\n  [proxy] reenvío cortado, sockets abiertos — el IED \"desaparece\"\n");

        long t0 = System.currentTimeMillis();
        boolean vivo = cli.heartbeat();
        long tardo = System.currentTimeMillis() - t0;

        chk("el latido detecta la caída silenciosa", !vivo, "false en " + tardo + " ms");
        chk("se notificó el cierre a la interfaz", cierres.get() == 1, "cierres=" + cierres.get());
        chk("el cliente quedó desconectado", !cli.isConnected(), "isConnected=false");

        // ── Un segundo latido no debe volver a notificar ────────────────────────
        cli.heartbeat();
        chk("el latido no repite la notificación", cierres.get() == 1, "cierres=" + cierres.get());

        proxy.cerrar();
        sap.stop();
        System.out.println(fallas == 0 ? "\nTODO OK" : "\n" + fallas + " FALLAS");
        System.exit(fallas == 0 ? 0 : 1);
    }

    /**
     * Proxy TCP que puede dejar de reenviar sin cerrar los sockets, imitando un enlace
     * que se muere sin avisar (cable, reinicio, ruta que desaparece).
     */
    static class AgujeroNegro extends Thread {
        private final int puertoEscucha, puertoDestino;
        private final AtomicBoolean tragando = new AtomicBoolean(false);
        private volatile ServerSocket escucha;
        private volatile Socket entrada, salida;

        AgujeroNegro(int puertoEscucha, int puertoDestino) {
            this.puertoEscucha = puertoEscucha;
            this.puertoDestino = puertoDestino;
            setDaemon(true);
        }

        void tragar() { tragando.set(true); }

        void cerrar() {
            try { if (entrada != null) entrada.close(); } catch (IOException ignore) { }
            try { if (salida != null) salida.close(); } catch (IOException ignore) { }
            try { if (escucha != null) escucha.close(); } catch (IOException ignore) { }
        }

        @Override public void run() {
            try {
                escucha = new ServerSocket(puertoEscucha);
                entrada = escucha.accept();
                salida = new Socket("127.0.0.1", puertoDestino);
                bombear(entrada, salida);
                bombear(salida, entrada);
            } catch (IOException e) {
                if (!tragando.get()) System.err.println("[proxy] " + e.getMessage());
            }
        }

        private void bombear(Socket de, Socket a) {
            Thread t = new Thread(() -> {
                byte[] buf = new byte[8192];
                try {
                    InputStream in = de.getInputStream();
                    OutputStream out = a.getOutputStream();
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        if (tragando.get()) {
                            // Se descarta lo leído y se deja de reenviar, pero los sockets
                            // siguen abiertos: ninguno de los dos extremos se entera.
                            while (!Thread.currentThread().isInterrupted()) Thread.sleep(200);
                            return;
                        }
                        out.write(buf, 0, n);
                        out.flush();
                    }
                } catch (Exception ignore) { }
            });
            t.setDaemon(true);
            t.start();
        }
    }
}
