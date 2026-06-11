import com.beanit.iec61850bean.SclParser;
import com.beanit.iec61850bean.ServerModel;

import java.io.File;
import java.lang.reflect.Method;
import java.util.List;

/** Prueba headless del generador de reportes HTML (PRO-7). */
public class TestHtmlReport {
    public static void main(String[] args) throws Exception {
        File scl = new File(args[0]);
        File out = new File(args[1]);
        boolean values = args.length > 2 && args[2].equals("--values");

        List<ServerModel> models = SclParser.parse(scl.getAbsolutePath());
        ServerModel model = models.get(0);

        Class<?> cls = Class.forName("com.iednavigator.ModelReportGenerator");
        Method gen = cls.getDeclaredMethod("generate", File.class, ServerModel.class,
            String.class, String[].class, boolean.class);
        gen.setAccessible(true);
        gen.invoke(null, out, model, scl.getName(),
            new String[]{"TestVendor", "TestModel", "Prueba", "1.0"}, values);

        System.out.println("OK: " + out.getAbsolutePath() + " (" + out.length() + " bytes)");
    }
}
