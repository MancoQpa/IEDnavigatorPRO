package com.iednavigator;

import com.beanit.iec61850bean.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tipos de la norma para los nodos del modelo: el CDC de un DO (IEC 61850-7-3), el tipo de un
 * DA construido y el tipo básico de un DA (IEC 61850-7-2), en lugar del nombre de la clase de
 * Java de iec61850bean ("BdaInt8", "FcDataObject"), que no existe en la norma.
 *
 * MMS no transmite el CDC: se deduce de los DA que tiene el DO. Como iec61850bean parte cada
 * DO en una variante por FC (Beh [ST], Beh [DC], …), la deducción junta todas las variantes.
 * La misma regla la usa {@link SclExporter} para reconstruir el CID, así que lo que muestra el
 * explorador y lo que se exporta coinciden.
 */
final class TiposIec61850 {

    private TiposIec61850() { }

    /** bType de SCL para un tipo básico ("BOOLEAN", "Dbpos", "VisString255"…); "Struct" si no hay. */
    static String bType(BdaType t) {
        if (t == null) return "Struct";
        switch (t) {
            case BOOLEAN:        return "BOOLEAN";
            case INT8:           return "INT8";
            case INT16:          return "INT16";
            case INT32:          return "INT32";
            case INT64:          return "INT64";
            case INT128:         return "INT128";
            case INT8U:          return "INT8U";
            case INT16U:         return "INT16U";
            case INT32U:         return "INT32U";
            case FLOAT32:        return "FLOAT32";
            case FLOAT64:        return "FLOAT64";
            case OCTET_STRING:   return "Octet64";
            case VISIBLE_STRING: return "VisString255";
            case UNICODE_STRING: return "Unicode255";
            case TIMESTAMP:      return "Timestamp";
            case ENTRY_TIME:     return "EntryTime";
            case CHECK:          return "Check";
            case QUALITY:        return "Quality";
            case DOUBLE_BIT_POS: return "Dbpos";
            case TAP_COMMAND:    return "Tcmd";
            case OPTFLDS:            return "OptFlds";
            case TRIGGER_CONDITIONS: return "TrgOps";
            case REASON_FOR_INCLUSION: return "ReasonForInclusion";
            default:             return "Struct";
        }
    }

    /**
     * Infiere el CDC (IEC 61850-7-3) por la forma del DO.
     *
     * @param das       nombre de cada DA del DO (todas sus variantes por FC) → su bType
     *                  ({@link #bType}); "Struct" para los construidos
     * @param sdos      nombres de los sub data objects
     * @param ctlValTipo bType del ctlVal (suelto o dentro de Oper/SBOw), o null
     * @param doName    nombre del DO, solo para desempatar placas (PhyNam es DPL); puede ser null
     * @return el CDC, o null si la forma no encaja en ningún patrón
     */
    static String inferirCdc(Map<String, String> das, Set<String> sdos, String ctlValTipo, String doName) {
        // 0. Seguimiento de servicios (IEC 61850-7-2 Ed.2, FC SR). Va primero: el de mando
        // (CTS) trae ctlVal y se confundía con un controlable.
        if (das.containsKey("objRef") && das.containsKey("serviceType")) {
            if (das.containsKey("respAddCause") || das.containsKey("ctlVal")) return "CTS";
            if (das.containsKey("svEna")) return das.containsKey("resv") ? "NTS" : "MTS";
            if (das.containsKey("purgeBuf") || das.containsKey("entryID"))   return "BTS";
            if (das.containsKey("resv") || das.containsKey("rptID"))         return "UTS";
            if (das.containsKey("logEna"))                                    return "LTS";
            if (das.containsKey("goEna"))                                     return "GTS";
            if (das.containsKey("numOfSG") || das.containsKey("actSG"))      return "STS";
            return "CST";
        }

        // 1. Agregados cuyos miembros son sub data objects
        if (das.containsKey("seqT")) return "SEQ";                       // SEQ: seqT + c1/c2/c3
        if (sdos.contains("phsA") || sdos.contains("phsB") || sdos.contains("phsC")) return "WYE";
        if (sdos.contains("phsAB") || sdos.contains("phsBC") || sdos.contains("phsCA")) return "DEL";
        if (sdos.contains("neut") || sdos.contains("net") || sdos.contains("res")) return "WYE";
        // Por MMS no se distingue un SDO de un DA: en modo Cliente sin SCL las fases llegan como
        // estructuras (ION 7400: PhV, A, PPV quedaban sin CDC). Un ACT también trae phsA, pero
        // booleano, así que no se confunde.
        if ("Struct".equals(das.get("phsA")) || "Struct".equals(das.get("phsB")) || "Struct".equals(das.get("phsC"))) return "WYE";
        if ("Struct".equals(das.get("phsAB")) || "Struct".equals(das.get("phsBC")) || "Struct".equals(das.get("phsCA"))) return "DEL";

        String stVal = das.get("stVal");
        boolean stDbpos = "Dbpos".equals(stVal);

        // 2. Controlables. Con ctlModel alcanza: un DO status-only sigue siendo de una clase
        // controlable (ENC/SPC/DPC), solo que no expone Oper.
        boolean controlable = das.containsKey("Oper") || das.containsKey("SBOw")
                || das.containsKey("ctlVal") || das.containsKey("ctlModel");
        if (controlable) {
            String t = ctlValTipo;
            // Analógicos: mxVal con ctlVal AnalogueValue (APC) o Tcmd (BAC)
            if (das.containsKey("mxVal")) return "Tcmd".equals(t) ? "BAC" : "APC";
            // Posición de toma: valWTr con ctlVal Tcmd (BSC) o entero (ISC)
            if (das.containsKey("valWTr")) return "Tcmd".equals(t) ? "BSC" : "ISC";
            if (t == null || "Struct".equals(t)) t = stVal;
            if ("Dbpos".equals(t))                          return "DPC";
            if ("BOOLEAN".equals(t))                        return stDbpos ? "DPC" : "SPC";
            // En SCL los enumerados son bType="Enum", pero por MMS llegan como INT8; los
            // enteros propiamente dichos usan INT32. De ahí ENC (enum) frente a INC (entero).
            if ("INT8".equals(t) || "INT16".equals(t))      return "ENC";
            if ("INT32".equals(t) || "INT32U".equals(t))    return "INC";
            if ("FLOAT32".equals(t) || "FLOAT64".equals(t)) return "APC";
            if ("Tcmd".equals(t))                           return "BSC";
            if (stDbpos)                                    return "DPC";
            return "SPC";
        }

        // 3. Ajustes (antes que las medidas: un ASG también trae sVC, units y minVal)
        if (das.containsKey("setCal") || das.containsKey("setTm")) return "TSG";
        if (das.containsKey("setSrcRef") || das.containsKey("setRef")) return "ORG";   // setRef: Ed1
        if (das.containsKey("crvPts"))     return "CSG";
        if (das.containsKey("setMag"))     return "ASG";
        if (das.containsKey("setCharact")) return "CURVE";
        String setVal = das.get("setVal");
        if (setVal != null) {
            if ("BOOLEAN".equals(setVal))                          return "SPG";
            if ("FLOAT32".equals(setVal) || "FLOAT64".equals(setVal)) return "ASG";
            if ("INT8".equals(setVal) || "INT16".equals(setVal))   return "ENG";   // enumerado
            if (setVal.startsWith("INT"))                          return "ING";
            if (setVal.startsWith("VisString"))                    return "VSG";
            return "SPG";
        }

        // 4. Medidas. Armónicos: HWYE (phsAHar…), HDEL (phsABHar…), HMV (har).
        if (das.containsKey("phsAHar") || das.containsKey("phsBHar") || das.containsKey("phsCHar")) return "HWYE";
        if (das.containsKey("phsABHar") || das.containsKey("phsBCHar") || das.containsKey("phsCAHar")) return "HDEL";
        if (das.containsKey("har")) return "HMV";
        // SAV trae instMag pero no mag (un MV también puede traer sVC).
        if (das.containsKey("instMag") && !das.containsKey("mag")) return "SAV";
        if (das.containsKey("mag"))                                return "MV";
        if (das.containsKey("cVal") || das.containsKey("instCVal")) return "CMV";
        if (das.containsKey("actVal") || das.containsKey("frVal"))  return "BCR";

        // 5. Arranques y disparos. Str es ACD por IEC 61850-7-4 aunque no traiga dirección.
        if (das.containsKey("general") && "Str".equals(doName)) return "ACD";
        if (das.containsKey("general")
                && (das.containsKey("dirGeneral") || das.containsKey("dirPhsA"))) return "ACD";
        if (das.containsKey("general")) return "ACT";
        if (stVal == null && ("BOOLEAN".equals(das.get("phsA")) || "BOOLEAN".equals(das.get("phsAB")))) return "ACT";

        // 6. Estados simples. Mod y Pos son controlables por IEC 61850-7-4 aunque el IED no
        // exponga ctlModel (status-only sin FC CF).
        if ("Mod".equals(doName) && ("INT8".equals(stVal) || "INT16".equals(stVal))) return "ENC";
        if ("Pos".equals(doName) && stDbpos) return "DPC";
        if (stVal != null) {
            if (stDbpos)                                         return "DPS";
            if ("BOOLEAN".equals(stVal))                         return "SPS";
            if ("INT8".equals(stVal) || "INT16".equals(stVal))   return "ENS";   // enumerado
            if ("INT32".equals(stVal) || "INT32U".equals(stVal)) return "INS";
            if (stVal.startsWith("VisString"))                   return "VSS";
            return "INS";
        }

        // 7. Placas: DPL (equipo físico) antes que LPL (nodo lógico)
        if (das.containsKey("hwRev") || das.containsKey("serNum")
                || das.containsKey("model") || das.containsKey("location")) return "DPL";
        if ("PhyNam".equals(doName)) return "DPL";
        if (das.containsKey("configRev") || das.containsKey("ldNs")
                || das.containsKey("lnNs") || das.containsKey("vendor")
                || das.containsKey("swRev")) return "LPL";

        // 8. Descripción suelta
        if (das.size() == 1 && das.containsKey("val")) return "VSD";

        return null;
    }

    /** Las variantes por FC de un DO (o de un SDO), en el orden del modelo. */
    static List<FcDataObject> variantes(FcDataObject d) {
        List<FcDataObject> out = new ArrayList<>();
        ModelNode padre = d.getParent();
        if (padre instanceof LogicalNode) {
            for (ModelNode h : padre.getChildren()) {
                if (h instanceof FcDataObject && h.getName().equals(d.getName())) out.add((FcDataObject) h);
            }
        } else if (padre instanceof FcDataObject) {
            for (FcDataObject v : variantes((FcDataObject) padre)) {
                ModelNode h = v.getChild(d.getName());
                if (h instanceof FcDataObject) out.add((FcDataObject) h);
            }
        }
        if (out.isEmpty()) out.add(d);
        return out;
    }

    /** CDC de un DO del modelo (juntando sus variantes por FC), o null si no se puede deducir. */
    static String cdcDe(FcDataObject d) {
        // Un CDC que no es de la norma se declara en EX.cdcName (IEC 61850-7-1 Ed2)
        for (FcDataObject v : variantes(d)) {
            ModelNode n = v.getChild("cdcName");
            if (n instanceof BdaVisibleString) {
                String c = ((BdaVisibleString) n).getStringValue();
                if (c != null && !c.trim().isEmpty()) return c.trim();
            }
        }
        Map<String, String> das = new LinkedHashMap<>();
        Set<String> sdos = new LinkedHashSet<>();
        String ctlVal = null;
        for (FcDataObject v : variantes(d)) {
            if (v.getChildren() == null) continue;
            for (ModelNode h : v.getChildren()) {
                if (h instanceof FcDataObject) { sdos.add(h.getName()); continue; }
                das.putIfAbsent(h.getName(), bTypeDe(h));
                if ("ctlVal".equals(h.getName()) && h instanceof BasicDataAttribute) {
                    ctlVal = bTypeDe(h);
                } else if (ctlVal == null && ("Oper".equals(h.getName()) || "SBOw".equals(h.getName()))) {
                    ModelNode c = h.getChild("ctlVal");
                    if (c != null) ctlVal = bTypeDe(c);
                }
            }
        }
        return inferirCdc(das, sdos, ctlVal, d.getName());
    }

    private static String bTypeDe(ModelNode n) {
        if (n instanceof Array && ((Array) n).size() > 0) n = ((Array) n).getChild(0);
        return n instanceof BasicDataAttribute ? bType(((BasicDataAttribute) n).getBasicType()) : "Struct";
    }

    /** FC presentes de un DO, "ST, DC, EX". */
    static String fcsDe(FcDataObject d) {
        List<String> fcs = new ArrayList<>();
        for (FcDataObject v : variantes(d)) fcs.add(String.valueOf(v.getFc()));
        return String.join(", ", fcs);
    }

    /**
     * Tipo de un DA construido según IEC 61850-7-3 / 7-2 (AnalogueValue, Vector, Unit,
     * Originator…), o null si el nombre no es uno conocido.
     */
    static String tipoConstruido(ConstructedDataAttribute da) {
        switch (da.getName()) {
            case "mag": case "instMag": case "subMag": case "setMag": case "minVal": case "maxVal":
            case "stepSize": case "hhLim": case "hLim": case "lLim": case "llLim": case "min": case "max":
            case "mxVal": case "smpRate": case "db":
                return da.getChild("f") != null || da.getChild("i") != null ? "AnalogueValue" : null;
            case "cVal": case "instCVal": case "subCVal":
                return "Vector";
            case "units":
                return "Unit";
            case "sVC":
                return "ScaledValueConfig";
            case "rangeC":
                return "RangeConfig";
            case "origin":
                return "Originator";
            case "Oper":
                return "Operate";
            case "SBOw":
                return "SelectWithValue";
            case "Cancel":
                return "Cancel";
            case "pulseConfig":
                return "PulseConfig";
            case "valWTr":
                return "ValWithTrans";
            case "ang":
                return "AnalogueValue";
            default:
                return null;
        }
    }

    /**
     * El tipo de cualquier nodo, en términos de la norma, para mostrar:
     * "LN XCBR", "CDC ENS", "AnalogueValue", "ENUMERATED (BehaviourModeKind)", "Quality"…
     */
    static String tipoDe(ModelNode n) {
        if (n instanceof LogicalDevice) return "LD";
        if (n instanceof LogicalNode) {
            String cls = Iec61850Dictionary.inferLnClass(n.getName());
            if (cls == null && n.getName().startsWith("LLN0")) cls = "LLN0";
            return cls != null ? "LN " + cls : "LN";
        }
        if (n instanceof FcDataObject) {
            String cdc = cdcDe((FcDataObject) n);
            return cdc != null ? "CDC " + cdc : "DO";
        }
        if (n instanceof Array) {
            Array a = (Array) n;
            String elem = a.size() > 0 ? tipoDe(a.getChild(0)) : "?";
            return "ARRAY [" + a.size() + "] OF " + elem;
        }
        if (n instanceof ConstructedDataAttribute) {
            String t = tipoConstruido((ConstructedDataAttribute) n);
            return t != null ? t : "STRUCT";
        }
        if (n instanceof BasicDataAttribute) return CatalogoIec61850.tipoNorma((BasicDataAttribute) n);
        return "";
    }

    /** Lo mismo que {@link #tipoDe} pero sin el prefijo "CDC "/"LN " (para columnas angostas). */
    static String tipoCorto(ModelNode n) {
        String t = tipoDe(n);
        if (t.startsWith("CDC ")) return t.substring(4);
        return t;
    }
}
