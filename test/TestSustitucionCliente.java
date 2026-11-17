import com.beanit.iec61850bean.*;
import com.iednavigator.IEC61850Client;
import com.iednavigator.IEC61850Server;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Sustitución desde el cliente de la app (IEC61850Client.sustituir / quitarSustitucion /
 * escribirTexto), contra el simulador.
 *
 * Uso: java -cp "classes;lib\*;test" TestSustitucionCliente
 */
public class TestSustitucionCliente {
    static int ok = 0, mal = 0;
    static void chk(String que, boolean c) {
        System.out.println((c ? "OK    " : "FALLA ") + que);
        if (c) ok++; else mal++;
    }

    public static void main(String[] a) throws Exception {
        File f = File.createTempFile("sustcliente", ".cid");
        f.deleteOnExit();
        Files.write(f.toPath(), TestSustitucion.SCL.getBytes(StandardCharsets.UTF_8));
        IEC61850Server srv = new IEC61850Server();
        srv.loadSclFile(f.getAbsolutePath());
        chk("servidor iniciado", srv.start(23210));
        IEC61850Client c = new IEC61850Client();
        chk("conecta", c.connect("127.0.0.1", 23210));
        String alm = "SUSLD0/LLN0.Alm", med = "SUSLD0/LLN0.Med";
        ServerModel m = c.getServerModel();

        chk("admite sustitución: Alm y Med", c.admiteSustitucion(alm) && c.admiteSustitucion(med));
        chk("no admite: un DO sin subEna", !c.admiteSustitucion("SUSLD0/LLN0.Beh"));

        c.sustituir(alm, "true", BdaQuality.Validity.QUESTIONABLE, "Operador");
        Thread.sleep(400);
        FcModelNode st = (FcModelNode) m.findModelNode(alm + ".stVal", Fc.ST);
        c.readNodeValues(st);
        BdaQuality q = (BdaQuality) m.findModelNode(alm + ".q", Fc.ST);
        c.readNodeValues(q);
        chk("sustituido: stVal true, q questionable y substituted",
                ((BdaBoolean) st).getValue() && q.getValidity() == BdaQuality.Validity.QUESTIONABLE && q.isSubstituted());
        chk("sustitucionActiva lee subEna = true", c.sustitucionActiva(alm));

        c.quitarSustitucion(alm);
        Thread.sleep(400);
        c.readNodeValues(st);
        c.readNodeValues(q);
        chk("quitada: stVal vuelve a false y q buena", !((BdaBoolean) st).getValue()
                && q.getValidity() == BdaQuality.Validity.GOOD && !q.isSubstituted());

        c.sustituir(med, "33,5", BdaQuality.Validity.GOOD, "Operador");
        Thread.sleep(400);
        FcModelNode mag = (FcModelNode) m.findModelNode(med + ".mag.f", Fc.MX);
        c.readNodeValues(mag);
        chk("MV con coma decimal: mag.f = 33.5 (" + ((BdaFloat32) mag).getFloat() + ")", ((BdaFloat32) mag).getFloat() == 33.5f);
        c.quitarSustitucion(med);

        boolean fallo = false;
        try {
            c.escribirTexto(med + ".subMag.f", Fc.SV, "abc");
        } catch (java.io.IOException e) {
            fallo = true;
            System.out.println("       " + e.getMessage());
        }
        chk("valor inválido: falla en vez de escribir el anterior", fallo);
        chk("FC escribibles: SV y CF sí, ST y MX no", IEC61850Client.fcEscribible(Fc.SV)
                && IEC61850Client.fcEscribible(Fc.CF) && !IEC61850Client.fcEscribible(Fc.ST) && !IEC61850Client.fcEscribible(Fc.MX));

        c.disconnect();
        srv.stop();
        System.out.println(ok + " pasaron, " + mal + " fallaron");
        if (mal == 0) System.out.println("TODO OK");
        System.exit(mal == 0 ? 0 : 1);
    }
}
