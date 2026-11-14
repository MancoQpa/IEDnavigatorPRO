import com.beanit.iec61850bean.*;
import com.iednavigator.IEC61850Client;
import com.iednavigator.IEC61850Server;
import com.iednavigator.TerminacionComando;
import com.iednavigator.mando.EstadoLocal;

/**
 * Modo servidor con el motor de mando del IEDsimulatorPRO: el cliente de la propia aplicación
 * maniobra un CSWI con SBO de seguridad reforzada (ctlModel 4) contra el servidor de la misma
 * aplicación, en la misma JVM.
 *
 * Antes de este cambio el servidor solo aceptaba ctlModel 1: con 4, la biblioteca respondía
 * "unsupported" y la orden no llegaba a la aplicación. Verifica:
 *   1. cierre: SELECT y OPERATE aceptados, CommandTermination positiva y stVal en "on";
 *   2. apertura: idem, stVal en "off";
 *   3. autoridad LOCAL: el SELECT se rechaza y el LastApplError trae AddCause 2
 *      (blocked-by-switching-hierarchy), como un relé real con el selector en local.
 */
public class TestServidorMando {
    static int ok = 0, fallas = 0;
    static final String OBJ = "BAYCTRLCTRL/Q0CSWI1.Pos";

    public static void main(String[] a) throws Exception {
        int puerto = 10478;
        IEC61850Server srv = new IEC61850Server();
        ver(srv.loadSclFile("test/test_bay_control.cid"), "carga test_bay_control.cid");
        ver(srv.start(puerto), "servidor iniciado en " + puerto);

        IEC61850Client cli = new IEC61850Client();
        cli.setValueChangeListener(new IEC61850Client.ValueChangeListener() {
            @Override public void onValueChanged(String r, String v, String t) { }
            @Override public void onError(String r, String e) { }
            @Override public void onConnectionClosed(String m) { }
            @Override public void onLog(String m) { }
        });
        ver(cli.connect("127.0.0.1", puerto), "cliente conectado");
        FcModelNode oper = (FcModelNode) cli.getServerModel().findModelNode(OBJ + ".Oper", Fc.CO);
        ver(oper != null, "existe " + OBJ + ".Oper");

        maniobrar(cli, oper, "true", "on");
        maniobrar(cli, oper, "false", "off");

        ver(srv.ponerAutoridad("BAYCTRLCTRL", EstadoLocal.Autoridad.LOCAL), "autoridad LOCAL");
        Thread.sleep(300);
        IEC61850Client.ControlResult sel = cli.selectControl(oper, "true", false, "TestServidorMando", false, false, 10000);
        ver(!sel.success, "en LOCAL el SELECT se rechaza");
        ver(sel.addCause == 2, "AddCause 2 (blocked-by-switching-hierarchy), llegó " + sel.addCause);
        cli.clearPendingSelect();

        cli.disconnect();
        srv.stop();
        System.out.println(ok + " pasaron, " + fallas + " fallaron");
        System.out.println(fallas == 0 ? "TODO OK" : "HAY FALLAS");
        System.exit(fallas == 0 ? 0 : 1);
    }

    static void maniobrar(IEC61850Client cli, FcModelNode oper, String valor, String esperado) throws Exception {
        String que = "ctlVal=" + valor;
        IEC61850Client.ControlResult sel = cli.selectControl(oper, valor, false, "TestServidorMando", false, false, 10000);
        ver(sel.success, que + ": SELECT (SBOw) aceptado" + (sel.success ? "" : " — " + sel.error + " AddCause " + sel.addCause));
        if (!sel.success) return;
        IEC61850Client.ControlResult ex = cli.executeControl(oper);
        ver(ex.success, que + ": OPERATE aceptado" + (ex.success ? "" : " — " + ex.error));
        if (!ex.success) return;
        IEC61850Client.ResultadoMando rm = cli.esperarResultadoMando(oper, valor, true, true, 5000, 500);
        TerminacionComando.Mensaje m = rm.terminacion;
        ver(m != null && m.esPositiva(), que + ": CommandTermination positiva");
        ver(rm.posicion != null && rm.posicion.confirmed, que + ": posición confirmada ("
                + (rm.posicion == null ? "-" : rm.posicion.observed) + ")");
        String st = cli.readValue(OBJ + ".stVal", Fc.ST);
        ver(esperado.equalsIgnoreCase(st), que + ": stVal = " + st);
    }

    static void ver(boolean c, String msg) {
        if (c) ok++; else fallas++;
        System.out.println((c ? "OK    " : "FALLA ") + msg);
    }
}
