import com.beanit.iec61850bean.DataSet;
import com.beanit.iec61850bean.FcModelNode;
import com.beanit.iec61850bean.ServerModel;
import com.iednavigator.IEC61850Client;

/**
 * Prueba headless del DataSet Browser (PRO-4):
 * conecta al IED, lista DataSets y lee el contenido en lote (GetDataSetValues).
 */
public class TestDataSetBrowser {
    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "169.254.150.110";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 102;

        IEC61850Client client = new IEC61850Client();
        System.out.println("=== Conectando a " + host + ":" + port + " ===");
        if (!client.connect(host, port)) {
            System.out.println("FALLO: no se pudo conectar");
            return;
        }
        ServerModel model = client.getServerModel();
        if (model == null) {
            System.out.println("FALLO: modelo no recuperado");
            client.disconnect();
            return;
        }
        System.out.println("Modelo recuperado. DataSets:");
        int n = 0;
        for (DataSet ds : model.getDataSets()) {
            System.out.println("  [" + (n++) + "] " + ds.getReferenceStr()
                + " (" + ds.getMembers().size() + " miembros)");
        }
        if (n == 0) {
            System.out.println("El IED no expone DataSets");
            client.disconnect();
            return;
        }
        for (DataSet ds : model.getDataSets()) {
            String ref = ds.getReferenceStr();
            System.out.println("\n=== GetDataSetValues: " + ref + " ===");
            try {
                long t0 = System.currentTimeMillis();
                DataSet result = client.readDataSetValues(ref);
                long ms = System.currentTimeMillis() - t0;
                System.out.println("OK en " + ms + " ms. Valores:");
                for (FcModelNode m : result.getMembers()) {
                    System.out.println("  " + m.getReference() + " [" + m.getFc() + "] = "
                        + client.formatValue(m));
                }
            } catch (Exception e) {
                System.out.println("ERROR: " + e.getMessage());
            }
        }
        client.disconnect();
        System.out.println("\n=== Prueba finalizada ===");
    }
}
