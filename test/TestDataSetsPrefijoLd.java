import com.beanit.iec61850bean.*;

import java.net.InetAddress;
import java.nio.file.*;
import java.util.*;

/**
 * El servidor no debe anunciar en un LD los DataSets de otro LD cuyo nombre empieza igual.
 *
 * El caso de campo (2026-10-18): con un SCD real de un relé de distancia, el LD Ln1
 * anunciaba "LLN0$DataSet", que es de Ln1_8521PermOverr. Al pedir su definición el servidor
 * respondía PARAMETER_VALUE_INAPPROPRIATE "DataSet with that reference does not exist", y un
 * cliente que hace retrieveModel() abortaba la carga del modelo entero. La causa estaba en
 * ServerModel.getDataSetNames() de iec61850bean: startsWith(ldName) sin la barra.
 *
 * Todo con la librería sola —ServerSap y ClientSap de iec61850bean— y un SCL mínimo generado
 * acá. La corrección es la clase sombreada src/main/java/com/beanit/iec61850bean/ServerModel.java,
 * que se carga antes que la del jar porque "classes" va primero en el classpath.
 *
 * Uso:  java -cp "classes;lib\*;test" TestDataSetsPrefijoLd
 */
public class TestDataSetsPrefijoLd {

    private static int fallas = 0;
    private static int puerto = 10331;

    static void chk(String titulo, boolean ok, String detalle) {
        if (!ok) fallas++;
        System.out.printf("%-62s %-34s %s%n", titulo, detalle, ok ? "OK" : "<<< FALLA");
    }

    public static void main(String[] args) throws Exception {
        // El defecto: el DataSet está en A_B, y A es prefijo de A_B
        String r1 = cargar(new String[] { "A_B", "A" }, Set.of("A_B"));
        chk("DataSet en A_B, existe A: retrieveModel completa", r1.startsWith("OK"), r1);
        chk("  y trae exactamente 1 DataSet", r1.equals("OK 1"), r1);

        // DataSets en los dos LD: cada uno aparece una sola vez, en su LD
        String r2 = cargar(new String[] { "A", "A_B" }, Set.of("A", "A_B"));
        chk("DataSets en A y en A_B: cada uno en su LD", r2.equals("OK 2"), r2);

        // Tres niveles de prefijo, como Ln1 / Ln1_8521PermOverr / Ln1_8521PermOverr2
        String r3 = cargar(new String[] { "Ln1", "Ln1_X", "Ln1_X2" }, Set.of("Ln1_X", "Ln1_X2"));
        chk("Ln1 / Ln1_X / Ln1_X2 con DataSets en los dos últimos", r3.equals("OK 2"), r3);

        // Control: sin prefijo común siempre funcionó
        String r4 = cargar(new String[] { "X_B", "A" }, Set.of("X_B"));
        chk("Control sin prefijo común", r4.equals("OK 1"), r4);

        System.out.println(fallas == 0 ? "\nTODO OK" : "\n" + fallas + " FALLAS");
        System.exit(fallas == 0 ? 0 : 1);
    }

    /** Sirve un SCL con esos LD (DataSet "DS" en los indicados) y lo carga con retrieveModel(). */
    static String cargar(String[] lds, Set<String> conDataSet) throws Exception {
        Path f = Files.createTempFile("prefijo", ".cid");
        Files.write(f, scl(lds, conDataSet).getBytes("UTF-8"));
        int p = puerto++;
        ServerSap sap = null;
        try {
            List<ServerModel> ms = SclParser.parse(f.toString());
            sap = new ServerSap(p, 0, null, ms.get(0), null);
            sap.startListening(new ServerEventListener() {
                public List<ServiceError> write(List<BasicDataAttribute> b) { return null; }
                public void serverStoppedListening(ServerSap s) { }
            });
            ClientAssociation as = new ClientSap().associate(InetAddress.getByName("127.0.0.1"), p, null,
                new ClientEventListener() {
                    public void newReport(Report r) { }
                    public void associationClosed(java.io.IOException e) { }
                });
            try {
                ServerModel m = as.retrieveModel();
                return "OK " + m.getDataSets().size();
            } catch (ServiceError e) {
                return "FALLA: " + e.getMessage().replaceAll(".*Description: Service error: ", "").replaceAll("\\(.*", "");
            } finally {
                as.close();
            }
        } finally {
            if (sap != null) sap.stop();
            Files.deleteIfExists(f);
        }
    }

    static String scl(String[] lds, Set<String> conDataSet) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
          .append("<SCL xmlns=\"http://www.iec.ch/61850/2003/SCL\" version=\"2007\" revision=\"B\"><Header id=\"t\"/>")
          .append("<IED name=\"IED\"><AccessPoint name=\"AP\"><Server><Authentication/>");
        for (String ld : lds) {
            sb.append("<LDevice inst=\"").append(ld).append("\">");
            if (conDataSet.contains(ld)) {
                sb.append("<LN0 lnClass=\"LLN0\" inst=\"\" lnType=\"LLN0_T\"><DataSet name=\"DS\">")
                  .append("<FCDA ldInst=\"").append(ld)
                  .append("\" prefix=\"\" lnClass=\"LLN0\" lnInst=\"\" doName=\"Beh\" daName=\"stVal\" fc=\"ST\"/>")
                  .append("</DataSet></LN0>");
            } else {
                sb.append("<LN0 lnClass=\"LLN0\" inst=\"\" lnType=\"LLN0_T\"/>");
            }
            sb.append("</LDevice>");
        }
        sb.append("</Server></AccessPoint></IED>")
          .append("<DataTypeTemplates><LNodeType id=\"LLN0_T\" lnClass=\"LLN0\"><DO name=\"Beh\" type=\"ENS_T\"/></LNodeType>")
          .append("<DOType id=\"ENS_T\" cdc=\"ENS\"><DA name=\"stVal\" fc=\"ST\" bType=\"INT32\"/></DOType></DataTypeTemplates></SCL>");
        return sb.toString();
    }
}
