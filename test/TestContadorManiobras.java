import com.beanit.iec61850bean.*;
import com.iednavigator.IEC61850Client;
import com.iednavigator.IEC61850Server;

/**
 * Contador de maniobras (XCBR.OpCnt) del simulador. Hasta 2026-11-15 quedaba en 0 al operar
 * (lo vio la herramienta comercial leyendo un reporte con los OpCnt de un CID real). Ahora cuenta como
 * un IED: una vez por maniobra que cambia la posición, solo en el LN del aparato.
 *
 *   1. Mando SBO por MMS sobre el CSWI que comanda el XCBR: cuenta 1.
 *   2. Un cierre sobre algo cerrado: no cuenta.
 *   3. La apertura: cuenta.
 *   4. La maniobra local (interruptor virtual) sobre el XCBR: cuenta.
 *
 * Uso: java -cp "classes;lib\*;test" TestContadorManiobras
 */
public class TestContadorManiobras {
    static int ok = 0, mal = 0;
    static void chk(String que, boolean c) {
        System.out.println((c ? "OK    " : "FALLA ") + que);
        if (c) ok++; else mal++;
    }

    static final String CSWI = "BAYCTRLCTRL/Q0CSWI1.Pos";
    static final String XCBR = "BAYCTRLCTRL/Q0XCBR1";

    static long cuenta(IEC61850Server srv) {
        ModelNode n = srv.getServerModel().findModelNode(XCBR + ".OpCnt.stVal", Fc.ST);
        if (n instanceof BdaInt32) return ((BdaInt32) n).getValue();
        if (n instanceof BdaInt32U) return ((BdaInt32U) n).getValue();
        return -1;
    }

    static String posicion(IEC61850Server srv) {
        ModelNode n = srv.getServerModel().findModelNode(XCBR + ".Pos.stVal", Fc.ST);
        return n instanceof BdaDoubleBitPos ? String.valueOf(((BdaDoubleBitPos) n).getDoubleBitPos()) : "?";
    }

    static boolean mandar(IEC61850Client cli, FcModelNode oper, String valor) throws Exception {
        IEC61850Client.ControlResult sel = cli.selectControl(oper, valor, false, "TestContadorManiobras", false, false, 10000);
        if (!sel.success) return false;
        IEC61850Client.ControlResult ex = cli.executeControl(oper);
        Thread.sleep(600);
        return ex.success;
    }

    public static void main(String[] a) throws Exception {
        int puerto = 23110;
        IEC61850Server srv = new IEC61850Server();
        chk("carga test_bay_control.cid", srv.loadSclFile("test/test_bay_control.cid"));
        chk("servidor iniciado", srv.start(puerto));
        IEC61850Client cli = new IEC61850Client();
        chk("cliente conectado", cli.connect("127.0.0.1", puerto));
        FcModelNode oper = (FcModelNode) cli.getServerModel().findModelNode(CSWI + ".Oper", Fc.CO);

        long c0 = cuenta(srv);
        String p0 = posicion(srv);
        chk("OpCnt inicial " + c0 + ", posición " + p0, c0 >= 0);

        mandar(cli, oper, "true");
        long c1 = cuenta(srv);
        chk("1. cierre por MMS sobre el CSWI: " + p0 + " -> " + posicion(srv) + ", OpCnt " + c0 + " -> " + c1,
                c1 == c0 + ("ON".equals(p0) ? 0 : 1));

        mandar(cli, oper, "true");
        long c2 = cuenta(srv);
        chk("2. cerrar algo cerrado no cuenta: OpCnt " + c2, c2 == c1);

        mandar(cli, oper, "false");
        long c3 = cuenta(srv);
        chk("3. apertura: " + posicion(srv) + ", OpCnt " + c3, c3 == c2 + 1);

        srv.maniobrarLocal(XCBR + ".Pos", true);
        Thread.sleep(300);
        long c4 = cuenta(srv);
        chk("4. maniobra local sobre el XCBR: " + posicion(srv) + ", OpCnt " + c4, c4 == c3 + 1);

        // El cliente lo ve por MMS
        ModelNode n = cli.getServerModel().findModelNode(XCBR + ".OpCnt", Fc.ST);
        String leido = cli.readValue(XCBR + ".OpCnt.stVal", Fc.ST);
        chk("el cliente lee OpCnt = " + leido, String.valueOf(c4).equals(leido.trim()));

        cli.disconnect();
        srv.stop();
        System.out.println(ok + " pasaron, " + mal + " fallaron");
        if (mal == 0) System.out.println("TODO OK");
        System.exit(mal == 0 ? 0 : 1);
    }
}
