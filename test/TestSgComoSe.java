import com.beanit.iec61850bean.*;
import com.iednavigator.IEC61850Server;
import com.iednavigator.SclExporter;

import java.nio.file.*;
import java.util.*;

/**
 * Un ajuste declarado fc="SG" también existe como SE, y un DataSet con FCDA fc="SE" carga.
 *
 * iec61850bean 1.9.0 creaba cada atributo solo con el FC de su DOType. En IEC 61850-7-2 el SE
 * es el búfer de edición del mismo ajuste, y un DataSet puede referenciarlo: el NARI PCS-9611S
 * tiene dsSetting1 con sus ajustes en SE. El CID que la app exporta de ese equipo —y cualquier
 * CID de fabricante con un DataSet así— se rechazaba entero con "Specified FCDA ... not found
 * in Model". Corregido en la clase sombreada src/main/java/com/beanit/iec61850bean/SclParser.java.
 *
 * Opcional: la ruta de un CID real con DataSets en SE como argumento; se carga con
 * IEC61850Server, como lo hace el modo servidor.
 *
 * Uso:  java -cp "classes;lib\*;test" TestSgComoSe [archivo.cid]
 */
public class TestSgComoSe {

    private static int fallas = 0;

    static void chk(String titulo, boolean ok, String detalle) {
        if (!ok) fallas++;
        System.out.printf("%-56s %-28s %s%n", titulo, detalle, ok ? "OK" : "<<< FALLA");
    }

    public static void main(String[] args) throws Exception {
        // 1) DataSet con un ajuste en SE y el mismo DO en SG: carga
        ServerModel m = null;
        String error = "";
        try {
            m = parsear(scl(true));
        } catch (SclParseException e) {
            error = e.getMessage();
        }
        chk("DataSet con FCDA fc=SE sobre un DA fc=SG: carga", m != null,
            m != null ? "carga" : error.length() > 28 ? error.substring(0, 28) : error);
        if (m == null) { fin(); return; }

        // 2) El ajuste existe en SG y en SE, con el mismo valor del archivo
        String sg = valor(m, Fc.SG), se = valor(m, Fc.SE);
        chk("el ajuste existe en SG con su valor", "1.5".equals(sg), String.valueOf(sg));
        chk("el ajuste existe en SE con el mismo valor", "1.5".equals(se), String.valueOf(se));

        // 3) El DataSet tiene su miembro en SE
        DataSet ds = m.getDataSet("IEDPROT/LLN0.dsSetting1");
        String miembros = ds == null ? "sin DataSet" : ds.getMembers().size() + " " + ds.getMembers().get(0).getFc();
        chk("el DataSet tiene el miembro en SE", ds != null && ds.getMembers().size() == 1
            && ds.getMembers().get(0).getFc() == Fc.SE, miembros);

        // 4) Un arreglo SG también tiene su copia SE, elemento por elemento
        ModelNode arrSe = m.findModelNode("IEDPROT/PTOC1.Crv.vals", Fc.SE);
        ModelNode arrSg = m.findModelNode("IEDPROT/PTOC1.Crv.vals", Fc.SG);
        chk("arreglo SG: también en SE, con los mismos valores",
            arrSe instanceof Array && arrSg instanceof Array && texto(arrSe).equals(texto(arrSg))
                && texto(arrSe).equals("7;8;"), arrSe == null ? "sin SE" : texto(arrSe));

        // 5) Lo que no es SG no se duplica
        chk("un DA que no es SG no aparece en SE",
            m.findModelNode("IEDPROT/PTOC1.StrVal.d", Fc.SE) == null
                && m.findModelNode("IEDPROT/PTOC1.Beh.stVal", Fc.SE) == null, "sin copia");

        // 6) Sin DataSet en SE el modelo también lleva el SE (lo que expone un equipo real)
        ServerModel sinDs = parsear(scl(false));
        chk("sin DataSet en SE: el SE existe igual", "1.5".equals(valor(sinDs, Fc.SE)), String.valueOf(valor(sinDs, Fc.SE)));

        // 7) Ida y vuelta: exportar con SclExporter y volver a cargar
        String xml = SclExporter.export(m, "127.0.0.1", "IED", null, null, null, null).xml;
        ServerModel m7 = null;
        try {
            m7 = parsear(xml);
        } catch (SclParseException e) {
            error = e.getMessage();
        }
        chk("exportar y releer: carga, con el DataSet en SE", m7 != null
            && m7.getDataSet("IEDPROT/LLN0.dsSetting1") != null, m7 != null ? "carga" : error);
        if (m7 != null) chk("exportar y releer: mismo valor en SE", "1.5".equals(valor(m7, Fc.SE)), String.valueOf(valor(m7, Fc.SE)));

        // 8) Como el modo servidor (preprocesado incluido)
        Path f = Files.createTempFile("sgse", ".cid");
        Files.write(f, scl(true).getBytes("UTF-8"));
        IEC61850Server srv = new IEC61850Server();
        boolean ok = srv.loadSclFileWithIED(f.toString(), 0);
        Files.deleteIfExists(f);
        chk("modo servidor: carga", ok, ok ? "carga" : "no carga");

        // 9) Opcional: un CID real
        if (args.length > 0) {
            IEC61850Server real = new IEC61850Server();
            boolean okReal = real.loadSclFileWithIED(args[0], 0);
            chk("CID real: carga con IEC61850Server", okReal, Paths.get(args[0]).getFileName().toString());
            if (okReal) {
                int conSe = 0;
                for (DataSet d : real.getServerModel().getDataSets()) {
                    for (FcModelNode mem : d.getMembers()) if (mem.getFc() == Fc.SE) { conSe++; break; }
                }
                chk("CID real: DataSets con miembros en SE", conSe > 0, conSe + " DataSets");
            }
        }
        fin();
    }

    static void fin() {
        System.out.println(fallas == 0 ? "\nTODO OK" : "\n" + fallas + " FALLAS");
        System.exit(fallas == 0 ? 0 : 1);
    }

    static String valor(ServerModel m, Fc fc) {
        ModelNode n = m.findModelNode("IEDPROT/PTOC1.StrVal.setMag.f", fc);
        return n instanceof BdaFloat32 ? String.valueOf(((BdaFloat32) n).getFloat()) : null;
    }

    static ServerModel parsear(String xml) throws Exception {
        Path f = Files.createTempFile("sgse", ".cid");
        Files.write(f, xml.getBytes("UTF-8"));
        try {
            return SclParser.parse(f.toString()).get(0);
        } finally {
            Files.deleteIfExists(f);
        }
    }

    static String texto(ModelNode n) {
        if (n instanceof BasicDataAttribute) return String.valueOf(((BasicDataAttribute) n).getValueString());
        StringBuilder sb = new StringBuilder();
        if (n.getChildren() != null) for (ModelNode c : n.getChildren()) sb.append(texto(c)).append(";");
        return sb.toString();
    }

    /** SCL mínimo: PTOC1 con un ajuste SAV (setMag en SG) y un arreglo SG; DataSet opcional en SE. */
    static String scl(boolean conDataSetSe) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<SCL xmlns=\"http://www.iec.ch/61850/2003/SCL\" version=\"2007\" revision=\"B\"><Header id=\"t\"/>"
            + "<IED name=\"IED\"><AccessPoint name=\"AP\"><Server><Authentication/><LDevice inst=\"PROT\">"
            + "<LN0 lnClass=\"LLN0\" inst=\"\" lnType=\"LLN0_T\">"
            + (conDataSetSe ? "<DataSet name=\"dsSetting1\"><FCDA ldInst=\"PROT\" lnClass=\"PTOC\" lnInst=\"1\" doName=\"StrVal\" fc=\"SE\"/></DataSet>" : "")
            + "<SettingControl numOfSGs=\"4\" actSG=\"1\"/></LN0>"
            + "<LN lnClass=\"PTOC\" inst=\"1\" lnType=\"PTOC_T\">"
            + "<DOI name=\"StrVal\"><SDI name=\"setMag\"><DAI name=\"f\" sGroup=\"1\"><Val>1.5</Val></DAI></SDI></DOI>"
            + "<DOI name=\"Crv\"><DAI name=\"vals\" ix=\"0\" sGroup=\"1\"><Val>7</Val></DAI><DAI name=\"vals\" ix=\"1\" sGroup=\"1\"><Val>8</Val></DAI></DOI>"
            + "</LN></LDevice></Server></AccessPoint></IED><DataTypeTemplates>"
            + "<LNodeType id=\"LLN0_T\" lnClass=\"LLN0\"><DO name=\"Beh\" type=\"INS_T\"/></LNodeType>"
            + "<LNodeType id=\"PTOC_T\" lnClass=\"PTOC\"><DO name=\"Beh\" type=\"INS_T\"/>"
            + "<DO name=\"StrVal\" type=\"ASG_T\"/><DO name=\"Crv\" type=\"CRV_T\"/></LNodeType>"
            + "<DOType id=\"INS_T\" cdc=\"INS\"><DA name=\"stVal\" fc=\"ST\" bType=\"INT32\"/></DOType>"
            + "<DOType id=\"ASG_T\" cdc=\"ASG\"><DA name=\"setMag\" fc=\"SG\" bType=\"Struct\" type=\"AV_T\"/>"
            + "<DA name=\"d\" fc=\"DC\" bType=\"VisString255\"/></DOType>"
            + "<DOType id=\"CRV_T\" cdc=\"ING\"><DA name=\"vals\" fc=\"SG\" bType=\"INT32\" count=\"2\"/></DOType>"
            + "<DAType id=\"AV_T\"><BDA name=\"f\" bType=\"FLOAT32\"/></DAType>"
            + "</DataTypeTemplates></SCL>";
    }
}
