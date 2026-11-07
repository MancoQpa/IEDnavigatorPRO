import com.iednavigator.*;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * DescripcionesScl: los desc del SCL quedan indexados por la referencia del modelo.
 * Casos: LDevice, LN0 y LN con prefijo, DOI, SDI/DAI anidados, desc tomado del tipo cuando el
 * DOI no lo trae, ldName de Ed2 y FC entre corchetes al final de la referencia.
 */
public class TestDescripcionesScl {
    static int fallas = 0;

    static final String SCL =
        "<SCL xmlns='http://www.iec.ch/61850/2003/SCL'>"
      + "<IED name='IED1'><AccessPoint name='S1'><Server>"
      + " <LDevice inst='PROT' desc='Proteccion'>"
      + "  <LN0 lnClass='LLN0' inst='' lnType='T0'/>"
      + "  <LN prefix='P1' lnClass='PTOC' inst='1' lnType='TP' desc='50/51P1'>"
      + "   <DOI name='StrVal' desc='50/51P1.I_Set'/>"
      + "   <DOI name='Op'><SDI name='phsA' desc='Fase A'><DAI name='general' desc='Disparo A'/></SDI></DOI>"
      + "  </LN>"
      + " </LDevice>"
      + " <LDevice inst='X' ldName='OTRO' desc='Con ldName'/>"
      + "</Server></AccessPoint></IED>"
      + "<DataTypeTemplates>"
      + " <LNodeType id='TP' lnClass='PTOC'>"
      + "  <DO name='Str' type='ACD1' desc='Arranque'/>"
      + "  <DO name='OpDlTmms' type='ING1' desc='Temporizacion'/>"
      + " </LNodeType>"
      + " <DOType id='ACD1' cdc='ACD'><DA name='general' bType='BOOLEAN' fc='ST' desc='Arranque general'/></DOType>"
      + "</DataTypeTemplates></SCL>";

    public static void main(String[] a) throws Exception {
        Class<?> c = Class.forName("com.iednavigator.DescripcionesScl");
        Method de = c.getDeclaredMethod("de", java.io.InputStream.class);
        de.setAccessible(true);
        Method desc = c.getDeclaredMethod("descripcion", String.class);
        desc.setAccessible(true);
        Object d = de.invoke(null, new ByteArrayInputStream(SCL.getBytes(StandardCharsets.UTF_8)));

        ver(desc, d, "IED1PROT", "Proteccion");
        ver(desc, d, "IED1PROT/P1PTOC1", "50/51P1");
        ver(desc, d, "IED1PROT/P1PTOC1.StrVal", "50/51P1.I_Set");
        ver(desc, d, "IED1PROT/P1PTOC1.StrVal [SG]", "50/51P1.I_Set");
        ver(desc, d, "IED1PROT/P1PTOC1.Op.phsA", "Fase A");
        ver(desc, d, "IED1PROT/P1PTOC1.Op.phsA.general", "Disparo A");
        ver(desc, d, "IED1PROT/P1PTOC1.Str", "Arranque");                 // del LNodeType
        ver(desc, d, "IED1PROT/P1PTOC1.Str.general", "Arranque general"); // del DOType
        ver(desc, d, "IED1PROT/P1PTOC1.OpDlTmms", "Temporizacion");
        ver(desc, d, "OTRO", "Con ldName");
        ver(desc, d, "IED1PROT/LLN0", null);
        ver(desc, d, "IED1PROT/P1PTOC1.Nada", null);

        if (a.length > 0) {
            // Archivo real: cuántas descripciones y una muestra
            Method deF = c.getDeclaredMethod("de", java.io.File.class);
            deF.setAccessible(true);
            Object r = deF.invoke(null, new java.io.File(a[0]));
            Method n = c.getDeclaredMethod("cantidad");
            n.setAccessible(true);
            System.out.println("archivo real: " + n.invoke(r) + " descripciones");
            for (int i = 1; i < a.length; i++) System.out.println("  " + a[i] + " = " + desc.invoke(r, a[i]));
        }
        System.out.println(fallas == 0 ? "TODO OK" : fallas + " FALLAS");
        if (fallas > 0) System.exit(1);
    }

    static void ver(Method m, Object d, String ref, String esperado) throws Exception {
        Object v = m.invoke(d, ref);
        boolean ok = esperado == null ? v == null : esperado.equals(v);
        if (!ok) fallas++;
        System.out.println((ok ? "OK    " : "FALLA ") + ref + " -> " + v);
    }
}
