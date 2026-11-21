package com.iednavigator;

import com.beanit.iec61850bean.*;
import com.beanit.iec61850bean.Fc;
import com.beanit.iec61850bean.ModelNode;
import com.beanit.iec61850bean.ServerModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Lógica de sincronización GOOSE ↔ modelo de servidor, sin dependencias Swing.
 * Extraída de GoosePanel/IEDNavigatorApp para que el bridge headless y la GUI
 * Swing compartan el mismo comportamiento (plan Fase 5).
 */
public final class GooseModelSync {

    private GooseModelSync() {}

    /**
     * Tipo GOOSE de un atributo del modelo, por su clase en iec61850bean: es el tipo real que
     * define el SCL. null si no hay una codificacion GOOSE para el (el llamador cae en
     * {@link #inferDataType}).
     */
    public static GoosePublisher.DataValue.Type tipoDeAtributo(BasicDataAttribute bda) {
        if (bda instanceof BdaBoolean) return GoosePublisher.DataValue.Type.BOOLEAN;
        if (bda instanceof BdaDoubleBitPos) return GoosePublisher.DataValue.Type.DBPOS;
        if (bda instanceof BdaQuality) return GoosePublisher.DataValue.Type.BITSTRING;
        if (bda instanceof BdaTimestamp) return GoosePublisher.DataValue.Type.UTC_TIME;
        if (bda instanceof BdaFloat32 || bda instanceof BdaFloat64) return GoosePublisher.DataValue.Type.FLOAT;
        if (bda instanceof BdaInt8 || bda instanceof BdaInt16 || bda instanceof BdaInt32 || bda instanceof BdaInt64)
            return GoosePublisher.DataValue.Type.INTEGER;
        if (bda instanceof BdaInt8U || bda instanceof BdaInt16U || bda instanceof BdaInt32U)
            return GoosePublisher.DataValue.Type.UNSIGNED;
        if (bda instanceof BdaVisibleString || bda instanceof BdaUnicodeString)
            return GoosePublisher.DataValue.Type.VISIBLE_STRING;
        return null;
    }

    /**
     * Tipo GOOSE de un miembro de DataSet: el del atributo en el modelo si se encuentra; si no,
     * el que se deduce del nombre.
     */
    public static GoosePublisher.DataValue.Type tipoDeMiembro(String member, ServerModel model, String iedName) {
        if (model != null) {
            String ref = SclReferenceUtils.buildModelRefFromFCDA(member, iedName);
            Fc fc = SclReferenceUtils.extractFcFromMember(member);
            ModelNode n = ref == null || fc == null ? null : model.findModelNode(ref, fc);
            if (n instanceof BasicDataAttribute) {
                GoosePublisher.DataValue.Type t = tipoDeAtributo((BasicDataAttribute) n);
                if (t != null) return t;
            }
        }
        return inferDataType(member);
    }

    /**
     * Tipo deducido del nombre del miembro FCDA, cuando no hay modelo. El orden importa: la
     * regla de stVal booleano iba antes que la de Pos y una posicion doble (Pos.stVal) salia
     * como BOOLEAN (2026-11-21, visto en la trama de un interruptor virtual).
     */
    public static GoosePublisher.DataValue.Type inferDataType(String memberName) {
        String lower = memberName.toLowerCase();
        int corchete = lower.indexOf('[');
        String ref = (corchete > 0 ? lower.substring(0, corchete) : lower).trim();

        if (ref.endsWith(".q")) return GoosePublisher.DataValue.Type.BITSTRING;
        if (ref.endsWith(".t")) return GoosePublisher.DataValue.Type.UTC_TIME;
        // Posiciones dobles (DPC/DPS): Pos, PosA/PosB/PosC
        if (ref.matches(".*\\.pos[abc]?\\.(stval|ctlval)$")) return GoosePublisher.DataValue.Type.DBPOS;
        // Enumerados y enteros de estado frecuentes (Loc y LocKey son booleanos)
        if (ref.matches(".*\\.(beh|mod|health|phyhealth|eehealth|opcnt|cbopcap|swopcap|tappos)\\.stval$")) {
            return GoosePublisher.DataValue.Type.INTEGER;
        }
        if (ref.endsWith(".general") || ref.endsWith(".stval") || ref.matches(".*\\.(op|str)(\\..*)?$")) {
            return GoosePublisher.DataValue.Type.BOOLEAN;
        }
        if (ref.contains(".mag") || ref.contains(".instmag") || ref.contains(".cval")) {
            return GoosePublisher.DataValue.Type.FLOAT;
        }
        if (ref.endsWith(".ctlval") || ref.endsWith(".setval") || ref.endsWith(".actval")) {
            return GoosePublisher.DataValue.Type.INTEGER;
        }
        return GoosePublisher.DataValue.Type.BOOLEAN;
    }

    /** Valor por defecto para un tipo de DataValue GOOSE. */
    public static Object defaultValueForType(GoosePublisher.DataValue.Type type) {
        switch (type) {
            case BOOLEAN: return false;
            case INTEGER: return 0;
            case UNSIGNED: return (long) (System.currentTimeMillis() / 1000);
            case FLOAT: return 0.0f;
            case BITSTRING: return 0;
            case DBPOS: return 1;
            case VISIBLE_STRING: return "";
            case UTC_TIME: return GoosePublisher.utcAhora();
            default: return false;
        }
    }

    /** Construye los DataValues iniciales de un GoCB a partir de su DataSet SCL. */
    public static List<GoosePublisher.DataValue> buildDataValuesFromDataSet(
            SclGoCB gcb, List<SclDataSet> dataSets) {
        List<GoosePublisher.DataValue> values = new ArrayList<>();
        if (gcb.datSet == null) return values;

        SclDataSet ds = SclReferenceUtils.findDataSetForGoCB(gcb, dataSets);
        if (ds == null || ds.members.isEmpty()) return values;

        for (String member : ds.members) {
            String name = member;
            int bracketIdx = member.lastIndexOf('[');
            if (bracketIdx > 0) {
                name = member.substring(0, bracketIdx).trim();
            }
            GoosePublisher.DataValue.Type type = inferDataType(member);
            values.add(new GoosePublisher.DataValue(name, type, defaultValueForType(type)));
        }
        return values;
    }

    /**
     * Crea y configura un GoosePublisher para un GoCB del SCL
     * (gocbRef, goId, datSet, appId, confRev, MAC destino, VLAN, heartbeat
     * y DataValues iniciales). No registra listeners ni lo inicializa.
     */
    public static GoosePublisher configurePublisher(SclGoCB gcb, int gcbIndex,
                                                    List<SclDataSet> dataSets) {
        GoosePublisher pub = new GoosePublisher();

        pub.setGocbRef(gcb.ldInst + "/LLN0$GO$" + gcb.cbName);
        pub.setGoId(gcb.goID != null && !gcb.goID.isEmpty() ? gcb.goID : gcb.cbName);
        pub.setDatSet(gcb.ldInst + "/LLN0$" + (gcb.datSet != null ? gcb.datSet : "DataSet1"));

        if (gcb.appID != null && !gcb.appID.isEmpty()) {
            try {
                pub.setAppId(Integer.parseInt(gcb.appID, 16));
            } catch (NumberFormatException e) {
                try {
                    pub.setAppId(Integer.parseInt(gcb.appID));
                } catch (NumberFormatException e2) {
                    pub.setAppId(0x0001 + gcbIndex);
                }
            }
        } else {
            pub.setAppId(0x0001 + gcbIndex);
        }

        if (gcb.confRev > 0) pub.setConfRev(gcb.confRev);

        if (gcb.macAddress != null && !gcb.macAddress.isEmpty()) {
            try {
                pub.setDstMac(gcb.macAddress.replace(":", "-").replace(".", "-"));
            } catch (Exception ignore) {
                // MAC inválida en el SCL: se mantiene la MAC GOOSE por defecto
            }
        }

        if (gcb.vlanId >= 0) pub.setVlan(gcb.vlanId, gcb.vlanPriority);
        if (gcb.maxTime > 0) pub.setHeartbeatInterval(gcb.maxTime);

        List<GoosePublisher.DataValue> dataValues = buildDataValuesFromDataSet(gcb, dataSets);
        if (!dataValues.isEmpty()) {
            pub.setDataValues(dataValues);
        }
        return pub;
    }

    /** Convierte un DataValue del publisher a string para el modelo de servidor. */
    public static String convertPublisherValueToString(GoosePublisher.DataValue dv) {
        // La hora la maneja el publicador (la de su ultimo cambio): no se escribe en el modelo
        if (dv.type == GoosePublisher.DataValue.Type.UTC_TIME) return null;
        if (dv.value == null) return "0";

        switch (dv.type) {
            case BOOLEAN:
                return Boolean.TRUE.equals(dv.value) ? "true" : "false";
            case DBPOS:
                int dbpos = ((Number) dv.value).intValue();
                switch (dbpos) {
                    case 0: return "INTERMEDIATE_STATE";
                    case 1: return "OFF";
                    case 2: return "ON";
                    case 3: return "BAD_STATE";
                    default: return String.valueOf(dbpos);
                }
            default:
                return String.valueOf(dv.value);
        }
    }

    /**
     * GOOSE → modelo: escribe el valor actual del publisher (índice dataIndex)
     * en el modelo del servidor simulado.
     *
     * @return la referencia del modelo escrita, o null si no se pudo escribir.
     */
    public static String syncPublisherToServerModel(IEC61850Server server, String iedName,
                                                    SclGoCB gcb, List<SclDataSet> dataSets,
                                                    GoosePublisher pub, int dataIndex) {
        if (server == null || server.getServerModel() == null) return null;
        SclDataSet ds = SclReferenceUtils.findDataSetForGoCB(gcb, dataSets);
        if (ds == null || dataIndex >= ds.members.size()) return null;

        List<GoosePublisher.DataValue> pubValues = pub.getDataValues();
        if (dataIndex >= pubValues.size()) return null;

        String modelRef = SclReferenceUtils.buildModelRefFromFCDA(ds.members.get(dataIndex), iedName);
        if (modelRef == null) return null;

        String strValue = convertPublisherValueToString(pubValues.get(dataIndex));
        if (strValue == null) return null;
        return server.setDataValue(modelRef, strValue) ? modelRef : null;
    }

    /**
     * Modelo → GOOSE: refresca los DataValues de un publisher desde el modelo
     * del servidor.
     *
     * @return true si algún valor cambió (el llamador debe publicar el cambio
     *         de estado con publishStateChange()).
     */
    public static boolean propagateModelToPublisher(ServerModel model, String iedName,
                                                    SclGoCB gcb, List<SclDataSet> dataSets,
                                                    GoosePublisher pub) {
        if (model == null) return false;
        SclDataSet ds = SclReferenceUtils.findDataSetForGoCB(gcb, dataSets);
        if (ds == null) return false;

        boolean changed = false;
        List<GoosePublisher.DataValue> pubValues = pub.getDataValues();
        for (int i = 0; i < ds.members.size() && i < pubValues.size(); i++) {
            String member = ds.members.get(i);
            String modelRef = SclReferenceUtils.buildModelRefFromFCDA(member, iedName);
            Fc fc = SclReferenceUtils.extractFcFromMember(member);
            if (modelRef == null || fc == null) continue;
            ModelNode node = model.findModelNode(modelRef, fc);
            if (node instanceof BasicDataAttribute) {
                BasicDataAttribute bda = (BasicDataAttribute) node;
                Object newVal = SclReferenceUtils.convertBdaToPublisherValue(bda, pubValues.get(i).type);
                if (newVal != null && !newVal.equals(pubValues.get(i).value)) {
                    pub.setDataValue(i, newVal);
                    changed = true;
                }
            }
        }
        return changed;
    }
}
