package com.iednavigator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Maniobra publicada por GOOSE como un interruptor real (2026-11-16): de OFF a ON se publica
 * INTERMEDIATE (00) con su propio stNum y, al cabo del recorrido, ON con otro stNum. Así lo hace
 * el simulador de interruptor de referencia (st 6 OFF, st 7 INTERMEDIATE, st 8 ON). Antes la app
 * saltaba en un solo stNum, y a los GoCB del modelo les escribía true/false aunque fueran Dbpos.
 *
 * Sin interfaz de red: el publicador avisa cada trama al listener aunque no la mande.
 *
 * Uso: java -cp "classes;lib\*;test" com.iednavigator.TestManiobraGoose
 */
public class TestManiobraGoose {
    static int ok = 0, mal = 0;
    static void chk(String que, boolean c) {
        System.out.println((c ? "OK    " : "FALLA ") + que);
        if (c) ok++; else mal++;
    }

    /** "stNum:valor" de cada trama, sin repetir las retransmisiones del mismo stNum. */
    static List<String> estados(List<String> tramas) {
        List<String> r = new ArrayList<>();
        for (String t : tramas) if (r.isEmpty() || !r.get(r.size() - 1).equals(t)) r.add(t);
        return r;
    }

    static List<String> grabar(GoosePublisher p) {
        List<String> tramas = Collections.synchronizedList(new ArrayList<>());
        p.setPublishListener(m -> tramas.add(m.stNum + ":" + m.dataValues.get(0).value));
        return tramas;
    }

    public static void main(String[] a) throws Exception {
        // 1. OFF -> ON con recorrido de 100 ms
        GoosePublisher p = new GoosePublisher();
        List<String> t = grabar(p);
        long t0 = System.currentTimeMillis();
        p.publicarManiobra(0, 2, 100);
        Thread.sleep(400);
        List<String> e = estados(t);
        chk("1. OFF -> ON: intermedio y final con stNum propios " + e, e.equals(java.util.Arrays.asList("2:0", "3:2")));
        int retrans = 0;
        for (String s : t) if (s.equals("2:0")) retrans++;
        chk("1. el intermedio sale con su retransmisión rápida (" + retrans + " tramas)", retrans >= 5);

        // 2. ON -> ON: un solo cambio, sin intermedio
        t.clear();
        p.publicarManiobra(0, 2, 100);
        Thread.sleep(300);
        chk("2. mismo estado: un solo stNum, sin intermedio " + estados(t), estados(t).equals(java.util.Arrays.asList("4:2")));

        // 3. Sin recorrido (casilla apagada): salto directo
        t.clear();
        p.publicarManiobra(0, 1, 0);
        Thread.sleep(300);
        chk("3. sin recorrido: OFF directo " + estados(t), estados(t).equals(java.util.Arrays.asList("5:1")));

        // 4. INTERMEDIATE o BAD elegidos a mano: se publican tal cual
        t.clear();
        p.publicarManiobra(0, 3, 100);
        Thread.sleep(300);
        chk("4. BAD elegido a mano: un solo cambio " + estados(t), estados(t).equals(java.util.Arrays.asList("6:3")));

        // 5. Primer dato BOOLEAN: true para ON, sin intermedio
        GoosePublisher pb = new GoosePublisher();
        List<GoosePublisher.DataValue> dv = new ArrayList<>();
        dv.add(new GoosePublisher.DataValue("Ind.stVal", GoosePublisher.DataValue.Type.BOOLEAN, false));
        pb.setDataValues(dv);
        List<String> tb = grabar(pb);
        pb.publicarManiobra(0, 2, 100);
        Thread.sleep(300);
        chk("5. BOOLEAN: true en un solo cambio " + estados(tb), estados(tb).equals(java.util.Arrays.asList("2:true")));

        System.out.println(ok + " pasaron, " + mal + " fallaron");
        if (mal == 0) System.out.println("TODO OK");
        System.exit(mal == 0 ? 0 : 1);
    }
}
