import com.beanit.iec61850bean.*;
import com.iednavigator.IEC61850Server;
import com.iednavigator.SclExporter;

import java.nio.file.*;
import java.util.*;

/**
 * Al cargar un SCL, cada elemento de un arreglo toma el valor de su DAI/SDI con ix.
 *
 * iec61850bean 1.9.0 le pasaba a todos los elementos el primer DAI/SDI con ese nombre, y
 * todos quedaban con el valor del elemento 0 (había un TODO). El caso de campo: los puntos de
 * curva crvPts del CID de un Ingeteam eF cargaban en el simulador todos iguales. Corregido en
 * la clase sombreada src/main/java/com/beanit/iec61850bean/SclParser.java.
 *
 * Opcional: la ruta de un CID real con arreglos (p. ej. el del Ingeteam) como argumento;
 * se carga con IEC61850Server, como lo hace el modo servidor, y se compara contra el XML.
 *
 * Uso:  java -cp "classes;lib\*;test" TestSclParserIx [archivo.cid]
 */
public class TestSclParserIx {

    private static int fallas = 0;

    static void chk(String titulo, boolean ok, String detalle) {
        if (!ok) fallas++;
        System.out.printf("%-54s %-34s %s%n", titulo, detalle, ok ? "OK" : "<<< FALLA");
    }

    public static void main(String[] args) throws Exception {
        // 1) Con ix en todos los elementos
        ServerModel m = parsear(scl(
            "<SDI name=\"crvPts\" ix=\"0\"><DAI name=\"xVal\"><Val>1.5</Val></DAI><DAI name=\"yVal\"><Val>10</Val></DAI></SDI>"
          + "<SDI name=\"crvPts\" ix=\"1\"><DAI name=\"xVal\"><Val>2.5</Val></DAI><DAI name=\"yVal\"><Val>20</Val></DAI></SDI>"
          + "<SDI name=\"crvPts\" ix=\"2\"><DAI name=\"xVal\"><Val>3.5</Val></DAI><DAI name=\"yVal\"><Val>30</Val></DAI></SDI>"
          + "<DAI name=\"vals\" ix=\"0\"><Val>100</Val></DAI><DAI name=\"vals\" ix=\"1\"><Val>101</Val></DAI>"
          + "<DAI name=\"vals\" ix=\"2\"><Val>102</Val></DAI><DAI name=\"vals\" ix=\"3\"><Val>103</Val></DAI>"));
        chk("struct: cada punto con su xVal/yVal", "1.5/10.0 2.5/20.0 3.5/30.0".equals(puntos(m)), puntos(m));
        chk("básico: cada elemento con su valor", "100 101 102 103".equals(enteros(m)), enteros(m));

        // 2) Desordenados en el archivo: manda ix, no el orden
        ServerModel m2 = parsear(scl(
            "<DAI name=\"vals\" ix=\"3\"><Val>103</Val></DAI><DAI name=\"vals\" ix=\"0\"><Val>100</Val></DAI>"
          + "<DAI name=\"vals\" ix=\"2\"><Val>102</Val></DAI><DAI name=\"vals\" ix=\"1\"><Val>101</Val></DAI>"));
        chk("desordenados en el XML: manda ix", "100 101 102 103".equals(enteros(m2)), enteros(m2));

        // 3) Sin ix: como antes, el mismo valor para todos (compatibilidad)
        ServerModel m3 = parsear(scl("<DAI name=\"vals\"><Val>7</Val></DAI>"));
        chk("sin ix: el valor a todos, como la librería", "7 7 7 7".equals(enteros(m3)), enteros(m3));

        // 4) Con ix solo para algunos: el resto queda con el valor por defecto
        ServerModel m4 = parsear(scl("<DAI name=\"vals\" ix=\"1\"><Val>55</Val></DAI>"));
        chk("ix parcial: el resto en el valor por defecto", "0 55 0 0".equals(enteros(m4)), enteros(m4));

        // 5) Ida y vuelta: exportar con SclExporter y volver a cargar
        String xml = SclExporter.export(m, "127.0.0.1", "IED", null, null, null, null).xml;
        Path t = Files.createTempFile("idavuelta", ".cid");
        Files.write(t, xml.getBytes("UTF-8"));
        ServerModel m5 = SclParser.parse(t.toString()).get(0);
        Files.deleteIfExists(t);
        chk("exportar y releer: mismos puntos", puntos(m).equals(puntos(m5)), puntos(m5));
        chk("exportar y releer: mismos enteros", enteros(m).equals(enteros(m5)), enteros(m5));

        // 6) Opcional: un CID real, cargado como lo hace el modo servidor
        if (args.length > 0) {
            IEC61850Server srv = new IEC61850Server();
            boolean ok = srv.loadSclFileWithIED(args[0], 0);
            chk("CID real: carga con IEC61850Server", ok, Paths.get(args[0]).getFileName().toString());
            if (ok) {
                List<String> distintos = new ArrayList<>();
                int arreglos = 0;
                Deque<ModelNode> q = new ArrayDeque<>(srv.getServerModel().getChildren());
                while (!q.isEmpty()) {
                    ModelNode n = q.poll();
                    if (n instanceof com.beanit.iec61850bean.Array && ((Array) n).size() > 1) {
                        arreglos++;
                        Set<String> valores = new LinkedHashSet<>();
                        for (int i = 0; i < ((Array) n).size(); i++) valores.add(texto(((Array) n).getChild(i)));
                        if (valores.size() > 1) distintos.add(n.getReference() + " -> " + valores.size() + " valores distintos");
                    }
                    if (n.getChildren() != null) q.addAll(n.getChildren());
                }
                System.out.println("   arreglos de más de un elemento: " + arreglos);
                for (int i = 0; i < Math.min(4, distintos.size()); i++) System.out.println("   " + distintos.get(i));
                chk("CID real: hay arreglos con elementos distintos", !distintos.isEmpty(), distintos.size() + " arreglos");
            }
        }

        System.out.println(fallas == 0 ? "\nTODO OK" : "\n" + fallas + " FALLAS");
        System.exit(fallas == 0 ? 0 : 1);
    }

    static ServerModel parsear(String xml) throws Exception {
        Path f = Files.createTempFile("ix", ".cid");
        Files.write(f, xml.getBytes("UTF-8"));
        try {
            return SclParser.parse(f.toString()).get(0);
        } finally {
            Files.deleteIfExists(f);
        }
    }

    static String puntos(ServerModel m) {
        Array a = (Array) m.findModelNode("IEDLD0/PDIS1.Crv.crvPts", Fc.SP);
        if (a == null) return "sin crvPts";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.size(); i++) {
            ModelNode p = a.getChild(i);
            sb.append(i > 0 ? " " : "").append(((BdaFloat32) p.getChild("xVal")).getFloat())
              .append("/").append(((BdaFloat32) p.getChild("yVal")).getFloat());
        }
        return sb.toString();
    }

    static String enteros(ServerModel m) {
        Array a = (Array) m.findModelNode("IEDLD0/PDIS1.Crv.vals", Fc.SP);
        if (a == null) return "sin vals";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.size(); i++) sb.append(i > 0 ? " " : "").append(((BdaInt32) a.getChild(i)).getValue());
        return sb.toString();
    }

    /** Todos los valores básicos de un nodo, para comparar elementos entre sí. */
    static String texto(ModelNode n) {
        if (n instanceof BasicDataAttribute) return String.valueOf(((BasicDataAttribute) n).getValueString());
        StringBuilder sb = new StringBuilder();
        if (n.getChildren() != null) for (ModelNode c : n.getChildren()) sb.append(texto(c)).append(";");
        return sb.toString();
    }

    static String scl(String dois) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<SCL xmlns=\"http://www.iec.ch/61850/2003/SCL\" version=\"2007\" revision=\"B\"><Header id=\"t\"/>"
            + "<IED name=\"IED\"><AccessPoint name=\"AP\"><Server><Authentication/><LDevice inst=\"LD0\">"
            + "<LN0 lnClass=\"LLN0\" inst=\"\" lnType=\"LLN0_T\"/>"
            + "<LN lnClass=\"PDIS\" inst=\"1\" lnType=\"PDIS_T\"><DOI name=\"Crv\">" + dois + "</DOI></LN>"
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
}
