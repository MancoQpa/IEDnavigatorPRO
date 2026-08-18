package com.beanit.iec61850bean;

/**
 * Functional Constraints de IEC 61850-7-2 / -8-1.
 *
 * <p><b>Esta clase sombrea deliberadamente a com.beanit.iec61850bean.Fc de iec61850bean-1.9.0.</b>
 * El enum original solo declara 15 FCs. Cuando el IED devuelve en LLN0 un componente con una
 * FC que no esta en esa lista, Fc.fromString() devuelve null y la libreria revienta al
 * construir el LogicalNode:
 *
 * <pre>
 *   NullPointerException: Cannot invoke "Fc.toString()" because "fcDataObject.fc" is null
 *       at LogicalNode.&lt;init&gt;(LogicalNode.java:41)
 *       at DataDefinitionResParser.parseGetDataDefinitionResponse(DataDefinitionResParser.java:80)
 *       at ClientAssociation.decodeGetDataDefinitionResponse(ClientAssociation.java:781)
 *       at ClientAssociation.retrieveDataDefinitions(ClientAssociation.java:755)
 *       at ClientAssociation.retrieveModel(ClientAssociation.java:609)
 * </pre>
 *
 * <p>El efecto es que se pierde LLN0 entero y con el los DataSets, RCBs y el SGCB.
 * Verificado contra un ZIV 2IRX-A3N en 192.168.1.81 (2026-08-12): de 136 LNs solo fallaba
 * LLN0, y la FC responsable resulto ser 'SC' (capturada instrumentando fromString con
 * -Died.debug.fc=true, que sigue disponible para diagnosticar otros equipos). Con SC
 * agregada, retrieveModel() completa al primer intento y LLN0 entrega 7 URCBs, 7 BRCBs
 * y el SGCB.
 *
 * <p>Ojo: DataDefinitionResParser ya descarta por su cuenta LG, GO, GS, MS y US antes de
 * llamar a fromString, asi que esas cinco nunca fueron la causa del fallo.
 *
 * <p>Los 15 valores originales conservan su orden y por tanto su ordinal; los nuevos se
 * agregan al final. La clase se resuelve antes que la del jar porque el classpath de
 * ejecucion es "classes;lib\*.jar" (ver IEDNavigatorPRO.bat) y porque maven-assembly-plugin
 * da precedencia a las clases del proyecto sobre las de las dependencias.
 *
 * <p>Al actualizar iec61850bean, comprobar si el enum upstream ya incluye estos valores;
 * si es asi, borrar este archivo.
 */
public enum Fc {

    // ---- Los 15 originales de iec61850bean 1.9.0, en su orden original ----
    /** Status information. */
    ST,
    /** Measurands. */
    MX,
    /** Setpoint. */
    SP,
    /** Substitution. */
    SV,
    /** Configuration. */
    CF,
    /** Description. */
    DC,
    /** Setting group. */
    SG,
    /** Setting group editable. */
    SE,
    /** Service response. */
    SR,
    /** Operate received. */
    OR,
    /** Blocking. */
    BL,
    /** Extended definition. */
    EX,
    /** Control. */
    CO,
    /** Unbuffered report. */
    RP,
    /** Buffered report. */
    BR,

    // ---- El agregado que resuelve el fallo del ZIV ----
    /**
     * SCL Control Block. FC propietaria de ZIV, no normalizada en IEC 61850-8-1: en LLN0
     * aparece el nodo 'sclcb' con FC=SC, que el equipo usa para gestionar su propio CID.
     * No esta en el enum de iec61850bean ni en la lista de FCs que DataDefinitionResParser
     * descarta, asi que fromString() devolvia null y la construccion del LogicalNode reventaba.
     * (El SGCB de Setting Groups es aparte y usa FC=SP, la estandar.)
     */
    SC,

    // ---- Presentes en IEC 61850-8-1 pero ausentes del enum upstream ----
    // Nota: en el camino del cliente estas cinco NO llegan a fromString(), porque
    // DataDefinitionResParser las filtra antes por comparacion de cadena. Se agregan por
    // correccion del enum y para codigo propio que resuelva FCs desde SCL o desde la GUI.
    /** GOOSE control block. */
    GO,
    /** Log control block. */
    LG,
    /** Multicast sampled value control block. */
    MS,
    /** Unicast sampled value control block. */
    US,
    /** GSSE control block (Edicion 1). */
    GS;

    /**
     * Devuelve la constante correspondiente, o null si la cadena no es una FC conocida.
     * Mismo contrato que el metodo original de iec61850bean.
     */
    public static Fc fromString(String fc) {
        try {
            return valueOf(fc);
        } catch (Exception e) {
            if (DEBUG_UNKNOWN) {
                System.out.println("[Fc] DESCONOCIDA: '" + fc + "' len=" + (fc == null ? -1 : fc.length())
                        + " bytes=" + toHex(fc));
            }
            return null;
        }
    }

    private static final boolean DEBUG_UNKNOWN = Boolean.getBoolean("ied.debug.fc");

    private static String toHex(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) sb.append(String.format("%02X ", (int) c));
        return sb.toString().trim();
    }
}
