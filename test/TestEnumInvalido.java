import com.beanit.iec61850bean.*;
import com.iednavigator.IEC61850Server;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Enumerados escritos fuera de norma en el SCL (IEC 61850-6 pide el texto literal):
 *
 *   - ordinal numérico con ceros ("004" en ctlModel): lo resuelve el relleno de EnumTypes;
 *   - un guion "-" como "sin valor" (pulseConfig.cmdQual): la librería decía "unknown enum
 *     value: -" y el archivo entero no cargaba. Ahora se descarta ese valor inicial y el
 *     atributo queda con el de defecto.
 *
 * Caso real: CID de una unidad de control de transformador (2026-11-16).
 *
 * Uso: java -cp "classes;lib\*;test" TestEnumInvalido
 */
public class TestEnumInvalido {
    static int ok = 0, mal = 0;
    static void chk(String que, boolean c) {
        System.out.println((c ? "OK    " : "FALLA ") + que);
        if (c) ok++; else mal++;
    }

    static final String SCL = "<?xml version='1.0' encoding='UTF-8'?>"
        + "<SCL xmlns='http://www.iec.ch/61850/2003/SCL' version='2007' revision='B'><Header id='T'/>"
        + "<IED name='ENU'><AccessPoint name='AP1'><Server><Authentication/>"
        + "<LDevice inst='CTRL'><LN0 lnClass='LLN0' inst='' lnType='LLN0_T'>"
        + " <DOI name='RemCtlBlk'><DAI name='ctlModel'><Val>004</Val></DAI>"
        + "  <SDI name='pulseConfig'><DAI name='cmdQual'><Val>-</Val></DAI></SDI></DOI>"
        + " <DOI name='Otro'><DAI name='ctlModel'><Val>001</Val></DAI>"
        + "  <SDI name='pulseConfig'><DAI name='cmdQual'><Val>persistent</Val></DAI></SDI></DOI>"
        + "</LN0></LDevice></Server></AccessPoint></IED>"
        + "<DataTypeTemplates>"
        + " <LNodeType id='LLN0_T' lnClass='LLN0'><DO name='RemCtlBlk' type='SPC_T'/><DO name='Otro' type='SPC_T'/></LNodeType>"
        + " <DOType id='SPC_T' cdc='SPC'><DA name='stVal' fc='ST' bType='BOOLEAN'/>"
        + "   <DA name='pulseConfig' fc='CF' bType='Struct' type='PC_T'/>"
        + "   <DA name='ctlModel' fc='CF' bType='Enum' type='ctlModel'/></DOType>"
        + " <DAType id='PC_T'><BDA name='cmdQual' bType='Enum' type='cmdQual'/><BDA name='onDur' bType='INT32U'/></DAType>"
        + " <EnumType id='ctlModel'><EnumVal ord='0'>status-only</EnumVal><EnumVal ord='1'>direct-with-normal-security</EnumVal>"
        + "  <EnumVal ord='2'>sbo-with-normal-security</EnumVal><EnumVal ord='3'>direct-with-enhanced-security</EnumVal>"
        + "  <EnumVal ord='4'>sbo-with-enhanced-security</EnumVal></EnumType>"
        + " <EnumType id='cmdQual'><EnumVal ord='0'>pulse</EnumVal><EnumVal ord='1'>persistent</EnumVal></EnumType>"
        + "</DataTypeTemplates></SCL>";

    static int entero(ServerModel m, String ref) {
        return ((BdaInt8) m.findModelNode(ref, Fc.CF)).getValue();
    }

    public static void main(String[] a) throws Exception {
        File f = File.createTempFile("enuminvalido", ".cid");
        f.deleteOnExit();
        Files.write(f.toPath(), SCL.getBytes(StandardCharsets.UTF_8));
        IEC61850Server srv = new IEC61850Server();
        chk("el archivo carga (antes: unknown enum value: -)", srv.loadSclFile(f.getAbsolutePath()));
        ServerModel m = srv.getServerModel();
        if (m != null) {
            chk("ctlModel \"004\" -> 4", entero(m, "ENUCTRL/LLN0.RemCtlBlk.ctlModel") == 4);
            chk("ctlModel \"001\" -> 1", entero(m, "ENUCTRL/LLN0.Otro.ctlModel") == 1);
            chk("cmdQual \"-\" queda en el valor por defecto (0, pulse)",
                    entero(m, "ENUCTRL/LLN0.RemCtlBlk.pulseConfig.cmdQual") == 0);
            chk("un cmdQual válido no se toca (persistent = 1)",
                    entero(m, "ENUCTRL/LLN0.Otro.pulseConfig.cmdQual") == 1);
        }
        System.out.println(ok + " pasaron, " + mal + " fallaron");
        if (mal == 0) System.out.println("TODO OK");
        System.exit(mal == 0 ? 0 : 1);
    }
}
