import com.beanit.iec61850bean.*;
import com.iednavigator.IEC61850Client;
import com.iednavigator.IEC61850Server;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * BRCB del lado cliente (IEC61850Client), contra el simulador:
 *
 *   1. Primera habilitación: no hay nada que retomar (Retoma.NINGUNA); un cambio llega con
 *      EntryID y hora de entrada, y el cliente recuerda la EntryID.
 *   2. Desconexión, dos cambios, reconexión con un cliente nuevo: escribe la última EntryID
 *      (Retoma.RETOMADO) y llegan solo los dos cambios guardados, con la hora en que el IED los
 *      guardó (antes de reconectar).
 *   3. Servidor reiniciado (buffer nuevo): la EntryID recordada ya no existe; el IED la rechaza
 *      (Retoma.RECHAZADO) y el BRCB se habilita igual.
 *
 * Uso: java -cp "classes;lib\*;test" TestBrcbCliente
 */
public class TestBrcbCliente {
    static int ok = 0, mal = 0;
    static void chk(String que, boolean c) {
        System.out.println((c ? "OK    " : "FALLA ") + que);
        if (c) ok++; else mal++;
    }

    static final int PUERTO = 23150;
    static final String REF = "BUFLD0/LLN0.Cnt.stVal";

    static Brcb brcbDe(IEC61850Client c) {
        ServerModel m = c.getServerModel();
        Brcb b = m.getBrcb("BUFLD0/LLN0.brcb01");
        return b != null ? b : m.getBrcb("BUFLD0/LLN0.brcb");
    }

    static String cnt(Report r) {
        List<FcModelNode> v = r.getValues();
        boolean[] inc = r.getInclusionBitString();
        int k = 0;
        for (int i = 0; i < inc.length; i++) {
            if (!inc[i]) continue;
            if (i == 1) return String.valueOf(((BdaInt32) v.get(k)).getValue());
            k++;
        }
        return null;
    }

    public static void main(String[] a) throws Exception {
        File f = File.createTempFile("brcbcli", ".cid");
        f.deleteOnExit();
        Files.write(f.toPath(), TestBrcb.SCL.getBytes(StandardCharsets.UTF_8));
        IEC61850Server srv = new IEC61850Server();
        chk("carga el SCL", srv.loadSclFile(f.getAbsolutePath()));
        chk("servidor iniciado", srv.start(PUERTO));

        // 1. Primera vez
        List<Report> r1 = Collections.synchronizedList(new ArrayList<>());
        IEC61850Client c1 = new IEC61850Client();
        chk("1. conecta", c1.connect("127.0.0.1", PUERTO));
        Brcb b1 = brcbDe(c1);
        IEC61850Client.Retoma t1 = c1.enableReportingRetomando(b1, r1::add);
        chk("1. nada que retomar la primera vez (" + t1 + ")", t1 == IEC61850Client.Retoma.NINGUNA);
        srv.setDataValue(REF, "43");
        Thread.sleep(800);
        chk("1. llegó el reporte (" + r1.size() + ")", r1.size() == 1);
        byte[] recordada = c1.ultimaEntryIdDe("BUF/brcb");
        chk("1. el cliente recuerda la EntryID " + IEC61850Client.hex(recordada),
                recordada != null && !r1.isEmpty() && Arrays.equals(recordada, r1.get(0).getEntryId().getValue()));
        long ms = r1.isEmpty() ? 0 : r1.get(0).getTimeOfEntry().getTimestampValue();
        chk("1. hora de entrada coherente (" + (System.currentTimeMillis() - ms) + " ms atrás)",
                Math.abs(System.currentTimeMillis() - ms) < 5000);

        // 2. Caída y retoma
        c1.disconnect();
        Thread.sleep(500);
        srv.setDataValue(REF, "44");
        srv.setDataValue(REF, "45");
        long guardado = System.currentTimeMillis();
        Thread.sleep(1200);
        List<Report> r2 = Collections.synchronizedList(new ArrayList<>());
        IEC61850Client c2 = new IEC61850Client();
        chk("2. reconecta", c2.connect("127.0.0.1", PUERTO));
        IEC61850Client.Retoma t2 = c2.enableReportingRetomando(brcbDe(c2), r2::add);
        chk("2. retoma desde la última EntryID (" + t2 + ")", t2 == IEC61850Client.Retoma.RETOMADO);
        Thread.sleep(800);
        List<String> vals = new ArrayList<>();
        for (Report r : r2) vals.add(cnt(r));
        chk("2. llegaron solo los guardados: " + vals, vals.equals(Arrays.asList("44", "45")));
        if (r2.size() == 2) {
            long t = r2.get(1).getTimeOfEntry().getTimestampValue();
            chk("2. con la hora en que se guardaron, no la de recepción",
                    Math.abs(t - guardado) < 500 && System.currentTimeMillis() - t > 1000);
        }
        c2.disconnect();

        // 3. Servidor reiniciado: la EntryID ya no existe
        srv.stop();
        Thread.sleep(500);
        IEC61850Server srv2 = new IEC61850Server();
        srv2.loadSclFile(f.getAbsolutePath());
        chk("3. servidor reiniciado", srv2.start(PUERTO));
        List<Report> r3 = Collections.synchronizedList(new ArrayList<>());
        IEC61850Client c3 = new IEC61850Client();
        chk("3. conecta", c3.connect("127.0.0.1", PUERTO));
        Brcb b3 = brcbDe(c3);
        IEC61850Client.Retoma t3 = c3.enableReportingRetomando(b3, r3::add);
        chk("3. el IED rechaza la EntryID vieja (" + t3 + ")", t3 == IEC61850Client.Retoma.RECHAZADO);
        chk("3. el BRCB quedó habilitado igual", b3.getRptEna().getValue());
        srv2.setDataValue(REF, "46");
        Thread.sleep(800);
        chk("3. y reporta (" + r3.size() + ")", r3.size() == 1 && "46".equals(cnt(r3.get(0))));
        c3.disconnect();
        srv2.stop();

        System.out.println(ok + " pasaron, " + mal + " fallaron");
        if (mal == 0) System.out.println("TODO OK");
        System.exit(mal == 0 ? 0 : 1);
    }
}
