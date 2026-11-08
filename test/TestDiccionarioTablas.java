import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Tablas del diccionario en recursos (diccionario/norma.tsv y do_fabricante.tsv), nombres de LD
 * con el nombre del IED delante y prefijos de LN de la familia PROT/CTRL/MEAS/RCD.
 *
 * Verifica que las tablas se carguen (si el recurso no llega al paquete, todo esto falla), que
 * una entrada de fabricante nunca tape a una de la norma y que los prefijos largos ganen a los
 * cortos (CBSYNC antes que CB).
 */
public class TestDiccionarioTablas {
    static int ok = 0, fallas = 0;
    static Method lookup, vendorPrefix, describeLd;
    static Field fType, fStd, fEs;

    public static void main(String[] a) throws Exception {
        Class<?> d = Class.forName("com.iednavigator.Iec61850Dictionary");
        lookup = d.getDeclaredMethod("lookup", String.class);
        lookup.setAccessible(true);
        vendorPrefix = d.getDeclaredMethod("vendorPrefix", String.class);
        vendorPrefix.setAccessible(true);
        describeLd = d.getDeclaredMethod("describeLogicalDevice", String.class);
        Class<?> e = Class.forName("com.iednavigator.Iec61850Dictionary$Entry");
        fType = e.getDeclaredField("type"); fType.setAccessible(true);
        fStd = e.getDeclaredField("standard"); fStd.setAccessible(true);
        fEs = e.getDeclaredField("fullNameES"); fEs.setAccessible(true);

        // Norma: clases, CDC, DO y DA nuevos
        tipo("LGOS1", "LOGICAL_NODE");
        tipo("RDIR1", "LOGICAL_NODE");
        tipo("ASG", "CDC");
        tipo("ASG01", "CDC");               // ajuste genérico numerado: explica el CDC
        tipo("PhyNam", "DATA_OBJECT");
        tipo("LocKey", "DATA_OBJECT");
        tipo("Ind17", "DATA_OBJECT");       // familia numerada
        tipo("ctlModel", "DATA_ATTRIBUTE");
        tipo("rptEna", "DATA_ATTRIBUTE");
        tipo("c3", "DATA_ATTRIBUTE");
        normaEmpieza("PhyNam", "IEC 61850-7-4");

        // Lo escrito a mano antes de las tablas no cambia
        nombre("Pos", null);                // sigue existiendo
        normaEmpieza("Pos", "IEC 61850");
        normaEmpieza("OpDlTmms", "IEC 61850");

        // Fabricante
        tipo("BF1BF2Ena", "DATA_OBJECT");
        normaEmpieza("BF1BF2Ena", "No definido");
        normaEmpieza("OUTMATR7", "No definido");   // familia numerada
        normaEmpieza("HarPhv03", "No definido");
        normaEmpieza("BO5PlsTmms", "No definido");  // dígito en el medio, clave propia
        normaEmpieza("Ena3U0", "No definido");      // dígitos con significado
        normaEmpieza("Enable", "No definido");

        // Prefijos
        prefijo("CBSYNCCSWI1", "CBSYNC");
        prefijo("CBNOCHKCSWI1", "CBNOCHK");
        prefijo("CBXCBR1", "CB");
        prefijo("DSXSWI3", "DS");
        prefijo("DEVPGGIO10", "DEVP");
        prefijo("DCBPSCH1", "DCB");
        prefijo("DCUBPSCH1", "DCUB");       // DCUB gana a DCB
        prefijo("POTTPSCH1", "POTT");
        prefijo("POTT_PSCH1", "POTT_");     // el de guion bajo sigue siendo el suyo
        prefijo("RBGGIO3", "RB");
        prefijo("ONB1_GGIO1", "ONB1_");
        prefijo("EXP3_GGIO4", "EXP3_");
        prefijo("CUS2_GGIO7", "CUS2_");

        // Armónicos (HWYE/HDEL)
        tipo("phsAHar", "DATA_ATTRIBUTE");
        tipo("numCyc", "DATA_ATTRIBUTE");

        // LD, con y sin el nombre del IED delante
        ld("IED1PROT", true);
        ld("IED1LD0", true);
        ld("IED1PIGO", true);
        ld("RCD", true);
        ld("IED1XYZW", false);
        ld("SEL_421MET", true);
        ld("SEL_401MU01", true);
        ldTexto("IED1PROT", "Funciones de protección");  // PROT, no el PRO más corto
        ldTexto("IED1PRO", "Elementos de protección");

        System.out.println(ok + " pasaron, " + fallas + " fallaron");
        System.out.println(fallas == 0 ? "TODO OK" : "HAY FALLAS");
        if (fallas > 0) System.exit(1);
    }

    static Object entrada(String n) throws Exception { return lookup.invoke(null, n); }

    static void tipo(String n, String esperado) throws Exception {
        Object e = entrada(n);
        ver(e != null && fType.get(e).toString().equals(esperado), n + " es " + esperado
            + (e == null ? " (no encontrado)" : " (es " + fType.get(e) + ")"));
    }

    static void nombre(String n, String esperado) throws Exception {
        Object e = entrada(n);
        ver(e != null && (esperado == null || esperado.equals(fEs.get(e))), n + " existe");
    }

    static void normaEmpieza(String n, String prefijo) throws Exception {
        Object e = entrada(n);
        String std = e == null ? null : (String) fStd.get(e);
        ver(std != null && std.startsWith(prefijo), n + " referencia \"" + std + "\"");
    }

    static void prefijo(String ln, String esperado) throws Exception {
        String[] v = (String[]) vendorPrefix.invoke(null, ln);
        ver(v != null && v[0].equals(esperado), ln + " prefijo " + (v == null ? null : v[0]));
    }

    static void ld(String nombre, boolean conocido) throws Exception {
        Object r = describeLd.invoke(null, nombre);
        ver((r != null) == conocido, nombre + (conocido ? " reconocido" : " no reconocido"));
    }

    static void ldTexto(String nombre, String empieza) throws Exception {
        String r = (String) describeLd.invoke(null, nombre);
        ver(r != null && r.startsWith(empieza), nombre + " → " + (r == null ? null : r.substring(0, Math.min(40, r.length()))));
    }

    static void ver(boolean c, String msg) {
        if (c) ok++; else fallas++;
        System.out.println((c ? "OK    " : "FALLA ") + msg);
    }
}
