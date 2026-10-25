package com.iednavigator;

import com.beanit.iec61850bean.BasicDataAttribute;
import com.beanit.iec61850bean.BdaType;
import com.beanit.iec61850bean.Fc;

import java.util.HashMap;
import java.util.Map;

/**
 * Tipo que IEC 61850 asigna a un atributo, y nombre de los enumerados estándar.
 *
 * MMS transporta tipos de codificación, no de la norma (IEC 61850-8-1): un ENUMERATED llega
 * como un entero de 8 bits, y Check y Dbpos llegan los dos como un bit-string de 2 bits.
 * iec61850bean decide la clase del atributo con ese tipo MMS, y para el bit-string de 2 bits
 * mira el FC: fuera de CO lo toma siempre como Dbpos. En las clases de seguimiento de la
 * edición 2 (LTRK, FC SR), el Check queda como Dbpos (visto en el NARI, 2026-10-25). No se
 * corrige el objeto, porque BdaCheck fija el FC en CO y el atributo dejaría de poder leerse:
 * el tipo de la norma lo da este catálogo, para mostrar y para el reporte.
 *
 * Los enumerados son los de IEC 61850-7-3 / 7-4 Ed2 (BehaviourModeKind, HealthKind,
 * CtlModelKind, SboClassKind, OriginatorCategoryKind, RangeKind, FaultDirectionKind,
 * PhaseFaultDirectionKind, multiplicador SI y unidades SI). Los enumerados privados de un
 * fabricante necesitan el SCL del equipo: acá quedan como el número.
 */
final class CatalogoIec61850 {

    private CatalogoIec61850() {}

    private static final Map<Integer, String> BEHAVIOUR = mapa(1, "on", 2, "on-blocked", 3, "test",
            4, "test/blocked", 5, "off");
    private static final Map<Integer, String> HEALTH = mapa(1, "Ok", 2, "Warning", 3, "Alarm");
    private static final Map<Integer, String> CTL_MODEL = mapa(0, "status-only",
            1, "direct-with-normal-security", 2, "sbo-with-normal-security",
            3, "direct-with-enhanced-security", 4, "sbo-with-enhanced-security");
    private static final Map<Integer, String> SBO_CLASS = mapa(0, "operate-once", 1, "operate-many");
    private static final Map<Integer, String> OR_CAT = mapa(0, "not-supported", 1, "bay-control",
            2, "station-control", 3, "remote-control", 4, "automatic-bay", 5, "automatic-station",
            6, "automatic-remote", 7, "maintenance", 8, "process");
    private static final Map<Integer, String> RANGE = mapa(0, "normal", 1, "high", 2, "low",
            3, "high-high", 4, "low-low");
    private static final Map<Integer, String> FAULT_DIR = mapa(0, "unknown", 1, "forward",
            2, "backward", 3, "both");
    private static final Map<Integer, String> PHASE_DIR = mapa(0, "unknown", 1, "forward", 2, "backward");
    private static final Map<Integer, String> MULTIPLIER = new HashMap<>();
    private static final Map<Integer, String> SI_UNIT = new HashMap<>();

    static {
        String[] prefijos = {"y", "z", "a", "f", "p", "n", "µ", "m", "c", "d", "", "da", "h", "k",
                "M", "G", "T", "P", "E", "Z", "Y"};
        int[] exponentes = {-24, -21, -18, -15, -12, -9, -6, -3, -2, -1, 0, 1, 2, 3, 6, 9, 12, 15, 18, 21, 24};
        for (int i = 0; i < prefijos.length; i++) {
            MULTIPLIER.put(exponentes[i], prefijos[i].isEmpty() ? "(sin prefijo)" : prefijos[i]);
        }
        Object[] u = {1, "(sin unidad)", 2, "m", 3, "kg", 4, "s", 5, "A", 6, "K", 7, "mol", 8, "cd",
                9, "deg", 10, "rad", 11, "sr", 21, "Gy", 22, "Bq", 23, "°C", 24, "Sv", 25, "F",
                26, "C", 27, "S", 28, "H", 29, "V", 30, "ohm", 31, "J", 32, "N", 33, "Hz", 34, "lx",
                35, "Lm", 36, "Wb", 37, "T", 38, "W", 39, "Pa", 41, "m²", 42, "m³", 43, "m/s",
                44, "m/s²", 45, "m³/s", 46, "m/m³", 47, "M", 48, "kg/m³", 49, "m²/s", 50, "W/m K",
                51, "J/K", 52, "ppm", 53, "1/s", 54, "rad/s", 55, "W/m²", 56, "J/m²", 57, "S/m",
                58, "K/s", 59, "Pa/s", 60, "J/kg K", 61, "VA", 62, "Watts", 63, "VAr", 64, "phi",
                65, "cos(phi)", 66, "Vs", 67, "V²", 68, "As", 69, "A²", 70, "A²t", 71, "VAh",
                72, "Wh", 73, "VArh", 74, "V/Hz", 75, "Hz/s", 76, "char", 77, "char/s",
                78, "kgm²", 79, "dB", 80, "J/Wh", 81, "W/s", 82, "l/s", 83, "dBm",
                // Ed2.1: horas y minutos (vistos en SCD reales de Siemens, OpTmh / TmOfsTmm)
                84, "h", 85, "min"};
        for (int i = 0; i < u.length; i += 2) SI_UNIT.put((Integer) u[i], (String) u[i + 1]);
    }

    /** Nombre de la clase de enumerado estándar que corresponde al atributo, o null. */
    static String claseEnumerado(String referencia, Fc fc) {
        String[] seg = referencia.split("\\.");
        if (seg.length < 2) return null;
        String da = seg[seg.length - 1];
        String obj = seg.length >= 2 ? seg[1] : "";          // el DO: "LD/LN.DO.…"
        switch (da) {
            case "ctlModel": return "CtlModelKind";
            case "sboClass": return "SboClassKind";
            case "orCat":    return "OriginatorCategoryKind";
            case "range":    return fc == Fc.MX ? "RangeKind" : null;
            case "multiplier": return "multiplicador SI";
            case "SIUnit":   return "unidad SI";
            case "dirGeneral": return "FaultDirectionKind";
            case "dirPhsA": case "dirPhsB": case "dirPhsC": case "dirNeut":
                return "PhaseFaultDirectionKind";
            case "stVal":
            case "ctlVal":
            case "subVal":
                if (obj.equals("Beh") || obj.equals("Mod")) return "BehaviourModeKind";
                if (obj.equals("Health") || obj.equals("PhyHealth") || obj.endsWith("Health")) return "HealthKind";
                return null;
            default: return null;
        }
    }

    /** "on (1)" para un enumerado estándar conocido; null si no lo es o el valor no está. */
    static String nombreEnumerado(String referencia, Fc fc, long valor) {
        String clase = claseEnumerado(referencia, fc);
        if (clase == null) return null;
        Map<Integer, String> m;
        switch (clase) {
            case "CtlModelKind": m = CTL_MODEL; break;
            case "SboClassKind": m = SBO_CLASS; break;
            case "OriginatorCategoryKind": m = OR_CAT; break;
            case "RangeKind": m = RANGE; break;
            case "multiplicador SI": m = MULTIPLIER; break;
            case "unidad SI": m = SI_UNIT; break;
            case "FaultDirectionKind": m = FAULT_DIR; break;
            case "PhaseFaultDirectionKind": m = PHASE_DIR; break;
            case "BehaviourModeKind": m = BEHAVIOUR; break;
            case "HealthKind": m = HEALTH; break;
            default: return null;
        }
        String nombre = m.get((int) valor);
        return nombre == null ? null : nombre + " (" + valor + ")";
    }

    /** ¿El valor existe en el enumerado estándar del atributo? null si no es uno conocido. */
    static Boolean valorPermitido(String referencia, Fc fc, long valor) {
        if (claseEnumerado(referencia, fc) == null) return null;
        return nombreEnumerado(referencia, fc, valor) != null;
    }

    /**
     * Tipo según IEC 61850-7-2/7-3 (por ejemplo "Check", "Dbpos", "ENUMERATED (HealthKind)",
     * "TimeStamp"). Corrige lo que el tipo MMS no distingue: un bit-string de 2 bits llamado
     * Check es Check aunque iec61850bean lo haya creado como Dbpos.
     */
    static String tipoNorma(BasicDataAttribute bda) {
        String ref = bda.getReference().toString();
        String da = ref.substring(ref.lastIndexOf('.') + 1);
        BdaType t = bda.getBasicType();
        if (t == null) return "";
        if ((t == BdaType.DOUBLE_BIT_POS || t == BdaType.CHECK) && da.equals("Check")) return "Check";
        String clase = claseEnumerado(ref, bda.getFc());
        if (clase != null && (t == BdaType.INT8 || t == BdaType.INT16 || t == BdaType.INT32
                || t == BdaType.INT8U)) {
            return "ENUMERATED (" + clase + ")";
        }
        switch (t) {
            case DOUBLE_BIT_POS: return "Dbpos";
            case TAP_COMMAND: return "Tcmd";
            case QUALITY: return "Quality";
            case TIMESTAMP: return "TimeStamp";
            case CHECK: return "Check";
            case TRIGGER_CONDITIONS: return "TriggerConditions";
            case OPTFLDS: return "OptFlds";
            case ENTRY_TIME: return "EntryTime";
            case OCTET_STRING: return "OCTET STRING";
            case VISIBLE_STRING: return "VISIBLE STRING";
            case UNICODE_STRING: return "UNICODE STRING";
            default: return t.toString();
        }
    }

    private static Map<Integer, String> mapa(Object... kv) {
        Map<Integer, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((Integer) kv[i], (String) kv[i + 1]);
        return m;
    }
}
