import com.beanit.iec61850bean.*;

import java.util.Collections;
import java.util.List;

/**
 * Servidor IEC 61850 de prueba para el bridge: incrementa
 * GGIO1.AnIn1.mag.f cada segundo para verificar el push WS.
 */
public class BridgeTestServer {
    public static void main(String[] args) throws Exception {
        String scl = args[0];
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 10199;

        List<ServerModel> models = SclParser.parse(scl);
        ServerModel model = models.get(0);
        ServerSap sap = new ServerSap(port, 0, null, model, null);
        sap.startListening(new ServerEventListener() {
            @Override
            public List<ServiceError> write(List<BasicDataAttribute> bdas) {
                for (BasicDataAttribute bda : bdas) {
                    System.out.println("WRITE: " + bda.getReference() + " = " + bda.getValueString());
                }
                return null;
            }

            @Override
            public void serverStoppedListening(ServerSap s) {
                System.out.println("Servidor detenido");
            }
        });
        System.out.println("BridgeTestServer escuchando en puerto " + port);

        ServerModel copy = sap.getModelCopy();
        BdaFloat32 mag = (BdaFloat32) copy.findModelNode(
                copy.getChildren().iterator().next().getName() + "/GGIO1.AnIn1.mag.f", Fc.MX);
        float v = 0f;
        while (true) {
            Thread.sleep(1000);
            v += 1.5f;
            mag.setFloat(v);
            sap.setValues(Collections.singletonList(mag));
        }
    }
}
