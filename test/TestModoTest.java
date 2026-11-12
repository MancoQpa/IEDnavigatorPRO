import com.beanit.iec61850bean.*;
import com.iednavigator.IEC61850Client;
import com.iednavigator.IEC61850Server;
import com.iednavigator.TerminacionComando;
import com.iednavigator.mando.EstadoLocal;

/**
 * Modo del IED, del LD y del LN: Beh derivado de LLN0.Mod y del Mod propio
 * (IEC 61850-7-4, anexo A), con test/test_bay_control.cid.
 *
 *   1. la tabla de comportamiento;
 *   2. IED en test: el CSWI pasa a Beh=test, rechaza el mando sin Test y acepta el que lo trae;
 *   3. de vuelta a on: mando normal;
 *   4. solo el LN en test: los demas nodos del LD siguen en on;
 *   5. LD en off: todo el LD en off y el mando se rechaza;
 *   6. editar LLN0.Mod.stVal desde el arbol tambien recalcula.
 */
public class TestModoTest {
    static int ok = 0, fallas = 0;
    static final String LD = "BAYCTRLCTRL";
    static final String CSWI = LD + "/Q0CSWI1";
    static IEC61850Client cli;
    static IEC61850Server srv;

    public static void main(String[] a) throws Exception {
        // 1. Tabla
        ver(EstadoLocal.comportamiento(1, 1) == 1, "on + on = on");
        ver(EstadoLocal.comportamiento(3, 1) == 3, "LD test + LN on = test");
        ver(EstadoLocal.comportamiento(1, 3) == 3, "LD on + LN test = test");
        ver(EstadoLocal.comportamiento(2, 3) == 4, "LD bloqueado + LN test = test/bloqueado");
        ver(EstadoLocal.comportamiento(3, 2) == 4, "LD test + LN bloqueado = test/bloqueado");
        ver(EstadoLocal.comportamiento(5, 3) == 5 && EstadoLocal.comportamiento(1, 5) == 5, "off gana siempre");

        int puerto = 10485;
        srv = new IEC61850Server();
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
        ver(beh(CSWI) == 1 && srv.modoIed() == 1, "arranca en on");

        // 2. IED en test
        srv.ponerModoIed(3);
        ver(srv.modoIed() == 3, "modo del IED: test");
        ver(beh(CSWI) == 3 && beh(LD + "/Q0XCBR1") == 3 && beh(LD + "/LLN0") == 3, "Beh=test en LLN0, CSWI y XCBR");
        ver("3".equals(leer(CSWI + ".Beh.stVal")), "el cliente lee Beh=3 en el CSWI");
        int r = mandar("true", false);
        ver(r == 8, "mando sin Test con el IED en test: rechazado AddCause " + r);
        r = mandar("true", true);
        ver(r == 0, "mando con Test=true: aceptado (" + r + ")");

        // 3. De vuelta a on
        srv.ponerModoIed(1);
        ver(beh(CSWI) == 1, "IED de vuelta en on");
        r = mandar("false", true);
        ver(r == 8, "mando con Test=true y el nodo en on: rechazado AddCause " + r);
        r = mandar("false", false);
        ver(r == 0, "mando normal: aceptado (" + r + ")");

        // 4. Solo el LN
        ver(srv.ponerModo(CSWI + ".Mod", 3), "Mod del CSWI en test");
        ver(beh(CSWI) == 3 && beh(LD + "/Q0XCBR1") == 1 && beh(LD + "/LLN0") == 1, "solo el CSWI queda en test");
        ver(srv.modoIed() == 1, "el modo del IED sigue en on");
        srv.ponerModo(CSWI + ".Mod", 1);

        // 5. LD en off
        srv.ponerModo(LD + "/LLN0.Mod", 5);
        ver(beh(CSWI) == 5 && beh(LD + "/Q0XCBR1") == 5, "LD en off: todos sus LN en off");
        r = mandar("true", false);
        ver(r != 0 && r != -1, "mando con el LD en off: rechazado (AddCause " + r + ")");
        srv.ponerModo(LD + "/LLN0.Mod", 1);
        ver(beh(CSWI) == 1, "LD de vuelta en on");

        // 6. Edicion desde el arbol
        srv.setDataValue(LD + "/LLN0.Mod.stVal", "3");
        Thread.sleep(100);
        ver(beh(CSWI) == 3, "editar LLN0.Mod.stVal desde el arbol pone el CSWI en test");
        srv.setDataValue(LD + "/LLN0.Mod.stVal", "1");
        Thread.sleep(100);
        ver(beh(CSWI) == 1, "y volver a 1 lo saca");

        cli.disconnect();
        srv.stop();
        System.out.println(ok + " pasaron, " + fallas + " fallaron");
        System.out.println(fallas == 0 ? "TODO OK" : "HAY FALLAS");
        System.exit(fallas == 0 ? 0 : 1);
    }

    static int beh(String ln) {
        Integer v = srv.valorEnum(ln + ".Beh");
        return v == null ? -1 : v;
    }

    /** 0 = terminacion positiva, AddCause del rechazo o de la negativa, -1 sin terminacion. */
    static int mandar(String valor, boolean test) throws Exception {
        FcModelNode oper = (FcModelNode) cli.getServerModel().findModelNode(CSWI + ".Pos.Oper", Fc.CO);
        IEC61850Client.ControlResult sel = cli.selectControl(oper, valor, test, "TestModoTest", false, false, 10000);
        if (!sel.success) { cli.clearPendingSelect(); return sel.addCause; }
        IEC61850Client.ControlResult ex = cli.executeControl(oper);
        if (!ex.success) { cli.clearPendingSelect(); return ex.addCause; }
        IEC61850Client.ResultadoMando rm = cli.esperarResultadoMando(oper, valor, true, false, 5000, 200);
        Thread.sleep(150);
        cli.clearPendingSelect();
        TerminacionComando.Mensaje t = rm.terminacion;
        return t == null ? -1 : (t.esPositiva() ? 0 : t.addCause);
    }

    static String leer(String ref) {
        try { return cli.readValue(ref, Fc.ST); } catch (Exception e) { return "error " + e.getMessage(); }
    }

    static void ver(boolean c, String msg) {
        if (c) ok++; else fallas++;
        System.out.println((c ? "OK    " : "FALLA ") + msg);
    }
}
