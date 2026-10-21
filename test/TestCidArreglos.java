import com.beanit.iec61850bean.*;
import com.iednavigator.IEC61850Client;
import com.iednavigator.SclExporter;
import org.w3c.dom.*;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.util.*;

/**
 * El CID exportado lleva los valores de los arreglos, elemento por elemento, con ix.
 *
 * Hasta 2026-10-21 los arreglos se declaraban (count en las plantillas) pero sus valores no
 * se emitían. El caso real del corpus son los puntos de curva de protección (crvPts,
 * estructuras xVal/yVal con FC SP; un Ingeteam eF trae 256 en su CID).
 *
 * ServerSap local con un SCL sintético: crvPts x3 (struct) y vals x4 (INT32), con valores
 * distintos por elemento puestos en el servidor. El cliente de la app los lee
 * (leerTodosLosValores) y SclExporter escribe el CID; se revisa el XML con DOM.
 *
 * No se relee el CID con iec61850bean: su SclParser 1.9.0 ignora ix y le da a todos los
 * elementos el valor del primero.
 *
 * Uso:  java -cp "classes;lib\*;test" TestCidArreglos
 */
public class TestCidArreglos {

    private static int fallas = 0;

    static void chk(String titulo, boolean ok, String detalle) {
        if (!ok) fallas++;
        System.out.printf("%-54s %-28s %s%n", titulo, detalle, ok ? "OK" : "<<< FALLA");
    }

    public static void main(String[] args) throws Exception {
        Path f = Files.createTempFile("arreglos", ".cid");
        Files.write(f, SCL.getBytes("UTF-8"));
        ServerModel sm = SclParser.parse(f.toString()).get(0);

        // Valores distintos por elemento, del lado servidor
        Array crv = (Array) sm.findModelNode("IEDLD0/PDIS1.Crv.crvPts", Fc.SP);
        Array vals = (Array) sm.findModelNode("IEDLD0/PDIS1.Crv.vals", Fc.SP);
        chk("el SCL arma los dos arreglos", crv != null && vals != null && crv.size() == 3 && vals.size() == 4,
            (crv == null ? "-" : crv.size()) + " / " + (vals == null ? "-" : vals.size()));
        for (int i = 0; i < 3; i++) {
            ((BdaFloat32) crv.getChild(i).getChild("xVal")).setFloat(1.5f + i);
            ((BdaFloat32) crv.getChild(i).getChild("yVal")).setFloat(10f * (i + 1));
        }
        for (int i = 0; i < 4; i++) ((BdaInt32) vals.getChild(i)).setValue(100 + i);

        int puerto = 10351;
        ServerSap sap = new ServerSap(puerto, 0, null, sm, null);
        sap.startListening(new ServerEventListener() {
            public List<ServiceError> write(List<BasicDataAttribute> b) { return null; }
            public void serverStoppedListening(ServerSap s) { }
        });
        IEC61850Client c = new IEC61850Client();
        c.setValueChangeListener(new IEC61850Client.ValueChangeListener() {
            public void onValueChanged(String r, String v, String t) { }
            public void onError(String r, String e) { }
            public void onConnectionClosed(String m) { }
            public void onLog(String m) { }
        });
        String xml;
        try {
            c.connect("127.0.0.1", puerto);
            IEC61850Client.LecturaValores lv = c.leerTodosLosValores(null);
            chk("el cliente leyó todo", lv.fallidos == 0 && lv.pedidos > 0, lv.pedidos + " pedidos, " + lv.fallidos + " rechazados");
            xml = SclExporter.export(c.getServerModel(), "127.0.0.1", "IED", null, null, null, lv.leidos).xml;
        } finally {
            try { c.disconnect(); } catch (Exception ignore) { }
            sap.stop();
            Files.deleteIfExists(f);
        }

        Document d = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes("UTF-8")));
        chk("el CID es XML bien formado", d != null, "");
        Element doi = porNombre(d.getElementsByTagName("DOI"), "Crv");
        chk("hay DOI Crv", doi != null, "");

        // crvPts: un SDI por elemento, con ix, y xVal/yVal adentro
        List<Element> sdis = hijos(doi, "SDI", "crvPts");
        chk("crvPts: 3 SDI", sdis.size() == 3, sdis.size() + " SDI");
        for (int i = 0; i < Math.min(3, sdis.size()); i++) {
            Element s = sdis.get(i);
            String x = val(porNombre(s.getElementsByTagName("DAI"), "xVal"));
            String y = val(porNombre(s.getElementsByTagName("DAI"), "yVal"));
            chk("  crvPts ix=" + i + ": xVal y yVal del elemento " + i,
                String.valueOf(i).equals(s.getAttribute("ix")) && String.valueOf(1.5f + i).equals(x)
                    && String.valueOf(10f * (i + 1)).equals(y),
                "ix=" + s.getAttribute("ix") + " x=" + x + " y=" + y);
        }

        // vals: un DAI por elemento, con ix
        List<Element> dais = hijos(doi, "DAI", "vals");
        chk("vals: 4 DAI", dais.size() == 4, dais.size() + " DAI");
        boolean bien = dais.size() == 4;
        StringBuilder vs = new StringBuilder();
        for (int i = 0; i < dais.size(); i++) {
            Element e = dais.get(i);
            vs.append(e.getAttribute("ix")).append("=").append(val(e)).append(" ");
            bien &= String.valueOf(i).equals(e.getAttribute("ix")) && String.valueOf(100 + i).equals(val(e));
        }
        chk("  cada uno con su ix y su valor", bien, vs.toString().trim());

        // La plantilla sigue declarando count
        chk("la plantilla declara count=\"3\" y count=\"4\"",
            xml.contains("name=\"crvPts\"") && xml.contains("count=\"3\"") && xml.contains("count=\"4\""), "");

        System.out.println(fallas == 0 ? "\nTODO OK" : "\n" + fallas + " FALLAS");
        System.exit(fallas == 0 ? 0 : 1);
    }

    static Element porNombre(NodeList l, String nombre) {
        for (int i = 0; i < l.getLength(); i++) {
            Element e = (Element) l.item(i);
            if (nombre.equals(e.getAttribute("name"))) return e;
        }
        return null;
    }

    /** Hijos directos con esa etiqueta y ese name, en orden. */
    static List<Element> hijos(Element padre, String tag, String nombre) {
        List<Element> r = new ArrayList<>();
        if (padre == null) return r;
        for (Node n = padre.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && tag.equals(((Element) n).getTagName())
                    && nombre.equals(((Element) n).getAttribute("name"))) r.add((Element) n);
        }
        return r;
    }

    static String val(Element dai) {
        if (dai == null) return null;
        NodeList v = dai.getElementsByTagName("Val");
        return v.getLength() == 0 ? null : v.item(0).getTextContent();
    }

    static final String SCL =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<SCL xmlns=\"http://www.iec.ch/61850/2003/SCL\" version=\"2007\" revision=\"B\"><Header id=\"t\"/>"
        + "<IED name=\"IED\"><AccessPoint name=\"AP\"><Server><Authentication/><LDevice inst=\"LD0\">"
        + "<LN0 lnClass=\"LLN0\" inst=\"\" lnType=\"LLN0_T\"/>"
        + "<LN lnClass=\"PDIS\" inst=\"1\" lnType=\"PDIS_T\"/>"
        + "</LDevice></Server></AccessPoint></IED><DataTypeTemplates>"
        + "<LNodeType id=\"LLN0_T\" lnClass=\"LLN0\"><DO name=\"Beh\" type=\"INS_T\"/></LNodeType>"
        + "<LNodeType id=\"PDIS_T\" lnClass=\"PDIS\"><DO name=\"Beh\" type=\"INS_T\"/><DO name=\"Crv\" type=\"CRV_T\"/></LNodeType>"
        + "<DOType id=\"INS_T\" cdc=\"INS\"><DA name=\"stVal\" fc=\"ST\" bType=\"INT32\"/></DOType>"
        + "<DOType id=\"CRV_T\" cdc=\"CSG\">"
        + "<DA name=\"crvPts\" fc=\"SP\" bType=\"Struct\" type=\"PT_T\" count=\"3\"/>"
        + "<DA name=\"vals\" fc=\"SP\" bType=\"INT32\" count=\"4\"/>"
        + "</DOType>"
        + "<DAType id=\"PT_T\"><BDA name=\"xVal\" bType=\"FLOAT32\"/><BDA name=\"yVal\" bType=\"FLOAT32\"/></DAType>"
        + "</DataTypeTemplates></SCL>";
}
