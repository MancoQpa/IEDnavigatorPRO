import com.beanit.iec61850bean.*;

import java.util.List;

/** Servidor IEC 61850 mínimo para probar el panel Ajustes SP (PRO-9). */
public class TestSpServer {
    public static void main(String[] args) throws Exception {
        String scl = args[0];
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 10102;

        List<ServerModel> models = SclParser.parse(scl);
        ServerSap sap = new ServerSap(port, 0, null, models.get(0), null);
        sap.startListening(new ServerEventListener() {
            @Override
            public List<ServiceError> write(List<BasicDataAttribute> bdas) {
                for (BasicDataAttribute bda : bdas) {
                    System.out.println("WRITE: " + bda.getReference() + " = " + bda.getValueString());
                }
                return null; // aceptar todas las escrituras
            }
            @Override
            public void serverStoppedListening(ServerSap sap) {
                System.out.println("Servidor detenido");
            }
        });
        System.out.println("Servidor SP escuchando en puerto " + port + " (modelo: " + scl + ")");
        Thread.sleep(Long.MAX_VALUE);
    }
}
