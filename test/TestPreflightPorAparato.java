import com.beanit.iec61850bean.*;
import com.iednavigator.IEC61850Client;

import java.util.List;

/**
 * Banco del acotamiento del preflight al aparato operado.
 *
 * El plan y el razonamiento completo están en docs/preflight-agrupamiento-por-aparato.md.
 * En corto: el enclavamiento y los bloqueos se acotaban por Logical Device, lo cual es
 * correcto cuando el LD *es* el aparato, pero hay familias donde varios aparatos comparten
 * un mismo LD. Ahí, operar el interruptor leía el CILO de todos, y como
 * interlockBlocking() corta en el primero que no habilite, el aviso de «vas a operar sin
 * verificación de enclavamiento» saltaba en CADA cierre — el CILO de una puesta a tierra en
 * un vano energizado está en false con toda razón.
 *
 * Ese diálogo es el que no puede volverse rutina: si aparece siempre, el operador aprende a
 * darle a continuar sin leer, y el día que el aviso es real ya no lo ve.
 *
 * Los cuatro casos son los que fijó el plan, más las guardas del agrupamiento.
 *
 * Uso:  java -cp "classes;lib\*;test" TestPreflightPorAparato [puerto]
 */
public class TestPreflightPorAparato {

    private static int ok = 0, fallas = 0;
    private static ServerModel srvModel;

    // Los helpers de agrupamiento son de paquete: se llega por reflexion para no ampliar su
    // visibilidad solo por el banco, igual que TestDiccionarioBahia con vendorPrefix().
    private static java.lang.reflect.Method mPrefijo, mClase;

    private static String prefijoDeLn(String n) throws Exception {
        return (mPrefijo == null) ? "(sin agrupamiento)" : (String) mPrefijo.invoke(null, n);
    }

    private static boolean claseDeLn(String n, String c) throws Exception {
        return (mClase != null) && (Boolean) mClase.invoke(null, n, c);
    }

    private static void check(String caso, boolean cond, String detalle) {
        if (cond) ok++; else fallas++;
        System.out.printf("  [%s] %-52s %s%n", cond ? "OK" : "FALLA", caso, detalle == null ? "" : detalle);
    }

    private static void set(String ref, boolean v) {
        ModelNode n = srvModel.findModelNode(ref, Fc.ST);
        if (n instanceof BdaBoolean) ((BdaBoolean) n).setValue(v);
    }

    private static void setEnum(String ref, int ord) {
        ModelNode n = srvModel.findModelNode(ref, Fc.ST);
        if (n instanceof BdaInt8) ((BdaInt8) n).setValue((byte) ord);
        else if (n instanceof BdaInt8U) ((BdaInt8U) n).setValue((short) ord);
        else if (n instanceof BdaInt16) ((BdaInt16) n).setValue((short) ord);
    }

    private static void setDbpos(String ref, int pos) {
        ModelNode n = srvModel.findModelNode(ref, Fc.ST);
        if (!(n instanceof BdaDoubleBitPos)) return;
        byte[] b = new byte[1];
        switch (pos) { case 1: b[0] = 0x40; break; case 2: b[0] = (byte) 0x80; break;
                       case 3: b[0] = (byte) 0xC0; break; default: b[0] = 0x00; }
        ((BdaDoubleBitPos) n).setValue(b);
    }

    private static long bloqueantes(List<IEC61850Client.PreflightCheck> cs) {
        return cs.stream().filter(c -> c.blocking).count();
    }

    private static boolean menciona(List<IEC61850Client.PreflightCheck> cs, String frag) {
        return cs.stream().anyMatch(c -> c.reference != null && c.reference.contains(frag));
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 10422;
        // Si los helpers no existen es que el agrupamiento no esta implementado: el banco
        // tiene que FALLAR esos casos, no reventar antes de correr los demas.
        try {
            mPrefijo = IEC61850Client.class.getDeclaredMethod("prefijoDeLn", String.class);
            mClase   = IEC61850Client.class.getDeclaredMethod("claseDeLn", String.class, String.class);
            mPrefijo.setAccessible(true); mClase.setAccessible(true);
        } catch (NoSuchMethodException e) {
            System.out.println("  (los helpers de agrupamiento no existen en esta build)");
        }
        String LD = "BAYCTRLCTRL";

        srvModel = SclParser.parse("test/test_bay_dos_aparatos.cid").get(0);
        ServerSap sap = new ServerSap(port, 0, null, srvModel, null);
        sap.startListening(new ServerEventListener() {
            @Override public List<ServiceError> write(List<BasicDataAttribute> bdas) { return null; }
            @Override public void serverStoppedListening(ServerSap s) { }
        });
        IEC61850Client cli = new IEC61850Client();
        cli.connect("127.0.0.1", port);

        FcModelNode operQ0 = (FcModelNode) cli.getServerModel()
            .findModelNode(LD + "/Q0CSWI1.Pos", Fc.CO).getChild("Oper");

        // Estado de partida: todo en orden salvo lo que cada caso cambie.
        setEnum(LD + "/LLN0.Beh.stVal", 1);       setEnum(LD + "/LLN0.Health.stVal", 1);
        setEnum(LD + "/Q0CSWI1.Beh.stVal", 1);    setEnum(LD + "/Q0CSWI1.Health.stVal", 1);
        set(LD + "/Q0XCBR1.BlkOpn.stVal", false); set(LD + "/Q0XCBR1.BlkCls.stVal", false);
        set(LD + "/Q8XSWI1.BlkOpn.stVal", false); set(LD + "/Q8XSWI1.BlkCls.stVal", false);
        set(LD + "/Q0CILO1.EnaOpn.stVal", true);
        setDbpos(LD + "/Q0CSWI1.Pos.stVal", 1);   // off: cerrar tiene sentido
        setDbpos(LD + "/Q8XSWI1.Pos.stVal", 1);   // la tierra, abierta

        // ── CASO 1: el CILO del vecino NO debe disparar el aviso ──────────────
        System.out.println("== 1. el vecino no bloquea: Q0 habilita, Q8 no ==");
        // Es el caso que motivó todo: vano energizado, la tierra no se puede cerrar
        // —correcto— y eso hacía saltar el diálogo de bypass en cada cierre de Q0.
        set(LD + "/Q0CILO1.EnaCls.stVal", true);
        set(LD + "/Q8CILO1.EnaCls.stVal", false);
        Boolean bloqueo1 = cli.interlockBlocking(operQ0, "true");
        check("interlockBlocking = false (sin diálogo)", Boolean.FALSE.equals(bloqueo1),
              String.valueOf(bloqueo1) + " — con el alcance por LD daba TRUE");
        List<IEC61850Client.PreflightCheck> p1 = cli.preflightControl(operQ0, "true");
        check("ninguna condición bloqueante", bloqueantes(p1) == 0, bloqueantes(p1) + " de " + p1.size());
        check("el CILO del vecino se sigue informando", menciona(p1, "Q8CILO1"),
              "no se oculta: pasa a contexto");
        check("la posición del vecino se informa", menciona(p1, "Q8XSWI1.Pos"),
              "control compensatorio de acotar el enclavamiento");

        // ── CASO 2: el CILO propio SÍ bloquea ─────────────────────────────────
        System.out.println("\n== 2. el propio sí bloquea: Q0 no habilita ==");
        // La guarda de que no se rompió lo del 06-08.
        set(LD + "/Q0CILO1.EnaCls.stVal", false);
        Boolean bloqueo2 = cli.interlockBlocking(operQ0, "true");
        check("interlockBlocking = true (con diálogo)", Boolean.TRUE.equals(bloqueo2),
              String.valueOf(bloqueo2));
        List<IEC61850Client.PreflightCheck> p2 = cli.preflightControl(operQ0, "true");
        check("una condición bloqueante", bloqueantes(p2) == 1, bloqueantes(p2) + " de " + p2.size());
        check("la bloqueante es la del aparato operado",
              p2.stream().anyMatch(c -> c.blocking && c.reference.contains("Q0CILO1")), null);

        // ── CASO 3: el bloqueo del vecino tampoco bloquea ─────────────────────
        System.out.println("\n== 3. el bloqueo del vecino es contexto, no condición ==");
        set(LD + "/Q0CILO1.EnaCls.stVal", true);
        set(LD + "/Q8XSWI1.BlkCls.stVal", true);
        List<IEC61850Client.PreflightCheck> p3 = cli.preflightControl(operQ0, "true");
        check("sigue sin bloqueantes", bloqueantes(p3) == 0, bloqueantes(p3) + " de " + p3.size());
        set(LD + "/Q0XCBR1.BlkCls.stVal", true);
        List<IEC61850Client.PreflightCheck> p3b = cli.preflightControl(operQ0, "true");
        check("el bloqueo del propio sí cuenta", bloqueantes(p3b) == 1,
              bloqueantes(p3b) + " de " + p3b.size());
        set(LD + "/Q0XCBR1.BlkCls.stVal", false);
        set(LD + "/Q8XSWI1.BlkCls.stVal", false);

        // ── CASO 4: el agrupamiento no se cuelga del número ───────────────────
        System.out.println("\n== 4. el agrupamiento, y lo que NO afirma ==");
        check("Q1 no captura a Q10", !"Q1".equals(prefijoDeLn("Q10CSWI1")),
              "prefijoDeLn(Q10CSWI1) = " + prefijoDeLn("Q10CSWI1"));
        check("un LN sin prefijo devuelve vacío", "".equals(prefijoDeLn("CSWI1")),
              "cae al alcance por LD");
        check("un LN de otra clase no agrupa", prefijoDeLn("MMXU1") == null, null);
        check("la clase se reconoce exacta", claseDeLn("Q0CSWI1", "CSWI")
              && !claseDeLn("Q0CSWIX", "CSWI"), null);

        // ── CASO 5: un aparato por LD cae al comportamiento anterior ──────────
        System.out.println("\n== 5. guarda de regresión: un aparato por LD ==");
        // Es la forma del vano de capacitores. Con un solo aparato el agrupamiento no
        // aporta nada y tiene que quedar el alcance de siempre, intacto.
        ServerModel uno = SclParser.parse("test/test_bay_control.cid").get(0);
        ServerSap sap2 = new ServerSap(port + 1, 0, null, uno, null);
        sap2.startListening(new ServerEventListener() {
            @Override public List<ServiceError> write(List<BasicDataAttribute> bdas) { return null; }
            @Override public void serverStoppedListening(ServerSap s) { }
        });
        IEC61850Client cli2 = new IEC61850Client();
        cli2.connect("127.0.0.1", port + 1);
        FcModelNode operUno = (FcModelNode) cli2.getServerModel()
            .findModelNode(LD + "/Q0CSWI1.Pos", Fc.CO).getChild("Oper");
        ModelNode ena = uno.findModelNode(LD + "/Q0CILO1.EnaCls.stVal", Fc.ST);
        if (ena instanceof BdaBoolean) ((BdaBoolean) ena).setValue(false);
        Boolean b5 = cli2.interlockBlocking(operUno, "true");
        check("con un solo aparato el aviso sigue saliendo", Boolean.TRUE.equals(b5),
              String.valueOf(b5));
        cli2.disconnect(); sap2.stop();

        cli.disconnect();
        sap.stop();
        System.out.printf("%n%d pasaron, %d fallaron%n", ok, fallas);
        if (fallas > 0) System.exit(1);
    }
}
