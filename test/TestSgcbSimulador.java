import com.beanit.iec61850bean.*;
import com.iednavigator.IEC61850Server;

import java.io.File;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Bloque de grupos de ajuste (LLN0$SP$SGCB) en el simulador. Hasta 2026-11-15 el parser ignoraba
 * &lt;SettingControl&gt; y el SGCB no existía por MMS (la herramienta comercial: "IED has no Setting Group Control
 * Blocks"). Ahora se crea desde el SCL y las escrituras se validan como en un IED:
 *
 *   1. El cliente ve el SGCB con NumOfSG y ActSG del SCL.
 *   2. ActSG dentro de 1..NumOfSG se acepta y actualiza LActTm.
 *   3. ActSG fuera de rango, o escribir NumOfSG, se rechaza.
 *   4. EditSG 0..NumOfSG se acepta.
 *
 * Uso: java -cp "classes;lib\*;test" TestSgcbSimulador
 */
public class TestSgcbSimulador {
    static int ok = 0, mal = 0;
    static void chk(String que, boolean c) {
        System.out.println((c ? "OK    " : "FALLA ") + que);
        if (c) ok++; else mal++;
    }

    static final String SCL = "<?xml version='1.0' encoding='UTF-8'?>"
        + "<SCL xmlns='http://www.iec.ch/61850/2003/SCL' version='2007' revision='B'><Header id='T'/>"
        + "<IED name='PRO'><AccessPoint name='AP1'><Server><Authentication/>"
        + "<LDevice inst='LD0'><LN0 lnClass='LLN0' inst='' lnType='LLN0_T'>"
        + "  <SettingControl numOfSGs='4' actSG='1' resvTms='30'/></LN0></LDevice>"
        + "</Server></AccessPoint></IED>"
        + "<DataTypeTemplates>"
        + " <LNodeType id='LLN0_T' lnClass='LLN0'><DO name='Beh' type='ENS_T'/></LNodeType>"
        + " <DOType id='ENS_T' cdc='ENS'><DA name='stVal' fc='ST' bType='Enum' type='Beh_E'/>"
        + "   <DA name='q' fc='ST' bType='Quality'/><DA name='t' fc='ST' bType='Timestamp'/></DOType>"
        + " <EnumType id='Beh_E'><EnumVal ord='1'>on</EnumVal><EnumVal ord='5'>off</EnumVal></EnumType>"
        + "</DataTypeTemplates></SCL>";

    static boolean escribir(ClientAssociation as, ServerModel m, String campo, int valor) {
        BdaInt8U n = (BdaInt8U) m.findModelNode("PROLD0/LLN0.SGCB." + campo, Fc.SP);
        n.setValue((short) valor);
        try {
            as.setDataValues(n);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void main(String[] a) throws Exception {
        File f = File.createTempFile("sgcb", ".cid");
        f.deleteOnExit();
        Files.write(f.toPath(), SCL.getBytes(StandardCharsets.UTF_8));
        int puerto = 23120;
        IEC61850Server srv = new IEC61850Server();
        chk("carga el SCL", srv.loadSclFile(f.getAbsolutePath()));
        chk("servidor iniciado", srv.start(puerto));

        ClientSap sap = new ClientSap();
        ClientAssociation as = sap.associate(InetAddress.getByName("127.0.0.1"), puerto, null,
                new ClientEventListener() {
                    public void newReport(Report r) { }
                    public void associationClosed(java.io.IOException e) { }
                });
        ServerModel m = as.retrieveModel();
        FcModelNode sgcb = (FcModelNode) m.findModelNode("PROLD0/LLN0.SGCB", Fc.SP);
        chk("1. el cliente ve LLN0$SP$SGCB", sgcb != null);
        as.getDataValues(sgcb);
        BdaInt8U num = (BdaInt8U) sgcb.getChild("NumOfSG");
        BdaInt8U act = (BdaInt8U) sgcb.getChild("ActSG");
        chk("1. NumOfSG = " + num.getValue() + ", ActSG = " + act.getValue(), num.getValue() == 4 && act.getValue() == 1);
        BdaTimestamp lact = (BdaTimestamp) sgcb.getChild("LActTm");
        java.time.Instant antes = lact.getInstant();

        Thread.sleep(50);
        chk("2. ActSG = 3 se acepta", escribir(as, m, "ActSG", 3));
        Thread.sleep(500);
        as.getDataValues(sgcb);
        chk("2. se lee ActSG = " + act.getValue(), act.getValue() == 3);
        chk("2. LActTm se actualizó", lact.getInstant() != null && antes != null && lact.getInstant().isAfter(antes));

        chk("3. ActSG = 9 (fuera de 1..4) se rechaza", !escribir(as, m, "ActSG", 9));
        chk("3. ActSG = 0 se rechaza", !escribir(as, m, "ActSG", 0));
        chk("3. escribir NumOfSG se rechaza", !escribir(as, m, "NumOfSG", 2));
        as.getDataValues(sgcb);
        chk("3. ActSG sigue en 3 y NumOfSG en 4", act.getValue() == 3 && num.getValue() == 4);

        chk("4. EditSG = 2 se acepta", escribir(as, m, "EditSG", 2));

        as.close();
        srv.stop();
        System.out.println(ok + " pasaron, " + mal + " fallaron");
        if (mal == 0) System.out.println("TODO OK");
        System.exit(mal == 0 ? 0 : 1);
    }
}
