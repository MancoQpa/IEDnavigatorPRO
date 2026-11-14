import com.beanit.iec61850bean.*;
import com.iednavigator.IEC61850Client;
import com.iednavigator.IEC61850Server;
import com.iednavigator.TerminacionComando;
import com.iednavigator.mando.EstadoLocal;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lo que hace el panel "Interruptor virtual", visto desde un cliente MMS.
 *
 * Con test/test_bay_control.cid en modo servidor (Q0CSWI1 con SBO reforzado sobre Q0XCBR1):
 *   1. el modelo ofrece aparatos de maniobra;
 *   2. maniobra local (a pie de equipo): el cliente lee la posición nueva;
 *   3. aparato TRABADO: la orden por MMS termina positiva pero la posición no cambia;
 *   4. posición forzada a bad-state y calidad inválida: el cliente las lee;
 *   5. el registro recibe los eventos del panel y los de MMS.
 */
public class TestInterruptorVirtual {
    static int ok = 0, fallas = 0;
    static final String CSWI = "BAYCTRLCTRL/Q0CSWI1.Pos";

    public static void main(String[] a) throws Exception {
        int puerto = 10479;
        IEC61850Server srv = new IEC61850Server();
        ver(srv.loadSclFile("test/test_bay_control.cid"), "carga test_bay_control.cid");
        ver(srv.start(puerto), "servidor iniciado");
        List<String> eventos = new CopyOnWriteArrayList<>();
        srv.agregarObservadorMando((origen, texto, aceptado) -> eventos.add(origen + "|" + aceptado + "|" + texto));

        List<EstadoLocal.Objeto> aparatos = srv.listarAparatos();
        ver(!aparatos.isEmpty(), "hay aparatos de maniobra (" + aparatos.size() + ")");
        String ref = null;
        for (EstadoLocal.Objeto o : aparatos) {
            System.out.println("      aparato " + o.ref + " " + o.estado + " ctlModel " + o.ctlModel + " " + o.comandadoPor);
            if (o.esAparato && o.ref.endsWith("XCBR1.Pos")) ref = o.ref;
        }
        ver(ref != null, "el XCBR es uno de ellos");

        IEC61850Client cli = new IEC61850Client();
        cli.setValueChangeListener(new IEC61850Client.ValueChangeListener() {
            @Override public void onValueChanged(String r, String v, String t) { }
            @Override public void onError(String r, String e) { }
            @Override public void onConnectionClosed(String m) { }
            @Override public void onLog(String m) { }
        });
        ver(cli.connect("127.0.0.1", puerto), "cliente conectado");

        // 2. Maniobra local
        srv.maniobrarLocal(ref, true);
        Thread.sleep(200);
        ver("on".equalsIgnoreCase(cli.readValue(ref + ".stVal", Fc.ST)), "cierre local: el cliente lee on");
        srv.maniobrarLocal(ref, false);
        Thread.sleep(200);
        ver("off".equalsIgnoreCase(cli.readValue(ref + ".stVal", Fc.ST)), "apertura local: el cliente lee off");

        // 3. Trabado: la orden se acepta y termina, pero no se mueve
        srv.ponerRespuesta(ref, EstadoLocal.Respuesta.TRABADO);
        FcModelNode oper = (FcModelNode) cli.getServerModel().findModelNode(CSWI + ".Oper", Fc.CO);
        IEC61850Client.ControlResult sel = cli.selectControl(oper, "true", false, "TestInterruptorVirtual", false, false, 10000);
        ver(sel.success, "trabado: SELECT aceptado");
        if (sel.success) {
            IEC61850Client.ControlResult ex = cli.executeControl(oper);
            ver(ex.success, "trabado: OPERATE aceptado");
            IEC61850Client.ResultadoMando rm = cli.esperarResultadoMando(oper, "true", true, true, 2500, 300);
            TerminacionComando.Mensaje m = rm.terminacion;
            ver(m != null && m.esPositiva(), "trabado: terminación positiva");
            ver(!"on".equalsIgnoreCase(cli.readValue(CSWI + ".stVal", Fc.ST)), "trabado: la posición no cambió");
        }
        cli.clearPendingSelect();
        srv.ponerRespuesta(ref, EstadoLocal.Respuesta.NORMAL);

        // 4. Posición forzada y calidad
        srv.forzarPosicion(ref, BdaDoubleBitPos.DoubleBitPos.BAD_STATE);
        Thread.sleep(200);
        String st = cli.readValue(ref + ".stVal", Fc.ST);
        ver(st != null && st.toLowerCase().contains("bad"), "bad-state forzado: el cliente lee " + st);
        srv.ponerCalidadInvalida(ref, true);
        Thread.sleep(200);
        String q = cli.readValue(ref + ".q", Fc.ST);
        ver(q != null && q.toLowerCase().contains("invalid"), "calidad inválida: el cliente lee " + q);
        srv.ponerCalidadInvalida(ref, false);
        Thread.sleep(200);
        q = cli.readValue(ref + ".q", Fc.ST);
        ver(q != null && !q.toLowerCase().contains("invalid"), "calidad restablecida: " + q);

        // 5. Registro
        boolean dePanel = eventos.stream().anyMatch(e -> e.startsWith("Panel|"));
        boolean deMms = eventos.stream().anyMatch(e -> e.startsWith("MMS|true|"));
        ver(dePanel, "el registro recibió eventos del panel");
        ver(deMms, "el registro recibió eventos de MMS");
        for (String e : eventos) System.out.println("      evento " + e);

        cli.disconnect();
        srv.stop();
        System.out.println(ok + " pasaron, " + fallas + " fallaron");
        System.out.println(fallas == 0 ? "TODO OK" : "HAY FALLAS");
        System.exit(fallas == 0 ? 0 : 1);
    }

    static void ver(boolean c, String msg) {
        if (c) ok++; else fallas++;
        System.out.println((c ? "OK    " : "FALLA ") + msg);
    }
}
