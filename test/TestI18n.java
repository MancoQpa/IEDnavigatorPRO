import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Banco permanente de i18n.
 *
 * El proyecto lleva tres incidentes de esta familia y las tres veces se encontraron a mano o de
 * casualidad: el apóstrofe sin escapar que MessageFormat se comía (27-07), los bundles que no
 * llegaban a classes\ y salieron así en la v4.10 (y otra vez en compile.ps1 el 05-08), y las
 * etiquetas en español fijo dentro de mensajes traducidos (07-08 y 18-08). Cada una se detectó
 * después de haber empaquetado o de haber corrido en campo.
 *
 * Lo que sigue es lo que un chequeo de bundles puede afirmar por sí solo, sin levantar la
 * interfaz. Todo lo que decide algo lo mide sobre los archivos y sobre el fuente, no sobre
 * supuestos:
 *
 *   1. paridad de claves por CONJUNTO, no por conteo (dos bundles pueden dar 856 y diferir)
 *   2. ningún valor vacío
 *   3. los placeholders de cada traducción coinciden con los de la base
 *   4. apóstrofes escapados en toda clave que pase por MessageFormat
 *   5. las claves renderizan en los 4 idiomas sin dejar marcadores sueltos
 *   6. classes\i18n tiene las mismas claves y los mismos valores que el fuente
 *   7. no se mezcla %s de String.format con {0} de MessageFormat en una misma clave
 *   8. toda clave literal citada en el fuente existe en el bundle
 *
 * Qué NO cubre, dicho para que nadie lo dé por cubierto:
 *   - La tercera familia —texto en español fijo concatenado dentro de un mensaje traducido— es
 *     un defecto del código, no del bundle: el renglón sale mezclado recién al ejecutarse. Un
 *     chequeo por vocabulario daría falsos positivos sobre términos que legítimamente no se
 *     traducen (ServiceError, CBAY.Loc, [SELECT OK]). Se detecta leyendo un registro en chino.
 *   - Las llamadas con clave variable (I18n.t(k), I18n.t(h)…) no las resuelve ningún análisis
 *     estático, así que el chequeo 8 no las ve y el informe de claves huérfanas es sólo
 *     informativo: una clave sin citar puede estar viva por esa vía.
 *
 * Uso:  java -cp "classes;lib\*;test" TestI18n
 *       java -cp "classes;lib\*;test" TestI18n <dirBundlesFuente> <dirBundlesClases> [dirJava]
 *
 * Sin argumentos se corre desde la raíz del repo, contra src\ y classes\. Con argumentos apunta
 * a otro árbol: sirve para verificar el classes\i18n de un instalador ya armado, y es lo que
 * permitió comprobar que este banco efectivamente ve los defectos —se le inyectaron los tres
 * históricos en copias de los bundles y falló en los tres, además del control sin tocar—.
 */
public class TestI18n {

    private static int ok = 0, fallas = 0;
    /** En modo silencioso sólo salen las fallas y el resumen: es como lo llama el armado. */
    private static boolean silencioso = false;

    /** Sufijo de archivo -> etiqueta legible. El primero es la base a la que caen los demás. */
    private static final String[][] IDIOMAS = {
        {"", "es"}, {"_en", "en"}, {"_pt", "pt"}, {"_zh", "zh"}
    };
    private static final String BASE = "es";

    // Rutas por defecto, relativas a la raíz del repo. Se pueden pisar por argumento para
    // apuntar el banco a otro árbol —el classes\ de un instalador ya armado, o un sandbox con
    // defectos inyectados para comprobar que el banco efectivamente los ve—.
    private static Path SRC_I18N = Paths.get("src", "main", "resources", "i18n");
    private static Path CLS_I18N = Paths.get("classes", "i18n");
    private static Path SRC_JAVA = Paths.get("src", "main", "java");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)");
    private static final Pattern PRINTF = Pattern.compile("%[-0-9.]*[sdfx]");
    /** I18n.t("clave"  ->  grupo 1 = clave, grupo 2 = "," si la llamada pasa argumentos. */
    private static final Pattern LLAMADA =
        Pattern.compile("I18n\\.t\\(\\s*\"([A-Za-z0-9._\\-]+)\"\\s*(,)?");

    private static void check(String caso, boolean cond, String detalle) {
        if (cond) ok++; else fallas++;
        if (silencioso && cond) return;
        System.out.printf("  [%s] %-52s %s%n", cond ? "OK" : "FALLA", caso, detalle == null ? "" : detalle);
    }

    /** Encabezado de sección y línea informativa: se callan en modo silencioso. */
    private static void di(String s) { if (!silencioso) System.out.println(s); }
    private static void dif(String f, Object... a) { if (!silencioso) System.out.printf(f, a); }

    /** Lista acotada, para que una falla masiva no tape el resto del informe. */
    private static String acotar(Collection<String> items) {
        List<String> l = new ArrayList<>(items);
        if (l.isEmpty()) return "";
        String cabeza = l.stream().limit(6).collect(Collectors.joining(", "));
        return l.size() <= 6 ? cabeza : cabeza + " … (+" + (l.size() - 6) + ")";
    }

    private static Properties cargar(Path dir, String sufijo) throws IOException {
        Properties p = new Properties();
        Path f = dir.resolve("messages" + sufijo + ".properties");
        if (!Files.exists(f)) return null;
        try (var r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return p;
    }

    private static Set<String> claves(Properties p) {
        return new TreeSet<>(p.stringPropertyNames());
    }

    /** Índices {n} presentes en el texto, como conjunto ordenado. */
    private static Set<String> placeholders(String valor) {
        Set<String> s = new TreeSet<>();
        Matcher m = PLACEHOLDER.matcher(valor);
        while (m.find()) s.add(m.group(1));
        return s;
    }

    /**
     * En MessageFormat cada apóstrofe literal va doblado; uno solo abre una zona citada que
     * suprime el formateo hasta el siguiente, y si no hay siguiente se come el resto del texto.
     * Sacando los pares, lo que sobre está sin escapar.
     */
    private static boolean apostrofeSinEscapar(String valor) {
        return valor.replace("''", "").indexOf('\'') >= 0;
    }

    public static void main(String[] argv) throws Exception {
        // -q en cualquier posición: el armado lo usa para que sólo salgan las fallas.
        List<String> args = new ArrayList<>();
        for (String a : argv) {
            if ("-q".equals(a) || "--quiet".equals(a)) silencioso = true; else args.add(a);
        }
        if (args.size() >= 1) SRC_I18N = Paths.get(args.get(0));
        if (args.size() >= 2) CLS_I18N = Paths.get(args.get(1));
        if (args.size() >= 3) SRC_JAVA = Paths.get(args.get(2));

        if (!Files.isDirectory(SRC_I18N)) {
            System.err.println("No encuentro " + SRC_I18N + " — hay que correrlo desde la raíz del repo.");
            System.exit(2);
        }

        // ---- carga ----
        Map<String, Properties> fuente = new java.util.LinkedHashMap<>();
        for (String[] idi : IDIOMAS) {
            Properties p = cargar(SRC_I18N, idi[0]);
            if (p == null) {
                System.err.println("Falta el bundle " + idi[1] + " en " + SRC_I18N);
                System.exit(2);
            }
            fuente.put(idi[1], p);
        }
        Properties base = fuente.get(BASE);

        di("== paridad de claves entre los cuatro idiomas ==");
        // Por conjunto y no por conteo: dos bundles pueden dar el mismo número y no ser los
        // mismos. Es la diferencia entre este chequeo y el que hace build_installer.ps1.
        Set<String> clavesBase = claves(base);
        dif("     base (%s): %d claves%n", BASE, clavesBase.size());
        for (String[] idi : IDIOMAS) {
            String lang = idi[1];
            if (lang.equals(BASE)) continue;
            Set<String> k = claves(fuente.get(lang));
            Set<String> faltan = new TreeSet<>(clavesBase); faltan.removeAll(k);
            Set<String> sobran = new TreeSet<>(k); sobran.removeAll(clavesBase);
            check(lang + ": mismo conjunto que la base", faltan.isEmpty() && sobran.isEmpty(),
                  faltan.isEmpty() && sobran.isEmpty()
                      ? k.size() + " claves"
                      : "faltan " + faltan.size() + " [" + acotar(faltan) + "]"
                        + " sobran " + sobran.size() + " [" + acotar(sobran) + "]");
        }

        di("\n==ningún valor vacío ==");
        // Una clave con valor vacío no falla en ningún lado: deja un hueco en la pantalla.
        for (String[] idi : IDIOMAS) {
            String lang = idi[1];
            Properties p = fuente.get(lang);
            Set<String> vacias = new TreeSet<>();
            for (String k : claves(p)) if (p.getProperty(k).trim().isEmpty()) vacias.add(k);
            check(lang + ": sin valores vacíos", vacias.isEmpty(), acotar(vacias));
        }

        di("\n==los placeholders de cada traducción coinciden con la base ==");
        // Una traducción a la que se le cayó un {0} pierde el dato en silencio; una que agrega
        // un {2} que la base no tiene lo muestra literal en pantalla.
        for (String[] idi : IDIOMAS) {
            String lang = idi[1];
            if (lang.equals(BASE)) continue;
            Properties p = fuente.get(lang);
            Set<String> malos = new TreeSet<>();
            for (String k : clavesBase) {
                String v = p.getProperty(k);
                if (v == null) continue; // ya lo reportó la paridad
                if (!placeholders(base.getProperty(k)).equals(placeholders(v))) malos.add(k);
            }
            check(lang + ": mismos {n} que la base", malos.isEmpty(), acotar(malos));
        }

        // ---- qué claves pasan efectivamente por MessageFormat ----
        // t(k) sin argumentos devuelve el texto crudo; sólo t(k, args) formatea. Así que el
        // riesgo del apóstrofe no depende de tener {n}, sino de cómo se llama a la clave: una
        // clave con style='width:430px' es inofensiva hasta que alguien le pasa un argumento.
        Map<String, Boolean> conArgs = new HashMap<>();
        Set<String> citadas = new TreeSet<>();
        int archivosJava = 0;
        try (Stream<Path> st = Files.walk(SRC_JAVA)) {
            List<Path> javas = st.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
            for (Path j : javas) {
                archivosJava++;
                String txt = new String(Files.readAllBytes(j), StandardCharsets.UTF_8);
                Matcher m = LLAMADA.matcher(txt);
                while (m.find()) {
                    String k = m.group(1);
                    citadas.add(k);
                    conArgs.merge(k, m.group(2) != null, (a, b) -> a || b);
                }
            }
        }

        di("\n==apóstrofes escapados donde MessageFormat los va a leer ==");
        dif("     %d archivos .java, %d claves citadas, %d de ellas con argumentos%n",
                archivosJava, citadas.size(),
                conArgs.values().stream().filter(Boolean::booleanValue).count());
        for (String[] idi : IDIOMAS) {
            String lang = idi[1];
            Properties p = fuente.get(lang);
            Set<String> malas = new TreeSet<>();
            for (String k : claves(p)) {
                String v = p.getProperty(k);
                boolean pasaPorFormat = !placeholders(v).isEmpty() || Boolean.TRUE.equals(conArgs.get(k));
                if (pasaPorFormat && apostrofeSinEscapar(v)) malas.add(k);
            }
            check(lang + ": apóstrofes doblados", malas.isEmpty(), acotar(malas));
        }

        di("\n==las claves renderizan sin dejar marcadores sueltos ==");
        // Se formatea de verdad, con argumentos de prueba, y se comprueba que no quede ningún
        // {n} en la salida. Es el chequeo que habría atrapado el apóstrofe del 27-07: la zona
        // citada se traga el {0} y el marcador desaparece llevándose el dato.
        for (String[] idi : IDIOMAS) {
            String lang = idi[1];
            Properties p = fuente.get(lang);
            Set<String> malas = new TreeSet<>();
            for (String k : claves(p)) {
                String v = p.getProperty(k);
                Set<String> ph = placeholders(v);
                if (ph.isEmpty() && !Boolean.TRUE.equals(conArgs.get(k))) continue;
                int n = 0;
                for (String i : ph) n = Math.max(n, Integer.parseInt(i) + 1);
                Object[] dummy = new Object[Math.max(n, 1)];
                for (int i = 0; i < dummy.length; i++) dummy[i] = "«arg" + i + "»";
                String salida;
                try {
                    salida = MessageFormat.format(v, dummy);
                } catch (RuntimeException e) {
                    malas.add(k + " (" + e.getClass().getSimpleName() + ")");
                    continue;
                }
                // Sobra un marcador, o se perdió un argumento que la base sí coloca.
                boolean quedanMarcadores = PLACEHOLDER.matcher(salida).find();
                boolean perdioArgumento = false;
                for (String i : ph) if (!salida.contains("«arg" + i + "»")) perdioArgumento = true;
                if (quedanMarcadores || perdioArgumento) {
                    malas.add(k + (perdioArgumento ? " (perdió un argumento)" : " ({n} sin sustituir)"));
                }
            }
            check(lang + ": renderiza limpio", malas.isEmpty(), acotar(malas));
        }

        di("\n==no se mezcla %s de String.format con {0} de MessageFormat ==");
        // Una clave con las dos convenciones sale mal por la ruta que sea: MessageFormat deja
        // el %s intacto y String.format deja el {0}.
        for (String[] idi : IDIOMAS) {
            String lang = idi[1];
            Properties p = fuente.get(lang);
            Set<String> mezcladas = new TreeSet<>();
            for (String k : claves(p)) {
                String v = p.getProperty(k);
                if (PRINTF.matcher(v).find() && !placeholders(v).isEmpty()) mezcladas.add(k);
            }
            check(lang + ": sin mezcla de convenciones", mezcladas.isEmpty(), acotar(mezcladas));
        }
        // Y que las claves de printf lleven la misma cantidad de marcadores en los 4 idiomas:
        // String.format sí revienta si sobran o faltan.
        Set<String> printfBase = new TreeSet<>();
        for (String k : clavesBase) if (PRINTF.matcher(base.getProperty(k)).find()) printfBase.add(k);
        Set<String> printfDispar = new TreeSet<>();
        for (String k : printfBase) {
            int n = 0; Matcher m = PRINTF.matcher(base.getProperty(k)); while (m.find()) n++;
            for (String[] idi : IDIOMAS) {
                String v = fuente.get(idi[1]).getProperty(k);
                if (v == null) continue;
                int c = 0; Matcher m2 = PRINTF.matcher(v); while (m2.find()) c++;
                if (c != n) printfDispar.add(k + "/" + idi[1]);
            }
        }
        check("claves %s: misma cantidad en los 4", printfDispar.isEmpty(),
              printfBase.size() + " clave(s) printf" + (printfDispar.isEmpty() ? "" : " — " + acotar(printfDispar)));

        di("\n==toda clave citada en el fuente existe en el bundle ==");
        // I18n.t() devuelve la clave cuando no la encuentra, así que una clave mal tipeada no
        // lanza nada: se muestra en pantalla como "ctl.pre.foo" y hay que verla para notarla.
        Set<String> huerfanasDeCodigo = new TreeSet<>(citadas);
        huerfanasDeCodigo.removeAll(clavesBase);
        check("sin claves citadas que falten en la base", huerfanasDeCodigo.isEmpty(),
              acotar(huerfanasDeCodigo));

        di("\n==classes\\i18n es lo que el fuente dice ==");
        // Es la familia que ya salió empaquetada dos veces. El .bat y el .exe corren con
        // classpath "classes;lib\*.jar": si el bundle no llegó ahí, la app usa el viejo aunque
        // el fuente esté impecable. Se comparan claves y valores, no bytes: la copia normaliza
        // fines de línea y eso no cambia lo que Properties lee.
        if (!Files.isDirectory(CLS_I18N)) {
            check("classes\\i18n existe", false, "no está — falta correr compile.ps1");
        } else {
            for (String[] idi : IDIOMAS) {
                String lang = idi[1];
                Properties c = cargar(CLS_I18N, idi[0]);
                if (c == null) { check(lang + ": bundle presente en classes", false, "no está"); continue; }
                Properties s = fuente.get(lang);
                Set<String> ks = claves(s), kc = claves(c);
                Set<String> faltan = new TreeSet<>(ks); faltan.removeAll(kc);
                Set<String> distintos = new TreeSet<>();
                for (String k : ks) {
                    String vs = s.getProperty(k), vc = c.getProperty(k);
                    if (vc != null && !vs.equals(vc)) distintos.add(k);
                }
                boolean bien = faltan.isEmpty() && distintos.isEmpty() && kc.size() == ks.size();
                check(lang + ": classes al día", bien,
                      bien ? kc.size() + " claves idénticas"
                           : "faltan " + faltan.size() + " [" + acotar(faltan) + "]"
                             + " distintas " + distintos.size() + " [" + acotar(distintos) + "]");
            }
        }

        // ---- informativo, no decide ----
        Set<String> nuncaCitadas = new TreeSet<>(clavesBase);
        nuncaCitadas.removeAll(citadas);
        dif("%n  [info] %d clave(s) sin cita literal en el fuente.%n", nuncaCitadas.size());
        di("         No es una falla: las llamadas con clave variable —I18n.t(k)— no");
        di("         se resuelven leyendo el código, así que muchas están vivas por ahí.");

        System.out.printf("%n%d pasaron, %d fallaron%n", ok, fallas);
        if (fallas > 0) System.exit(1);
    }
}
