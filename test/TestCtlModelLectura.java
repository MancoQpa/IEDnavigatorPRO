import com.beanit.iec61850bean.*;
import com.iednavigator.IEC61850Client;

import java.util.List;

/**
 * Qué ctlModel informa el cliente cuando el equipo no responde a la lectura.
 *
 * El caso de campo (2026-08-24): getCtlModelValue() se tragaba el error y devolvía el valor
 * local, que en un atributo nunca leído es el 0 por defecto — status-only, indistinguible de
 * un punto que realmente lo es. La pantalla decía status-only y no se sabía si era el archivo
 * o la comunicación.
 *
 * Para que la lectura falle con la asociación "viva" se usa el proxy de TestHeartbeat, que
 * deja de reenviar sin cerrar los sockets: el pedido vence por timeout y el cliente sigue
 * creyéndose conectado. En test_bay_control.cid, Q0CSWI1.Pos es sbo-with-enhanced-security (4).
 *
 * Uso:  java -cp "classes;lib\*;test" TestCtlModelLectura
 */
public class TestCtlModelLectura {

    private static int fallas = 0;

    static void chk(String titulo, boolean ok, String detalle) {
        if (!ok) fallas++;
        System.out.printf("%-58s %-12s %s%n", titulo, detalle, ok ? "OK" : "<<< FALLA");
    }

    public static void main(String[] args) throws Exception {
        int puertoServidor = 10312;

        List<ServerModel> models = SclParser.parse("test/test_bay_control.cid");
        ServerSap sap = new ServerSap(puertoServidor, 0, null, models.get(0), null);
        sap.startListening(new ServerEventListener() {
            @Override public List<ServiceError> write(List<BasicDataAttribute> b) { return null; }
            @Override public void serverStoppedListening(ServerSap s) { }
        });

        // ── Con lectura previa: se usa el último valor leído del equipo ────────────
        TestHeartbeat.AgujeroNegro proxy1 = new TestHeartbeat.AgujeroNegro(10313, puertoServidor);
        proxy1.start();
        IEC61850Client cli1 = cliente(10313);
        FcModelNode oper1 = oper(cli1);

        chk("enlace sano: lee el valor del equipo", cli1.getCtlModelValue(oper1) == 4,
            "=" + cli1.getCtlModelValue(oper1));
        proxy1.tragar();
        int v1 = cli1.getCtlModelValue(oper1);
        chk("lectura fallida tras una buena: el último leído", v1 == 4, "=" + v1);
        proxy1.cerrar();

        // ── Sin lectura previa: el default documentado, nunca el 0 local ───────────
        TestHeartbeat.AgujeroNegro proxy2 = new TestHeartbeat.AgujeroNegro(10314, puertoServidor);
        proxy2.start();
        IEC61850Client cli2 = cliente(10314);
        FcModelNode oper2 = oper(cli2);
        proxy2.tragar();
        int v2 = cli2.getCtlModelValue(oper2);
        chk("lectura fallida sin lectura previa: no es status-only", v2 != 0, "=" + v2);
        chk("lectura fallida sin lectura previa: default 1", v2 == 1, "=" + v2);
        proxy2.cerrar();

        sap.stop();
        System.out.println(fallas == 0 ? "\nTODO OK" : "\n" + fallas + " FALLAS");
        System.exit(fallas == 0 ? 0 : 1);
    }

    private static IEC61850Client cliente(int puerto) throws Exception {
        IEC61850Client cli = new IEC61850Client();
        cli.setConnectionTimeoutMs(3000);    // para no esperar 10 s a cada pedido tragado
        cli.connect("127.0.0.1", puerto);
        return cli;
    }

    private static FcModelNode oper(IEC61850Client cli) {
        for (ModelNode ld : cli.getServerModel().getChildren()) {
            ModelNode n = cli.getServerModel().findModelNode(ld.getName() + "/Q0CSWI1.Pos.Oper", Fc.CO);
            if (n instanceof FcModelNode) return (FcModelNode) n;
        }
        throw new IllegalStateException("No hay Q0CSWI1.Pos.Oper en el modelo");
    }
}
