import com.beanit.iec61850bean.*;
import com.iednavigator.IEC61850Client;
import com.iednavigator.IEC61850Server;
import com.iednavigator.TerminacionComando;
import com.iednavigator.mando.EstadoLocal;

import java.util.List;

/**
 * Interruptor con un XCBR por polo y otro trifásico, comandado por un solo CSWI
 * (test/test_interruptor_polos.cid).
 *
 *   1. el panel lo muestra como un solo aparato, comandado por Q0CSWI1;
 *   2. el mando por MMS mueve los cuatro XCBR y no el seccionador;
 *   3. la maniobra desde el panel sobre un polo mueve todo el interruptor;
 *   4. una falla puesta en el trifásico afecta al mando por el CSWI.
 */
public class TestInterruptorPolos {
    static int ok = 0, fallas = 0;
    static final String LD = "POLOSCTRL";
    static final String CSWI = LD + "/Q0CSWI1.Pos";
    static IEC61850Client cli;

    public static void main(String[] a) throws Exception {
        int puerto = 10484;
        IEC61850Server srv = new IEC61850Server();
        ver(srv.loadSclFile("test/test_interruptor_polos.cid"), "carga test_interruptor_polos.cid");
        ver(srv.start(puerto), "servidor iniciado");

        // 1. Un solo aparato
        int interruptores = 0;
        String comandado = "";
        for (EstadoLocal.Objeto o : srv.listarAparatos()) {
            if (!o.esAparato) continue;
            if (o.nombre.contains("XCBR")) { interruptores++; comandado = o.nombre + " por " + o.comandadoPor; }
        }
        ver(interruptores == 1, "los cuatro XCBR son un solo aparato en la lista (" + interruptores + ")");
        ver(comandado.equals("Q0XCBR1.Pos por Q0CSWI1"), "aparato: " + comandado);
        List<String> v = EstadoLocal.posicionesVinculadas(srv.getServerModel(), CSWI);
        ver(v.size() == 4 && !v.toString().contains("XSWI"), "vinculadas a Q0CSWI1: " + v);

        cli = new IEC61850Client();
        cli.setValueChangeListener(new IEC61850Client.ValueChangeListener() {
            @Override public void onValueChanged(String r, String x, String t) { }
            @Override public void onError(String r, String e) { }
            @Override public void onConnectionClosed(String m) { }
            @Override public void onLog(String m) { }
        });
        ver(cli.connect("127.0.0.1", puerto), "cliente conectado");

        // 2. Mando por MMS
        ver(mandar("true") == 0, "cierre por Q0CSWI1: terminación positiva");
        ver(polos().equals("on on on on"), "cierre: los cuatro XCBR en on (" + polos() + ")");
        ver("off".equalsIgnoreCase(leer(LD + "/Q0XSWI1.Pos.stVal")), "el seccionador sin CSWI no se mueve");
        ver(mandar("false") == 0, "apertura por Q0CSWI1: terminación positiva");
        ver(polos().equals("off off off off"), "apertura: los cuatro XCBR en off (" + polos() + ")");

        // 3. Panel sobre un polo
        srv.ordenarAparato(LD + "/Q0XCBR3.Pos", true, "Panel");
        Thread.sleep(150);
        ver(polos().equals("on on on on") && "on".equalsIgnoreCase(leer(CSWI + ".stVal")),
                "maniobra local sobre el polo C: cierra todo el interruptor y el CSWI");

        // 4. Falla en el trifásico
        srv.ponerTiempoFallaMs(300);
        srv.ponerFallaApertura(LD + "/Q0XCBR4.Pos", true);
        ver(mandar("false") == 16, "falla de apertura puesta en Q0XCBR4: el mando por Q0CSWI1 termina con AddCause 16");
        ver(polos().equals("on on on on"), "falla de apertura: el interruptor sigue cerrado");
        srv.ponerFallaApertura(LD + "/Q0XCBR4.Pos", false);

        cli.disconnect();
        srv.stop();
        System.out.println(ok + " pasaron, " + fallas + " fallaron");
        System.out.println(fallas == 0 ? "TODO OK" : "HAY FALLAS");
        System.exit(fallas == 0 ? 0 : 1);
    }

    /** 0 = terminación positiva, AddCause de la negativa o del rechazo, -1 sin terminación. */
    static int mandar(String valor) throws Exception {
        FcModelNode oper = (FcModelNode) cli.getServerModel().findModelNode(CSWI + ".Oper", Fc.CO);
        IEC61850Client.ControlResult sel = cli.selectControl(oper, valor, false, "TestInterruptorPolos", false, false, 10000);
        if (!sel.success) { cli.clearPendingSelect(); return sel.addCause; }
        IEC61850Client.ControlResult ex = cli.executeControl(oper);
        if (!ex.success) { cli.clearPendingSelect(); return ex.addCause; }
        IEC61850Client.ResultadoMando rm = cli.esperarResultadoMando(oper, valor, true, false, 5000, 200);
        Thread.sleep(150);
        cli.clearPendingSelect();
        TerminacionComando.Mensaje t = rm.terminacion;
        return t == null ? -1 : (t.esPositiva() ? 0 : t.addCause);
    }

    static String polos() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 4; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(leer(LD + "/Q0XCBR" + i + ".Pos.stVal").toLowerCase());
        }
        return sb.toString();
    }

    static String leer(String ref) throws Exception {
        return cli.readValue(ref, Fc.ST);
    }

    static void ver(boolean c, String msg) {
        if (c) ok++; else fallas++;
        System.out.println((c ? "OK    " : "FALLA ") + msg);
    }
}
