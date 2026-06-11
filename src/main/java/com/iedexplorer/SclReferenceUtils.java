package com.iedexplorer;

import com.beanit.iec61850bean.*;
import java.util.List;

/**
 * Fase 14: Utilidades estáticas para manejo de referencias FCDA e IEC 61850.
 * Extraído de IEDExplorerApp.java — sección GOOSE-MODEL SYNC.
 *
 * Nota: buildModelRefFromFCDA() también existe en GoosePanel — esta versión
 * acepta loadedIedName como parámetro explícito para ser independiente de estado de instancia.
 */
class SclReferenceUtils {

    private SclReferenceUtils() {}

    /**
     * Construye una referencia de modelo iec61850bean a partir de un string FCDA.
     * FCDA format:  "ldInst/prefixLNClassInst.doName.daName [FC]"
     * Model ref:    "IEDNameldInst/prefixLNClassInst.doName.daName"
     */
    static String buildModelRefFromFCDA(String member, String loadedIedName) {
        int bracket = member.lastIndexOf('[');
        String clean = bracket > 0 ? member.substring(0, bracket).trim() : member.trim();
        if (clean.isEmpty()) return null;

        if (loadedIedName != null) {
            int slash = clean.indexOf('/');
            if (slash > 0) {
                String ldInst = clean.substring(0, slash);
                String rest   = clean.substring(slash + 1);
                return loadedIedName + ldInst + "/" + rest;
            }
        }
        return clean;
    }

    /**
     * Extrae el Functional Constraint de un string FCDA con sufijo "[FC]".
     * Retorna Fc.ST si no se puede parsear.
     */
    static Fc extractFcFromMember(String member) {
        int open  = member.lastIndexOf('[');
        int close = member.lastIndexOf(']');
        if (open >= 0 && close > open) {
            String fcStr = member.substring(open + 1, close).trim();
            try { return Fc.valueOf(fcStr); }
            catch (Exception e) { return Fc.ST; }
        }
        return Fc.ST;
    }

    /**
     * Convierte el valor de un BasicDataAttribute al tipo esperado por el GOOSE publisher.
     */
    static Object convertBdaToPublisherValue(BasicDataAttribute bda, GoosePublisher.DataValue.Type targetType) {
        String val = bda.getValueString();
        if (val == null) return null;

        switch (targetType) {
            case BOOLEAN:
                return val.equalsIgnoreCase("true") || val.equals("1");
            case DBPOS:
                String lower = val.toLowerCase();
                if (lower.contains("on")  || lower.equals("2")) return 2;
                if (lower.contains("off") || lower.equals("1")) return 1;
                if (lower.contains("bad") || lower.equals("3")) return 3;
                return 0; // intermediate
            case INTEGER:
            case UNSIGNED:
                try { return Integer.parseInt(val); }
                catch (NumberFormatException e) { return 0; }
            case FLOAT:
                try { return Float.parseFloat(val); }
                catch (NumberFormatException e) { return 0.0f; }
            case BITSTRING:
                try { return Integer.parseInt(val); }
                catch (NumberFormatException e) { return 0; }
            default:
                return val;
        }
    }

    /**
     * Encuentra el SclDataSet asociado a un GoCB en la lista de DataSets.
     */
    static SclDataSet findDataSetForGoCB(SclGoCB gcb, List<SclDataSet> sclDataSets) {
        for (SclDataSet ds : sclDataSets) {
            if (ds.name != null && ds.name.equals(gcb.datSet)
                    && (ds.ldInst == null || ds.ldInst.equals(gcb.ldInst))) {
                return ds;
            }
        }
        return null;
    }
}
