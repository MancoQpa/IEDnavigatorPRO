import com.beanit.iec61850bean.*;
import com.iednavigator.IEC61850Client;
import java.util.*;

/**
 * Verificacion en vivo de los dos parches aplicados para el ZIV 2IRX:
 *   1) com.beanit.iec61850bean.Fc sombreado con GO/LG/MS/US/GS
 *   2) IEC61850Client.findSclFiles() con barrido recursivo
 *
 * Uso:  java -cp "classes;lib\*" VerifyZivFix [ip] [puerto]
 */
public class VerifyZivFix {

    public static void main(String[] args) throws Exception {
        String ip = args.length > 0 ? args[0] : "192.168.1.81";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 102;

        // ---------- Parche 1: el enum sombreado esta activo? ----------
        System.out.println("=== Fc en uso ===");
        System.out.println("origen : " + Fc.class.getProtectionDomain().getCodeSource().getLocation());
        System.out.println("valores: " + Fc.values().length + " -> " + Arrays.toString(Fc.values()));
        for (String s : new String[]{"GO", "LG", "MS", "US", "GS", "ST", "ZZ"}) {
            System.out.println("  fromString(\"" + s + "\") = " + Fc.fromString(s));
        }

        // ---------- retrieveModel() contra el IED ----------
        System.out.println();
        System.out.println("=== conexion a " + ip + ":" + port + " ===");
        IEC61850Client client = new IEC61850Client();
        long t0 = System.currentTimeMillis();
        boolean ok = client.connect(ip, port);
        long dt = System.currentTimeMillis() - t0;
        System.out.println("connect() = " + ok + " en " + dt + " ms");
        if (!ok) return;

        ServerModel model = client.getServerModel();
        int lns = 0, dataSets = 0, urcbs = 0, brcbs = 0, gocbs = 0;
        List<String> lln0Children = new ArrayList<>();

        for (ModelNode ldNode : model.getChildren()) {
            LogicalDevice ld = (LogicalDevice) ldNode;
            System.out.println("LD '" + ld.getName() + "': " + ld.getChildren().size() + " LNs");
            for (ModelNode lnNode : ld.getChildren()) {
                lns++;
                LogicalNode ln = (LogicalNode) lnNode;
                if ("LLN0".equals(ln.getName())) {
                    for (ModelNode ch : ln.getChildren()) {
                        String fc = (ch instanceof FcModelNode)
                                ? String.valueOf(((FcModelNode) ch).getFc()) : "-";
                        lln0Children.add(ch.getName() + " [" + ch.getClass().getSimpleName()
                                + ", FC=" + fc + "]");
                    }
                }
                urcbs += ln.getUrcbs().size();
                brcbs += ln.getBrcbs().size();
            }
        }
        dataSets = model.getDataSets().size();
        // Los GoCB llegan como FcDataObject con FC=GO dentro de LLN0
        for (String s : lln0Children) if (s.startsWith("gcb") || s.contains("GoCB")) gocbs++;

        System.out.println();
        System.out.println("=== resultado ===");
        System.out.println("LNs totales : " + lns);
        System.out.println("DataSets    : " + dataSets);
        System.out.println("URCBs       : " + urcbs);
        System.out.println("BRCBs       : " + brcbs);
        System.out.println("LLN0 presente: " + (!lln0Children.isEmpty()));
        System.out.println("hijos de LLN0 (" + lln0Children.size() + "):");
        for (String s : lln0Children) System.out.println("    " + s);

        // ---------- Parche 2: busqueda recursiva de SCL ----------
        System.out.println();
        System.out.println("=== findSclFiles() ===");
        List<String> scl = client.findSclFiles();
        System.out.println("encontrados: " + scl.size());
        for (String s : scl) System.out.println("    " + s);

        client.disconnect();
    }
}
