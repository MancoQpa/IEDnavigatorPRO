package com.iednavigator;

import com.beanit.iec61850bean.*;
import java.io.*;
import java.util.List;

/**
 * Smoke test headless para verificar funcionalidad básica post-refactorización.
 *
 * Uso:
 *   java -Djava.awt.headless=true -cp "classes;lib/*" com.iednavigator.SmokeTest <ruta_a_test_simple.cid>
 *
 * Retorna exit code 0 si todo pasa, 1 si algún test falla.
 */
public class SmokeTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        String cidPath = args.length > 0 ? args[0] : null;

        System.out.println("=== IEDNavigator SmokeTest ===");
        System.out.println();

        // --- Nivel 1: Instanciación de clases extraídas (Fase 1) ---
        System.out.println("-- Fase 1: clases extraídas --");
        testClassInstantiation();

        // --- Nivel 2: SclParser con CID mínimo ---
        System.out.println();
        System.out.println("-- SCL Parser --");
        if (cidPath != null) {
            testSclParsing(cidPath);
        } else {
            System.out.println("  [SKIP] No se pasó ruta a CID — omitiendo test de parser.");
        }

        // --- Nivel 3: IEC61850Server carga de clase ---
        System.out.println();
        System.out.println("-- Server/Client class load --");
        testServerClientLoad();

        // --- Nivel 4: el parche del enum Fc gana el classpath ---
        System.out.println();
        System.out.println("-- Parche de Functional Constraints --");
        testFcShadow();

        // --- Resumen ---
        System.out.println();
        System.out.println("==============================");
        System.out.printf("RESULTADO: %d pasaron, %d fallaron%n", passed, failed);
        System.out.println("==============================");

        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------
    // Test helpers
    // ---------------------------------------------------------------

    private static void pass(String name) {
        System.out.println("  [PASS] " + name);
        passed++;
    }

    private static void fail(String name, Throwable t) {
        System.out.println("  [FAIL] " + name + " — " + t);
        failed++;
    }

    // ---------------------------------------------------------------
    // Nivel 1: Instanciación de clases extraídas
    // ---------------------------------------------------------------

    private static void testClassInstantiation() {
        // SclGoCB
        try {
            SclGoCB g = new SclGoCB();
            g.ldInst = "LD0";
            g.lnClass = "LLN0";
            g.cbName = "GCB01";
            g.appID = "0x0001";
            g.datSet = "TestDS";
            g.confRev = 1;
            g.macAddress = "01:0C:CD:01:00:01";
            g.goID = "TestGoID";
            String s = g.toString();
            if (!s.contains("LD0")) throw new AssertionError("toString fallo: " + s);
            pass("SclGoCB instancia + toString");
        } catch (Throwable t) { fail("SclGoCB", t); }

        // MonitorItem
        try {
            MonitorItem mi = new MonitorItem("TestIED/LD0/LLN0.Mod.stVal", "stVal", "ST", "BOOLEAN", null);
            if (mi.reference == null) throw new AssertionError("reference null");
            pass("MonitorItem instancia");
        } catch (Throwable t) { fail("MonitorItem", t); }

        // SclDataSet
        try {
            SclDataSet ds = new SclDataSet();
            ds.name = "TestDS";
            ds.ldInst = "LD0";
            ds.lnClass = "LLN0";
            ds.members.add("LD0/LLN0.Mod.stVal [ST]");
            if (ds.members.isEmpty()) throw new AssertionError("members vacío");
            pass("SclDataSet instancia + member");
        } catch (Throwable t) { fail("SclDataSet", t); }

        // SclReport
        try {
            SclReport r = new SclReport();
            r.name = "urcb01";
            r.ldInst = "LD0";
            r.lnClass = "LLN0";
            r.datSet = "TestDS";
            if (r.name == null) throw new AssertionError("name null");
            pass("SclReport instancia");
        } catch (Throwable t) { fail("SclReport", t); }

        // NodeInfo
        try {
            NodeInfo ni = new NodeInfo();
            ni.name = "Mod";
            ni.prefix = "DO";
            ni.fc = "ST";
            ni.value = "on";
            ni.type = "INC";
            String s = ni.toString();
            if (!s.contains("Mod")) throw new AssertionError("toString fallo: " + s);
            pass("NodeInfo instancia + toString");
        } catch (Throwable t) { fail("NodeInfo", t); }

        // MonitorTableRenderer
        try {
            MonitorTableRenderer mtr = new MonitorTableRenderer();
            if (mtr == null) throw new AssertionError("null");
            pass("MonitorTableRenderer instancia");
        } catch (Throwable t) { fail("MonitorTableRenderer", t); }
    }

    // ---------------------------------------------------------------
    // Nivel 2: SCL parsing
    // ---------------------------------------------------------------

    private static void testSclParsing(String cidPath) {
        File f = new File(cidPath);
        if (!f.exists()) {
            System.out.println("  [SKIP] Archivo no encontrado: " + cidPath);
            return;
        }
        try (FileInputStream fis = new FileInputStream(f)) {
            List<ServerModel> models = SclParser.parse(fis);
            if (models == null || models.isEmpty()) {
                throw new AssertionError("No se obtuvieron modelos del CID");
            }
            ServerModel m = models.get(0);
            int ldCount = 0;
            for (ModelNode ld : m.getChildren()) ldCount++;
            if (ldCount == 0) throw new AssertionError("El modelo no tiene LDs");
            pass("SclParser.parse — " + ldCount + " LD(s) en " + f.getName());
        } catch (SclParseException | IOException e) {
            fail("SclParser.parse(" + f.getName() + ")", e);
        } catch (AssertionError e) {
            fail("SclParser.parse assertion", e);
        }
    }

    // ---------------------------------------------------------------
    // Nivel 4: el shadow de Fc gana el classpath
    // ---------------------------------------------------------------

    /**
     * El parche de src/main/java/com/beanit/iec61850bean/Fc.java sólo hace efecto si se
     * resuelve antes que la clase homónima del jar, y eso depende de que "classes" preceda a
     * "lib\*.jar" en el classpath. Nada lo garantiza, y en lib\ hay 335 .class sueltos de la
     * propia librería: si algún lanzador agregara lib\ como directorio, el parche quedaría
     * desactivado en silencio y volveríamos a perder Logical Nodes enteros sin enterarnos.
     * Estas tres comprobaciones cuestan nada y lo delatan.
     */
    private static void testFcShadow() {
        try {
            if (com.beanit.iec61850bean.Fc.fromString("SC") == null) {
                throw new AssertionError("FC=SC no reconocida: corre el enum del jar, no el parche");
            }
            pass("el parche de Fc gana el classpath (SC reconocida)");
        } catch (Throwable t) { fail("parche de Fc activo", t); }

        try {
            com.beanit.iec61850bean.Fc f = com.beanit.iec61850bean.Fc.fromString("QQ");
            if (f == null) {
                throw new AssertionError("una FC desconocida devolvió null: vuelve la NPE que "
                    + "se lleva el Logical Node entero");
            }
            if (f != com.beanit.iec61850bean.Fc.UNKNOWN) {
                throw new AssertionError("se esperaba UNKNOWN y llegó " + f);
            }
            pass("una FC desconocida cae en UNKNOWN, no en null");
        } catch (Throwable t) { fail("FC desconocida sin null", t); }

        try {
            // Los 15 originales tienen que conservar su ordinal: la librería serializa e
            // indexa por él, así que reordenarlos rompería cosas lejos de acá.
            String[] orig = {"ST","MX","SP","SV","CF","DC","SG","SE","SR","OR","BL","EX","CO","RP","BR"};
            for (int i = 0; i < orig.length; i++) {
                com.beanit.iec61850bean.Fc f = com.beanit.iec61850bean.Fc.valueOf(orig[i]);
                if (f.ordinal() != i) {
                    throw new AssertionError(orig[i] + " tiene ordinal " + f.ordinal() + ", esperaba " + i);
                }
            }
            pass("los 15 valores originales conservan su ordinal");
        } catch (Throwable t) { fail("ordinales originales", t); }
    }

    // ---------------------------------------------------------------
    // Nivel 3: IEC61850Server / IEC61850Client — solo carga de clase
    // ---------------------------------------------------------------

    private static void testServerClientLoad() {
        try {
            Class<?> cls = Class.forName("com.iednavigator.IEC61850Server");
            if (cls == null) throw new AssertionError("null");
            pass("IEC61850Server class load");
        } catch (Throwable t) { fail("IEC61850Server class load", t); }

        try {
            Class<?> cls = Class.forName("com.iednavigator.IEC61850Client");
            if (cls == null) throw new AssertionError("null");
            pass("IEC61850Client class load");
        } catch (Throwable t) { fail("IEC61850Client class load", t); }

        // Verificar que las clases extraídas también son accesibles vía classloader
        String[] extracted = {
            "com.iednavigator.SclGoCB",
            "com.iednavigator.MonitorItem",
            "com.iednavigator.SclDataSet",
            "com.iednavigator.SclReport",
            "com.iednavigator.NodeInfo",
            "com.iednavigator.MonitorTableRenderer"
        };
        for (String cn : extracted) {
            try {
                Class.forName(cn);
                pass("class load: " + cn.replace("com.iednavigator.", ""));
            } catch (Throwable t) { fail("class load: " + cn, t); }
        }
    }
}
