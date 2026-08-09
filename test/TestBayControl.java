import com.beanit.iec61850bean.*;
import com.iednavigator.IEC61850Client;

import java.util.List;

/**
 * Banco de la ruta de mando contra una asociación MMS viva.
 *
 * Levanta el servidor con {@code test/test_bay_control.cid}, conecta el cliente real de la
 * app contra sí mismo y corre el preflight forzando los valores del modelo. Cubre lo que no
 * se puede verificar compilando: que los chequeos encuentren los nodos donde están y que no
 * inventen condiciones donde no las hay.
 *
 * Uso:  java -cp classes;lib\*;test TestBayControl [puerto]
 */
public class TestBayControl {

    private static int fallas = 0;
    private static ServerModel srvModel;
    private static ServerSap sap;

    public static void main(String[] args) throws Exception {
        String scl = "test/test_bay_control.cid";
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 10202;

        List<ServerModel> models = SclParser.parse(scl);
        srvModel = models.get(0);
        sap = new ServerSap(port, 0, null, srvModel, null);
        sap.startListening(new ServerEventListener() {
            @Override public List<ServiceError> write(List<BasicDataAttribute> bdas) { return null; }
            @Override public void serverStoppedListening(ServerSap s) { }
        });
        System.out.println("Servidor de prueba en puerto " + port);

        IEC61850Client cli = new IEC61850Client();
        cli.connect("127.0.0.1", port);
        System.out.println("Cliente conectado\n");

        FcModelNode oper = (FcModelNode) cli.getServerModel()
            .findModelNode("BAYCTRLCTRL/Q0CSWI1.Pos", Fc.CO).getChild("Oper");

        // ── 1. Enclavamiento cerrado para el cierre ───────────────────────────
        set("BAYCTRLCTRL/Q0CILO1.EnaCls.stVal", false);
        set("BAYCTRLCTRL/Q0CILO1.EnaOpn.stVal", true);
        set("BAYCTRLCTRL/Q0XCBR1.BlkCls.stVal", false);
        set("BAYCTRLCTRL/Q0XCBR1.BlkOpn.stVal", false);
        setDbpos("BAYCTRLCTRL/Q0CSWI1.Pos.stVal", 1);   // off
        setEnum("BAYCTRLCTRL/Q0CSWI1.Beh.stVal", 1);    // on
        setEnum("BAYCTRLCTRL/Q0CSWI1.Health.stVal", 1); // Ok
        setEnum("BAYCTRLCTRL/LLN0.Beh.stVal", 1);
        setEnum("BAYCTRLCTRL/LLN0.Health.stVal", 1);
        caso(cli, oper, "true", "cerrar con EnaCls=false", 1, "EnaCls");

        // ── 2. Enclavamiento habilitando: ninguna condición bloquea ───────────
        set("BAYCTRLCTRL/Q0CILO1.EnaCls.stVal", true);
        caso(cli, oper, "true", "cerrar con EnaCls=true", 0, null);

        // ── 3. El bloqueo del aparato vive en el XCBR, no en el CSWI ──────────
        // Es el chequeo que antes no se hacía: el preflight lo buscaba en el CSWI.
        set("BAYCTRLCTRL/Q0XCBR1.BlkCls.stVal", true);
        caso(cli, oper, "true", "cerrar con XCBR.BlkCls=true", 1, "BlkCls");
        set("BAYCTRLCTRL/Q0XCBR1.BlkCls.stVal", false);

        // ── 4. Posición actual = posición comandada ───────────────────────────
        setDbpos("BAYCTRLCTRL/Q0CSWI1.Pos.stVal", 2);   // on
        caso(cli, oper, "true", "cerrar con el aparato ya cerrado", 1, "Pos.stVal");

        // ── 5. En posición intermedia el chequeo no debe evaluarse ────────────
        setDbpos("BAYCTRLCTRL/Q0CSWI1.Pos.stVal", 0);   // intermediate
        caso(cli, oper, "true", "cerrar con el aparato en posición intermedia", 0, null);

        // ── 6. Apertura: se miran EnaOpn/BlkOpn, no los de cierre ─────────────
        setDbpos("BAYCTRLCTRL/Q0CSWI1.Pos.stVal", 2);   // on
        set("BAYCTRLCTRL/Q0CILO1.EnaOpn.stVal", false);
        caso(cli, oper, "false", "abrir con EnaOpn=false", 1, "EnaOpn");

        // ── 7. Salud en alarma bloquea, y se suma al enclavamiento ────────────
        setEnum("BAYCTRLCTRL/Q0CSWI1.Health.stVal", 3); // Alarm
        caso(cli, oper, "false", "abrir con EnaOpn=false y Health=Alarm", 2, "Health");

        // ── 8. sboTimeout se lee del equipo, no del default ───────────────────
        int to = cli.getSboTimeoutMs(oper);
        boolean okTo = (to == 300000);
        if (!okTo) fallas++;
        System.out.printf("%-46s sboTimeout=%d ms %s%n",
            "lectura de sboTimeout", to, okTo ? "OK" : "<<< FALLA (esperado 300000)");

        cli.disconnect();
        sap.stop();
        System.out.println(fallas == 0 ? "\nTODO OK" : "\n" + fallas + " FALLAS");
        System.exit(fallas == 0 ? 0 : 1);
    }

    /** Corre el preflight y contrasta cuántas condiciones bloquean y cuál. */
    private static void caso(IEC61850Client cli, FcModelNode oper, String ctlVal,
                             String titulo, int bloqueantesEsperados, String refEsperada) {
        List<IEC61850Client.PreflightCheck> checks = cli.preflightControl(oper, ctlVal);
        int bloq = 0;
        StringBuilder cuales = new StringBuilder();
        for (IEC61850Client.PreflightCheck c : checks) {
            if (!c.blocking) continue;
            bloq++;
            if (cuales.length() > 0) cuales.append(", ");
            cuales.append(c.reference.substring(c.reference.indexOf('/') + 1));
        }
        boolean ok = (bloq == bloqueantesEsperados)
            && (refEsperada == null || cuales.toString().contains(refEsperada));
        if (!ok) fallas++;
        System.out.printf("%-46s leídas=%-2d bloqueantes=%d [%s] %s%n",
            titulo, checks.size(), bloq, cuales, ok ? "OK" : "<<< FALLA");
    }

    private static void set(String ref, boolean v) {
        BdaBoolean b = (BdaBoolean) srvModel.findModelNode(ref, Fc.ST);
        b.setValue(v);
    }
    private static void setEnum(String ref, int v) {
        BdaInt8 b = (BdaInt8) srvModel.findModelNode(ref, Fc.ST);
        b.setValue((byte) v);
    }
    private static void setDbpos(String ref, int v) {
        BdaDoubleBitPos b = (BdaDoubleBitPos) srvModel.findModelNode(ref, Fc.ST);
        b.setValue(new byte[]{(byte) (v << 6)});
    }
}
