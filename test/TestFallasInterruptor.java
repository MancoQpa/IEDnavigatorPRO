import com.beanit.iec61850bean.*;
import com.iednavigator.IEC61850Client;
import com.iednavigator.IEC61850Server;
import com.iednavigator.TerminacionComando;
import com.iednavigator.mando.EstadoLocal;

/**
 * Fallas del mecanismo y del gas, y tiempos de maniobra, vistos desde un cliente MMS
 * (test/test_bay_control.cid: Q0CSWI1 con SBO reforzado sobre Q0XCBR1).
 *
 *   1. tiempos: el cierre pasa por intermedio y la terminación positiva llega al cabo del tiempo;
 *   2. falla de apertura: OPERATE aceptado, no se mueve, terminación negativa AddCause 16;
 *   3. resorte descargado: el cierre se rechaza con AddCause 9; la apertura no;
 *   4. SF6 en alarma no bloquea; en bloqueo, la apertura se rechaza con AddCause 9;
 *   5. la orden directa a las bobinas (panel y GOOSE) respeta las mismas fallas.
 */
public class TestFallasInterruptor {
    static int ok = 0, fallas = 0;
    static final String CSWI = "BAYCTRLCTRL/Q0CSWI1.Pos";
    static final String XCBR = "BAYCTRLCTRL/Q0XCBR1.Pos";
    static IEC61850Client cli;
    static FcModelNode oper;

    public static void main(String[] a) throws Exception {
        int puerto = 10482;
        IEC61850Server srv = new IEC61850Server();
        ver(srv.loadSclFile("test/test_bay_control.cid"), "carga test_bay_control.cid");
        ver(srv.start(puerto), "servidor iniciado");
        cli = new IEC61850Client();
        cli.setValueChangeListener(new IEC61850Client.ValueChangeListener() {
            @Override public void onValueChanged(String r, String v, String t) { }
            @Override public void onError(String r, String e) { }
            @Override public void onConnectionClosed(String m) { }
            @Override public void onLog(String m) { }
        });
        ver(cli.connect("127.0.0.1", puerto), "cliente conectado");
        oper = (FcModelNode) cli.getServerModel().findModelNode(CSWI + ".Oper", Fc.CO);

        // 1. Tiempos de maniobra
        srv.ponerTiempoCierreMs(300);
        srv.ponerTiempoAperturaMs(150);
        Mando m = mandar("true");
        ver(m.aceptado && m.terminacion != null && m.terminacion.esPositiva(), "cierre con tiempo: terminación positiva");
        ver(m.terminacionMs >= 250, "cierre con tiempo: la terminación llega a los " + m.terminacionMs + " ms (>= 300 aprox.)");
        ver("on".equalsIgnoreCase(leer()), "cierre con tiempo: termina en on");
        srv.ponerTiempoCierreMs(0);
        srv.ponerTiempoAperturaMs(0);

        // 2. Falla de apertura
        srv.ponerTiempoFallaMs(600);
        srv.ponerFallaApertura(XCBR, true);
        m = mandar("false");
        ver(m.aceptado, "falla de apertura: OPERATE aceptado");
        ver(m.terminacion != null && !m.terminacion.esPositiva() && m.terminacion.addCause == 16,
                "falla de apertura: terminación negativa AddCause 16 (llegó "
                        + (m.terminacion == null ? "nada" : m.terminacion.addCause) + ")");
        ver(m.terminacionMs >= 500, "falla de apertura: a los " + m.terminacionMs + " ms (>= 600 aprox.)");
        ver("on".equalsIgnoreCase(leer()), "falla de apertura: sigue cerrado");
        ver(!srv.ordenarAparato(XCBR, false, "GOOSE"), "falla de apertura: la orden directa tampoco abre");
        srv.ponerFallaApertura(XCBR, false);

        // 3. Resorte descargado
        m = mandar("false");
        ver(m.aceptado && "off".equalsIgnoreCase(leer()), "sin fallas: abre");
        srv.ponerResorteDescargado(XCBR, true);
        m = mandar("true");
        ver(!m.aceptado && m.addCause == 9, "resorte descargado: el cierre se rechaza con AddCause 9 (llegó " + m.addCause + ")");
        ver(!srv.ordenarAparato(XCBR, true, "GOOSE"), "resorte descargado: la orden directa tampoco cierra");
        srv.ponerResorteDescargado(XCBR, false);
        m = mandar("true");
        ver(m.aceptado && "on".equalsIgnoreCase(leer()), "resorte cargado: cierra");

        // 4. SF6
        srv.ponerSf6(XCBR, EstadoLocal.Sf6.ALARMA);
        m = mandar("false");
        ver(m.aceptado && "off".equalsIgnoreCase(leer()), "SF6 en alarma: abre igual");
        srv.ponerSf6(XCBR, EstadoLocal.Sf6.BLOQUEO);
        m = mandar("true");
        ver(!m.aceptado && m.addCause == 9, "SF6 en bloqueo: el cierre se rechaza con AddCause 9 (llegó " + m.addCause + ")");
        ver(!srv.ordenarAparato(XCBR, true, "GOOSE"), "SF6 en bloqueo: la orden directa tampoco cierra");
        srv.ponerSf6(XCBR, EstadoLocal.Sf6.NORMAL);

        // 5. Orden directa sin fallas
        ver(srv.ordenarAparato(XCBR, true, "GOOSE"), "orden directa: cierra");
        Thread.sleep(200);
        ver("on".equalsIgnoreCase(leer()), "orden directa: el cliente lee on");

        cli.disconnect();
        srv.stop();
        System.out.println(ok + " pasaron, " + fallas + " fallaron");
        System.out.println(fallas == 0 ? "TODO OK" : "HAY FALLAS");
        System.exit(fallas == 0 ? 0 : 1);
    }

    static final class Mando {
        boolean aceptado;
        int addCause = -1;
        TerminacionComando.Mensaje terminacion;
        long terminacionMs;
    }

    static Mando mandar(String valor) throws Exception {
        Mando r = new Mando();
        IEC61850Client.ControlResult sel = cli.selectControl(oper, valor, false, "TestFallasInterruptor", false, false, 10000);
        if (!sel.success) { r.addCause = sel.addCause; cli.clearPendingSelect(); return r; }
        long t0 = System.currentTimeMillis();
        IEC61850Client.ControlResult ex = cli.executeControl(oper);
        if (!ex.success) { r.addCause = ex.addCause; cli.clearPendingSelect(); return r; }
        r.aceptado = true;
        IEC61850Client.ResultadoMando rm = cli.esperarResultadoMando(oper, valor, true, false, 5000, 200);
        r.terminacion = rm.terminacion;
        if (r.terminacion != null) r.terminacionMs = r.terminacion.recibidoMs - t0;
        Thread.sleep(150);
        cli.clearPendingSelect();
        return r;
    }

    static String leer() throws Exception {
        return cli.readValue(CSWI + ".stVal", Fc.ST);
    }

    static void ver(boolean c, String msg) {
        if (c) ok++; else fallas++;
        System.out.println((c ? "OK    " : "FALLA ") + msg);
    }
}
