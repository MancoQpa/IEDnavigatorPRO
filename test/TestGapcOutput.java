import com.beanit.iec61850bean.*;
import com.iednavigator.IEC61850Client;

/**
 * Ultimo candidato para ver un flanco de estado por mando: la salida de logica
 * libre LOGGAPC1.SPCSO01 (GAPC = generic automatic process control). No depende
 * de la posicion del interruptor, asi que si algun punto latchea, es este.
 *
 * Lee -> opera al valor opuesto -> lee -> restaura -> lee.
 */
public class TestGapcOutput {
    static final String IP = "192.168.1.81";
    static final String REF = "TEMPLATELD1/LOGGAPC1.SPCSO01";

    public static void main(String[] args) throws Exception {
        IEC61850Client c = new IEC61850Client();
        if (!c.connect(IP, 102)) { System.out.println("no conecto"); return; }
        ServerModel m = c.getServerModel();
        ClientAssociation a = assoc(c);

        FcModelNode st = (FcModelNode) m.findModelNode(new ObjectReference(REF), Fc.ST);
        ModelNode coDo = m.findModelNode(new ObjectReference(REF), Fc.CO);
        ModelNode cfDo = m.findModelNode(new ObjectReference(REF), Fc.CF);

        int cm = -1;
        if (cfDo != null && cfDo.getChild("ctlModel") != null) {
            a.getDataValues((FcModelNode) cfDo.getChild("ctlModel"));
            cm = ((BdaInt8) cfDo.getChild("ctlModel")).getValue();
        }
        System.out.println("SPCSO01 ctlModel = " + cm + "   tiene Oper? " + (coDo != null)
                + "   tiene ST.stVal? " + (st != null && st.getChild("stVal") != null));
        if (cm <= 0 || coDo == null || st == null || st.getChild("stVal") == null) {
            System.out.println(">>> no operable o sin estado observable; fin.");
            c.disconnect(); return;
        }

        FcModelNode oper = (FcModelNode) coDo.getChild("Oper");
        boolean orig = leer(a, st);
        System.out.println("stVal ORIGINAL = " + orig);

        System.out.println();
        System.out.println("-- Oper -> " + (!orig) + " --");
        oper(c, oper, !orig);
        boolean tras = leer(a, st);
        System.out.println("stVal DESPUES  = " + tras + (tras != orig ? "   [FLANCO OBSERVADO]" : "   [sin cambio]"));

        System.out.println();
        System.out.println("-- Restaurando -> " + orig + " --");
        oper(c, oper, orig);
        boolean fin = leer(a, st);
        System.out.println("stVal FINAL    = " + fin + (fin == orig ? "   [RESTAURADO]" : "   [OJO]"));

        c.disconnect();
    }

    static boolean leer(ClientAssociation a, FcModelNode st) throws Exception {
        a.getDataValues(st);
        ModelNode sv = st.getChild("stVal");
        return sv instanceof BdaBoolean && ((BdaBoolean) sv).getValue();
    }

    static void oper(IEC61850Client c, FcModelNode oper, boolean v) {
        try {
            IEC61850Client.ControlResult r = c.operateControl(oper, String.valueOf(v), false, "banco", false, false);
            System.out.println("  exito=" + r.success + "  " + r.ctlModelName
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
