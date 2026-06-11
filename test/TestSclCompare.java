import java.io.File;
import java.lang.reflect.Method;
import java.util.List;

/** Prueba headless del comparador SCL (PRO-6). */
public class TestSclCompare {
    public static void main(String[] args) throws Exception {
        Class<?> cls = Class.forName("com.iednavigator.SclCompare");
        Method compare = cls.getDeclaredMethod("compare", File.class, File.class, boolean.class);
        compare.setAccessible(true);

        boolean ignoreName = args.length > 2 && args[2].equals("--ignore-name");
        File a = new File(args[0]);
        File b = new File(args[1]);
        System.out.println("=== A: " + a.getName() + "  vs  B: " + b.getName()
            + (ignoreName ? "  [ignorar nombre IED]" : "") + " ===");
        List<?> diffs = (List<?>) compare.invoke(null, a, b, ignoreName);
        System.out.println("Diferencias: " + diffs.size());
        int shown = 0;
        for (Object d : diffs) {
            if (shown++ >= 40) { System.out.println("  ... (" + (diffs.size() - 40) + " más)"); break; }
            Class<?> dc = d.getClass();
            java.lang.reflect.Field fCat = dc.getDeclaredField("category"); fCat.setAccessible(true);
            java.lang.reflect.Field fEl  = dc.getDeclaredField("element");  fEl.setAccessible(true);
            java.lang.reflect.Field fA   = dc.getDeclaredField("valueA");   fA.setAccessible(true);
            java.lang.reflect.Field fB   = dc.getDeclaredField("valueB");   fB.setAccessible(true);
            Method status = dc.getDeclaredMethod("status"); status.setAccessible(true);
            System.out.printf("  [%-13s] %-70s A=%s B=%s (%s)%n",
                fCat.get(d), fEl.get(d), fA.get(d), fB.get(d), status.invoke(d));
        }
    }
}
