package com.iednavigator;

import com.beanit.iec61850bean.*;

/**
 * El tipo que asigna IEC 61850 a un atributo y el nombre de los enumerados estándar
 * (CatalogoIec61850), independientes del tipo MMS con que llegan.
 *
 * Casos de campo (2026-10-25, reporte del NARI): Check de LTRK (FC SR) que iec61850bean crea
 * como Dbpos; Beh/Mod/Health y SIUnit con 0, que no es un valor de sus enumerados.
 *
 * Uso:  javac -cp "classes;lib\*" -d test test\TestCatalogoIec61850.java
 *       java  -cp "classes;lib\*;test" com.iednavigator.TestCatalogoIec61850
 */
public class TestCatalogoIec61850 {

    private static int fallas = 0;

    static void chk(String titulo, String esperado, String obtenido) {
        boolean ok = esperado == null ? obtenido == null : esperado.equals(obtenido);
        if (!ok) fallas++;
        System.out.printf("%-50s %-36s %s%n", titulo, obtenido, ok ? "OK" : "<<< FALLA (esperado " + esperado + ")");
    }

    static ObjectReference r(String s) { return new ObjectReference(s); }

    public static void main(String[] args) {
        // Tipos según la norma
        chk("Check en SR (iec61850bean: Dbpos)", "Check",
            CatalogoIec61850.tipoNorma(new BdaDoubleBitPos(r("IED001LD0/LTRK1.SpcTrk.Check"), Fc.SR, null, false, false)));
        chk("Check en CO", "Check", CatalogoIec61850.tipoNorma(new BdaCheck(r("IED001CTRL/CB1CSWI1.Pos.Oper.Check"))));
        chk("Pos.stVal", "Dbpos",
            CatalogoIec61850.tipoNorma(new BdaDoubleBitPos(r("IED001CTRL/CB1CSWI1.Pos.stVal"), Fc.ST, null, false, false)));
        chk("Beh.stVal", "ENUMERATED (BehaviourModeKind)",
            CatalogoIec61850.tipoNorma(new BdaInt8(r("IED001CTRL/LLN0.Beh.stVal"), Fc.ST, null, false, false)));
        chk("Mod.Oper.ctlVal", "ENUMERATED (BehaviourModeKind)",
            CatalogoIec61850.tipoNorma(new BdaInt8(r("IED001CTRL/LLN0.Mod.Oper.ctlVal"), Fc.CO, null, false, false)));
        chk("Pos.ctlModel", "ENUMERATED (CtlModelKind)",
            CatalogoIec61850.tipoNorma(new BdaInt8(r("IED001CTRL/CB1CSWI1.Pos.ctlModel"), Fc.CF, null, false, false)));
        chk("Pos.q", "Quality", CatalogoIec61850.tipoNorma(new BdaQuality(r("IED001CTRL/CB1CSWI1.Pos.q"), Fc.ST, null, false)));
        chk("ctlNum (entero, no enumerado)", "INT8U",
            CatalogoIec61850.tipoNorma(new BdaInt8U(r("IED001CTRL/CB1CSWI1.Pos.Oper.ctlNum"), Fc.CO, null, false, false)));

        // Nombres de enumerados
        chk("Beh 1", "on (1)", CatalogoIec61850.nombreEnumerado("X/LLN0.Beh.stVal", Fc.ST, 1));
        chk("Mod 5", "off (5)", CatalogoIec61850.nombreEnumerado("X/LLN0.Mod.stVal", Fc.ST, 5));
        chk("Health 2", "Warning (2)", CatalogoIec61850.nombreEnumerado("X/LLN0.Health.stVal", Fc.ST, 2));
        chk("ctlModel 4", "sbo-with-enhanced-security (4)", CatalogoIec61850.nombreEnumerado("X/Y.Pos.ctlModel", Fc.CF, 4));
        chk("orCat 3", "remote-control (3)", CatalogoIec61850.nombreEnumerado("X/Y.Pos.origin.orCat", Fc.ST, 3));
        chk("SIUnit 5", "A (5)", CatalogoIec61850.nombreEnumerado("X/MMXU1.A.phsA.units.SIUnit", Fc.CF, 5));
        chk("SIUnit 29", "V (29)", CatalogoIec61850.nombreEnumerado("X/MMXU1.PhV.phsA.units.SIUnit", Fc.CF, 29));
        chk("multiplier 3", "k (3)", CatalogoIec61850.nombreEnumerado("X/MMXU1.PhV.phsA.units.multiplier", Fc.CF, 3));
        chk("dirGeneral 1", "forward (1)", CatalogoIec61850.nombreEnumerado("X/PTOC1.Str.dirGeneral", Fc.ST, 1));

        // Valores que la norma no permite (los del reporte del NARI sin leer)
        chk("Beh 0: fuera del enumerado", "false", String.valueOf(CatalogoIec61850.valorPermitido("X/LLN0.Beh.stVal", Fc.ST, 0)));
        chk("Health 0: fuera del enumerado", "false", String.valueOf(CatalogoIec61850.valorPermitido("X/LLN0.Health.stVal", Fc.ST, 0)));
        chk("SIUnit 0: no es una unidad", "false", String.valueOf(CatalogoIec61850.valorPermitido("X/Y.Z.units.SIUnit", Fc.CF, 0)));
        chk("ctlModel 0: permitido (status-only)", "true", String.valueOf(CatalogoIec61850.valorPermitido("X/Y.Pos.ctlModel", Fc.CF, 0)));
        chk("atributo sin enumerado: null", null, CatalogoIec61850.nombreEnumerado("X/Y.Pos.Oper.ctlNum", Fc.CO, 3));

        // En el reporte: "on (1)" en lugar de "1"
        chk("reporte: Beh.stVal \"1\"", "on (1)",
            ModelReportGenerator.conNombreDeEnumerado(new BdaInt8(r("IED001CTRL/LLN0.Beh.stVal"), Fc.ST, null, false, false), "1"));
        chk("reporte: texto no numérico intacto", "OFF",
            ModelReportGenerator.conNombreDeEnumerado(new BdaInt8(r("IED001CTRL/LLN0.Beh.stVal"), Fc.ST, null, false, false), "OFF"));

        // Check creado como Dbpos (SR): se muestra como sus dos bits, no como OFF/ON
        BdaDoubleBitPos chk = new BdaDoubleBitPos(r("IED001LD0/LTRK1.SpcTrk.Check"), Fc.SR, null, false, false);
        chk.setValue(new byte[] {(byte) 0x40});   // bit 1 = interlock-check
        chk("reporte: Check como bits", "synchrocheck=0, interlock-check=1",
            ModelReportGenerator.conNombreDeEnumerado(chk, "OFF"));

        System.out.println(fallas == 0 ? "\nTODO OK" : "\n" + fallas + " FALLAS");
        System.exit(fallas == 0 ? 0 : 1);
    }
}
