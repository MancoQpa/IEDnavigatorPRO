import com.beanit.iec61850bean.*;

import java.util.List;

/** Prueba headless: cuenta atributos FC=SP en un modelo SCL (PRO-9). */
public class TestSpSettings {
    static int count = 0, shown = 0;

    public static void main(String[] args) throws Exception {
        List<ServerModel> models = SclParser.parse(args[0]);
        ServerModel model = models.get(0);
        for (ModelNode ld : model.getChildren()) {
            for (ModelNode ln : ld.getChildren()) walk(ln);
        }
        System.out.println("Total FC=SP: " + count);
    }

    static void walk(ModelNode node) {
        if (node instanceof BasicDataAttribute) {
            BasicDataAttribute bda = (BasicDataAttribute) node;
            if (bda.getFc() == Fc.SP) {
                count++;
                if (shown++ < 15) {
                    System.out.printf("  %-60s %-10s = %s%n", bda.getReference(),
                        bda.getBasicType(), bda.getValueString());
                }
            }
            return;
        }
        if (node.getChildren() != null) {
            for (ModelNode child : node.getChildren()) walk(child);
        }
    }
}
