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
 * <h2>Por que fromString() no devuelve null</h2>
 *
 * <p>Agregar SC arreglaba el ZIV y dejaba el mecanismo intacto: cualquier otro equipo con una
 * FC propietaria distinta perdia su Logical Node del mismo modo. Se verifico en el bytecode de
 * DataDefinitionResParser que el resultado de fromString() se pasa directo a
 * getFcDataObjectsFromSubStructure() <b>sin ningun chequeo de null</b>, asi que todo el defecto
 * pasa por el contrato de este metodo — que es nuestro.
 *
 * <p>Por eso fromString() <b>nunca devuelve null</b>: lo desconocido cae en {@link #UNKNOWN}.
 * El nodo propietario queda bajo una FC que no es la suya y por lo tanto no se podra leer, pero
 * <b>el resto del Logical Node se conserva</b>. En el ZIV la diferencia era perder LLN0 entero
 * —7 URCB, 7 BRCB, los DataSets y el SGCB— por un unico nodo no reconocido.
 *
 * <p>Cada FC desconocida se reporta una sola vez, con su cadena exacta, para poder agregarla
 * como constante propia mas adelante. El aviso va a consola y, si la aplicacion registro un
 * listener con {@link #setUnknownFcListener}, tambien al registro de sesion: un diagnostico que
 * solo va a consola es invisible para quien corre el .exe.
 *
 * <p><b>Este archivo no es transitorio.</b> iec61850bean tiene una sola version publicada en
 * Maven Central, 1.9.0, con ultimo cambio del 30-06-2020: no hay una version futura que
 * incorpore estos valores. El parche es el estado final, no una espera.
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
    GS,

    // ---- Reserva para lo que no esta en ninguna de las listas de arriba ----
    /**
     * Cajon de las FC no reconocidas. No es una FC del estandar y no viaja al equipo: existe
     * para que {@link #fromString} nunca devuelva null y la libreria no reviente construyendo
     * el Logical Node. Un nodo que caiga aca se ve en el modelo pero no se puede leer, porque
     * su FC real se perdio; el aviso de fromString() dice cual era, para agregarla como
     * constante propia si vuelve a aparecer.
     */
    UNKNOWN;

    /**
     * Devuelve la constante correspondiente. <b>Nunca devuelve null</b>: lo que no reconoce
     * cae en {@link #UNKNOWN}.
     *
     * <p>Se aparta a proposito del contrato del metodo original, que devolvia null. Ver la
     * nota de la clase: el null llegaba sin filtrar hasta la construccion del LogicalNode y se
     * llevaba el nodo logico entero.
     */
    public static Fc fromString(String fc) {
        try {
            return valueOf(fc);
        } catch (Exception e) {
            reportarDesconocida(fc);
            return UNKNOWN;
        }
    }

    // ── Aviso de FC desconocida ──────────────────────────────────────────────
    // Una vez por cadena distinta: esto se llama desde el bucle de construccion del modelo y
    // un equipo con la misma FC en veinte nodos inundaria el registro.
    private static final java.util.Set<String> YA_AVISADAS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static volatile java.util.function.Consumer<String> unknownFcListener;
    private static final boolean DEBUG_UNKNOWN = Boolean.getBoolean("ied.debug.fc");

    /**
     * Registra a quien avisarle de una FC desconocida, ademas de la consola. La aplicacion lo
     * engancha a su panel de Log al arrancar. Es un hook y no una llamada directa para que
     * esta clase no dependa en compilacion del codigo de la aplicacion: sigue siendo un
     * reemplazo directo del enum de la libreria.
     */
    public static void setUnknownFcListener(java.util.function.Consumer<String> listener) {
        unknownFcListener = listener;
    }

    private static void reportarDesconocida(String fc) {
        String clave = String.valueOf(fc);
        if (!YA_AVISADAS.add(clave)) return;
        String msg = "[Fc] Functional Constraint no reconocida: '" + clave + "'"
                + " (bytes " + toHex(fc) + ")."
                + " El nodo queda bajo FC=UNKNOWN y no se podra leer; el resto del nodo logico"
                + " se conserva.";
        System.out.println(msg);
        if (DEBUG_UNKNOWN) {
            System.out.println("[Fc] len=" + (fc == null ? -1 : fc.length()));
        }
        java.util.function.Consumer<String> l = unknownFcListener;
        if (l != null) {
            try {
                l.accept(msg);
            } catch (Exception ignored) {
                // Un listener roto no puede tumbar la construccion del modelo.
            }
        }
    }

    private static String toHex(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) sb.append(String.format("%02X ", (int) c));
        return sb.toString().trim();
    }
}
