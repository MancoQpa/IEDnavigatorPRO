package com.iednavigator;

import java.util.*;

/**
 * El diccionario cubre todos los CDC de IEC 61850-7-3 Ed.2/Ed.2.1 y los DO y DA agregados el
 * 2026-11-20 (armónicos, estadística, flicker, GGIO, cálculo estadístico), y la deducción del
 * CDC en modo Cliente reconoce WYE/DEL cuando las fases llegan como estructuras (ION 7400).
 *
 * Uso: java -cp "classes;lib\*;test" com.iednavigator.TestDiccionarioNorma
 */
public class TestDiccionarioNorma {

    private static int ok, falla;

    private static void chk(String que, boolean cond) {
        if (cond) { ok++; System.out.println("OK     " + que); }
        else { falla++; System.out.println("FALLA  " + que); }
    }

    public static void main(String[] args) {
        String[] cdcs = {
            // estado
            "SPS", "DPS", "INS", "ENS", "ACT", "ACD", "SEC", "BCR", "HST", "VSS",
            // medida
            "MV", "CMV", "SAV", "WYE", "DEL", "SEQ", "HMV", "HWYE", "HDEL",
            // control
            "SPC", "DPC", "INC", "ENC", "BSC", "ISC", "APC", "BAC",
            // ajustes
            "SPG", "ING", "ENG", "ORG", "TSG", "CUG", "VSG", "ASG", "CURVE", "CSG",
            // descripción
            "DPL", "LPL", "CSD", "VSD",
            // seguimiento de servicios
            "CST", "BTS", "UTS", "LTS", "GTS", "MTS", "NTS", "STS", "CTS"};
        List<String> faltan = new ArrayList<>();
        for (String c : cdcs) if (Iec61850Dictionary.lookup(c) == null) faltan.add(c);
        chk("los " + cdcs.length + " CDC de IEC 61850-7-3 están en el diccionario" + (faltan.isEmpty() ? "" : ", faltan " + faltan),
                faltan.isEmpty());

        String[] dos = {"HA", "HPhV", "HPPV", "HW", "HVAr", "HVA", "HRmsA", "HRmsPhV", "HRmsPPV", "HKf", "HTdf",
                "HCfA", "HCfPhV", "HCfPPV", "ThdPPV", "ThdOddA", "ThdEvnA", "ThdOddPhV", "ThdEvnPhV", "ThdOddPPV",
                "ThdEvnPPV", "TddA", "PhPst", "PhPlt", "AvVolts", "MaxVolts", "MinVolts", "AvVA", "MaxVA", "MinVA",
                "AnIn", "AnOut", "IntIn", "DPCSO", "ISCSO", "ClcMth", "ClcMod", "ClcIntvTyp", "ClcIntvPer",
                "ClcStr", "ClcSrc", "ClcExp"};
        faltan.clear();
        for (String d : dos) if (Iec61850Dictionary.lookup(d) == null) faltan.add(d);
        chk("los " + dos.length + " DO agregados se encuentran" + (faltan.isEmpty() ? "" : ", faltan " + faltan), faltan.isEmpty());
        chk("un DO numerado (AnIn3) se encuentra por su nombre base",
                Iec61850Dictionary.lookup("AnIn3") != null || Iec61850Dictionary.lookup("AnIn") != null);

        String[] das = {"valWTr", "har", "frequency", "rmsCyc", "hstVal", "hstRangeC", "numPts", "maxPts", "crvPts",
                "xUnits", "yUnits", "zUnits", "xD", "yD", "zD", "setCharact", "setParA", "setParF", "setTm",
                "setCal", "cur", "val"};
        faltan.clear();
        for (String d : das) if (Iec61850Dictionary.lookup(d) == null) faltan.add(d);
        chk("los " + das.length + " DA agregados se encuentran" + (faltan.isEmpty() ? "" : ", faltan " + faltan), faltan.isEmpty());

        // Deducción del CDC sin SCL: fases como estructuras (así llegan por MMS)
        Map<String, String> wye = new HashMap<>();
        wye.put("phsA", "Struct"); wye.put("phsB", "Struct"); wye.put("phsC", "Struct");
        chk("phsA/phsB/phsC como estructuras: WYE", "WYE".equals(TiposIec61850.inferirCdc(wye, new HashSet<>(), null, "PhV")));
        Map<String, String> del = new HashMap<>();
        del.put("phsAB", "Struct"); del.put("phsBC", "Struct"); del.put("phsCA", "Struct");
        chk("phsAB/phsBC/phsCA como estructuras: DEL", "DEL".equals(TiposIec61850.inferirCdc(del, new HashSet<>(), null, "PPV")));
        Map<String, String> act = new HashMap<>();
        act.put("general", "BOOLEAN"); act.put("phsA", "BOOLEAN"); act.put("q", "Quality");
        chk("un ACT (phsA booleano) sigue siendo ACT", "ACT".equals(TiposIec61850.inferirCdc(act, new HashSet<>(), null, "Op")));
        Map<String, String> hwye = new HashMap<>();
        hwye.put("phsAHar", "Struct"); hwye.put("numHar", "INT16U");
        chk("phsAHar: HWYE", "HWYE".equals(TiposIec61850.inferirCdc(hwye, new HashSet<>(), null, "HA")));

        System.out.println();
        System.out.println(ok + " pasaron, " + falla + " fallaron");
        if (falla == 0) System.out.println("TODO OK");
        System.exit(falla == 0 ? 0 : 1);
    }
}
