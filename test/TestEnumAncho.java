import com.beanit.iec61850bean.*;
import com.iednavigator.IEC61850Client;
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
 * DESPUES se acoto el parche para que no ensanche —porque el ensanchamiento ademas dejaba a la
 * propia libreria sin poder leer ctlModel al atender un mando, ver TestPreprocesadoScl—, asi
 * que hoy la ruta de la aplicacion ya NO mueve el ancho. Las comprobaciones de la seccion "la
 * condicion real" quedaron como guarda de regresion de aquel arreglo.
 *
 * El guard ancho sigue haciendo falta igual: SclParser elige el ancho segun el rango de
 * ordinales, asi que un EnumType que legitimamente pase del byte sigue dando BdaInt16 y hay
 * que reconocerlo. Eso se cubre ejercitando los tipos directamente, mas arriba.
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

            // HISTORIA, para que no se lea mal: cuando se escribio este banco, la ruta de la
            // aplicacion ensanchaba este mismo archivo de BdaInt8 a BdaInt16, porque
            // patchMissingEnumOrdinals() le agregaba a cada EnumType todos los <Val>
            // numericos del documento, incluidos los grandes. Eso rompia el guard de
            // getEnumOptionsForNode() y ademas dejaba a la propia libreria sin poder leer
            // ctlModel al atender un mando. El parche se acoto despues (ver
            // TestPreprocesadoScl), asi que hoy el ancho ya no se mueve.
            //
            // Estas dos comprobaciones son la guarda de regresion de aquel arreglo: si el
            // parche volviera a ensanchar, vuelven a fallar.
            check("crudo: la libreria elige BdaInt8",
                  crudo.containsKey("BdaInt8") && !crudo.containsKey("BdaInt16"), crudo.toString());
            check("por la app: el ancho NO se mueve",
                  porLaApp.equals(crudo), porLaApp + "  (crudo: " + crudo + ")");

            boolean todosReconocidos = true;
            for (String tipo : porLaApp.keySet()) {
                if (!esEnteroDeEnum(bda(tipo))) todosReconocidos = false;
            }
            check("todos los anchos presentes se reconocen", todosReconocidos, porLaApp.keySet().toString());
        }

        System.out.println("\n== ordinalDeBda lee cualquier ancho ==");
        // Barrido del proyecto buscando instanceof sobre Bda de enumerados: aparecio un
        // cuarto defecto de la familia, y el mas caro de los que quedaban.
        // getCtlModelValue() preguntaba solo por BdaInt8/BdaInt8U; con un ctlModel que
        // llegara mas ancho no matcheaba y caia al default 1. O sea que el cliente
        // informaba direct-with-normal-security sobre puntos que en el modelo eran
        // status-only o SBO — y ese valor decide por que rama sale la orden.
        Method mOrd = IEC61850Client.class.getDeclaredMethod("ordinalDeBda", ModelNode.class);
        mOrd.setAccessible(true);
        BdaInt8 o8 = (BdaInt8) bda("BdaInt8");     o8.setValue((byte) 4);
        BdaInt16 o16 = (BdaInt16) bda("BdaInt16"); o16.setValue((short) 4);
        BdaInt32 o32 = (BdaInt32) bda("BdaInt32"); o32.setValue(4);
        check("BdaInt8  -> 4", Integer.valueOf(4).equals(mOrd.invoke(null, o8)), null);
        check("BdaInt16 -> 4", Integer.valueOf(4).equals(mOrd.invoke(null, o16)),
              "el ancho que hacia caer getCtlModelValue al default");
        check("BdaInt32 -> 4", Integer.valueOf(4).equals(mOrd.invoke(null, o32)), null);
        check("un no-entero devuelve null, que no es cero",
              mOrd.invoke(null, new BdaBoolean(new ObjectReference("L/L.D.d"), Fc.ST, null, false, false)) == null,
              "distinguir 'no se pudo leer' de 'vale 0'");

        System.out.println("\n== getValueString() de la libreria no cubre todos los anchos ==");
        // Medido, no deducido: iec61850bean devuelve el numero para BdaInt8 y BdaInt32, y
        // null para BdaInt8U, BdaInt16 y BdaInt16U. Como el parche de enums empuja casi todos
        // los enumerados a BdaInt16, el efecto en la aplicacion era que NINGUN enumerado
        // mostraba valor en el arbol —ni Mod, ni Beh, ni Health— mientras un booleano al lado
        // si lo mostraba. Si algun dia la libreria lo implementa, este banco lo avisa.
        BdaInt8 g8 = (BdaInt8) bda("BdaInt8");     g8.setValue((byte) 5);
        BdaInt16 g16 = (BdaInt16) bda("BdaInt16"); g16.setValue((short) 5);
        BdaInt32 g32 = (BdaInt32) bda("BdaInt32"); g32.setValue(5);
        check("BdaInt8.getValueString() devuelve el numero", "5".equals(g8.getValueString()),
              String.valueOf(g8.getValueString()));
        check("BdaInt16.getValueString() devuelve null", g16.getValueString() == null,
              "hueco de la libreria — es la razon del fallback");
        check("BdaInt32.getValueString() devuelve el numero", "5".equals(g32.getValueString()),
              String.valueOf(g32.getValueString()));

        System.out.println("\n== formatEnumValue tolera el valor sin asignar ==");
        // Regresion encontrada probando en la aplicacion, no en un banco: al ensanchar el
        // guard, formatEnumValue paso a entrar donde antes salia temprano, y reventaba con
        // NPE en rawValue.trim() cuando getValueString() devuelve null — que es lo normal
        // mientras el servidor no arranco. Tiraba abajo buildTree entero y dejaba el arbol
        // vacio. El guard roto estaba tapando un segundo bug.
        Method mFormat = gp.getDeclaredMethod("formatEnumValue", ModelNode.class, String.class);
        mFormat.setAccessible(true);
        Object panel = construirGoosePanel(gp);
        if (panel == null) {
            check("se pudo construir un GoosePanel para la prueba", false, "no se pudo instanciar");
        } else {
            // Primero: que el montaje reproduzca la condicion. Si el enum saliera vacio,
            // formatEnumValue se iria por el return temprano y el caso de abajo pasaria sin
            // haber tocado rawValue — o sea, sin probar nada.
            Method mOpts = gp.getDeclaredMethod("getEnumOptionsForNode", ModelNode.class);
            mOpts.setAccessible(true);
            Object opciones = mOpts.invoke(panel, bda("BdaInt16"));
            check("el montaje devuelve un enum NO vacio",
                  opciones instanceof Map && !((Map<?, ?>) opciones).isEmpty(),
                  String.valueOf(opciones));
            // Y de paso: sobre un BdaInt16 el guard viejo habria devuelto null aca.
            try {
                // Con el ordinal en 3 y el enum del montaje, tiene que salir "3 [test]"
                // aunque getValueString() devuelva null: ese es el fallback.
                BdaInt16 n3 = (BdaInt16) bda("BdaInt16"); n3.setValue((short) 3);
                Object r3 = mFormat.invoke(panel, n3, (String) null);
                check("con rawValue null resuelve por el ordinal", "3 [test]".equals(r3),
                      String.valueOf(r3));
                Object r = mFormat.invoke(panel, bda("BdaInt16"), (String) null);
                check("rawValue null no lanza NPE", true, "devolvio " + r);
            } catch (Exception e) {
                Throwable causa = e.getCause() != null ? e.getCause() : e;
                check("rawValue null no lanza NPE", false,
                      causa.getClass().getSimpleName() + ": " + causa.getMessage());
            }
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

    /**
     * GoosePanel necesita un Context, que es una interfaz grande y de paquete. Se la implementa
     * con un Proxy dinamico que devuelve valores neutros: alcanza para llegar a
     * formatEnumValue(), que es lo unico que se quiere ejercitar aca.
     */
    private static Object construirGoosePanel(Class<?> gp) {
        try {
            Class<?> ctxIf = Class.forName("com.iednavigator.GoosePanel$Context");
            // Los cuatro mapas se pueblan para que getEnumOptionsForNode() devuelva un enum
            // NO vacio sobre TESTLD/LLN0.Mod.stVal. Es la condicion exacta del bug: si el
            // mapa volviera vacio, formatEnumValue saldria por el return temprano y el banco
            // pasaria sin haber ejercitado nada.
            Map<String, String> lnClassToLnType = new java.util.HashMap<>();
            lnClassToLnType.put("LLN0", "LNT_TEST");
            Map<String, Map<String, String>> lnTypeDoTypes = new java.util.HashMap<>();
            Map<String, String> doMap = new java.util.HashMap<>();
            doMap.put("Mod", "DOT_TEST");
            lnTypeDoTypes.put("LNT_TEST", doMap);
            Map<String, String> daEnumType = new java.util.HashMap<>();
            daEnumType.put("DOT_TEST.stVal", "ET_TEST");
            Map<String, java.util.LinkedHashMap<Integer, String>> enumTypes = new java.util.HashMap<>();
            java.util.LinkedHashMap<Integer, String> modVals = new java.util.LinkedHashMap<>();
            modVals.put(1, "on"); modVals.put(2, "blocked"); modVals.put(3, "test"); modVals.put(5, "off");
            enumTypes.put("ET_TEST", modVals);

            Object ctx = java.lang.reflect.Proxy.newProxyInstance(
                    ctxIf.getClassLoader(), new Class<?>[]{ctxIf},
                    (proxy, metodo, argumentos) -> {
                        switch (metodo.getName()) {
                            case "getSclLnClassToLnType": return lnClassToLnType;
                            case "getSclLnTypeDoTypes":   return lnTypeDoTypes;
                            case "getSclDaEnumType":      return daEnumType;
                            case "getSclEnumTypes":       return enumTypes;
                        }
                        Class<?> r = metodo.getReturnType();
                        if (r == boolean.class) return false;
                        if (r == int.class) return 0;
                        if (r == List.class) return new java.util.ArrayList<>();
                        if (r == Map.class) return new java.util.HashMap<>();
                        return null;
                    });
            java.lang.reflect.Constructor<?> c = gp.getDeclaredConstructor(ctxIf);
            c.setAccessible(true);
            return c.newInstance(ctx);
        } catch (Throwable t) {
            System.out.println("     (no se pudo construir GoosePanel: " + t + ")");
            return null;
        }
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
