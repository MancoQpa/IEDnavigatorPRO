package com.iednavigator;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Los atributos {@code desc} de un archivo SCL, indexados por la referencia del modelo.
 *
 * El diccionario explica los nombres que define la norma y los de algunos fabricantes, pero
 * cada fabricante agrega los suyos (un DO de ajuste propio, un LN con prefijo de proyecto), y
 * muchos los describen en el propio archivo: el {@code desc} del DOI suele ser el nombre del
 * ajuste o de la señal tal como figura en el frente del equipo y en su manual. Esta clase los
 * deja a mano para la ayuda, sin interpretarlos: se muestran tal cual vienen.
 *
 * Claves (iguales a {@code ModelNode.getReference()} del modelo de iec61850bean):
 *   {@code <IED><LDinst>}                      desc del LDevice
 *   {@code <IED><LDinst>/<prefijo><clase><inst>} desc del LN
 *   {@code .../LN.DO}, {@code .../LN.DO.SDO}, {@code .../LN.DO.DA}   desc de DOI, SDI y DAI
 * Si el DOI no trae desc se usa el del DO en su LNodeType, y para los DA el del DOType.
 *
 * Se lee con StAX: un SCD de 25 MB no se carga entero en memoria. Se guarda la última
 * lectura (archivo + fecha de modificación) para no releer en cada consulta.
 */
final class DescripcionesScl {

    private final Map<String, String> porReferencia = new HashMap<>();
    /** referencia de LN → id de su LNodeType */
    private final Map<String, String> tipoDeLn = new HashMap<>();
    /** id de LNodeType + "." + DO → desc del DO en el tipo */
    private final Map<String, String> descDoEnTipo = new HashMap<>();
    /** id de LNodeType + "." + DO → id del DOType */
    private final Map<String, String> tipoDeDo = new HashMap<>();
    /** id de DOType + "." + DA/SDO → desc en el tipo */
    private final Map<String, String> descEnDoType = new HashMap<>();

    private static File ultimoArchivo;
    private static long ultimaFecha;
    private static DescripcionesScl ultimo;

    /** Descripciones del archivo; null si no hay archivo o no se pudo leer. */
    static synchronized DescripcionesScl de(File scl) {
        if (scl == null || !scl.isFile()) return null;
        if (scl.equals(ultimoArchivo) && scl.lastModified() == ultimaFecha) return ultimo;
        DescripcionesScl d = new DescripcionesScl();
        try (InputStream in = new FileInputStream(scl)) {
            d.leer(in);
        } catch (Exception e) {
            System.out.println("[DescripcionesScl] no se pudo leer " + scl + ": " + e.getMessage());
            return null;
        }
        ultimoArchivo = scl;
        ultimaFecha = scl.lastModified();
        ultimo = d;
        return d;
    }

    /** Lectura desde un flujo, para las pruebas. */
    static DescripcionesScl de(InputStream in) throws Exception {
        DescripcionesScl d = new DescripcionesScl();
        d.leer(in);
        return d;
    }

    int cantidad() { return porReferencia.size() + descDoEnTipo.size() + descEnDoType.size(); }

    /**
     * La descripción que el archivo da para el nodo con esa referencia, o null.
     * Acepta referencias de LD, LN, DO, SDO y DA (con o sin FC entre corchetes al final).
     */
    String descripcion(String ref) {
        if (ref == null || ref.isBlank()) return null;
        ref = ref.replaceAll("\\s*\\[[A-Z]{2}\\]\\s*$", "").trim();
        String d = porReferencia.get(ref);
        if (d != null) return d;

        // Del tipo: <LD>/<LN>.<DO>[.<DA o SDO>]
        int barra = ref.indexOf('/');
        if (barra < 0) return null;
        int punto = ref.indexOf('.', barra);
        if (punto < 0) return null;
        String ln = ref.substring(0, punto);
        String[] resto = ref.substring(punto + 1).split("\\.");
        String lnType = tipoDeLn.get(ln);
        if (lnType == null) return null;
        String clave = lnType + "." + resto[0];
        if (resto.length == 1) return descDoEnTipo.get(clave);
        String doType = tipoDeDo.get(clave);
        if (doType == null) return null;
        return descEnDoType.get(doType + "." + resto[resto.length - 1]);
    }

    // ── Lectura ──────────────────────────────────────────────────────────────

    private void leer(InputStream in) throws Exception {
        XMLInputFactory f = XMLInputFactory.newInstance();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        XMLStreamReader r = f.createXMLStreamReader(in);

        String ied = null, ld = null, ln = null;
        String lnodeType = null, doType = null;
        // Camino de DOI/SDI/DAI dentro del LN actual
        Deque<String> camino = new ArrayDeque<>();
        Deque<String> elementos = new ArrayDeque<>();

        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                String tag = r.getLocalName();
                elementos.push(tag);
                String desc = r.getAttributeValue(null, "desc");
                switch (tag) {
                    case "IED":
                        ied = r.getAttributeValue(null, "name");
                        break;
                    case "LDevice": {
                        String ldName = r.getAttributeValue(null, "ldName");
                        String inst = r.getAttributeValue(null, "inst");
                        ld = (ldName != null && !ldName.isEmpty()) ? ldName
                             : (ied == null ? "" : ied) + (inst == null ? "" : inst);
                        poner(ld, desc);
                        break;
                    }
                    case "LN0":
                    case "LN":
                        if (ld == null) break;
                        String pre = nz(r.getAttributeValue(null, "prefix"));
                        String cls = nz(r.getAttributeValue(null, "lnClass"));
                        String inst = nz(r.getAttributeValue(null, "inst"));
                        ln = ld + "/" + ("LN0".equals(tag) ? "LLN0" : pre + cls + inst);
                        poner(ln, desc);
                        String t = r.getAttributeValue(null, "lnType");
                        if (t != null) tipoDeLn.put(ln, t);
                        camino.clear();
                        break;
                    case "DOI":
                    case "SDI":
                    case "DAI":
                        if (ln == null) break;
                        camino.addLast(nz(r.getAttributeValue(null, "name")));
                        poner(ln + "." + String.join(".", camino), desc);
                        break;
                    case "LNodeType":
                        lnodeType = r.getAttributeValue(null, "id");
                        break;
                    case "DOType":
                        doType = r.getAttributeValue(null, "id");
                        break;
                    case "DO":
                        if (lnodeType != null) {
                            String k = lnodeType + "." + r.getAttributeValue(null, "name");
                            if (desc != null && !desc.isBlank()) descDoEnTipo.put(k, desc.trim());
                            String tipo = r.getAttributeValue(null, "type");
                            if (tipo != null) tipoDeDo.put(k, tipo);
                        }
                        break;
                    case "DA":
                    case "SDO":
                        if (doType != null && desc != null && !desc.isBlank()) {
                            descEnDoType.put(doType + "." + r.getAttributeValue(null, "name"), desc.trim());
                        }
                        break;
                    default:
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                String tag = r.getLocalName();
                if (!elementos.isEmpty()) elementos.pop();
                switch (tag) {
                    case "IED": ied = null; break;
                    case "LDevice": ld = null; break;
                    case "LN0": case "LN": ln = null; camino.clear(); break;
                    case "DOI": case "SDI": case "DAI":
                        if (!camino.isEmpty()) camino.removeLast();
                        break;
                    case "LNodeType": lnodeType = null; break;
                    case "DOType": doType = null; break;
                    default:
                }
            }
        }
        r.close();
    }

    private void poner(String ref, String desc) {
        if (ref != null && desc != null && !desc.isBlank()) porReferencia.put(ref, desc.trim());
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
