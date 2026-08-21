import com.beanit.iec61850bean.*;
import com.iednavigator.IEC61850Server;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Banco del ancho del entero con que llega un enumerado, y de la tolerancia a excepciones no
 * declaradas de la libreria. Los dos arreglos salieron del trabajo con el simulador Android
 * (HALLAZGOS_DESDE_EL_SIMULADOR.md, hallazgos 6 y 7).
 *
 * EL PUNTO QUE HAY QUE ENTENDER ANTES DE TOCAR ESTO:
 *
 * El guard viejo era `instanceof BdaInt8 || instanceof BdaInt8U`, y era un no-op silencioso.
 * La razon no es la que uno supondria. Medido sobre los 162 archivos de fabricante del corpus:
 *
 *   beanit crudo .................. BdaInt8 48144 / BdaInt16     0
 *   por la ruta de la aplicacion ... BdaInt8     3 / BdaInt16 51996
 *
 * O sea que los archivos de fabricante SI dan BdaInt8 cuando los parsea la libreria sola. Lo
 * que ensancha el ordinal es `IEC61850Server.patchMissingEnumOrdinals()`, que sintetiza los
 * EnumVal que faltan y con eso empuja el rango fuera del byte, con lo cual SclParser elige
 * BdaInt16. Es decir: dos funciones del propio proyecto peleandose entre si. El guard se
 * escribio para el mundo anterior al parche de enums y dejo de matchear en silencio cuando el
 * parche se agrego.
 *
 * Por eso no alcanza con parsear un CID sintetico y mirar el tipo: hay que hacerlo pasar por
 * la ruta de la aplicacion. El banco lo mide en la misma corrida y sobre el mismo archivo —
 * test_bay_control.cid da BdaInt8 con la libreria sola y BdaInt16 por la ruta de la app—, asi
 * que la causalidad queda reproducida sin depender de ningun archivo de fabricante.
 *
 * Uso:  java -cp "classes;lib\*;test" TestEnumAncho
 */
public class TestEnumAncho {

    private static int ok = 0, fallas = 0;

    // GoosePanel es de paquete: se llega por reflexion para no ampliar su visibilidad solo
    // por el banco, igual que TestDiccionarioBahia con vendorPrefix().
    private static Method mEsEnteroDeEnum, mEnumOrdinalOf;

    private static boolean esEnteroDeEnum(ModelNode n) throws Exception {
        return (Boolean) mEsEnteroDeEnum.invoke(null, n);
    }

    private static int enumOrdinalOf(ModelNode n) throws Exception {
        return (Integer) mEnumOrdinalOf.invoke(null, n);
    }

    private static void check(String caso, boolean cond, String detalle) {
        if (cond) ok++; else fallas++;
        System.out.printf("  [%s] %-52s %s%n", cond ? "OK" : "FALLA", caso, detalle == null ? "" : detalle);
    }

    /** El guard que habia antes del arreglo, para poder medir contra el. */
    private static boolean guardViejo(ModelNode n) {
        return n instanceof BdaInt8 || n instanceof BdaInt8U;
    }

    private static ModelNode bda(String tipo) {
        ObjectReference r = new ObjectReference("TESTLD/LLN0.Mod.stVal");
        switch (tipo) {
            case "BdaInt8":   return new BdaInt8(r, Fc.ST, null, false, false);
            case "BdaInt8U":  return new BdaInt8U(r, Fc.ST, null, false, false);
            case "BdaInt16":  return new BdaInt16(r, Fc.ST, null, false, false);
            case "BdaInt16U": return new BdaInt16U(r, Fc.ST, null, false, false);
            case "BdaInt32":  return new BdaInt32(r, Fc.ST, null, false, false);
            case "BdaInt32U": return new BdaInt32U(r, Fc.ST, null, false, false);
            case "BdaInt64":  return new BdaInt64(r, Fc.ST, null, false, false);
            default: throw new IllegalArgumentException(tipo);
        }
    }

    public static void main(String[] args) throws Exception {

        Class<?> gp = Class.forName("com.iednavigator.GoosePanel");
        mEsEnteroDeEnum = gp.getDeclaredMethod("esEnteroDeEnum", ModelNode.class);
        mEnumOrdinalOf  = gp.getDeclaredMethod("enumOrdinalOf", ModelNode.class);
        mEsEnteroDeEnum.setAccessible(true);
        mEnumOrdinalOf.setAccessible(true);

        System.out.println("== el guard reconoce los anchos que la libreria puede elegir ==");
        for (String t : new String[]{"BdaInt8", "BdaInt8U", "BdaInt16", "BdaInt16U", "BdaInt32", "BdaInt32U"}) {
            check(t + " se reconoce como entero de enum", esEnteroDeEnum(bda(t)), null);
        }
        // Deliberadamente afuera: un ordinal de enumerado no necesita 64 bits y el parser no
        // elige ese ancho para un Enum. Aceptarlo ampliaria la superficie sin ningun caso.
        check("BdaInt64 queda afuera a proposito", !esEnteroDeEnum(bda("BdaInt64")), null);
        check("un no-entero no se confunde", !esEnteroDeEnum(
                new BdaBoolean(new ObjectReference("TESTLD/LLN0.Mod.stVal"), Fc.ST, null, false, false)), null);

        System.out.println("\n== el banco prueba algo: el guard viejo fallaba justo donde importa ==");
        // Si esto pasara a dar true, el arreglo dejo de tener sentido y hay que revisar por que.
        check("guard viejo NO veia BdaInt16", !guardViejo(bda("BdaInt16")), "es el caso real");
        check("guard viejo NO veia BdaInt16U", !guardViejo(bda("BdaInt16U")), null);
        check("guard viejo SI veia BdaInt8", guardViejo(bda("BdaInt8")), "por eso pasaba inadvertido");

        System.out.println("\n== el ordinal se lee bien sea cual sea el ancho ==");
        BdaInt8 i8 = (BdaInt8) bda("BdaInt8");     i8.setValue((byte) 3);
        BdaInt16 i16 = (BdaInt16) bda("BdaInt16"); i16.setValue((short) 5);
        BdaInt16U i16u = (BdaInt16U) bda("BdaInt16U"); i16u.setValue(300);
        BdaInt32 i32 = (BdaInt32) bda("BdaInt32"); i32.setValue(70000);
        check("BdaInt8  -> 3",     enumOrdinalOf(i8) == 3, null);
        check("BdaInt16 -> 5",     enumOrdinalOf(i16) == 5, "el ancho del caso real");
        check("BdaInt16U -> 300",  enumOrdinalOf(i16u) == 300, "fuera del rango de un byte");
        check("BdaInt32 -> 70000", enumOrdinalOf(i32) == 70000, null);
        check("un tipo no contemplado devuelve 0 y no rompe",
              enumOrdinalOf(bda("BdaInt64")) == 0, null);

        System.out.println("\n== la condicion real: el parche de enums ensancha el ancho ==");
        // Se hace pasar un CID del repo por getAvailableIEDs(), que es lo que corre la
        // aplicacion: expande arrays y parchea EnumTypes antes de entregar el modelo.
        Path cid = Path.of("test", "test_bay_control.cid");
        if (!Files.exists(cid)) {
            check("test_bay_control.cid presente", false, "no esta — se corre desde la raiz del repo");
        } else {
            Map<String, Integer> crudo = new TreeMap<>(), porLaApp = new TreeMap<>();

            try (InputStream in = Files.newInputStream(cid)) {
                for (ServerModel m : SclParser.parse(in)) contarAnchos(m, crudo);
            }
            IEC61850Server srv = new IEC61850Server();
            List<String> ieds = srv.getAvailableIEDs(cid.toString());
            for (int i = 0; i < ieds.size(); i++) {
                ServerModel m = srv.getMergedModel(i);
                if (m != null) contarAnchos(m, porLaApp);
            }

            System.out.println("     crudo    : " + crudo);
            System.out.println("     por la app: " + porLaApp);
            check("el modelo trae Mod/Beh/Health", !porLaApp.isEmpty(), porLaApp.toString());

            // La causalidad, medida sobre el mismo archivo y en la misma corrida: crudo da
            // Int8 y por la ruta de la aplicacion da Int16. Es la prueba de que el ancho lo
            // mueve el parche de enums del propio proyecto y no el archivo del fabricante.
            // Si algun dia esto dejara de cumplirse, el arreglo hay que repensarlo entero.
            check("crudo: la libreria elige BdaInt8",
                  crudo.containsKey("BdaInt8") && !crudo.containsKey("BdaInt16"), crudo.toString());
            check("por la app: pasa a BdaInt16",
                  porLaApp.containsKey("BdaInt16") && !porLaApp.containsKey("BdaInt8"), porLaApp.toString());
            check("el guard viejo habria fallado sobre este mismo modelo",
                  !guardViejo(bda(porLaApp.keySet().iterator().next())),
                  "que es el bug, reproducido de punta a punta");

            boolean todosReconocidos = true;
            for (String tipo : porLaApp.keySet()) {
                if (!esEnteroDeEnum(bda(tipo))) todosReconocidos = false;
            }
            check("todos los anchos presentes se reconocen", todosReconocidos, porLaApp.keySet().toString());
        }

        System.out.println("\n== una excepcion no declarada degrada en false, no tumba al llamador ==");
        // Hallazgo 7: el count de un SDO/DA/BDA puede ser el nombre de otro DA (array de
        // tamano dinamico, valido por IEC 61850-6). SclParser le hace Integer.parseInt y sale
        // una NumberFormatException, que NO es SclParseException: el catch viejo no la veia.
        Path roto = Files.createTempFile("count_no_numerico", ".cid");
        String xml = Files.readString(cid, StandardCharsets.UTF_8);
        String conCount = xml.replaceFirst("<DA ", "<DA count=\"maxPts\" ");
        boolean seInyecto = !conCount.equals(xml);
        Files.writeString(roto, conCount, StandardCharsets.UTF_8);
        check("se pudo inyectar el count no numerico", seInyecto, seInyecto ? null : "el CID no tiene <DA ");

        if (seInyecto) {
            // Primero: comprobar que la libreria efectivamente revienta con algo que no es
            // SclParseException. Si dejara de hacerlo, este banco ya no prueba nada.
            String claseExcepcion = "ninguna";
            try (InputStream in = Files.newInputStream(roto)) {
                SclParser.parse(in);
            } catch (SclParseException e) {
                claseExcepcion = "SclParseException";
            } catch (RuntimeException e) {
                claseExcepcion = e.getClass().getSimpleName();
            }
            check("la libreria lanza una excepcion NO declarada",
                  !claseExcepcion.equals("SclParseException") && !claseExcepcion.equals("ninguna"),
                  claseExcepcion);

            // Y ahora lo que se arreglo: el metodo devuelve false en vez de propagarla.
            IEC61850Server srv2 = new IEC61850Server();
            boolean cargo = true, propago = false;
            try {
                cargo = srv2.loadSclFile(roto.toString());
            } catch (RuntimeException e) {
                propago = true;
            }
            check("loadSclFile no propaga la excepcion", !propago,
                  propago ? "se propago: el catch no la atrapa" : null);
            check("loadSclFile devuelve false", !cargo, null);
        }
        Files.deleteIfExists(roto);

        System.out.printf("%n%d pasaron, %d fallaron%n", ok, fallas);
        if (fallas > 0) System.exit(1);
    }

    private static void contarAnchos(ModelNode root, Map<String, Integer> acc) {
        for (ModelNode ld : root.getChildren())
            for (ModelNode ln : ld.getChildren())
                for (ModelNode dobj : ln.getChildren()) {
                    String nm = dobj.getName();
                    if (!nm.equals("Mod") && !nm.equals("Beh") && !nm.equals("Health")) continue;
                    for (ModelNode da : dobj.getChildren())
                        if (da.getName().equals("stVal"))
                            acc.merge(da.getClass().getSimpleName(), 1, Integer::sum);
                }
    }
}
