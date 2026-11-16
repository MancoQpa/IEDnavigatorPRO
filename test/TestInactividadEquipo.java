import com.iednavigator.IEC61850Client;
import com.iednavigator.IEC61850Server;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Equipo con temporizador de inactividad corto (reporte de 2026-11-16: un relé de alimentador
 * cortaba la conexión a los dos minutos con RST, con la lectura de mantenimiento cada 60 s).
 *
 * Un proxy TCP entre el cliente y el simulador hace de "equipo": si el cliente pasa 45 s sin
 * mandar nada, corta los dos sockets con RST (SO_LINGER 0). Con el latido cada 20 s
 * (IEC61850Client.KEEPALIVE_MS) la asociación tiene que seguir viva a los 150 s; con el de
 * 60 s se cortaba.
 *
 * Tarda unos 2 min 40 s. Uso: java -cp "classes;lib\*;test" TestInactividadEquipo
 */
public class TestInactividadEquipo {
    static int ok = 0, mal = 0;
    static void chk(String que, boolean c) {
        System.out.println((c ? "OK    " : "FALLA ") + que);
        if (c) ok++; else mal++;
    }

    static final long LIMITE_MS = 45_000;
    static volatile long ultimoDelCliente = System.currentTimeMillis();
    static volatile boolean cortado = false;

    /** Copia de un lado al otro; del lado del cliente, cada byte reinicia el temporizador. */
    static void copiar(Socket de, Socket a, boolean esCliente) {
        Thread t = new Thread(() -> {
            byte[] b = new byte[8192];
            try (InputStream in = de.getInputStream(); OutputStream out = a.getOutputStream()) {
                int n;
                while ((n = in.read(b)) > 0) {
                    if (esCliente) ultimoDelCliente = System.currentTimeMillis();
                    out.write(b, 0, n);
                    out.flush();
                }
            } catch (Exception ignorada) { }
        });
        t.setDaemon(true);
        t.start();
    }

    public static void main(String[] args) throws Exception {
        File f = File.createTempFile("inactividad", ".cid");
        f.deleteOnExit();
        Files.write(f.toPath(), TestBrcb.SCL.getBytes(StandardCharsets.UTF_8));
        IEC61850Server srv = new IEC61850Server();
        srv.loadSclFile(f.getAbsolutePath());
        int puertoSrv = 23180, puertoProxy = 23181;
        chk("servidor iniciado", srv.start(puertoSrv));

        ServerSocket ss = new ServerSocket(puertoProxy);
        Thread aceptar = new Thread(() -> {
            try {
                Socket cli = ss.accept();
                Socket equipo = new Socket("127.0.0.1", puertoSrv);
                ultimoDelCliente = System.currentTimeMillis();
                copiar(cli, equipo, true);
                copiar(equipo, cli, false);
                while (!cortado) {
                    Thread.sleep(500);
                    if (System.currentTimeMillis() - ultimoDelCliente > LIMITE_MS) {
                        cli.setSoLinger(true, 0);   // RST, como el relé
                        cli.close();
                        equipo.close();
                        cortado = true;
                        System.out.println("       [equipo] corte por inactividad");
                    }
                }
            } catch (Exception ignorada) { }
        });
        aceptar.setDaemon(true);
        aceptar.start();

        IEC61850Client c = new IEC61850Client();
        chk("conecta a traves del equipo", c.connect("127.0.0.1", puertoProxy));
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < 150_000 && c.isConnected() && !cortado) Thread.sleep(1000);
        long dur = (System.currentTimeMillis() - t0) / 1000;
        chk("la asociacion sigue viva a los " + dur + " s sin pedidos del usuario",
                c.isConnected() && !cortado);

        c.disconnect();
        srv.stop();
        System.out.println(ok + " pasaron, " + mal + " fallaron");
        if (mal == 0) System.out.println("TODO OK");
        System.exit(mal == 0 ? 0 : 1);
    }
}
