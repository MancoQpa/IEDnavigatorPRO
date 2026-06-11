import java.io.File;
import java.lang.reflect.Method;

/**
 * Prueba headless del parser de Communication/GSE (PRO-5):
 * verifica extracción de MAC, APPID, VLAN-ID, VLAN-PRIORITY, MinTime, MaxTime.
 */
public class TestSclComm {
    public static void main(String[] args) throws Exception {
        Class<?> procCls = Class.forName("com.iednavigator.SclFileProcessor");
        Method parse = procCls.getDeclaredMethod("parseFirstIED", File.class, java.util.function.Consumer.class);
        parse.setAccessible(true);

        for (String path : args) {
            System.out.println("\n===== " + new File(path).getName() + " =====");
            Object result = parse.invoke(null, new File(path), null);
            java.lang.reflect.Field fGoCBs = result.getClass().getDeclaredField("goCBs");
            fGoCBs.setAccessible(true);
            java.util.List<?> goCBs = (java.util.List<?>) fGoCBs.get(result);
            if (goCBs.isEmpty()) { System.out.println("  (sin GoCBs)"); continue; }
            for (Object gcb : goCBs) {
                System.out.printf("  %s | MAC=%s APPID=%s VLAN-ID=%s VLAN-PRIO=%s MinTime=%s MaxTime=%s%n",
                    gcb,
                    field(gcb, "macAddress"), field(gcb, "appID"),
                    field(gcb, "vlanId"), field(gcb, "vlanPriority"),
                    field(gcb, "minTime"), field(gcb, "maxTime"));
            }
        }
    }

    private static Object field(Object obj, String name) throws Exception {
        java.lang.reflect.Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(obj);
    }
}
