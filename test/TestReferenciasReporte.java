import com.beanit.iec61850bean.*;
import com.iednavigator.IEC61850Server;

import java.io.File;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reportes con data-reference (OptFlds dataRef). El Urcb de iec61850bean anunciaba el campo sin
 * mandar las referencias, y un cliente leía los valores corridos. Ahora ServerAssociation las
 * inserta (ReferenciasEnReporte) y el SclParser respeta dataRef del SCL.
 *
 * El cliente de iec61850bean saltea tantas entradas como miembros incluidos cuando el OptFlds
 * trae data-reference: si las referencias no estuvieran, leería los valores desplazados. Que
 * los valores lleguen bien prueba que el servidor las puso en su lugar.
 *
 *   1. dataRef desde el SCL: el reporte de GI trae los dos miembros con su valor.
 *   2. Sin dataRef (el cliente lo apaga): el reporte sigue igual de bien.
 *
 * Uso: java -cp "classes;lib\*;test" TestReferenciasReporte
 */
public class TestReferenciasReporte {
    static int ok = 0, mal = 0;
    static void chk(String que, boolean c) {
        System.out.println((c ? "OK    " : "FALLA ") + que);
        if (c) ok++; else mal++;
    }

    static final String SCL = "<?xml version='1.0' encoding='UTF-8'?>"
        + "<SCL xmlns='http://www.iec.ch/61850/2003/SCL' version='2007' revision='B'><Header id='T'/>"
        + "<IED name='REF'><AccessPoint name='AP1'><Server><Authentication/>"
        + "<LDevice inst='LD0'><LN0 lnClass='LLN0' inst='' lnType='LLN0_T'>"
        + " <DataSet name='ds'><FCDA ldInst='LD0' lnClass='LLN0' doName='Beh' daName='stVal' fc='ST'/>"
        + "  <FCDA ldInst='LD0' lnClass='LLN0' doName='Cnt' daName='stVal' fc='ST'/></DataSet>"
        + " <ReportControl name='urcb' datSet='ds' rptID='REF/urcb' confRev='7' buffered='false' intgPd='0'>"
        + "  <TrgOps dchg='true' gi='true'/>"
        + "  <OptFields seqNum='true' timeStamp='true' dataSet='true' reasonCode='true' dataRef='true' configRef='true'/>"
        + "  <RptEnabled max='2'/></ReportControl>"
        + " <DOI name='Cnt'><DAI name='stVal'><Val>42</Val></DAI></DOI>"
        + "</LN0></LDevice></Server></AccessPoint></IED>"
        + "<DataTypeTemplates>"
        + " <LNodeType id='LLN0_T' lnClass='LLN0'><DO name='Beh' type='ENS_T'/><DO name='Cnt' type='INS_T'/></LNodeType>"
        + " <DOType id='ENS_T' cdc='ENS'><DA name='stVal' fc='ST' dchg='true' bType='Enum' type='Beh_E'/>"
        + "   <DA name='q' fc='ST' bType='Quality'/><DA name='t' fc='ST' bType='Timestamp'/></DOType>"
        + " <DOType id='INS_T' cdc='INS'><DA name='stVal' fc='ST' dchg='true' bType='INT32'/>"
        + "   <DA name='q' fc='ST' bType='Quality'/><DA name='t' fc='ST' bType='Timestamp'/></DOType>"
        + " <EnumType id='Beh_E'><EnumVal ord='1'>on</EnumVal><EnumVal ord='5'>off</EnumVal></EnumType>"
        + "</DataTypeTemplates></SCL>";

    public static void main(String[] a) throws Exception {
        File f = File.createTempFile("dataref", ".cid");
        f.deleteOnExit();
        Files.write(f.toPath(), SCL.getBytes(StandardCharsets.UTF_8));
        int puerto = 23130;
        IEC61850Server srv = new IEC61850Server();
        chk("carga el SCL", srv.loadSclFile(f.getAbsolutePath()));
        chk("servidor iniciado", srv.start(puerto));

        final List<Report> recibidos = Collections.synchronizedList(new ArrayList<>());
        final List<String> errores = Collections.synchronizedList(new ArrayList<>());
        ClientSap sap = new ClientSap();
        ClientAssociation as = sap.associate(InetAddress.getByName("127.0.0.1"), puerto, null,
                new ClientEventListener() {
                    public void newReport(Report r) { recibidos.add(r); }
                    public void associationClosed(java.io.IOException e) { errores.add(String.valueOf(e)); }
                });
        ServerModel m = as.retrieveModel();
        Urcb u = m.getUrcb("REFLD0/LLN0.urcb01");
        if (u == null) u = m.getUrcb("REFLD0/LLN0.urcb");
        chk("el cliente ve el URCB", u != null);
        as.getRcbValues(u);
        chk("1. OptFlds del SCL con data-reference y config-revision",
                u.getOptFlds().isDataReference() && u.getOptFlds().isConfigRevision());

        as.enableReporting(u);
        as.startGi(u);
        Thread.sleep(1500);
        chk("1. llegó el reporte de GI (" + recibidos.size() + ")", recibidos.size() == 1);
        if (!recibidos.isEmpty()) {
            Report r = recibidos.get(0);
            List<FcModelNode> v = r.getValues();
            String beh = v.size() > 0 ? ((BasicDataAttribute) v.get(0)).getValueString() : "?";
            String cnt = v.size() > 1 ? ((BasicDataAttribute) v.get(1)).getValueString() : "?";
            chk("1. dos valores en su lugar: Beh=" + beh + ", Cnt=" + cnt + ", confRev=" + r.getConfRev(),
                    v.size() == 2 && "42".equals(cnt) && Long.valueOf(7).equals(r.getConfRev()));
        }

        // 2. El cliente apaga data-reference: sigue funcionando
        as.disableReporting(u);
        u.getOptFlds().setDataReference(false);
        as.setRcbValues(u, false, false, false, false, true, false, false, false);
        recibidos.clear();
        as.enableReporting(u);
        as.startGi(u);
        Thread.sleep(1500);
        boolean bien = recibidos.size() == 1 && recibidos.get(0).getValues().size() == 2
                && "42".equals(((BasicDataAttribute) recibidos.get(0).getValues().get(1)).getValueString());
        chk("2. sin data-reference el reporte sigue bien", bien);
        chk("la asociación no se cayó " + errores, errores.isEmpty());

        as.close();
        srv.stop();
        System.out.println(ok + " pasaron, " + mal + " fallaron");
        if (mal == 0) System.out.println("TODO OK");
        System.exit(mal == 0 ? 0 : 1);
    }
}
