import com.iednavigator.Iec61850Dictionary;

import java.lang.reflect.Method;

/**
 * Banco del diccionario para la familia de control de vano serie 670.
 *
 * Los nombres que se prueban salen del modelo real de un equipo explorado con la aplicación,
 * no de documentación del fabricante. Lo que se verifica es que cada nodo resuelva su clase de
 * forma exacta —sin caer en coincidencia parcial— y que los prefijos nuevos no capturen los de
 * las familias que ya estaban.
 *
 * Uso:  java -cp "classes;lib\*;test" TestDiccionarioBahia
 */
public class TestDiccionarioBahia {

    private static int ok = 0, fallas = 0;
    private static Method vendorPrefix;

    private static void check(String caso, boolean cond, String detalle) {
        if (cond) ok++; else fallas++;
        System.out.printf("  [%s] %-46s %s%n", cond ? "OK" : "FALLA", caso, detalle == null ? "" : detalle);
    }

    /** vendorPrefix() es de paquete; se llega por reflexión para no ampliar su visibilidad. */
    private static String[] prefijo(String nodo) throws Exception {
        return (String[]) vendorPrefix.invoke(null, nodo);
    }

    public static void main(String[] args) throws Exception {
        vendorPrefix = Iec61850Dictionary.class.getDeclaredMethod("vendorPrefix", String.class);
        vendorPrefix.setAccessible(true);

        System.out.println("== los nodos del equipo resuelven su prefijo ==");
        String[][] casos = {
            // nodo real          prefijo esperado
            {"SP16GGIO1",  "SP16"}, {"SP16GGIO9",  "SP16"}, {"SP16GGIO16", "SP16"},
            {"MVGGIO1",    "MV"},   {"MVGGIO12",   "MV"},
            {"DRPRDRE1",   "DRP"},
            {"QCBAY1",     "Q"},
        };
        for (String[] c : casos) {
            String[] r = prefijo(c[0]);
            check(c[0], r != null && c[1].equals(r[0]),
                  r == null ? "no resolvió" : "prefijo=" + r[0]);
        }

        System.out.println("\n== los prefijos nuevos no pisan a los que ya estaban ==");
        // Q existe ahora como prefijo suelto; Q0CSWI1 tiene que seguir cayendo en Q0, no en Q,
        // porque lo que queda tras el prefijo debe ser una clase LN conocida.
        String[] q0 = prefijo("Q0CSWI1");
        check("Q0CSWI1 sigue resolviendo a Q0", q0 != null && "Q0".equals(q0[0]),
              q0 == null ? "no resolvió" : "prefijo=" + q0[0]);
        String[] q1 = prefijo("Q1XSWI1");
        check("Q1XSWI1 sigue resolviendo a Q1", q1 != null && "Q1".equals(q1[0]),
              q1 == null ? "no resolvió" : "prefijo=" + q1[0]);
        String[] pott = prefijo("POTT_PSCH1");
        check("POTT_PSCH1 sin cambios", pott != null && "POTT_".equals(pott[0]),
              pott == null ? "no resolvió" : "prefijo=" + pott[0]);

        System.out.println("\n== expansión sólo donde el modelo la confirma ==");
        check("SP16 lleva expansión", prefijo("SP16GGIO1")[1] != null, prefijo("SP16GGIO1")[1]);
        check("MV lleva expansión",   prefijo("MVGGIO1")[1]   != null, prefijo("MVGGIO1")[1]);
        check("DRP sin expansión inventada", prefijo("DRPRDRE1")[1] == null, "null, con nota");
        check("DRP igual trae nota",         prefijo("DRPRDRE1")[2] != null, null);
        check("Q sin expansión inventada",   prefijo("QCBAY1")[1]   == null, "null, con nota");

        System.out.println("\n== la clase CBAY, que faltaba, está descrita ==");
        java.util.Map<String, String> info = Iec61850Dictionary.describe("QCBAY1");
        check("QCBAY1 devuelve descripción", info != null && !info.isEmpty(),
              info == null ? "null" : info.keySet().toString());
        check("se identifica como control de vano",
              info != null && String.valueOf(info).toLowerCase().contains("vano"), null);

        System.out.println("\n== un nodo inventado no inventa significado ==");
        check("ZZ99WXYZ1 no resuelve", prefijo("ZZ99WXYZ1") == null, "null");

        System.out.printf("%n%d pasaron, %d fallaron%n", ok, fallas);
        if (fallas > 0) System.exit(1);
    }
}
