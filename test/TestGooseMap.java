import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/** Prueba headless del mapa de suscripciones GOOSE (PRO-8). */
public class TestGooseMap {
    public static void main(String[] args) throws Exception {
        Class<?> cls = Class.forName("com.iednavigator.GooseMapAnalyzer");
        Method analyze = cls.getDeclaredMethod("analyze", File.class);
        analyze.setAccessible(true);

        File f = new File(args[0]);
        System.out.println("=== " + f.getName() + " ===");
        Object res = analyze.invoke(null, f);

        List<?> pubs = (List<?>) field(res, "publishers");
        List<?> subs = (List<?>) field(res, "subscriptions");
        List<?> ieds = (List<?>) field(res, "iedNames");
        System.out.println("IEDs: " + ieds);
        System.out.println("Publicadores: " + pubs.size());
        for (Object p : pubs) {
            System.out.printf("  PUB %-30s datSet=%-20s mac=%-18s appid=%-6s miembros=%s subs=%s%n",
                field(p, "iedName") + " " + field(p, "ldInst") + "/" + field(p, "cbName"),
                field(p, "datSet"), field(p, "mac"), field(p, "appidHex"),
                ((List<?>) field(p, "members")).size(), field(p, "subscriberCount"));
        }
        System.out.println("Suscripciones: " + subs.size());
        int shown = 0;
        for (Object s : subs) {
            if (shown++ >= 30) { System.out.println("  ... (" + (subs.size() - 30) + " más)"); break; }
            Method pubRef = s.getClass().getDeclaredMethod("pubRef");
            pubRef.setAccessible(true);
            System.out.printf("  SUB [%-6s %-11s] %-28s <- %-45s -> %s :: %s%n",
                field(s, "via"), (Boolean) field(s, "resolved") ? "OK" : "NO-RESUELTO",
                field(s, "subscriberIed"), pubRef.invoke(s), field(s, "target"), field(s, "dataRef"));
        }
    }

    static Object field(Object o, String name) throws Exception {
        Field fl = o.getClass().getDeclaredField(name);
        fl.setAccessible(true);
        return fl.get(o);
    }
}
