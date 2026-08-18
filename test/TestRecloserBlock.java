import com.beanit.iec61850bean.*;
import com.iednavigator.IEC61850Client;

/**
 * On/off del recierre (RREC1, ANSI 79) en el ZIV 2IRX de banco.
 *
 * El on/off de funcion via Mod NO es comandable (Mod.ctlModel=0). Lo que si es
 * operable es el par bloqueo/desbloqueo:
 *   RREC1.BlkRec2   (ctlModel=3) -> bloquea el recierre
 *   RREC1.RsBlkRec2 (ctlModel=3) -> resetea el bloqueo
 * ambos con estado observable en BlkRec2.stVal y RREC1.Auto.
 *
 * Secuencia: lee -> RsBlkRec2 (desbloquear) -> lee -> BlkRec2 (rebloquear, restaura) -> lee.
 */
public class TestRecloserBlock {

    static final String IP = "192.168.1.81";

    public static void main(String[] args) throws Exception {
        IEC61850Client c = new IEC61850Client();
        if (!c.connect(IP, 102)) { System.out.println("no conecto"); return; }
        ServerModel m = c.getServerModel();
        ClientAssociation a = assoc(c);

        FcModelNode blkSt   = (FcModelNode) m.findModelNode(new ObjectReference("TEMPLATELD1/RREC1.BlkRec2"), Fc.ST);
        FcModelNode autoSt  = (FcModelNode) m.findModelNode(new ObjectReference("TEMPLATELD1/RREC1.Auto"),    Fc.ST);
        FcModelNode blkOper = (FcModelNode) ((ModelNode) m.findModelNode(new ObjectReference("TEMPLATELD1/RREC1.BlkRec2"),   Fc.CO)).getChild("Oper");
        FcModelNode rsOper  = (FcModelNode) ((ModelNode) m.findModelNode(new ObjectReference("TEMPLATELD1/RREC1.RsBlkRec2"), Fc.CO)).getChild("Oper");

        System.out.println("== estado inicial ==");
        estado(a, blkSt, autoSt);

        System.out.println();
        System.out.println("== 1) RsBlkRec2 = true  (desbloquear recierre) ==");
        oper(c, rsOper, "true");
        estado(a, blkSt, autoSt);

        System.out.println();
        System.out.println("== 2) BlkRec2 = true  (rebloquear, restaurar estado original) ==");
        oper(c, blkOper, "true");
        estado(a, blkSt, autoSt);

        c.disconnect();
    }

    static void estado(ClientAssociation a, FcModelNode blk, FcModelNode auto) throws Exception {
        a.getDataValues(blk);
        a.getDataValues(auto);
        System.out.println("  BlkRec2.stVal = " + val(blk) + "   |   Auto.stVal = " + val(auto));
    }

    static String val(FcModelNode st) {
        ModelNode sv = st.getChild("stVal");
        return (sv instanceof BdaBoolean) ? String.valueOf(((BdaBoolean) sv).getValue()) : String.valueOf(sv);
    }

    static void oper(IEC61850Client c, FcModelNode oper, String v) {
        try {
            long t0 = System.currentTimeMillis();
            IEC61850Client.ControlResult r = c.operateControl(oper, v, false, "banco", false, false);
            long dt = System.currentTimeMillis() - t0;
            System.out.println("  exito=" + r.success + "  " + r.ctlModelName + "  (" + dt + " ms)"
                    + (r.error != null ? "  error=" + r.error : "")
                    + (r.lastApplError != null ? "  ApplError=" + r.lastApplError : ""));
        } catch (Exception e) {
            System.out.println("  EXCEPCION: " + e.getMessage());
        }
    }

    static ClientAssociation assoc(IEC61850Client c) throws Exception {
        java.lang.reflect.Field f = c.getClass().getDeclaredField("association");
        f.setAccessible(true);
        return (ClientAssociation) f.get(c);
    }
}
