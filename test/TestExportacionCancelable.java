import com.beanit.iec61850bean.*;
import com.iednavigator.IEC61850Client;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Las dos lecturas previas a exportar un CID se pueden cortar.
 *
 * Contra un modelo grande la exportación son ~17.000 lecturas MMS, del orden de minutos, y
 * hasta 2026-10-19 no había forma de pararla. Ahora leerTodosLosValores() recibe una
 * condición de corte que se consulta antes de cada lectura; ConnectionManager la conecta
 * al botón Cancelar de la ventana de avance. (Había una segunda lectura,
 * leerValoresParaExportar(), que se quitó el mismo día por redundante.)
 *
 * Servidor local (ServerSap) con un SCL sintético de 200 LN x 3 DO, y el cliente de la app.
 *
 * Uso:  java -cp "classes;lib\*;test" TestExportacionCancelable
 */
public class TestExportacionCancelable {

    private static int fallas = 0;

    static void chk(String titulo, boolean ok, String detalle) {
        if (!ok) fallas++;
        System.out.printf("%-56s %-30s %s%n", titulo, detalle, ok ? "OK" : "<<< FALLA");
    }

    public static void main(String[] args) throws Exception {
        Path f = Files.createTempFile("cancelable", ".cid");
        Files.write(f, scl(200).getBytes("UTF-8"));
        int puerto = 10341;
        ServerSap sap = new ServerSap(puerto, 0, null, SclParser.parse(f.toString()).get(0), null);
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
        try {
            c.connect("127.0.0.1", puerto);

            // Sin corte: completa, como antes
            IEC61850Client.LecturaValores v1 = c.leerTodosLosValores(null, () -> false);
            int total2 = v1.pedidos;
            chk("sin cortar: completa", !v1.cancelada && total2 >= 600, v1.pedidos + " pedidos");

            // Las firmas de antes siguen andando igual
            IEC61850Client.LecturaValores v0 = c.leerTodosLosValores(null);
            chk("leerTodosLosValores(avance) sin cambios", !v0.cancelada && v0.pedidos == total2, v0.pedidos + " pedidos");

            // Con corte a las 100 consultas
            AtomicInteger consultas = new AtomicInteger();
            IEC61850Client.LecturaValores v2 = c.leerTodosLosValores(null, () -> consultas.incrementAndGet() > 100);
            chk("cortada: cancelada", v2.cancelada, "");
            chk("  y se detuvo en la lectura 100", v2.pedidos == 100, v2.pedidos + " pedidos");

            // Cortado antes de empezar: ninguna lectura
            IEC61850Client.LecturaValores v3 = c.leerTodosLosValores(null, () -> true);
            chk("cortado desde el inicio: ninguna lectura", v3.cancelada && v3.pedidos == 0, v3.pedidos + " pedidos");

            // La asociación sigue sirviendo después de cortar
            IEC61850Client.LecturaValores v4 = c.leerTodosLosValores(null, () -> false);
            chk("después de cortar, la conexión sigue sirviendo", !v4.cancelada && v4.pedidos == total2, v4.pedidos + " pedidos");
        } finally {
            try { c.disconnect(); } catch (Exception ignore) { }
            sap.stop();
            Files.deleteIfExists(f);
        }
        System.out.println(fallas == 0 ? "\nTODO OK" : "\n" + fallas + " FALLAS");
        System.exit(fallas == 0 ? 0 : 1);
    }

    /** Un LD con LLN0 y n GGIO de tres DO cada uno. */
    static String scl(int n) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
          .append("<SCL xmlns=\"http://www.iec.ch/61850/2003/SCL\" version=\"2007\" revision=\"B\"><Header id=\"t\"/>")
          .append("<IED name=\"IED\"><AccessPoint name=\"AP\"><Server><Authentication/><LDevice inst=\"LD0\">")
          .append("<LN0 lnClass=\"LLN0\" inst=\"\" lnType=\"LLN0_T\"/>");
        for (int i = 1; i <= n; i++) {
            sb.append("<LN lnClass=\"GGIO\" inst=\"").append(i).append("\" lnType=\"GGIO_T\"/>");
        }
        sb.append("</LDevice></Server></AccessPoint></IED><DataTypeTemplates>")
          .append("<LNodeType id=\"LLN0_T\" lnClass=\"LLN0\"><DO name=\"Beh\" type=\"INS_T\"/></LNodeType>")
          .append("<LNodeType id=\"GGIO_T\" lnClass=\"GGIO\"><DO name=\"Beh\" type=\"INS_T\"/>")
          .append("<DO name=\"Ind1\" type=\"SPS_T\"/><DO name=\"Ind2\" type=\"SPS_T\"/></LNodeType>")
          .append("<DOType id=\"INS_T\" cdc=\"INS\"><DA name=\"stVal\" fc=\"ST\" bType=\"INT32\"/></DOType>")
          .append("<DOType id=\"SPS_T\" cdc=\"SPS\"><DA name=\"stVal\" fc=\"ST\" bType=\"BOOLEAN\"/>")
          .append("<DA name=\"q\" fc=\"ST\" bType=\"Quality\"/></DOType></DataTypeTemplates></SCL>");
        return sb.toString();
    }
}
