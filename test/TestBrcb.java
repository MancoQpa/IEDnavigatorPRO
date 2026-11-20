import com.beanit.iec61850bean.*;
import com.iednavigator.IEC61850Server;

import java.io.File;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Reportes con buffer (BRCB) en el simulador. Hasta 2026-11-15 iec61850bean no mandaba
 * reportes de un BRCB: RptEna=true se aceptaba y no pasaba nada. Ahora ReportadorBrcb:
 *
 *   1. Habilitar y GI: llega el reporte con EntryID de 8 bytes, BufOvfl, ConfRev y los valores.
 *   2. Un cambio de valor llega como reporte dchg con EntryID nuevo.
 *   3. Con la asociación cerrada RptEna queda en false y los cambios se guardan; al volver,
 *      escribiendo la última EntryID recibida, llegan solo los que faltaban, en orden.
 *   4. PurgeBuf vacía el buffer; una EntryID desconocida se rechaza.
 *   5. Otro cliente no puede habilitar un BRCB habilitado por otro.
 *   6. OptFlds de un URCB se puede escribir aunque el SCL traiga dataRef/configRef.
 *
 * Uso: java -cp "classes;lib\*;test" TestBrcb
 */
public class TestBrcb {
    static int ok = 0, mal = 0;
    static void chk(String que, boolean c) {
        System.out.println((c ? "OK    " : "FALLA ") + que);
        if (c) ok++; else mal++;
    }

    static final String SCL = "<?xml version='1.0' encoding='UTF-8'?>"
        + "<SCL xmlns='http://www.iec.ch/61850/2003/SCL' version='2007' revision='B'><Header id='T'/>"
        + "<IED name='BUF'><AccessPoint name='AP1'><Server><Authentication/>"
        + "<LDevice inst='LD0'><LN0 lnClass='LLN0' inst='' lnType='LLN0_T'>"
        + " <DataSet name='ds'><FCDA ldInst='LD0' lnClass='LLN0' doName='Beh' daName='stVal' fc='ST'/>"
        + "  <FCDA ldInst='LD0' lnClass='LLN0' doName='Cnt' daName='stVal' fc='ST'/></DataSet>"
        + " <ReportControl name='brcb' datSet='ds' rptID='BUF/brcb' confRev='3' buffered='true' bufTime='0' intgPd='0'>"
        + "  <TrgOps dchg='true' gi='true'/>"
        + "  <OptFields seqNum='true' timeStamp='true' dataSet='true' reasonCode='true' dataRef='true'"
        + "   entryID='true' bufOvfl='true' configRef='true'/>"
        + "  <RptEnabled max='1'/></ReportControl>"
        + " <ReportControl name='urcb' datSet='ds' rptID='BUF/urcb' confRev='3' buffered='false'>"
        + "  <TrgOps dchg='true' gi='true'/>"
        + "  <OptFields seqNum='true' dataRef='true' configRef='true'/><RptEnabled max='1'/></ReportControl>"
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

    static final int PUERTO = 23140;

    static final class Cliente {
        final List<Report> rep = Collections.synchronizedList(new ArrayList<>());
        final List<String> err = Collections.synchronizedList(new ArrayList<>());
        ClientAssociation as;
        ServerModel m;
        Brcb b;

        Cliente() throws Exception {
            as = new ClientSap().associate(InetAddress.getByName("127.0.0.1"), PUERTO, null,
                    new ClientEventListener() {
                        public void newReport(Report r) { rep.add(r); }
                        public void associationClosed(java.io.IOException e) { err.add(String.valueOf(e)); }
                    });
            m = as.retrieveModel();
            b = m.getBrcb("BUFLD0/LLN0.brcb01");
            if (b == null) b = m.getBrcb("BUFLD0/LLN0.brcb");
            as.getRcbValues(b);
        }
    }

    static String cnt(Report r) {
        List<FcModelNode> v = r.getValues();
        boolean[] inc = r.getInclusionBitString();
        int k = 0;
        for (int i = 0; i < inc.length; i++) {
            if (!inc[i]) continue;
            if (i == 1) return ((BasicDataAttribute) v.get(k)).getValueString();
            k++;
        }
        return null;
    }

    public static void main(String[] a) throws Exception {
        File f = File.createTempFile("brcb", ".cid");
        f.deleteOnExit();
        Files.write(f.toPath(), SCL.getBytes(StandardCharsets.UTF_8));
        IEC61850Server srv = new IEC61850Server();
        chk("carga el SCL", srv.loadSclFile(f.getAbsolutePath()));
        chk("servidor iniciado", srv.start(PUERTO));
        String ref = "BUFLD0/LLN0.Cnt.stVal";

        // 1. Habilitar y GI
        Cliente c = new Cliente();
        chk("el cliente ve el BRCB", c.b != null);
        chk("1. OptFlds con entryID y bufOvfl", c.b.getOptFlds().isEntryId() && c.b.getOptFlds().isBufferOverflow());
        c.as.enableReporting(c.b);
        c.as.getRcbValues(c.b);
        chk("1. RptEna se lee en true", c.b.getRptEna().getValue());
        c.as.startGi(c.b);
        Thread.sleep(800);
        chk("1. llegó el reporte de GI (" + c.rep.size() + ")", c.rep.size() == 1);
        Report gi = c.rep.isEmpty() ? null : c.rep.get(0);
        if (gi != null) {
            chk("1. GI: Cnt=" + cnt(gi) + ", ConfRev=" + gi.getConfRev() + ", BufOvfl=" + gi.getBufOvfl(),
                    "42".equals(cnt(gi)) && Long.valueOf(3).equals(gi.getConfRev()) && Boolean.FALSE.equals(gi.getBufOvfl()));
            chk("1. GI con EntryID de 8 bytes y motivo GI",
                    gi.getEntryId() != null && gi.getEntryId().getValue().length == 8
                            && gi.getReasonCodes().get(0).isGeneralInterrogation());
        }

        // 2. Cambio de valor
        srv.setDataValue(ref, "43");
        Thread.sleep(800);
        chk("2. llegó el reporte dchg (" + c.rep.size() + ")", c.rep.size() == 2);
        byte[] ultimo = null;
        if (c.rep.size() == 2) {
            Report r = c.rep.get(1);
            ultimo = r.getEntryId().getValue();
            chk("2. Cnt=" + cnt(r) + ", motivo dchg, EntryID distinto",
                    "43".equals(cnt(r)) && r.getReasonCodes().get(0).isDataChange()
                            && gi != null && !Arrays.equals(ultimo, gi.getEntryId().getValue()));
        }

        // 3. Asociación cerrada: se guarda; al volver llegan los que faltaban
        c.as.close();
        Thread.sleep(500);
        srv.setDataValue(ref, "44");
        srv.setDataValue(ref, "45");
        Thread.sleep(300);
        Cliente c2 = new Cliente();
        chk("3. RptEna quedó en false tras la caída", !c2.b.getRptEna().getValue());
        if (ultimo != null) {
            c2.b.getEntryId().setValue(ultimo);
            List<ServiceError> e = c2.as.setRcbValues(c2.b, false, false, false, false, false, false, false, true);
            chk("3. se acepta la última EntryID recibida", e.get(0) == null);
        }
        c2.as.enableReporting(c2.b);
        Thread.sleep(800);
        List<String> vals = new ArrayList<>();
        for (Report r : c2.rep) vals.add(cnt(r));
        chk("3. llegaron solo los que faltaban, en orden: " + vals, vals.equals(Arrays.asList("44", "45")));

        // 5. Otro cliente no puede habilitarlo
        Cliente c3 = new Cliente();
        boolean rechazado;
        try {
            c3.as.enableReporting(c3.b);
            rechazado = false;
        } catch (ServiceError e) {
            rechazado = true;
        }
        chk("5. otro cliente no puede habilitar el BRCB tomado", rechazado);

        // 4. PurgeBuf y EntryID desconocida
        c2.as.disableReporting(c2.b);
        c2.b.getPurgeBuf().setValue(true);
        List<ServiceError> e = c2.as.setRcbValues(c2.b, false, false, false, false, false, false, true, false);
        chk("4. PurgeBuf aceptado", e.get(0) == null);
        c2.b.getEntryId().setValue(new byte[] {9, 9, 9, 9, 9, 9, 9, 9});
        e = c2.as.setRcbValues(c2.b, false, false, false, false, false, false, false, true);
        chk("4. EntryID desconocida rechazada", e.get(0) != null);
        c2.b.getEntryId().setValue(new byte[8]);
        c2.as.setRcbValues(c2.b, false, false, false, false, false, false, false, true);
        c2.rep.clear();
        c2.as.enableReporting(c2.b);
        Thread.sleep(500);
        chk("4. tras PurgeBuf no llega nada viejo (" + c2.rep.size() + ")", c2.rep.isEmpty());

        // 6. OptFlds de un URCB
        Urcb u = c3.m.getUrcb("BUFLD0/LLN0.urcb01");
        if (u == null) u = c3.m.getUrcb("BUFLD0/LLN0.urcb");
        c3.as.getRcbValues(u);
        u.getOptFlds().setReasonForInclusion(true);
        e = c3.as.setRcbValues(u, false, false, true, false, false, false, false, false);
        chk("6. OptFlds del URCB se puede escribir", e.get(0) == null);

        chk("las asociaciones no se cayeron " + c2.err + c3.err, c2.err.isEmpty() && c3.err.isEmpty());
        c2.as.close();
        c3.as.close();
        srv.stop();
        System.out.println(ok + " pasaron, " + mal + " fallaron");
        if (mal == 0) System.out.println("TODO OK");
        System.exit(mal == 0 ? 0 : 1);
    }
}
