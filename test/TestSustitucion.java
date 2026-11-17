import com.beanit.iec61850bean.*;
import com.iednavigator.IEC61850Server;

import java.io.File;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Sustitución de valores (IEC 61850-7-3, FC=SV) en el simulador, con un cliente MMS:
 *
 *   1. SPS: subVal=true, subQ, subID y subEna=true -> stVal publica true y q queda "substituted".
 *   2. Mientras está sustituido, el proceso cambia stVal: no se publica.
 *   3. subEna=false -> vuelve el valor de proceso (el último, el que cambió en 2) y q sin sustituir.
 *   4. MV: subMag.f=12.5 y subEna -> mag.f publica 12.5; al quitarla vuelve el valor de proceso.
 *
 * Uso: java -cp "classes;lib\*;test" TestSustitucion
 */
public class TestSustitucion {
    static int ok = 0, mal = 0;
    static void chk(String que, boolean c) {
        System.out.println((c ? "OK    " : "FALLA ") + que);
        if (c) ok++; else mal++;
    }

    static final String SCL = "<?xml version='1.0' encoding='UTF-8'?>"
        + "<SCL xmlns='http://www.iec.ch/61850/2003/SCL' version='2007' revision='B'><Header id='T'/>"
        + "<IED name='SUS'><AccessPoint name='AP1'><Server><Authentication/>"
        + "<LDevice inst='LD0'><LN0 lnClass='LLN0' inst='' lnType='LLN0_T'>"
        + " <DOI name='Med'><SDI name='mag'><DAI name='f'><Val>100</Val></DAI></SDI></DOI>"
        + "</LN0></LDevice></Server></AccessPoint></IED>"
        + "<DataTypeTemplates>"
        + " <LNodeType id='LLN0_T' lnClass='LLN0'><DO name='Alm' type='SPS_T'/><DO name='Med' type='MV_T'/></LNodeType>"
        + " <DOType id='SPS_T' cdc='SPS'><DA name='stVal' fc='ST' dchg='true' bType='BOOLEAN'/>"
        + "  <DA name='q' fc='ST' qchg='true' bType='Quality'/><DA name='t' fc='ST' bType='Timestamp'/>"
        + "  <DA name='subEna' fc='SV' bType='BOOLEAN'/><DA name='subVal' fc='SV' bType='BOOLEAN'/>"
        + "  <DA name='subQ' fc='SV' bType='Quality'/><DA name='subID' fc='SV' bType='VisString64'/></DOType>"
        + " <DOType id='MV_T' cdc='MV'><DA name='mag' fc='MX' dchg='true' bType='Struct' type='AV_T'/>"
        + "  <DA name='q' fc='MX' qchg='true' bType='Quality'/><DA name='t' fc='MX' bType='Timestamp'/>"
        + "  <DA name='subEna' fc='SV' bType='BOOLEAN'/><DA name='subMag' fc='SV' bType='Struct' type='AV_T'/>"
        + "  <DA name='subQ' fc='SV' bType='Quality'/><DA name='subID' fc='SV' bType='VisString64'/></DOType>"
        + " <DAType id='AV_T'><BDA name='f' bType='FLOAT32'/></DAType>"
        + "</DataTypeTemplates></SCL>";

    static ClientAssociation as;
    static ServerModel m;

    static void escribir(String ref, Fc fc, Object valor) throws Exception {
        BasicDataAttribute b = (BasicDataAttribute) m.findModelNode(ref, fc);
        if (b instanceof BdaBoolean) ((BdaBoolean) b).setValue((Boolean) valor);
        else if (b instanceof BdaFloat32) ((BdaFloat32) b).setFloat((Float) valor);
        else if (b instanceof BdaVisibleString) ((BdaVisibleString) b).setValue((String) valor);
        as.setDataValues(b);
    }

    static boolean stVal() throws Exception {
        BdaBoolean b = (BdaBoolean) m.findModelNode("SUSLD0/LLN0.Alm.stVal", Fc.ST);
        as.getDataValues(b);
        return b.getValue();
    }

    static BdaQuality q(String doRef, Fc fc) throws Exception {
        BdaQuality b = (BdaQuality) m.findModelNode(doRef + ".q", fc);
        as.getDataValues(b);
        return b;
    }

    static float mag() throws Exception {
        BdaFloat32 b = (BdaFloat32) m.findModelNode("SUSLD0/LLN0.Med.mag.f", Fc.MX);
        as.getDataValues(b);
        return b.getFloat();
    }

    public static void main(String[] a) throws Exception {
        File f = File.createTempFile("sustitucion", ".cid");
        f.deleteOnExit();
        Files.write(f.toPath(), SCL.getBytes(StandardCharsets.UTF_8));
        IEC61850Server srv = new IEC61850Server();
        chk("carga el SCL", srv.loadSclFile(f.getAbsolutePath()));
        chk("servidor iniciado", srv.start(23200));
        as = new ClientSap().associate(InetAddress.getByName("127.0.0.1"), 23200, null,
                new ClientEventListener() {
                    public void newReport(Report r) { }
                    public void associationClosed(java.io.IOException e) { }
                });
        m = as.retrieveModel();
        String alm = "SUSLD0/LLN0.Alm";

        chk("antes: stVal = false", !stVal());
        escribir(alm + ".subVal", Fc.SV, true);
        escribir(alm + ".subID", Fc.SV, "Prueba");
        escribir(alm + ".subEna", Fc.SV, true);
        Thread.sleep(400);
        chk("1. sustituido: stVal publica true", stVal());
        chk("1. q marcada substituted", q(alm, Fc.ST).isSubstituted());

        escribir(alm + ".subVal", Fc.SV, false);      // ahora sustituye con false
        Thread.sleep(300);
        chk("2. cambiar subVal con la sustitución activa: publica false", !stVal());
        srv.setDataValue(alm + ".stVal", "true");      // el proceso cambia mientras tanto
        Thread.sleep(300);
        chk("2. el cambio del proceso no se publica: sigue false", !stVal());

        escribir(alm + ".subEna", Fc.SV, false);
        Thread.sleep(400);
        chk("3. sin sustitución: vuelve el último valor de proceso (true)", stVal());
        chk("3. q ya no dice substituted", !q(alm, Fc.ST).isSubstituted());

        String med = "SUSLD0/LLN0.Med";
        chk("4. antes: mag.f = 100 (" + mag() + ")", mag() == 100f);
        escribir(med + ".subMag.f", Fc.SV, 12.5f);
        escribir(med + ".subEna", Fc.SV, true);
        Thread.sleep(400);
        chk("4. sustituido: mag.f = 12.5 (" + mag() + ")", mag() == 12.5f);
        chk("4. q de MX marcada substituted", q(med, Fc.MX).isSubstituted());
        escribir(med + ".subEna", Fc.SV, false);
        Thread.sleep(400);
        chk("4. sin sustitución: vuelve mag.f = 100 (" + mag() + ")", mag() == 100f);

        as.close();
        srv.stop();
        System.out.println(ok + " pasaron, " + mal + " fallaron");
        if (mal == 0) System.out.println("TODO OK");
        System.exit(mal == 0 ? 0 : 1);
    }
}
