import com.beanit.iec61850bean.*;
import com.iednavigator.IEC61850Client;

import java.util.ArrayList;
import java.util.List;

/** Cliente headless: lee y escribe un ajuste FC=SP contra TestSpServer (PRO-9). */
public class TestSpClient {
    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 10102;

        IEC61850Client client = new IEC61850Client();
        if (!client.connect(host, port)) {
            System.out.println("FALLO: no conecta");
            return;
        }
        System.out.println("Conectado a " + host + ":" + port);

        // Recolectar BDAs FC=SP del modelo descubierto
        ServerModel model = client.getServerModel();
        List<BasicDataAttribute> sps = new ArrayList<>();
        for (ModelNode ld : model.getChildren())
            for (ModelNode ln : ld.getChildren()) collect(ln, sps);
        System.out.println("FC=SP encontrados: " + sps.size());
        for (int i = 0; i < Math.min(5, sps.size()); i++) {
            System.out.println("  " + sps.get(i).getReference() + " (" + sps.get(i).getBasicType() + ")");
        }
        if (sps.isEmpty()) return;

        // Leer, escribir y releer el primero
        String ref = sps.get(0).getReference().toString();
        String v0 = client.readValue(ref, Fc.SP);
        System.out.println("LEER  " + ref + " = '" + v0 + "'");
        String nuevo = "@PruebaSP";
        client.writeValue(ref, Fc.SP, nuevo);
        System.out.println("ESCRIBIR " + ref + " = '" + nuevo + "'");
        String v1 = client.readValue(ref, Fc.SP);
        System.out.println("RELEER " + ref + " = '" + v1 + "'");
        System.out.println(nuevo.equals(v1) ? "RESULTADO: OK (escritura verificada)" : "RESULTADO: MISMATCH");

        client.disconnect();
    }

    static void collect(ModelNode node, List<BasicDataAttribute> out) {
        if (node instanceof BasicDataAttribute) {
            if (((BasicDataAttribute) node).getFc() == Fc.SP) out.add((BasicDataAttribute) node);
            return;
        }
        if (node.getChildren() != null)
            for (ModelNode child : node.getChildren()) collect(child, out);
    }
}
