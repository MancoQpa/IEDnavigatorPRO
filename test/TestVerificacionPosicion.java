import com.beanit.iec61850bean.*;
import com.iednavigator.IEC61850Client;
import com.iednavigator.IEC61850Server;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Banco de la verificación de posición del cliente, contra el simulador.
 *
 * `verifyControlFeedback()` es el lazo cerrado que se agregó el 06-08 para el vano de
 * capacitores: el ack MMS del OPERATE sólo dice "comando aceptado", y lo que importa es si el
 * aparato se movió. El 07-08 esa distinción fue el hallazgo de la jornada — un OPERATE
 * aceptado que dejó el interruptor donde estaba— y desde entonces la rama que NO confirma
 * tiene su propio encabezado en el registro.
 *
 * Hasta que el simulador movió el estado, esta parte del cliente sólo se podía ejercitar
 * contra hardware de subestación. Acá se ejercitan sus tres desenlaces sin salir de la
 * máquina.
 *
 * Las tres ramas de FeedbackResult:
 *   verifiable=false                → el DO no expone stVal legible, no hay nada que confirmar
 *   verifiable=true,  confirmed=true  → el estado alcanzó lo comandado
 *   verifiable=true,  confirmed=false → se pudo leer y NO llegó: es el caso del 07-08
 *
 * Uso:  java -cp "classes;lib\*;test" TestVerificacionPosicion [puerto]
 */
public class TestVerificacionPosicion {

    private static int ok = 0, fallas = 0;

    private static void check(String caso, boolean cond, String detalle) {
        if (cond) ok++; else fallas++;
        System.out.printf("  [%s] %-48s %s%n", cond ? "OK" : "FALLA", caso, detalle == null ? "" : detalle);
    }

    /**
     * Tres puntos comandables, uno por desenlace:
     *   GGIO1.SPCSO1  SPC con stVal booleano  -> confirma
     *   CSWI1.Pos     DPC con stVal de 2 bits -> confirma, y prueba la traducción on/off
     *   GGIO1.SPCSO2  Oper SIN rama ST        -> no verificable
     *   GGIO1.SPCSO3  stVal entero            -> verificable y no confirma (ver el banco)
     */
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
            + "        <DOI name=\"SPCSO2\"><DAI name=\"ctlModel\"><Val>1</Val></DAI></DOI>\n"
            + "        <DOI name=\"SPCSO3\"><DAI name=\"ctlModel\"><Val>1</Val></DAI></DOI>\n"
            + "      </LN>\n"
            + "      <LN lnClass=\"CSWI\" inst=\"1\" lnType=\"T_CSWI\">\n"
            + "        <DOI name=\"Pos\"><DAI name=\"ctlModel\"><Val>1</Val></DAI></DOI>\n"
            + "      </LN>\n"
            + "    </LDevice>\n"
            + "  </Server></AccessPoint></IED>\n"
            + "  <DataTypeTemplates>\n"
            + "    <LNodeType id=\"T_LLN0\" lnClass=\"LLN0\"><DO name=\"Mod\" type=\"T_INC\"/></LNodeType>\n"
            + "    <LNodeType id=\"T_GGIO\" lnClass=\"GGIO\">\n"
            + "      <DO name=\"SPCSO1\" type=\"T_SPC\"/>\n"
            + "      <DO name=\"SPCSO2\" type=\"T_SPC_SINEST\"/>\n"
            + "      <DO name=\"SPCSO3\" type=\"T_SPC_ENTERO\"/>\n"
            + "    </LNodeType>\n"
            + "    <LNodeType id=\"T_CSWI\" lnClass=\"CSWI\"><DO name=\"Pos\" type=\"T_DPC\"/></LNodeType>\n"
            + "    <DOType id=\"T_INC\" cdc=\"INC\"><DA name=\"stVal\" bType=\"Enum\" type=\"T_MOD\" fc=\"ST\"/></DOType>\n"
            + "    <DOType id=\"T_SPC\" cdc=\"SPC\">\n"
            + "      <DA name=\"stVal\" bType=\"BOOLEAN\" fc=\"ST\"/>\n"
            + "      <DA name=\"t\" bType=\"Timestamp\" fc=\"ST\"/>\n"
            + "      <DA name=\"ctlModel\" bType=\"Enum\" type=\"T_CTL\" fc=\"CF\"/>\n"
            + "      <DA name=\"Oper\" bType=\"Struct\" type=\"T_OPER_B\" fc=\"CO\"/>\n"
            + "    </DOType>\n"
            // Sin rama ST: el DO se puede comandar pero no hay posicion que leer.
            + "    <DOType id=\"T_SPC_SINEST\" cdc=\"SPC\">\n"
            + "      <DA name=\"ctlModel\" bType=\"Enum\" type=\"T_CTL\" fc=\"CF\"/>\n"
            + "      <DA name=\"Oper\" bType=\"Struct\" type=\"T_OPER_B\" fc=\"CO\"/>\n"
            + "    </DOType>\n"
            // stVal entero: el cliente lo LEE, pero un comando binario nunca va a coincidir.
            + "    <DOType id=\"T_SPC_ENTERO\" cdc=\"INC\">\n"
            + "      <DA name=\"stVal\" bType=\"INT8\" fc=\"ST\"/>\n"
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
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 10412;

        Path cid = Files.createTempFile("verifpos", ".cid");
        Files.write(cid, modelo().getBytes(StandardCharsets.UTF_8));
        cid.toFile().deleteOnExit();

        IEC61850Server srv = new IEC61850Server();
        if (srv.getAvailableIEDs(cid.toString()).isEmpty() || !srv.loadSclFile(cid.toString(), 0)) {
            check("el modelo del banco carga", false, null); resumen(); return;
        }
        if (!srv.start(port)) { check("el servidor arranca", false, null); resumen(); return; }
        check("el modelo carga y el servidor arranca", true, "puerto " + port);

        IEC61850Client cli = new IEC61850Client();
        cli.connect("127.0.0.1", port);

        // ── 1. Confirma: SPC booleano ─────────────────────────────────────────
        System.out.println("\n== confirma la posicion: SPC con stVal booleano ==");
        FcModelNode spc = oper(cli, "SIMIEDCTRL/GGIO1.SPCSO1");
        cli.operateControl(spc, "true", false, "banco");
        IEC61850Client.FeedbackResult r1 = cli.verifyControlFeedback(spc, "true", 5000);
        check("verificable", r1.verifiable, null);
        check("CONFIRMADA", r1.confirmed, "stVal=" + r1.observed + " en " + r1.elapsedMs + " ms");
        check("el stVal observado es el comandado", "on".equals(r1.observed), r1.observed);

        // ── 2. Confirma: DPC, con la traduccion de posicion ───────────────────
        System.out.println("\n== confirma la posicion: DPC con stVal de dos bits ==");
        FcModelNode dpc = oper(cli, "SIMIEDCTRL/CSWI1.Pos");
        cli.operateControl(dpc, "true", false, "banco");
        IEC61850Client.FeedbackResult r2 = cli.verifyControlFeedback(dpc, "true", 5000);
        check("cerrar confirma en on", r2.verifiable && r2.confirmed && "on".equals(r2.observed),
              r2.observed + " en " + r2.elapsedMs + " ms");
        // Antes de abrir hay que estar cerrado: si no, "confirma en off" daria verde sin que
        // el mando haya hecho nada. Es la misma trampa que un control que pasa porque el
        // estado ya estaba donde se lo queria llevar.
        String previo = cli.readValue("SIMIEDCTRL/CSWI1.Pos.stVal", Fc.ST);
        check("el punto quedo cerrado antes de abrir", "on".equalsIgnoreCase(String.valueOf(previo)),
              "stVal=" + previo + " — sin esto, abrir confirmaria trivialmente");
        cli.operateControl(dpc, "false", false, "banco");
        IEC61850Client.FeedbackResult r3 = cli.verifyControlFeedback(dpc, "false", 5000);
        check("abrir confirma en off", r3.verifiable && r3.confirmed && "off".equals(r3.observed),
              r3.observed + " en " + r3.elapsedMs + " ms");

        // ── 3. No verificable: el DO no expone posicion ───────────────────────
        System.out.println("\n== no verificable: el DO no expone stVal ==");
        // Es la rama que el 07-08 se dejo como [CONTROL OK] a proposito: no hay nada que
        // confirmar, que es distinto de haber verificado y que no cambiara.
        FcModelNode sinEst = oper(cli, "SIMIEDCTRL/GGIO1.SPCSO2");
        check("el punto se puede comandar", sinEst != null, "SPCSO2");
        if (sinEst != null) {
            cli.operateControl(sinEst, "true", false, "banco");
            IEC61850Client.FeedbackResult r4 = cli.verifyControlFeedback(sinEst, "true", 2000);
            check("NO verificable", !r4.verifiable, "observed=" + r4.observed);
            check("no se reporta como confirmada", !r4.confirmed, null);
        }

        // ── 4. Verificable y NO confirma: el caso del 07-08 ───────────────────
        System.out.println("\n== verificable y NO confirma: el caso del 07-08 ==");
        // El punto tiene stVal legible, la orden se acepta, y el estado no llega al valor
        // comandado. En campo fue un interruptor que no se movio; acá se reproduce con un
        // stVal entero, al que un comando binario nunca va a igualar. Lo que se ejercita es
        // la rama del cliente, que es la misma.
        FcModelNode entero = oper(cli, "SIMIEDCTRL/GGIO1.SPCSO3");
        check("el punto se puede comandar", entero != null, "SPCSO3");
        if (entero != null) {
            try { cli.operateControl(entero, "true", false, "banco"); } catch (Exception ignore) {}
            long t0 = System.currentTimeMillis();
            IEC61850Client.FeedbackResult r5 = cli.verifyControlFeedback(entero, "true", 2000);
            long real = System.currentTimeMillis() - t0;
            check("verificable", r5.verifiable, "el stVal se pudo leer: " + r5.observed);
            check("NO confirmada", !r5.confirmed, "ultimo stVal=" + r5.observed);
            check("agoto el timeout, no salio antes", real >= 1900, real + " ms de 2000 pedidos");
        }

        // ── 5. Un comando no binario no es verificable por esta via ───────────
        System.out.println("\n== un comando no binario no se verifica por posicion ==");
        IEC61850Client.FeedbackResult r6 = cli.verifyControlFeedback(spc, "7", 2000);
        check("NO verificable con ctlVal no binario", !r6.verifiable, "'7' no es on/off");

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
