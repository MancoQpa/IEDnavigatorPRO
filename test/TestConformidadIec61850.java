package com.iednavigator;

import com.beanit.iec61850bean.*;

/**
 * Conformidad de un atributo con IEC 61850 (ConformidadIec61850), con los casos de la revisión
 * del reporte del NARI (2026-10-25).
 *
 * Uso:  javac -cp "classes;lib\*" -d test test\TestConformidadIec61850.java
 *       java  -cp "classes;lib\*;test" com.iednavigator.TestConformidadIec61850
 */
public class TestConformidadIec61850 {

    private static int fallas = 0;

    static void chk(String titulo, ConformidadIec61850.Nivel esperado, ConformidadIec61850.Resultado r) {
        boolean ok = r.nivel == esperado;
        if (!ok) fallas++;
        String m = r.toString();
        if (m.length() > 64) m = m.substring(0, 64) + "…";
        System.out.printf("%-48s %-66s %s%n", titulo, m, ok ? "OK" : "<<< FALLA (esperado " + esperado + ")");
    }

    static ObjectReference r(String s) { return new ObjectReference(s); }

    static BdaInt8 int8(String ref, Fc fc, int v) {
        BdaInt8 b = new BdaInt8(r(ref), fc, null, false, false);
        b.setValue((byte) v);
        return b;
    }

    public static void main(String[] args) {
        ConformidadIec61850.Nivel OK = ConformidadIec61850.Nivel.OK;
        ConformidadIec61850.Nivel FN = ConformidadIec61850.Nivel.FUERA_DE_NORMA;
        ConformidadIec61850.Nivel AV = ConformidadIec61850.Nivel.AVISO;
        ConformidadIec61850.Nivel SC = ConformidadIec61850.Nivel.SIN_CONFIGURAR;
        ConformidadIec61850.Nivel NL = ConformidadIec61850.Nivel.NO_LEIDO;

        // Estructura
        chk("Check en SR creado como Dbpos: conforme", OK, ConformidadIec61850.evaluar(
            new BdaDoubleBitPos(r("IED001LD0/LTRK1.SpcTrk.Check"), Fc.SR, null, false, false), true, false));
        chk("ctlModel con FC ST: fuera de norma", FN, ConformidadIec61850.evaluar(
            int8("X/Y.Pos.ctlModel", Fc.ST, 4), true, false));
        chk("q con tipo BOOLEAN: fuera de norma", FN, ConformidadIec61850.evaluar(
            new BdaBoolean(r("X/Y.Pos.q"), Fc.ST, null, false, false), true, false));
        chk("setMag.f en SG: conforme", OK, ConformidadIec61850.evaluar(
            new BdaFloat32(r("X/PTOC1.StrVal.setMag.f"), Fc.SG, null, false, false), true, false));
        chk("subCVal.mag.f en SV: conforme", OK, ConformidadIec61850.evaluar(
            new BdaFloat32(r("X/MMXN1.Imp.subCVal.mag.f"), Fc.SV, null, false, false), true, false));
        chk("mag.f en CF fuera de límites: fuera de norma", FN, ConformidadIec61850.evaluar(
            new BdaFloat32(r("X/MMXU1.TotW.mag.f"), Fc.CF, null, false, false), true, false));
        chk("SGCB.ResvTms (no es un RCB): conforme", OK, ConformidadIec61850.evaluar(
            new BdaInt16U(r("X/LLN0.SGCB.ResvTms"), Fc.SP, null, false, false), true, false));

        // Enumerados
        chk("Beh 0 leído del equipo: fuera de norma", FN, ConformidadIec61850.evaluar(int8("X/LLN0.Beh.stVal", Fc.ST, 0), true, false));
        chk("Beh 0 de un archivo SCL: sin configurar", SC, ConformidadIec61850.evaluar(int8("X/LLN0.Beh.stVal", Fc.ST, 0), true, true));
        chk("Beh 1 leído: conforme", OK, ConformidadIec61850.evaluar(int8("X/LLN0.Beh.stVal", Fc.ST, 1), true, false));
        chk("Health 4 leído: fuera de norma", FN, ConformidadIec61850.evaluar(int8("X/LLN0.Health.stVal", Fc.ST, 4), true, false));
        chk("SIUnit 0 leído: fuera de norma", FN, ConformidadIec61850.evaluar(int8("X/Y.Z.units.SIUnit", Fc.CF, 0), true, false));
        chk("SIUnit -7 (MiCOM): aviso, no error", AV, ConformidadIec61850.evaluar(int8("X/Y.Z.units.SIUnit", Fc.CF, -7), true, false));

        chk("Beh.subVal 0 (sustitución sin cargar): aviso", AV, ConformidadIec61850.evaluar(int8("X/LLN0.Beh.subVal", Fc.SV, 0), true, false));

        chk("Mod.SBOw.ctlVal 0 (parámetro de mando, CO): conforme", OK, ConformidadIec61850.evaluar(int8("X/LLN0.Mod.SBOw.ctlVal", Fc.CO, 0), true, false));
        chk("EEHealth.stVal 0 leído: fuera de norma", FN, ConformidadIec61850.evaluar(int8("X/XCBR1.EEHealth.stVal", Fc.ST, 0), true, false));

        // Calidad
        BdaQuality qRes = new BdaQuality(r("X/Y.Pos.q"), Fc.ST, null, false);
        qRes.setValidity(BdaQuality.Validity.RESERVED);
        chk("calidad con validez reservada: fuera de norma", FN, ConformidadIec61850.evaluar(qRes, true, false));
        BdaQuality qOld = new BdaQuality(r("X/Y.Pos.q"), Fc.ST, null, false);
        qOld.setOldData(true);
        chk("calidad buena con oldData: aviso", AV, ConformidadIec61850.evaluar(qOld, true, false));
        BdaQuality qInv = new BdaQuality(r("X/Y.Pos.q"), Fc.ST, null, false);
        qInv.setValidity(BdaQuality.Validity.INVALID);
        qInv.setFailure(true);
        chk("calidad inválida con failure: conforme", OK, ConformidadIec61850.evaluar(qInv, true, false));

        // Hora
        BdaTimestamp t0 = new BdaTimestamp(r("X/Y.Pos.t"), Fc.ST, null, false, false);
        chk("hora 1970 leída: aviso", AV, ConformidadIec61850.evaluar(t0, true, false));
        chk("hora 1970 de un archivo: conforme (valor inicial)", OK, ConformidadIec61850.evaluar(t0, true, true));
        BdaTimestamp tOk = new BdaTimestamp(r("X/Y.Pos.t"), Fc.ST, null, false, false);
        tOk.setInstant(java.time.Instant.parse("2026-10-22T12:30:37Z"));
        chk("hora real leída: conforme", OK, ConformidadIec61850.evaluar(tOk, true, false));

        // No leído
        chk("objeto no leído: no leído", NL, ConformidadIec61850.evaluar(int8("X/LLN0.Beh.stVal", Fc.ST, 0), false, false));
        chk("no leído pero con FC mal: fuera de norma", FN, ConformidadIec61850.evaluar(int8("X/Y.Pos.ctlModel", Fc.ST, 0), false, false));

        System.out.println(fallas == 0 ? "\nTODO OK" : "\n" + fallas + " FALLAS");
        System.exit(fallas == 0 ? 0 : 1);
    }
}
