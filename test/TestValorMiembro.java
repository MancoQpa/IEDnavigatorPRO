package com.iednavigator;

import com.beanit.iec61850bean.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Valor de un miembro de reporte que es un DO entero (FCD). Antes la celda "Valor Nuevo"
 * mostraba todos los atributos en orden (en un Pos empezaba por origin=) y el stVal quedaba
 * fuera de la vista. Ahora muestra el valor principal: stVal, general, mag (f o i)...
 *
 * Uso: java -cp "classes;lib\*;test" com.iednavigator.TestValorMiembro
 */
public class TestValorMiembro {
    static int ok = 0, mal = 0;
    static void chk(String que, boolean c) {
        System.out.println((c ? "OK    " : "FALLA ") + que);
        if (c) ok++; else mal++;
    }

    static final String SCL = "<?xml version='1.0' encoding='UTF-8'?>"
        + "<SCL xmlns='http://www.iec.ch/61850/2003/SCL' version='2007' revision='B'><Header id='T'/>"
        + "<IED name='VM'><AccessPoint name='AP1'><Server><Authentication/>"
        + "<LDevice inst='LD0'><LN0 lnClass='LLN0' inst='' lnType='LLN0_T'>"
        + " <DOI name='Cnt'><DAI name='stVal'><Val>42</Val></DAI></DOI>"
        + " <DOI name='Med'><SDI name='mag'><DAI name='f'><Val>12.5</Val></DAI></SDI></DOI>"
        + "</LN0></LDevice></Server></AccessPoint></IED>"
        + "<DataTypeTemplates>"
        + " <LNodeType id='LLN0_T' lnClass='LLN0'><DO name='Cnt' type='INS_T'/><DO name='Med' type='MV_T'/></LNodeType>"
        + " <DOType id='INS_T' cdc='INS'><DA name='q' fc='ST' bType='Quality'/>"
        + "   <DA name='stVal' fc='ST' bType='INT32'/><DA name='t' fc='ST' bType='Timestamp'/></DOType>"
        + " <DOType id='MV_T' cdc='MV'><DA name='mag' fc='MX' bType='Struct' type='AV_T'/>"
        + "   <DA name='q' fc='MX' bType='Quality'/></DOType>"
        + " <DAType id='AV_T'><BDA name='f' bType='FLOAT32'/></DAType>"
        + "</DataTypeTemplates></SCL>";

    public static void main(String[] a) throws Exception {
        File f = File.createTempFile("valormiembro", ".cid");
        f.deleteOnExit();
        Files.write(f.toPath(), SCL.getBytes(StandardCharsets.UTF_8));
        IEC61850Server srv = new IEC61850Server();
        chk("carga el SCL", srv.loadSclFile(f.getAbsolutePath()));
        ServerModel m = srv.getServerModel();
        ModelNode cnt = m.findModelNode("VMLD0/LLN0.Cnt", Fc.ST);
        String v1 = ReportsPanel.valorDeMiembro(cnt);
        chk("DO con stVal (q va antes en el tipo): " + v1, "42".equals(v1));
        ModelNode med = m.findModelNode("VMLD0/LLN0.Med", Fc.MX);
        String v2 = ReportsPanel.valorDeMiembro(med);
        chk("DO con mag.f: " + v2, v2 != null && v2.startsWith("12.5"));
        ModelNode st = m.findModelNode("VMLD0/LLN0.Cnt.stVal", Fc.ST);
        chk("un atributo suelto da su valor", "42".equals(ReportsPanel.valorDeMiembro(st)));
        System.out.println(ok + " pasaron, " + mal + " fallaron");
        if (mal == 0) System.out.println("TODO OK");
        System.exit(mal == 0 ? 0 : 1);
    }
}
