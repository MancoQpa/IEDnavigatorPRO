import com.beanit.iec61850bean.*;
import com.iednavigator.IEC61850Client;

import java.util.List;

/**
 * Banco del preflight cuando la autoridad de mando vive a nivel de vano.
 *
 * Levanta el servidor con {@code test/test_bay_cbay.cid} —un CSWI sin Loc y un CBAY que sí la
 * declara— y conecta el cliente real contra sí mismo. Reproduce el caso que el preflight no
 * cubría: buscando la autoridad sólo en el nodo que se opera, no la encontraba, la omitía en
 * silencio e informaba menos condiciones de las que gobiernan la orden.
 *
 * Uso:  java -cp "classes;lib\*;test" TestPreflightCbay [puerto]
 */
public class TestPreflightCbay {

    private static int fallas = 0;
    private static ServerModel srvModel;

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 10404;

        List<ServerModel> models = SclParser.parse("test/test_bay_cbay.cid");
        srvModel = models.get(0);
        ServerSap sap = new ServerSap(port, 0, null, srvModel, null);
        sap.startListening(new ServerEventListener() {
            @Override public List<ServiceError> write(List<BasicDataAttribute> bdas) { return null; }
            @Override public void serverStoppedListening(ServerSap s) { }
        });
        System.out.println("Servidor de prueba en puerto " + port + "\n");

        IEC61850Client cli = new IEC61850Client();
        cli.connect("127.0.0.1", port);

        FcModelNode oper = (FcModelNode) cli.getServerModel()
            .findModelNode("BAYCBLD0/Q0CSWI1.Pos", Fc.CO).getChild("Oper");

        // Estado de partida: todo permisivo.
        setEnum("BAYCBLD0/Q0CSWI1.Beh.stVal", 1);
        setEnum("BAYCBLD0/Q0CSWI1.Health.stVal", 1);
        setEnum("BAYCBLD0/LLN0.Beh.stVal", 1);
        setEnum("BAYCBLD0/LLN0.Health.stVal", 1);
        setDbpos("BAYCBLD0/Q0XCBR1.Pos.stVal", 1);      // abierto
        set("BAYCBLD0/Q0XCBR1.BlkOpn.stVal", false);
        set("BAYCBLD0/Q0XCBR1.BlkCls.stVal", false);
        set("BAYCBLD0/QCBAY1.Loc.stVal",    false);
        set("BAYCBLD0/QCBAY1.Rem.stVal",    true);
        set("BAYCBLD0/QCBAY1.BlkCmd.stVal", false);

        System.out.println("== el vano permisivo: nada bloquea ==");
        caso(cli, oper, "true", "cerrar con el vano habilitando", 0, null);

        System.out.println("\n== la autoridad de mando, que el CSWI no declara ==");
        set("BAYCBLD0/QCBAY1.Loc.stVal", true);
        caso(cli, oper, "true", "vano en mando Local", 1, "QCBAY1.Loc");
        set("BAYCBLD0/QCBAY1.Loc.stVal", false);

        System.out.println("\n== bloqueo de órdenes a nivel de vano ==");
        set("BAYCBLD0/QCBAY1.BlkCmd.stVal", true);
        caso(cli, oper, "true", "vano con órdenes bloqueadas", 1, "QCBAY1.BlkCmd");
        set("BAYCBLD0/QCBAY1.BlkCmd.stVal", false);

        System.out.println("\n== las dos a la vez ==");
        set("BAYCBLD0/QCBAY1.Loc.stVal", true);
        set("BAYCBLD0/QCBAY1.BlkCmd.stVal", true);
        caso(cli, oper, "true", "Local y bloqueo simultáneos", 2, "QCBAY1.BlkCmd");
        set("BAYCBLD0/QCBAY1.Loc.stVal", false);
        set("BAYCBLD0/QCBAY1.BlkCmd.stVal", false);

        System.out.println("\n== Rem se informa pero no bloquea ==");
        // No está verificado que Rem sea el complemento exacto de Loc en toda implementación,
        // así que se lee y se muestra, pero no se marca bloqueante: marcar lo que no se
        // comprobó es lo que llena de falsas alarmas el informe.
        set("BAYCBLD0/QCBAY1.Rem.stVal", false);
        caso(cli, oper, "true", "Rem=false no bloquea", 0, null);
        set("BAYCBLD0/QCBAY1.Rem.stVal", true);

        System.out.println("\n== lo que el modelo no declara se omite en silencio ==");
        List<IEC61850Client.PreflightCheck> checks = cli.preflightControl(oper, "true");
        boolean sinLocCswi = checks.stream().noneMatch(c -> c.reference.contains("CSWI1.Loc"));
        boolean sinCilo    = checks.stream().noneMatch(c -> c.reference.contains("CILO"));
        boolean conCbay    = checks.stream().anyMatch(c -> c.reference.contains("CBAY1.Loc"));
        chequeo("el CSWI no aporta Loc (no lo declara)", sinLocCswi);
        chequeo("no se inventa un CILO ausente", sinCilo);
        chequeo("la autoridad se lee del CBAY", conCbay);
        System.out.println("    condiciones leídas: " + checks.size());
        for (IEC61850Client.PreflightCheck c : checks) {
            System.out.println("      " + c.reference.substring(c.reference.indexOf('/') + 1)
                + " = " + c.value + (c.blocking ? "   <-- bloquea" : ""));
        }

        System.out.println(fallas == 0 ? "\nTODO OK" : "\n" + fallas + " FALLARON");
        cli.disconnect();
        sap.stop();
        if (fallas > 0) System.exit(1);
    }

    private static void chequeo(String titulo, boolean ok) {
        if (!ok) fallas++;
        System.out.printf("  [%s] %s%n", ok ? "OK" : "FALLA", titulo);
    }

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
        System.out.printf("  %-42s leídas=%-2d bloqueantes=%d [%s] %s%n",
            titulo, checks.size(), bloq, cuales, ok ? "OK" : "<<< FALLA");
    }

    private static void set(String ref, boolean v) {
        ((BdaBoolean) srvModel.findModelNode(ref, Fc.ST)).setValue(v);
    }

    private static void setEnum(String ref, int v) {
        ((BdaInt8) srvModel.findModelNode(ref, Fc.ST)).setValue((byte) v);
    }

    private static void setDbpos(String ref, int v) {
        // Dbpos ocupa los dos bits altos del octeto, igual que en TestBayControl.
        ((BdaDoubleBitPos) srvModel.findModelNode(ref, Fc.ST)).setValue(new byte[]{(byte) (v << 6)});
    }
}
