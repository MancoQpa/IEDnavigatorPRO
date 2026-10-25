package com.iednavigator;

import com.beanit.iec61850bean.BasicDataAttribute;
import com.beanit.iec61850bean.BdaQuality;
import com.beanit.iec61850bean.BdaTimestamp;
import com.beanit.iec61850bean.BdaType;
import com.beanit.iec61850bean.Fc;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Conformidad de un atributo con IEC 61850-7-2/7-3/7-4/8-1, para el reporte HTML.
 *
 * Nació de la revisión a mano del reporte del NARI (2026-10-25, 71.665 atributos): FC y tipos
 * correctos salvo el Check de LTRK, y miles de valores que la norma no permite (Beh/Mod/Health
 * en 0, SIUnit 0) porque nunca se habían leído. Este chequeo hace esa revisión solo.
 *
 * Reglas:
 *  - Estructura: el FC y el tipo que corresponden al nombre del atributo (stVal en ST, q
 *    Quality, ctlModel ENUMERATED en CF, mag.f FLOAT32 en MX…). Vale siempre, porque el FC y el
 *    tipo vienen del modelo.
 *  - Enumerados estándar con un valor que no existe (CatalogoIec61850). Leído del equipo, es
 *    un error; un 0 que viene de un archivo SCL es "sin configurar": un SCL no configura los
 *    datos de estado (visto en 163 SCL del corpus).
 *  - Calidad: validez reservada (10) es un error; validez buena con bits de detalle de falla,
 *    un aviso.
 *  - Hora: un dato leído con hora 1970-01-01 o reloj sin sincronizar, un aviso.
 */
final class ConformidadIec61850 {

    enum Nivel { FUERA_DE_NORMA, AVISO, SIN_CONFIGURAR, NO_LEIDO, OK }

    static final class Resultado {
        final Nivel nivel;
        final String motivo;   // "" si OK
        Resultado(Nivel nivel, String motivo) { this.nivel = nivel; this.motivo = motivo; }
        @Override public String toString() { return nivel + (motivo.isEmpty() ? "" : ": " + motivo); }
    }

    static final Resultado OK = new Resultado(Nivel.OK, "");

    private ConformidadIec61850() {}

    // ── Estructura: FC y tipo por nombre de atributo ─────────────────────────────

    private static final class Regla {
        final Set<Fc> fcs; final Set<BdaType> tipos; final String norma;
        Regla(Set<Fc> fcs, Set<BdaType> tipos, String norma) { this.fcs = fcs; this.tipos = tipos; this.norma = norma; }
    }

    private static final Map<String, Regla> REGLAS = new HashMap<>();
    private static final Set<String> RCB = new HashSet<>(Arrays.asList("RptID", "RptEna", "Resv",
        "DatSet", "ConfRev", "OptFlds", "BufTm", "SqNum", "TrgOps", "IntgPd", "GI", "PurgeBuf",
        "EntryID", "TimeofEntry", "ResvTms", "Owner"));

    private static Set<Fc> fc(Fc... f) { return EnumSet.copyOf(Arrays.asList(f)); }
    private static Set<BdaType> t(BdaType... x) { return EnumSet.copyOf(Arrays.asList(x)); }
    private static void r(String da, Set<Fc> fcs, Set<BdaType> tipos, String norma) { REGLAS.put(da, new Regla(fcs, tipos, norma)); }

    static {
        Set<BdaType> enumT = t(BdaType.INT8);
        Set<BdaType> vis = t(BdaType.VISIBLE_STRING);
        r("q", fc(Fc.ST, Fc.MX, Fc.SR, Fc.OR), t(BdaType.QUALITY), "q: Quality");
        r("t", fc(Fc.ST, Fc.MX, Fc.SR, Fc.OR), t(BdaType.TIMESTAMP), "t: TimeStamp");
        r("blkEna", fc(Fc.BL), t(BdaType.BOOLEAN), "blkEna: BOOLEAN, FC BL");
        r("subEna", fc(Fc.SV), t(BdaType.BOOLEAN), "subEna: BOOLEAN, FC SV");
        r("subQ", fc(Fc.SV), t(BdaType.QUALITY), "subQ: Quality, FC SV");
        r("ctlModel", fc(Fc.CF), enumT, "ctlModel: ENUMERATED, FC CF");
        r("sboClass", fc(Fc.CF), enumT, "sboClass: ENUMERATED, FC CF");
        r("sboTimeout", fc(Fc.CF), t(BdaType.INT32U), "sboTimeout: INT32U, FC CF");
        r("operTimeout", fc(Fc.CF), t(BdaType.INT32U), "operTimeout: INT32U, FC CF");
        r("SIUnit", fc(Fc.CF), enumT, "units.SIUnit: ENUMERATED, FC CF");
        r("multiplier", fc(Fc.CF), enumT, "units.multiplier: ENUMERATED, FC CF");
        r("db", fc(Fc.CF), t(BdaType.INT32U), "db: INT32U, FC CF");
        r("zeroDb", fc(Fc.CF), t(BdaType.INT32U), "zeroDb: INT32U, FC CF");
        r("scaleFactor", fc(Fc.CF), t(BdaType.FLOAT32), "sVC.scaleFactor: FLOAT32, FC CF");
        r("offset", fc(Fc.CF), t(BdaType.FLOAT32), "sVC.offset: FLOAT32, FC CF");
        r("range", fc(Fc.MX), enumT, "range: ENUMERATED, FC MX");
        r("d", fc(Fc.DC), vis, "d: VisString255, FC DC");
        r("dU", fc(Fc.DC), t(BdaType.UNICODE_STRING), "dU: Unicode255, FC DC");
        for (String x : new String[] {"vendor", "swRev", "hwRev", "serNum", "model", "location", "configRev"}) {
            r(x, fc(Fc.DC), vis, x + ": VisString, FC DC");
        }
        for (String x : new String[] {"cdcNs", "cdcName", "dataNs", "ldNs", "lnNs"}) {
            r(x, fc(Fc.EX), vis, x + ": VisString, FC EX");
        }
        r("ctlNum", fc(Fc.CO, Fc.ST, Fc.MX, Fc.SR, Fc.OR), t(BdaType.INT8U), "ctlNum: INT8U");
        r("T", fc(Fc.CO, Fc.SR), t(BdaType.TIMESTAMP), "T: TimeStamp");
        r("Test", fc(Fc.CO, Fc.SR), t(BdaType.BOOLEAN), "Test: BOOLEAN");
        // Check: CHECK, o Dbpos si iec61850bean lo creó así fuera de CO (mismo bit-string de 2 bits)
        r("Check", fc(Fc.CO, Fc.SR), t(BdaType.CHECK, BdaType.DOUBLE_BIT_POS), "Check: PACKED LIST de 2 bits");
        r("orCat", fc(Fc.CO, Fc.ST, Fc.MX, Fc.SR, Fc.OR), enumT, "origin.orCat: ENUMERATED");
        r("orIdent", fc(Fc.CO, Fc.ST, Fc.MX, Fc.SR, Fc.OR), t(BdaType.OCTET_STRING), "origin.orIdent: OCTET STRING");
        // En APC (consigna analógica) origin, ctlNum y stSeld van con FC MX (7-3 Ed2; Siemens 7UT85)
        r("stSeld", fc(Fc.ST, Fc.MX), t(BdaType.BOOLEAN), "stSeld: BOOLEAN, FC ST (MX en APC)");
        r("opRcvd", fc(Fc.OR), t(BdaType.BOOLEAN), "opRcvd: BOOLEAN, FC OR");
        r("opOk", fc(Fc.OR), t(BdaType.BOOLEAN), "opOk: BOOLEAN, FC OR");
        r("tOpOk", fc(Fc.OR), t(BdaType.TIMESTAMP), "tOpOk: TimeStamp, FC OR");
        r("general", fc(Fc.ST), t(BdaType.BOOLEAN), "general: BOOLEAN, FC ST");
        r("dirGeneral", fc(Fc.ST), enumT, "dirGeneral: ENUMERATED, FC ST");
    }

    static Resultado estructura(BasicDataAttribute bda) {
        String ref = bda.getReference().toString();
        String[] seg = ref.split("\\.");
        String da = seg[seg.length - 1];
        String padre = seg.length >= 2 ? seg[seg.length - 2] : "";
        Fc fc = bda.getFc();
        BdaType tipo = bda.getBasicType();

        // Magnitudes analógicas: el FC depende de dónde están (medida, sustitución, ajuste, límites)
        if ((da.equals("f") || da.equals("i")) && (padre.equals("mag") || padre.equals("instMag")
                || padre.equals("ang") || padre.equals("setMag"))) {
            Set<Fc> esperados;
            if (ref.contains(".subMag.") || ref.contains(".subCVal.")) esperados = fc(Fc.SV);
            else if (padre.equals("setMag")) esperados = fc(Fc.SP, Fc.SG, Fc.SE);
            else if (ref.contains(".minVal.") || ref.contains(".maxVal.") || ref.contains(".stepSize."))
                esperados = fc(Fc.CF);
            else esperados = fc(Fc.MX);
            boolean tipoOk = da.equals("f") ? tipo == BdaType.FLOAT32 : (tipo == BdaType.INT32 || tipo == BdaType.INT32U);
            if (!esperados.contains(fc)) return new Resultado(Nivel.FUERA_DE_NORMA, "FC " + fc + " en " + padre + "." + da + " (norma: " + esperados + ")");
            if (!tipoOk) return new Resultado(Nivel.FUERA_DE_NORMA, "tipo " + tipo + " en " + padre + "." + da + " (norma: " + (da.equals("f") ? "FLOAT32" : "INT32") + ")");
            return OK;
        }
        // Atributos de un RCB: solo dentro de un RCB (FC RP/BR); ResvTms del SGCB es otra cosa
        if (RCB.contains(da) && fc != Fc.RP && fc != Fc.BR) return OK;

        Regla regla = REGLAS.get(da);
        if (regla == null) return OK;
        if (!regla.fcs.contains(fc)) return new Resultado(Nivel.FUERA_DE_NORMA, "FC " + fc + " en " + da + " (norma: " + regla.norma + ")");
        if (tipo != null && !regla.tipos.contains(tipo)) return new Resultado(Nivel.FUERA_DE_NORMA, "tipo " + tipo + " en " + da + " (norma: " + regla.norma + ")");
        return OK;
    }

    /**
     * @param leido        el objeto del atributo se leyó del equipo
     * @param deArchivo    el modelo viene de un archivo SCL (modo servidor), no de un equipo
     */
    static Resultado evaluar(BasicDataAttribute bda, boolean leido, boolean deArchivo) {
        Resultado e = estructura(bda);
        if (e.nivel != Nivel.OK) return e;
        if (!leido && !deArchivo) return new Resultado(Nivel.NO_LEIDO, "");

        String ref = bda.getReference().toString();
        Fc fc = bda.getFc();

        // Enumerados estándar. No en FC CO: Oper/SBOw/Cancel son los parámetros de la orden que
        // escribe el cliente; leídos sin orden previa valen 0, y no son un estado del equipo.
        if (fc != Fc.CO && CatalogoIec61850.claseEnumerado(ref, fc) != null) {
            Long v = entero(bda);
            if (v != null && !CatalogoIec61850.valorPermitido(ref, fc, v)) {
                String clase = CatalogoIec61850.claseEnumerado(ref, fc);
                if (deArchivo && v == 0) return new Resultado(Nivel.SIN_CONFIGURAR, clase + " en 0: sin valor en el archivo");
                // subVal (FC SV) es el valor de sustitución: un equipo lo deja sin cargar mientras no
                // se usa la sustitución (subEna en false). No se trata como error.
                if (fc == Fc.SV)
                    return new Resultado(Nivel.AVISO, "valor de sustitución " + v + " fuera de " + clase + " (sin cargar mientras no se sustituye)");
                if (clase.equals("unidad SI") && v < 0)
                    return new Resultado(Nivel.AVISO, "SIUnit " + v + ": código fuera del catálogo estándar (¿del fabricante o de otra edición?)");
                return new Resultado(Nivel.FUERA_DE_NORMA, "valor " + v + " no existe en " + clase);
            }
        }

        if (deArchivo) return OK;   // calidad y hora de un archivo son valores iniciales, no del equipo

        if (bda instanceof BdaQuality) {
            BdaQuality q = (BdaQuality) bda;
            if (q.getValidity() == BdaQuality.Validity.RESERVED)
                return new Resultado(Nivel.FUERA_DE_NORMA, "calidad con validez reservada (10)");
            if (q.getValidity() == BdaQuality.Validity.GOOD && (q.isOverflow() || q.isOutOfRange()
                    || q.isBadReference() || q.isOscillatory() || q.isFailure() || q.isOldData()
                    || q.isInconsistent() || q.isInaccurate()))
                return new Resultado(Nivel.AVISO, "calidad buena con bits de detalle de falla marcados");
        }
        if (bda instanceof BdaTimestamp && (fc == Fc.ST || fc == Fc.MX)) {
            BdaTimestamp ts = (BdaTimestamp) bda;
            java.time.Instant i = ts.getInstant();
            if (i == null || i.getEpochSecond() == 0)
                return new Resultado(Nivel.AVISO, "hora 1970-01-01: el dato nunca se actualizó o el equipo no tiene hora");
            if (ts.getClockNotSynchronized())
                return new Resultado(Nivel.AVISO, "marca de tiempo con reloj sin sincronizar");
        }
        return OK;
    }

    private static Long entero(BasicDataAttribute bda) {
        try {
            return Long.parseLong(ValorBda.texto(bda).trim());
        } catch (Exception e) {
            return null;
        }
    }
}
