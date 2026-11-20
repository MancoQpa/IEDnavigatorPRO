package com.iednavigator;

import com.beanit.iec61850bean.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Descripción de los LN en el árbol, igual en Servidor y Cliente (reporte de usuarios,
 * 2026-11-16): primero qué es el LN y, si agrega, la desc del SCL. Un CSWI o CILO dice si es de
 * interruptor o de seccionador; antes todo CSWI decía "Controlador de Interruptor" y en Servidor
 * salía solo la desc del archivo ("CB", "DS01").
 *
 * Uso: java -cp "classes;lib\*;test" com.iednavigator.TestAparatoDeLn
 */
public class TestAparatoDeLn {
    static int ok = 0, mal = 0;
    static void chk(String que, boolean c) {
        System.out.println((c ? "OK    " : "FALLA ") + que);
        if (c) ok++; else mal++;
    }

    static String ln(String prefix, String cls, String inst, String desc) {
        return "<LN prefix='" + prefix + "' lnClass='" + cls + "' inst='" + inst + "' lnType='" + cls + "_T'"
                + (desc != null ? " desc='" + desc + "'" : "") + "/>";
    }

    static final String SCL = "<?xml version='1.0' encoding='UTF-8'?>"
        + "<SCL xmlns='http://www.iec.ch/61850/2003/SCL' version='2007' revision='B'><Header id='T'/>"
        + "<IED name='APA'><AccessPoint name='AP1'><Server><Authentication/>"
        + "<LDevice inst='CTRL'><LN0 lnClass='LLN0' inst='' lnType='LLN0_T'/>"
        + ln("CBNOCHK", "CSWI", "1", "CB") + ln("DS", "CSWI", "1", "DS01") + ln("DS", "CSWI", "2", "DS02")
        + ln("CB", "CILO", "1", "CB") + ln("DS", "CILO", "2", "DS01")
        + ln("CB", "XCBR", "1", null) + ln("DS", "XSWI", "1", null) + ln("DS", "XSWI", "2", null)
        + "</LDevice>"
        + "<LDevice inst='OTRO'><LN0 lnClass='LLN0' inst='' lnType='LLN0_T'/>"
        + ln("Q8", "CSWI", "1", null) + ln("", "CSWI", "7", null) + "</LDevice>"
        + "</Server></AccessPoint></IED>"
        + "<DataTypeTemplates>"
        + " <LNodeType id='LLN0_T' lnClass='LLN0'><DO name='Beh' type='INS_T'/></LNodeType>"
        + " <LNodeType id='CSWI_T' lnClass='CSWI'><DO name='Beh' type='INS_T'/></LNodeType>"
        + " <LNodeType id='CILO_T' lnClass='CILO'><DO name='Beh' type='INS_T'/></LNodeType>"
        + " <LNodeType id='XCBR_T' lnClass='XCBR'><DO name='Beh' type='INS_T'/></LNodeType>"
        + " <LNodeType id='XSWI_T' lnClass='XSWI'><DO name='Beh' type='INS_T'/></LNodeType>"
        + " <DOType id='INS_T' cdc='INS'><DA name='stVal' fc='ST' bType='INT32'/></DOType>"
        + "</DataTypeTemplates></SCL>";

    static NodeInfo info(ServerModel m, String ref) {
        NodeInfo i = new NodeInfo();
        i.node = m.findModelNode(ref, null);
        i.name = i.node.getName();
        i.prefix = "LN";
        return i;
    }

    static NodeInfo sinClase() {
        NodeInfo i = new NodeInfo();
        i.name = "ZZZZ1";
        i.prefix = "LN";
        return i;
    }

    public static void main(String[] a) throws Exception {
        I18n.setLocale(java.util.Locale.forLanguageTag("es"));
        File f = File.createTempFile("aparatoln", ".cid");
        f.deleteOnExit();
        Files.write(f.toPath(), SCL.getBytes(StandardCharsets.UTF_8));
        ServerModel m = SclParser.parse(f.getAbsolutePath()).get(0);

        String[][] casos = {
            {"APACTRL/CBNOCHKCSWI1", "Controlador de interruptor", "CB"},
            {"APACTRL/DSCSWI1", "Controlador de seccionador", "DS01"},
            {"APACTRL/DSCSWI2", "Controlador de seccionador", "DS02"},
            {"APACTRL/CBCILO1", "Enclavamiento de interruptor", "CB"},
            {"APACTRL/DSCILO2", "Enclavamiento de seccionador", "DS01"},
            {"APAOTRO/Q8CSWI1", "Controlador de seccionador", null},
        };
        for (String[] c : casos) {
            String d = AparatoDeLn.descripcion(m.findModelNode(c[0], null));
            chk(c[0] + " -> " + d, c[1].equals(d));
            String arbol = ArbolCeldaRenderer.descripcionLn(info(m, c[0]), c[2]);
            String esperado = c[1];   // igual en Servidor (con desc del SCL) y en Cliente (sin)
            chk("   en el árbol: " + arbol, esperado.equals(arbol));
        }
        chk("CSWI sin pistas: nombre genérico de la clase",
                "Controlador de maniobra".equals(ArbolCeldaRenderer.descripcionLn(info(m, "APAOTRO/CSWI7"), null)));
        chk("misma descripción con y sin desc del SCL (Servidor = Cliente)",
                ArbolCeldaRenderer.descripcionLn(info(m, "APACTRL/DSCSWI1"), "DS01")
                        .equals(ArbolCeldaRenderer.descripcionLn(info(m, "APACTRL/DSCSWI1"), null)));
        chk("clase fuera del diccionario: usa la desc del SCL",
                "Algo propio".equals(ArbolCeldaRenderer.descripcionLn(sinClase(), "Algo propio")));
        String xcbr = ArbolCeldaRenderer.descripcionLn(info(m, "APACTRL/CBXCBR1"), null);
        chk("XCBR: la descripción de su clase (" + xcbr + ")", xcbr != null && !xcbr.contains("·"));
        System.out.println(ok + " pasaron, " + mal + " fallaron");
        if (mal == 0) System.out.println("TODO OK");
        System.exit(mal == 0 ? 0 : 1);
    }
}
