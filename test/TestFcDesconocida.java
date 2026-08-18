import com.beanit.iec61850bean.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Banco del parche del enum {@link Fc}.
 *
 * Reproduce el fallo original y comprueba que el arreglo lo cubre en general, no sólo para la
 * FC concreta que lo destapó. El fallo es este: la librería pasa el resultado de
 * {@code Fc.fromString()} a la construcción del Logical Node sin chequear null, así que una
 * sola Functional Constraint no reconocida se lleva el nodo lógico entero — en el equipo donde
 * apareció eso significó perder LLN0 completo, con sus RCBs, DataSets y el SGCB.
 *
 * Se construyen los objetos de la librería directamente, que es donde revienta, en vez de
 * necesitar un IED que publique una FC rara.
 *
 * Uso:  java -cp "classes;lib\*;test" TestFcDesconocida
 */
public class TestFcDesconocida {

    private static int ok = 0, fallas = 0;

    private static void check(String caso, boolean cond, String detalle) {
        if (cond) ok++; else fallas++;
        System.out.printf("  [%s] %-52s %s%n", cond ? "OK" : "FALLA", caso, detalle == null ? "" : detalle);
    }

    /** Un DO cualquiera bajo la FC dada, con la forma que arma el parser al recuperar el modelo. */
    private static FcDataObject dataObject(String nombre, Fc fc) {
        return new FcDataObject(new ObjectReference("IED1LD0/LLN0." + nombre), fc,
                                new ArrayList<FcModelNode>());
    }

    public static void main(String[] args) {
        System.out.println("== el fallo original, reproducido ==");
        try {
            List<FcDataObject> dos = new ArrayList<>();
            dos.add(dataObject("sclcb", null));          // lo que devolvía fromString() antes
            new LogicalNode(new ObjectReference("IED1LD0/LLN0"), dos);
            check("una FC null tumba el Logical Node", false, "no lanzó: el banco ya no prueba nada");
        } catch (NullPointerException e) {
            check("una FC null tumba el Logical Node", true, "NullPointerException, como en campo");
        }

        System.out.println("\n== fromString() ya no devuelve null ==");
        check("la FC que destapó el fallo", Fc.fromString("SC") == Fc.SC, "SC");
        check("una FC inventada cae en UNKNOWN", Fc.fromString("QQ") == Fc.UNKNOWN, "QQ -> UNKNOWN");
        check("cadena vacía", Fc.fromString("") == Fc.UNKNOWN, null);
        check("null", Fc.fromString(null) == Fc.UNKNOWN, null);
        check("las estándar siguen resolviendo", Fc.fromString("ST") == Fc.ST, "ST");

        System.out.println("\n== el Logical Node sobrevive a una FC desconocida ==");
        try {
            List<FcDataObject> dos = new ArrayList<>();
            dos.add(dataObject("Mod",    Fc.fromString("ST")));
            dos.add(dataObject("Health", Fc.fromString("ST")));
            dos.add(dataObject("sclcb",  Fc.fromString("ZZ")));   // la propietaria desconocida
            dos.add(dataObject("NamPlt", Fc.fromString("DC")));
            LogicalNode ln = new LogicalNode(new ObjectReference("IED1LD0/LLN0"), dos);
            check("se construye sin excepción", true, null);
            int hijos = ln.getChildren() == null ? 0 : ln.getChildren().size();
            check("conserva los 4 Data Objects", hijos == 4, hijos + " hijos");
            check("los normales siguen bajo su FC",
                  ln.getChild("Mod", Fc.ST) != null && ln.getChild("NamPlt", Fc.DC) != null, null);
            check("el desconocido queda bajo UNKNOWN",
                  ln.getChild("sclcb", Fc.UNKNOWN) != null, null);
        } catch (Throwable t) {
            check("se construye sin excepción", false, t.getClass().getSimpleName() + ": " + t.getMessage());
        }

        System.out.println("\n== el aviso llega a la aplicación, una vez por FC ==");
        final List<String> avisos = new ArrayList<>();
        Fc.setUnknownFcListener(avisos::add);
        Fc.fromString("W1");
        Fc.fromString("W1");
        Fc.fromString("W1");
        Fc.fromString("W2");
        check("una sola línea por FC distinta", avisos.size() == 2, avisos.size() + " avisos para 4 llamadas");
        check("el aviso dice cuál era la FC",
              !avisos.isEmpty() && avisos.get(0).contains("W1"), avisos.isEmpty() ? "" : avisos.get(0));
        Fc.setUnknownFcListener(null);

        System.out.println("\n== los 15 originales conservan su ordinal ==");
        String[] orig = {"ST","MX","SP","SV","CF","DC","SG","SE","SR","OR","BL","EX","CO","RP","BR"};
        boolean ordinalesOk = true;
        for (int i = 0; i < orig.length; i++) {
            if (Fc.valueOf(orig[i]).ordinal() != i) { ordinalesOk = false; break; }
        }
        check("la librería indexa por ordinal", ordinalesOk, null);

        System.out.printf("%n%d pasaron, %d fallaron%n", ok, fallas);
        if (fallas > 0) System.exit(1);
    }
}
