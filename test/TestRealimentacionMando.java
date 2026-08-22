import com.beanit.iec61850bean.*;
import com.iednavigator.IEC61850Client;
import com.iednavigator.IEC61850Server;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Banco de la realimentación del mando en el simulador.
 *
 * Hasta que se implementó, una orden entraba y no pasaba nada: la librería aceptaba el Oper,
 * respondía positivo, y el stVal se quedaba quieto. Es el mismo cuadro que el 07-08 hubo que
 * detectar contra un IED real —el equipo dice que sí y el aparato no se mueve—, sólo que
 * visto del lado servidor. Sin el estado moviéndose no se puede ejercitar la verificación de
 * posición del cliente, que es lo único que separa las dos cosas.
 *
 * Se levanta el servidor por la ruta de la aplicación —IEC61850Server.start(), que se registra
 * a sí mismo como listener— y no con un ServerSap suelto: la realimentación vive en su
 * write(), así que un banco que ponga su propio listener no probaría nada.
 *
 * El modelo es sintético y se construye acá: un SPC (stVal booleano) y un DPC (stVal de dos
 * bits), los dos con ctlModel=1, que es el único que iec61850bean atiende del lado servidor.
 *
 * Uso:  java -cp "classes;lib\*;test" TestRealimentacionMando [puerto]
 */
public class TestRealimentacionMando {

    private static int ok = 0, fallas = 0;

    private static void check(String caso, boolean cond, String detalle) {
        if (cond) ok++; else fallas++;
        System.out.printf("  [%s] %-46s %s%n", cond ? "OK" : "FALLA", caso, detalle == null ? "" : detalle);
    }

    /** Un LD con un SPC y un DPC comandables directo, y un SPS que NO es comandable. */
    private static String modelo() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<SCL xmlns=\"http://www.iec.ch/61850/2003/SCL\">\n"
            + "  <Header id=\"BANCO\"/>\n"
            + "  <IED name=\"SIMIED\"><AccessPoint name=\"AP1\"><Server>\n"
            + "    <Authentication/>\n"
            + "    <LDevice inst=\"CTRL\">\n"
            + "      <LN0 lnClass=\"LLN0\" inst=\"\" lnType=\"T_LLN0\"/>\n"
            + "      <LN lnClass=\"GGIO\" inst=\"1\" lnType=\"T_GGIO\">\n"
            + "        <DOI name=\"SPCSO1\"><DAI name=\"ctlModel\"><Val>1</Val></DAI></DOI>\n"
            + "      </LN>\n"
            + "      <LN lnClass=\"CSWI\" inst=\"1\" lnType=\"T_CSWI\">\n"
            + "        <DOI name=\"Pos\"><DAI name=\"ctlModel\"><Val>1</Val></DAI></DOI>\n"
            + "      </LN>\n"
            + "    </LDevice>\n"
            + "  </Server></AccessPoint></IED>\n"
            + "  <DataTypeTemplates>\n"
            + "    <LNodeType id=\"T_LLN0\" lnClass=\"LLN0\"><DO name=\"Mod\" type=\"T_INC\"/></LNodeType>\n"
            + "    <LNodeType id=\"T_GGIO\" lnClass=\"GGIO\">\n"
            + "      <DO name=\"SPCSO1\" type=\"T_SPC\"/><DO name=\"Ind1\" type=\"T_SPS\"/>\n"
            + "    </LNodeType>\n"
            + "    <LNodeType id=\"T_CSWI\" lnClass=\"CSWI\"><DO name=\"Pos\" type=\"T_DPC\"/></LNodeType>\n"
            + "    <DOType id=\"T_INC\" cdc=\"INC\">\n"
            + "      <DA name=\"stVal\" bType=\"Enum\" type=\"T_MOD\" fc=\"ST\"/>\n"
            + "    </DOType>\n"
            + "    <DOType id=\"T_SPS\" cdc=\"SPS\"><DA name=\"stVal\" bType=\"BOOLEAN\" fc=\"ST\"/></DOType>\n"
            + "    <DOType id=\"T_SPC\" cdc=\"SPC\">\n"
            + "      <DA name=\"stVal\" bType=\"BOOLEAN\" fc=\"ST\"/>\n"
            + "      <DA name=\"t\" bType=\"Timestamp\" fc=\"ST\"/>\n"
            + "      <DA name=\"ctlModel\" bType=\"Enum\" type=\"T_CTL\" fc=\"CF\"/>\n"
            + "      <DA name=\"Oper\" bType=\"Struct\" type=\"T_OPER_B\" fc=\"CO\"/>\n"
            + "    </DOType>\n"
            + "    <DOType id=\"T_DPC\" cdc=\"DPC\">\n"
            + "      <DA name=\"stVal\" bType=\"Dbpos\" fc=\"ST\"/>\n"
            + "      <DA name=\"t\" bType=\"Timestamp\" fc=\"ST\"/>\n"
            + "      <DA name=\"ctlModel\" bType=\"Enum\" type=\"T_CTL\" fc=\"CF\"/>\n"
            + "      <DA name=\"Oper\" bType=\"Struct\" type=\"T_OPER_B\" fc=\"CO\"/>\n"
            + "    </DOType>\n"
            + "    <DAType id=\"T_OPER_B\">\n"
            + "      <BDA name=\"ctlVal\" bType=\"BOOLEAN\"/>\n"
            + "      <BDA name=\"origin\" bType=\"Struct\" type=\"T_ORG\"/>\n"
            + "      <BDA name=\"ctlNum\" bType=\"INT8U\"/>\n"
            + "      <BDA name=\"T\" bType=\"Timestamp\"/>\n"
            + "      <BDA name=\"Test\" bType=\"BOOLEAN\"/>\n"
            + "      <BDA name=\"Check\" bType=\"Check\"/>\n"
            + "    </DAType>\n"
            + "    <DAType id=\"T_ORG\">\n"
            + "      <BDA name=\"orCat\" bType=\"Enum\" type=\"T_ORCAT\"/>\n"
            + "      <BDA name=\"orIdent\" bType=\"Octet64\"/>\n"
            + "    </DAType>\n"
            + "    <EnumType id=\"T_MOD\"><EnumVal ord=\"1\">on</EnumVal></EnumType>\n"
            + "    <EnumType id=\"T_CTL\">\n"
            + "      <EnumVal ord=\"0\">status-only</EnumVal>\n"
            + "      <EnumVal ord=\"1\">direct-with-normal-security</EnumVal>\n"
            + "    </EnumType>\n"
            + "    <EnumType id=\"T_ORCAT\">\n"
            + "      <EnumVal ord=\"2\">bay-control</EnumVal>\n"
            + "      <EnumVal ord=\"3\">remote-control</EnumVal>\n"
            + "    </EnumType>\n"
            + "  </DataTypeTemplates>\n"
            + "</SCL>\n";
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 10402;

        Path cid = Files.createTempFile("realim", ".cid");
        Files.write(cid, modelo().getBytes(StandardCharsets.UTF_8));
        cid.toFile().deleteOnExit();

        IEC61850Server srv = new IEC61850Server();
        if (srv.getAvailableIEDs(cid.toString()).isEmpty()) {
            check("el modelo del banco carga", false, "no carga"); resumen(); return;
        }
        if (!srv.loadSclFile(cid.toString(), 0)) {
            check("el modelo del banco carga", false, "loadSclFile devolvio false"); resumen(); return;
        }
        check("el modelo del banco carga", true, null);
        if (!srv.start(port)) { check("el servidor arranca", false, "puerto " + port); resumen(); return; }
        check("el servidor arranca", true, "puerto " + port);

        IEC61850Client cli = new IEC61850Client();
        cli.connect("127.0.0.1", port);

        // ── SPC: stVal booleano ────────────────────────────────────────────────
        System.out.println("\n== SPC: el mando mueve un stVal booleano ==");
        String refSpc = "SIMIEDCTRL/GGIO1.SPCSO1";
        FcModelNode operSpc = oper(cli, refSpc);
        check("el punto expone Oper", operSpc != null, refSpc);
        if (operSpc != null) {
            check("ctlModel = 1", cli.getCtlModelValue(operSpc) == 1,
                  "es el unico que la libreria atiende del lado servidor");
            String antes = cli.readValue(refSpc + ".stVal", Fc.ST);
            cli.operateControl(operSpc, "true", false, "banco");
            String despues = cli.readValue(refSpc + ".stVal", Fc.ST);
            check("stVal pasa de false a true", "false".equalsIgnoreCase(antes) && "true".equalsIgnoreCase(despues),
                  antes + " -> " + despues);

            // Y vuelve: si sólo supiera poner true, no seria realimentacion sino un latch.
            cli.operateControl(operSpc, "false", false, "banco");
            check("y vuelve a false", "false".equalsIgnoreCase(cli.readValue(refSpc + ".stVal", Fc.ST)),
                  null);
        }

        // ── DPC: stVal de dos bits ─────────────────────────────────────────────
        System.out.println("\n== DPC: el ctlVal booleano se traduce a la posicion ==");
        String refDpc = "SIMIEDCTRL/CSWI1.Pos";
        FcModelNode operDpc = oper(cli, refDpc);
        check("el punto expone Oper", operDpc != null, refDpc);
        if (operDpc != null) {
            cli.operateControl(operDpc, "true", false, "banco");
            String cerrado = cli.readValue(refDpc + ".stVal", Fc.ST);
            check("cerrar deja la posicion en on", "on".equalsIgnoreCase(String.valueOf(cerrado)), String.valueOf(cerrado));
            cli.operateControl(operDpc, "false", false, "banco");
            String abierto = cli.readValue(refDpc + ".stVal", Fc.ST);
            check("abrir deja la posicion en off", "off".equalsIgnoreCase(String.valueOf(abierto)), String.valueOf(abierto));
        }

        // ── La marca de tiempo ─────────────────────────────────────────────────
        System.out.println("\n== la marca de tiempo del estado se actualiza ==");
        // Sin esto un cliente que mire el t no veria que el valor es nuevo.
        String tAntes = cli.readValue(refSpc + ".t", Fc.ST);
        Thread.sleep(1100);
        if (operSpc != null) cli.operateControl(operSpc, "true", false, "banco");
        String tDespues = cli.readValue(refSpc + ".t", Fc.ST);
        check("t cambia con la maniobra", tAntes != null && !tAntes.equals(tDespues),
              tAntes + " -> " + tDespues);

        // ── Lo que NO tiene que tocar ──────────────────────────────────────────
        System.out.println("\n== no toca lo que no es un mando ==");
        // Una escritura comun a un dato de estado no debe disparar la realimentacion, y un
        // punto sin rama de control no debe verse afectado por maniobrar otro.
        String indAntes = cli.readValue("SIMIEDCTRL/GGIO1.Ind1.stVal", Fc.ST);
        if (operDpc != null) cli.operateControl(operDpc, "true", false, "banco");
        String indDespues = cli.readValue("SIMIEDCTRL/GGIO1.Ind1.stVal", Fc.ST);
        check("un punto ajeno no se mueve", String.valueOf(indAntes).equals(String.valueOf(indDespues)),
              indAntes + " -> " + indDespues);

        cli.disconnect();
        srv.stop();
        resumen();
    }

    private static FcModelNode oper(IEC61850Client cli, String refDo) {
        ModelNode co = cli.getServerModel().findModelNode(refDo, Fc.CO);
        if (co == null) return null;
        ModelNode op = co.getChild("Oper");
        return (op instanceof FcModelNode) ? (FcModelNode) op : null;
    }

    private static void resumen() {
        System.out.printf("%n%d pasaron, %d fallaron%n", ok, fallas);
        if (fallas > 0) System.exit(1);
    }
}
