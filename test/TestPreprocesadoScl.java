import com.iednavigator.IEC61850Server;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Banco del pre-procesado de SCL: los parches que la aplicacion le aplica al documento antes de
 * dárselo a iec61850bean. Portados del simulador de IED para Android, donde se probaron contra
 * 72 archivos reales de ocho fabricantes (HALLAZGOS_DESDE_EL_SIMULADOR.md, hallazgos 1, 1b, 3
 * y 4).
 *
 * Los casos se construyen al efecto, con la FORMA del defecto real pero sin nada de ningún
 * archivo de fabricante ni de ninguna instalación: test\ se versiona y el repo es público.
 *
 * Cada caso está armado para fallar con el código anterior al port:
 *
 *   1  el EnumType define el ordinal 1 con la etiqueta "on" y el DAI trae <Val>1</Val>. El
 *      parche viejo preguntaba "¿está el ord 1?", veía que sí, y no hacía nada — pero la
 *      librería busca por TEXTO, así que el archivo no cargaba.
 *   1b el EnumType de ctlModel define un solo valor y el documento usa "status-only", que
 *      otro EnumType del mismo archivo sí define. El parche viejo sólo juntaba números.
 *   3  un <Val/> vacío aborta la carga entera.
 *   4  <RptEnabled max="0"/> aborta la carga. El atributo culpable está en RptEnabled y NO en
 *      ReportControl: parchear ReportControl no cambia nada.
 *
 * Uso:  java -cp "classes;lib\*;test" TestPreprocesadoScl
 */
public class TestPreprocesadoScl {

    private static int ok = 0, fallas = 0;

    private static void check(String caso, boolean cond, String detalle) {
        if (cond) ok++; else fallas++;
        System.out.printf("  [%s] %-50s %s%n", cond ? "OK" : "FALLA", caso, detalle == null ? "" : detalle);
    }

    /** Carga por la ruta de la aplicación y devuelve los IEDs, o lista vacía si no cargó. */
    private static List<String> cargar(Path cid) {
        return new IEC61850Server().getAvailableIEDs(cid.toString());
    }

    private static Path escribir(String nombre, String xml) throws Exception {
        Path p = Files.createTempFile(nombre, ".cid");
        Files.write(p, xml.getBytes(StandardCharsets.UTF_8));
        p.toFile().deleteOnExit();
        return p;
    }

    /**
     * Modelo mínimo pero completo: un IED con un AccessPoint, un Server, un LD y un LLN0 con
     * Mod/Beh/Health. Los parámetros permiten inyectar cada defecto por separado.
     */
    private static String modelo(String valMod, String valCtl, boolean valVacio, String rptMax) {
        return modelo(valMod, valCtl, valVacio, rptMax, null);
    }

    /**
     * @param valBooleano si no es null, se pone como valor inicial de un atributo BOOLEAN.
     *                    Sirve para inyectar el hallazgo 2: un booleano cuyo valor inicial es
     *                    una referencia a otro objeto, que la librería no sabe convertir.
     */
    private static String modelo(String valMod, String valCtl, boolean valVacio, String rptMax,
                                 String valBooleano) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<SCL xmlns=\"http://www.iec.ch/61850/2003/SCL\">\n");
        sb.append("  <Header id=\"BANCO\"/>\n");
        sb.append("  <IED name=\"TESTIED\"><AccessPoint name=\"AP1\"><Server>\n");
        sb.append("    <Authentication/>\n");
        sb.append("    <LDevice inst=\"LD0\">\n");
        sb.append("      <LN0 lnClass=\"LLN0\" inst=\"\" lnType=\"T_LLN0\">\n");
        sb.append("        <DOI name=\"Mod\"><DAI name=\"stVal\"><Val>").append(valMod).append("</Val></DAI></DOI>\n");
        if (valCtl != null) {
            sb.append("        <DOI name=\"Beh\"><DAI name=\"ctlModel\"><Val>")
              .append(valCtl).append("</Val></DAI></DOI>\n");
        }
        if (valVacio) {
            sb.append("        <DOI name=\"Health\"><DAI name=\"stVal\"><Val></Val></DAI></DOI>\n");
        }
        if (valBooleano != null) {
            sb.append("        <DOI name=\"Ind\"><DAI name=\"general\"><Val>")
              .append(valBooleano).append("</Val></DAI></DOI>\n");
        }
        if (rptMax != null) {
            sb.append("        <ReportControl name=\"rcb01\" datSet=\"DS1\" rptID=\"r\" confRev=\"1\" buffered=\"false\">\n");
            sb.append("          <TrgOps dchg=\"true\"/>\n");
            sb.append("          <OptFields/>\n");
            sb.append("          <RptEnabled max=\"").append(rptMax).append("\"/>\n");
            sb.append("        </ReportControl>\n");
            sb.append("        <DataSet name=\"DS1\">\n");
            sb.append("          <FCDA ldInst=\"LD0\" lnClass=\"LLN0\" doName=\"Mod\" daName=\"stVal\" fc=\"ST\"/>\n");
            sb.append("        </DataSet>\n");
        }
        sb.append("      </LN0>\n");
        sb.append("    </LDevice>\n");
        sb.append("  </Server></AccessPoint></IED>\n");
        sb.append("  <DataTypeTemplates>\n");
        sb.append("    <LNodeType id=\"T_LLN0\" lnClass=\"LLN0\">\n");
        sb.append("      <DO name=\"Mod\" type=\"T_INC\"/>\n");
        sb.append("      <DO name=\"Beh\" type=\"T_INC\"/>\n");
        sb.append("      <DO name=\"Health\" type=\"T_INC\"/>\n");
        sb.append("      <DO name=\"Ind\" type=\"T_SPS\"/>\n");
        sb.append("    </LNodeType>\n");
        sb.append("    <DOType id=\"T_INC\" cdc=\"INC\">\n");
        sb.append("      <DA name=\"stVal\" bType=\"Enum\" type=\"T_MOD\" fc=\"ST\"/>\n");
        sb.append("      <DA name=\"ctlModel\" bType=\"Enum\" type=\"T_CTL\" fc=\"CF\"/>\n");
        sb.append("    </DOType>\n");
        sb.append("    <DOType id=\"T_SPS\" cdc=\"SPS\">\n");
        sb.append("      <DA name=\"general\" bType=\"BOOLEAN\" fc=\"ST\"/>\n");
        sb.append("    </DOType>\n");
        // El EnumType de Mod define el ordinal 1 SOLO con etiqueta: es el caso del hallazgo 1.
        sb.append("    <EnumType id=\"T_MOD\">\n");
        sb.append("      <EnumVal ord=\"1\">on</EnumVal>\n");
        sb.append("      <EnumVal ord=\"2\">blocked</EnumVal>\n");
        sb.append("      <EnumVal ord=\"5\">off</EnumVal>\n");
        sb.append("    </EnumType>\n");
        // El de ctlModel esta truncado: define uno solo. "status-only" aparece definido en
        // OTRO EnumType del mismo documento, que es de donde hay que aprender su ordinal.
        sb.append("    <EnumType id=\"T_CTL\">\n");
        sb.append("      <EnumVal ord=\"3\">direct-with-enhanced-security</EnumVal>\n");
        sb.append("    </EnumType>\n");
        sb.append("    <EnumType id=\"T_CTL_COMPLETO\">\n");
        sb.append("      <EnumVal ord=\"0\">status-only</EnumVal>\n");
        sb.append("      <EnumVal ord=\"1\">direct-with-normal-security</EnumVal>\n");
        sb.append("    </EnumType>\n");
        sb.append("  </DataTypeTemplates>\n");
        sb.append("</SCL>\n");
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {

        System.out.println("== control: un modelo sin ningun defecto carga ==");
        // Este caso tiene que pasar TAMBIEN con el codigo anterior al port: es el control que
        // dice que el banco en si no esta roto. Por eso el <Val> es "on", un texto que el
        // EnumType ya declara, y no "1", que es justamente el caso del hallazgo 1.
        Path sano = escribir("scl_sano", modelo("on", null, false, null));
        List<String> r = cargar(sano);
        check("el modelo base carga", !r.isEmpty(), r.toString());

        System.out.println("\n== hallazgo 1: ordinal presente con etiqueta, <Val> numerico ==");
        // El EnumType ya define ord=1 (como "on") y el DAI trae <Val>1</Val>. El parche viejo
        // comparaba por ordinal, veia el 1 definido, y no sintetizaba nada: la libreria busca
        // por texto y "1" no estaba, asi que abortaba con "unknown enum value: 1".
        Path h1 = escribir("scl_h1", modelo("1", null, false, null));
        check("carga con <Val>1</Val> y EnumVal etiquetado", !cargar(h1).isEmpty(),
              "el parche viejo abortaba aca");

        System.out.println("\n== hallazgo 1b: valor simbolico que el EnumType no declara ==");
        // "status-only" no esta en T_CTL, pero si en T_CTL_COMPLETO del mismo documento. El
        // ordinal hay que aprenderlo de ahi: inventarlo guardaria un valor equivocado.
        Path h1b = escribir("scl_h1b", modelo("1", "status-only", false, null));
        check("carga con <Val>status-only</Val> fuera del EnumType", !cargar(h1b).isEmpty(),
              "el parche viejo solo juntaba numeros");

        System.out.println("\n== hallazgo 3: <Val> vacio ==");
        Path h3 = escribir("scl_h3", modelo("1", null, true, null));
        check("carga con un <Val/> vacio", !cargar(h3).isEmpty(),
              "antes: invalid ... configured value:");

        System.out.println("\n== hallazgo 4: RptEnabled max fuera de rango ==");
        Path h4 = escribir("scl_h4", modelo("1", null, false, "0"));
        check("carga con RptEnabled max=0", !cargar(h4).isEmpty(),
              "el atributo culpable esta en RptEnabled, no en ReportControl");
        Path h4b = escribir("scl_h4b", modelo("1", null, false, "500"));
        check("carga con RptEnabled max=500", !cargar(h4b).isEmpty(), "fuera de 1..99 por arriba");

        System.out.println("\n== hallazgo 2: valor inicial que la libreria no sabe convertir ==");
        // Un booleano cuyo <Val> es una referencia a otro objeto. La libreria aborta la carga
        // ENTERA por ese unico valor inicial; se descarta el <Val> y se reintenta, con lo que
        // el atributo queda en su valor por defecto — que es la degradacion correcta, porque
        // lo que se pierde es un valor inicial y no la estructura del modelo.
        Path h2 = escribir("scl_h2", modelo("on", null, false, null, "CTRL/GAPC1.Op1.ST.general"));
        check("carga con un booleano cuyo valor es una referencia", !cargar(h2).isEmpty(),
              "antes: invalid boolean configured value: ...");

        // Un valor bueno no se toca: si el descarte fuera indiscriminado, esto tambien caeria.
        Path h2ok = escribir("scl_h2ok", modelo("on", null, false, null, "true"));
        check("un booleano con valor valido sigue cargando", !cargar(h2ok).isEmpty(), null);

        System.out.println("\n== los cinco defectos juntos en un mismo archivo ==");
        Path todos = escribir("scl_todos",
                modelo("1", "status-only", true, "0", "CTRL/GAPC1.Op1.ST.general"));
        List<String> rt = cargar(todos);
        check("carga con los cinco a la vez", !rt.isEmpty(), rt.toString());

        System.out.println("\n== la salvaguarda: no se toca lo que no es un enumerado ==");
        // Un texto no numerico que el documento NO define en ningun EnumType no debe
        // sintetizarse: pertenece a un atributo que no es enum. Si se sintetizara, se estarian
        // metiendo EnumVal basura en todos los EnumType del archivo.
        String conTextoAjeno = modelo("on", null, false, null)
                .replace("<Header id=\"BANCO\"/>",
                         "<Header id=\"BANCO\"/>\n  <Communication><SubNetwork name=\"W1\">"
                         + "<ConnectedAP iedName=\"TESTIED\" apName=\"AP1\"><Address>"
                         + "<P type=\"IP\">127.0.0.1</P></Address></ConnectedAP></SubNetwork></Communication>");
        Path ajeno = escribir("scl_ajeno", conTextoAjeno);
        check("un <P> con texto no enumerado no rompe la carga", !cargar(ajeno).isEmpty(),
              "127.0.0.1 no se define en ningun EnumType");

        System.out.printf("%n%d pasaron, %d fallaron%n", ok, fallas);
        if (fallas > 0) System.exit(1);
    }
}
