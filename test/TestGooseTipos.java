package com.iednavigator;

import com.beanit.iec61850bean.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Tipo de cada dato que publica el GOOSE (2026-11-21). El tipo salía del nombre del miembro y la
 * regla de stVal booleano iba antes que la de Pos: la posición de un interruptor (Dbpos) se
 * publicaba como BOOLEAN, sin intermedio ni estado inválido, y Beh/Health como booleanos. Visto
 * en la trama de un interruptor virtual con otra PC.
 *
 * Verifica: el tipo tomado del modelo (test/test_bay_control.cid), la regla por nombre cuando no
 * hay modelo, la codificación en la trama (Dbpos 0x84 de 2 bits, calidad de 13 bits, hora 0x91
 * de 8 bytes) y que la hora no se sincronice con el modelo.
 *
 * Uso: java -cp "classes;lib\*;test" com.iednavigator.TestGooseTipos
 */
public class TestGooseTipos {

    private static int ok, falla;

    private static void chk(String que, boolean cond) {
        if (cond) { ok++; System.out.println("OK     " + que); }
        else { falla++; System.out.println("FALLA  " + que); }
    }

    public static void main(String[] args) throws Exception {
        GoosePublisher.DataValue.Type B = GoosePublisher.DataValue.Type.BOOLEAN, D = GoosePublisher.DataValue.Type.DBPOS,
                I = GoosePublisher.DataValue.Type.INTEGER, Q = GoosePublisher.DataValue.Type.BITSTRING,
                T = GoosePublisher.DataValue.Type.UTC_TIME, F = GoosePublisher.DataValue.Type.FLOAT;

        // 1) Sin modelo: regla por nombre
        String[][] casos = {
            {"LD0/XCBR1.Pos.stVal [ST]", "DBPOS"}, {"LD0/XCBR1.PosA.stVal [ST]", "DBPOS"},
            {"LD0/CSWI1.Pos.ctlVal [CO]", "DBPOS"}, {"LD0/GGIO1.Ind1.stVal [ST]", "BOOLEAN"},
            {"LD0/LLN0.Beh.stVal [ST]", "INTEGER"}, {"LD0/LLN0.Health.stVal [ST]", "INTEGER"},
            {"LD0/XCBR1.Loc.stVal [ST]", "BOOLEAN"}, {"LD0/XCBR1.OpCnt.stVal [ST]", "INTEGER"},
            {"LD0/XCBR1.Pos.q [ST]", "BITSTRING"}, {"LD0/XCBR1.Pos.t [ST]", "UTC_TIME"},
            {"LD0/MMXU1.TotW.mag.f [MX]", "FLOAT"}, {"PROT/PTOC1.Op.general [ST]", "BOOLEAN"},
            {"PROT/PTOC1.Op [ST]", "BOOLEAN"},
        };
        List<String> mal = new ArrayList<>();
        for (String[] c : casos) {
            GoosePublisher.DataValue.Type t = GooseModelSync.inferDataType(c[0]);
            if (!t.name().equals(c[1])) mal.add(c[0] + " -> " + t + " (esperado " + c[1] + ")");
        }
        for (String m : mal) System.out.println("       " + m);
        chk("sin modelo, el nombre da el tipo correcto en " + casos.length + " casos (Pos.stVal = DBPOS)", mal.isEmpty());

        // 2) Con modelo: el tipo real del atributo
        IEC61850Server srv = new IEC61850Server();
        chk("carga test_bay_control.cid", srv.loadSclFile("test/test_bay_control.cid"));
        ServerModel m = srv.getServerModel();
        chk("Pos.stVal del modelo: DBPOS", GooseModelSync.tipoDeMiembro("CTRL/Q0XCBR1.Pos.stVal [ST]", m, "BAYCTRL") == D);
        chk("Pos.q del modelo: BITSTRING (calidad)", GooseModelSync.tipoDeMiembro("CTRL/Q0XCBR1.Pos.q [ST]", m, "BAYCTRL") == Q);
        chk("Pos.t del modelo: UTC_TIME", GooseModelSync.tipoDeMiembro("CTRL/Q0XCBR1.Pos.t [ST]", m, "BAYCTRL") == T);
        chk("Beh.stVal del modelo: INTEGER (enumerado)", GooseModelSync.tipoDeMiembro("CTRL/LLN0.Beh.stVal [ST]", m, "BAYCTRL") == I);
        chk("Loc.stVal del modelo: BOOLEAN", GooseModelSync.tipoDeMiembro("CTRL/Q0XCBR1.Loc.stVal [ST]", m, "BAYCTRL") == B);
        chk("un miembro que no está en el modelo cae en la regla por nombre",
                GooseModelSync.tipoDeMiembro("CTRL/XXXX1.Pos.stVal [ST]", m, "BAYCTRL") == D);

        // 3) Codificación en la trama
        GoosePublisher pub = new GoosePublisher();
        List<GoosePublisher.DataValue> vals = new ArrayList<>();
        vals.add(new GoosePublisher.DataValue("Pos.stVal", D, 1));            // OFF
        vals.add(new GoosePublisher.DataValue("Pos.q", Q, 0));                // good
        byte[] hora = GoosePublisher.utc(1763696627720L);                   // 2025-11-21 ... .720
        vals.add(new GoosePublisher.DataValue("Pos.t", T, hora));
        vals.add(new GoosePublisher.DataValue("Beh.stVal", I, 1));            // on
        pub.setDataValues(vals);
        Method enc = GoosePublisher.class.getDeclaredMethod("encodeAllData");
        enc.setAccessible(true);
        byte[] d = (byte[]) enc.invoke(pub);
        int o = 0;
        chk("Dbpos OFF: 84 02 06 40 (bit-string de 2 bits)", d[o] == (byte) 0x84 && d[o + 1] == 2 && d[o + 2] == 6 && d[o + 3] == 0x40);
        o += 4;
        chk("calidad good: 84 03 03 00 00 (13 bits)", d[o] == (byte) 0x84 && d[o + 1] == 3 && d[o + 2] == 3 && d[o + 3] == 0 && d[o + 4] == 0);
        o += 5;
        boolean horaOk = d[o] == (byte) 0x91 && d[o + 1] == 8;
        for (int i = 0; horaOk && i < 8; i++) horaOk = d[o + 2 + i] == hora[i];
        chk("hora: 91 08 y los 8 bytes de utc-time", horaOk);
        o += 10;
        chk("Beh como entero: 85 01 01", d[o] == (byte) 0x85 && d[o + 1] == 1 && d[o + 2] == 1);
        String txt = GooseSubscriber.textoUtc(hora, 0);
        chk("la hora se lee de vuelta con sus milisegundos (" + txt + ")", txt.endsWith(".720"));
        chk("la calidad de la hora es 0x0A", hora[7] == 0x0A);

        // 4) Sincronización con el modelo
        BdaTimestamp t = (BdaTimestamp) m.findModelNode("BAYCTRLCTRL/Q0XCBR1.Pos.t", Fc.ST);
        chk("la hora del modelo no se copia al publicador", SclReferenceUtils.convertBdaToPublisherValue(t, T) == null);
        chk("la hora del publicador no se escribe en el modelo",
                GooseModelSync.convertPublisherValueToString(new GoosePublisher.DataValue("t", T, hora)) == null);
        BdaDoubleBitPos pos = (BdaDoubleBitPos) m.findModelNode("BAYCTRLCTRL/Q0XCBR1.Pos.stVal", Fc.ST);
        pos.setDoubleBitPos(BdaDoubleBitPos.DoubleBitPos.ON);
        chk("Dbpos ON del modelo -> 2", Integer.valueOf(2).equals(SclReferenceUtils.convertBdaToPublisherValue(pos, D)));
        pos.setDoubleBitPos(BdaDoubleBitPos.DoubleBitPos.INTERMEDIATE_STATE);
        chk("Dbpos intermedio del modelo -> 0", Integer.valueOf(0).equals(SclReferenceUtils.convertBdaToPublisherValue(pos, D)));
        pos.setDoubleBitPos(BdaDoubleBitPos.DoubleBitPos.BAD_STATE);
        chk("Dbpos bad del modelo -> 3", Integer.valueOf(3).equals(SclReferenceUtils.convertBdaToPublisherValue(pos, D)));

        System.out.println();
        System.out.println(ok + " pasaron, " + falla + " fallaron");
        if (falla == 0) System.out.println("TODO OK");
        System.exit(falla == 0 ? 0 : 1);
    }
}
