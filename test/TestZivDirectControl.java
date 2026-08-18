import com.beanit.iec61850bean.*;
import com.iednavigator.IEC61850Client;

/**
 * Prueba de mando directo contra el ZIV 2IRX-A3N de banco (192.168.1.81).
 *
 * Punto: TEMPLATELD1/GLOG1.OpCntRs (Events Recorder), ctlModel=3
 * direct-with-enhanced-security. Es el primer mando ejercitado contra un IED
 * sin SBO: toda la validacion de campo previa (06-08) fue sobre
 * ctlModel=4, que este equipo no soporta por opcion de pedido.
 *
 * Secuencia: lee estado -> Oper con Test=true -> lee -> Oper real -> lee.
 *
 * Uso: java -cp "test;classes;lib\*" TestZivDirectControl
 */
public class TestZivDirectControl {

    static final String IP    = "192.168.1.81";
    static final String DO_REF = "TEMPLATELD1/GLOG1.OpCntRs";
    static final String CTLVAL = "0";

    public static void main(String[] args) throws Exception {
        IEC61850Client c = new IEC61850Client();
        if (!c.connect(IP, 102)) { System.out.println("no conecto"); return; }
        ServerModel m = c.getServerModel();
        ClientAssociation a = assoc(c);

        FcModelNode operDo = (FcModelNode) m.findModelNode(new ObjectReference(DO_REF), Fc.CO);
        FcModelNode oper   = (FcModelNode) operDo.getChild("Oper");

        System.out.println("== estado inicial ==");
        leer(a, m);

        System.out.println();
        System.out.println("== 1) Oper con Test=true ==");
        mandar(c, oper, true);
        leer(a, m);

        System.out.println();
        System.out.println("== 2) Oper real (Test=false) ==");
        mandar(c, oper, false);
        leer(a, m);

        c.disconnect();
    }

    static void mandar(IEC61850Client c, FcModelNode oper, boolean test) {
        try {
            long t0 = System.currentTimeMillis();
            IEC61850Client.ControlResult r = c.operateControl(
                    oper, CTLVAL, test, "IEDNavigator-banco", false, false);
            long dt = System.currentTimeMillis() - t0;
            System.out.println("  exito       : " + r.success + "   (" + dt + " ms)");
            System.out.println("  ctlModel    : " + r.ctlModel + " (" + r.ctlModelName + ")");
            if (r.error != null)         System.out.println("  error       : " + r.error);
            if (r.lastApplError != null) System.out.println("  ApplError   : " + r.lastApplError);
        } catch (Exception e) {
            System.out.println("  EXCEPCION   : " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    static void leer(ClientAssociation a, ServerModel m) {
        try {
            FcModelNode st = (FcModelNode) m.findModelNode(new ObjectReference(DO_REF), Fc.ST);
            a.getDataValues(st);
            System.out.println("  OpCntRs.stVal = " + st.getChild("stVal")
                             + "   t = " + st.getChild("t"));
        } catch (Exception e) {
            System.out.println("  <error leyendo: " + e.getMessage() + ">");
        }
    }

    static ClientAssociation assoc(IEC61850Client c) throws Exception {
        java.lang.reflect.Field f = c.getClass().getDeclaredField("association");
        f.setAccessible(true);
        return (ClientAssociation) f.get(c);
    }
}
