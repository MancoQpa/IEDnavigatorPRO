import com.beanit.iec61850bean.*;
import com.iednavigator.IEC61850Client;

/**
 * Dos preguntas sobre el XCBR1 del ZIV 2IRX de banco (192.168.1.81):
 *   A) Se puede operar la POSICION del interruptor (XCBR1.Pos)?
 *   B) Se puede operar un punto de control con estado observable y verlo cambiar?
 *
 * Para (B) se usa XCBR1.BlkOpn (ctlModel=3, tiene contraparte en FC=ST), con
 * lectura antes/despues y RESTAURACION al valor original. Todo en modo banco.
 */
public class TestXcbrOperate {

    static final String IP = "192.168.1.81";

    public static void main(String[] args) throws Exception {
        IEC61850Client c = new IEC61850Client();
        if (!c.connect(IP, 102)) { System.out.println("no conecto"); return; }
        ServerModel m = c.getServerModel();
        ClientAssociation a = assoc(c);

        // ---------- A) POSICION ----------
        System.out.println("========== A) XCBR1.Pos (posicion del interruptor) ==========");
        String posRef = "TEMPLATELD1/XCBR1.Pos";
        FcModelNode posSt = (FcModelNode) m.findModelNode(new ObjectReference(posRef), Fc.ST);
        a.getDataValues(posSt);
        System.out.println("Pos.stVal = " + posSt.getChild("stVal") + "   (2=abierto/off, 1=cerrado/on segun DbPos)");

        ModelNode posCf = m.findModelNode(new ObjectReference(posRef), Fc.CF);
        if (posCf != null && posCf.getChild("ctlModel") != null) {
            a.getDataValues((FcModelNode) posCf.getChild("ctlModel"));
            System.out.println("Pos.ctlModel = " + posCf.getChild("ctlModel"));
        }
        ModelNode posCo = m.findModelNode(new ObjectReference(posRef), Fc.CO);
        System.out.println("Pos tiene nodo de control (FC=CO)? " + (posCo != null));

        if (posCo != null) {
            FcModelNode oper = (FcModelNode) posCo.getChild("Oper");
            System.out.println("Intentando operar Pos...");
            IEC61850Client.ControlResult r = c.operateControl(oper, "on", false, "banco", false, false);
            System.out.println("  exito=" + r.success + "  " + r.ctlModelName
                    + (r.error != null ? "  error=" + r.error : ""));
        } else {
            System.out.println(">>> Pos es status-only en este IED: no expone Oper. No se puede comandar la posicion.");
        }

        // ---------- B) BLOQUEO DE APERTURA, con estado observable ----------
        System.out.println();
        System.out.println("========== B) XCBR1.BlkOpn (bloqueo de apertura, operable) ==========");
        String blkRef = "TEMPLATELD1/XCBR1.BlkOpn";
        FcModelNode blkSt = (FcModelNode) m.findModelNode(new ObjectReference(blkRef), Fc.ST);
        FcModelNode blkOper = (FcModelNode) ((ModelNode) m.findModelNode(new ObjectReference(blkRef), Fc.CO)).getChild("Oper");

        boolean original = leerBool(a, blkSt);
        System.out.println("BlkOpn.stVal ORIGINAL = " + original);

        boolean objetivo = !original;
        System.out.println();
        System.out.println("-- Oper -> " + objetivo + " (Test=false, cambio real) --");
        oper(c, blkOper, objetivo);
        boolean tras = leerBool(a, blkSt);
        System.out.println("BlkOpn.stVal DESPUES  = " + tras + (tras == objetivo ? "   [CAMBIO CONFIRMADO]" : "   [sin cambio]"));

        System.out.println();
        System.out.println("-- Restaurando -> " + original + " --");
        oper(c, blkOper, original);
        boolean fin = leerBool(a, blkSt);
        System.out.println("BlkOpn.stVal FINAL    = " + fin + (fin == original ? "   [RESTAURADO]" : "   [OJO: no volvio]"));

        c.disconnect();
    }

    static void oper(IEC61850Client c, FcModelNode oper, boolean v) {
        try {
            long t0 = System.currentTimeMillis();
            IEC61850Client.ControlResult r = c.operateControl(oper, String.valueOf(v), false, "banco", false, false);
            long dt = System.currentTimeMillis() - t0;
            System.out.println("  exito=" + r.success + "  " + r.ctlModelName + "  (" + dt + " ms)"
                    + (r.error != null ? "  error=" + r.error : "")
                    + (r.lastApplError != null ? "  ApplError=" + r.lastApplError : ""));
        } catch (Exception e) {
            System.out.println("  EXCEPCION: " + e.getMessage());
        }
    }

    static boolean leerBool(ClientAssociation a, FcModelNode st) throws Exception {
        a.getDataValues(st);
        ModelNode sv = st.getChild("stVal");
        return sv instanceof BdaBoolean && ((BdaBoolean) sv).getValue();
    }

    static ClientAssociation assoc(IEC61850Client c) throws Exception {
        java.lang.reflect.Field f = c.getClass().getDeclaredField("association");
        f.setAccessible(true);
        return (ClientAssociation) f.get(c);
    }
}
