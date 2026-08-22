package com.iednavigator;

import com.beanit.iec61850bean.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.Collection;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Servidor IEC 61850 usando iec61850bean
 * Basado en el codigo de la APK Android que funciona correctamente
 */
public class IEC61850Server implements ServerEventListener {

    private ServerSap serverSap;
    private ServerModel serverModel;
    private int port = 102;
    private boolean running = false;

    // Referencias a nodos para actualizacion
    private final Map<String, BasicDataAttribute> attributeRefs = new HashMap<>();

    // Listener para eventos
    private ServerListener listener;

    public interface ServerListener {
        void onServerStarted(int port);
        void onServerStopped();
        void onClientWrite(String nodeRef, String value);
        void onError(String message);
        default void onLog(String message) {}  // opcional: log informativo a la GUI
    }

    public void setServerListener(ServerListener listener) {
        this.listener = listener;
    }

    // Cache de modelos parseados para selección de IED
    // parsedModels: un ServerModel por AccessPoint (resultado directo de SclParser)
    // mergedModels: un ServerModel por IED (todos los AccessPoints fusionados)
    private List<ServerModel> parsedModels = null;
    private List<ServerModel> mergedModels = null;
    private String currentSclPath = null;
    private int loadedIedIndex = 0;

    /**
     * Obtiene la lista de IEDs disponibles en un archivo SCL.
     *
     * SclParser.parse() devuelve un ServerModel por cada AccessPoint, no por IED.
     * Este método agrupa los modelos por IED y fusiona sus LDs en un único ServerModel
     * por IED, de modo que el resultado es equivalente a lo que muestra CET850.
     */
    public List<String> getAvailableIEDs(String sclPath) {
        List<String> iedNames = new ArrayList<>();
        try {
            File sclFile = new File(sclPath);
            if (!sclFile.exists()) return iedNames;

            // Pre-procesar: expandir arrays (SDO/DA/BDA con count > 1) ANTES de parsear
            String expandedPath = expandSclArrays(sclPath);
            File expandedFile = new File(expandedPath);

            // Paso 1: Parsear con SclParser (devuelve 1 modelo por AccessPoint).
            // Tolerante: un valor inicial que la librería no sabe convertir se descarta en vez
            // de perder el archivo entero.
            parsedModels = parseSclTolerante(expandedFile);
            currentSclPath = sclPath;  // guardar ruta original como clave de cache

            if (parsedModels == null || parsedModels.isEmpty()) return iedNames;

            // Paso 2: Parsear XML para obtener IED names y conteo de AccessPoints por IED
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(expandedFile);  // usar archivo expandido también aquí

            NodeList ieds = doc.getElementsByTagName("IED");
            mergedModels = new ArrayList<>();
            int modelIndex = 0;

            for (int i = 0; i < ieds.getLength(); i++) {
                Element ied = (Element) ieds.item(i);
                String name = ied.getAttribute("name");
                if (name == null || name.isEmpty()) name = "IED_" + i;
                iedNames.add(name);

                // Contar AccessPoints de este IED
                NodeList aps = ied.getElementsByTagName("AccessPoint");
                int apCount = Math.max(1, aps.getLength());

                // Fusionar todos los AccessPoints de este IED en un único ServerModel
                ServerModel merged = mergeModels(parsedModels, modelIndex, modelIndex + apCount);
                mergedModels.add(merged);

                System.out.println("[SERVER] IED '" + name + "': " + apCount
                        + " AccessPoint(s), " + merged.getChildren().size() + " LDs total");

                modelIndex += apCount;
            }

            // Fallback: si el XML no tenía IEDs pero SclParser sí devolvió modelos
            if (iedNames.isEmpty() && !parsedModels.isEmpty()) {
                iedNames.add("IED_0");
                mergedModels.add(parsedModels.get(0));
            }

        } catch (Exception e) {
            System.err.println("[SERVER] Error getting IED list: " + e.getMessage());
        }
        return iedNames;
    }

    /**
     * Fusiona los LDs y DataSets de varios AccessPoints consecutivos en un único ServerModel.
     * fromIdx es inclusivo, toIdx es exclusivo.
     *
     * Después de fusionar, re-resuelve el campo package-private Rcb.dataSet para todos los RCBs
     * del modelo fusionado. SclParser solo resuelve referencias dentro del mismo AccessPoint;
     * referencias cruzadas entre APs quedan en null, lo que causa que iec61850bean responda
     * PARAMETER_VALUE_INAPPROPRIATE cuando un cliente llama retrieveModel().
     */
    private ServerModel mergeModels(List<ServerModel> models, int fromIdx, int toIdx) {
        List<LogicalDevice> allLDs = new ArrayList<>();
        List<DataSet> allDataSets = new ArrayList<>();

        for (int i = fromIdx; i < toIdx && i < models.size(); i++) {
            ServerModel m = models.get(i);
            for (ModelNode child : m.getChildren()) {
                if (child instanceof LogicalDevice) {
                    allLDs.add((LogicalDevice) child);
                }
            }
            Collection<DataSet> ds = m.getDataSets();
            if (ds != null) allDataSets.addAll(ds);
        }

        ServerModel merged = new ServerModel(allLDs, allDataSets);
        relinkRcbDataSets(merged);
        return merged;
    }

    /**
     * Re-resuelve el campo Rcb.dataSet (package-private en iec61850bean) para todos los RCBs
     * del modelo fusionado, usando reflexión.
     *
     * Cuando SclParser construye modelos por AccessPoint separados, los RCBs que referencian
     * DataSets de otro AccessPoint quedan con dataSet=null. Tras fusionar, el DataSet existe
     * en el modelo pero el campo no fue actualizado. Este método lo corrige.
     *
     * Para cualquier RCB cuyo datSet no pueda resolverse (ni por referencia exacta ni por
     * coincidencia de sufijo), limpia el valor del atributo para que el cliente no solicite
     * un DataSet inexistente.
     */
    /** Busca el campo de tipo DataSet en la jerarquía de clases (recorre superclases). */
    private static java.lang.reflect.Field findDataSetField(Class<?> cls) {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (DataSet.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return f;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private void relinkRcbDataSets(ServerModel model) {
        // Índice de DataSets: referencia completa → objeto
        Map<String, DataSet> dsIndex = new HashMap<>();
        Collection<DataSet> allDs = model.getDataSets();
        if (allDs != null) {
            for (DataSet ds : allDs) {
                dsIndex.put(ds.getReferenceStr(), ds);
                // También indexar con '$' en lugar de '.' (formato alternativo que usa iec61850bean)
                String altRef = ds.getReferenceStr().replaceAll("\\.(?=[^./]+$)", "\\$");
                dsIndex.put(altRef, ds);
            }
        }

        // Obtener el campo DataSet por reflexión buscando por tipo en la jerarquía
        java.lang.reflect.Field urcbDataSetField = findDataSetField(Urcb.class);
        java.lang.reflect.Field brcbDataSetField = findDataSetField(Brcb.class);
        int linked = 0, cleared = 0;

        for (ModelNode ldNode : model.getChildren()) {
            if (!(ldNode instanceof LogicalDevice)) continue;
            for (ModelNode lnNode : ldNode.getChildren()) {
                if (!(lnNode instanceof LogicalNode)) continue;
                LogicalNode ln = (LogicalNode) lnNode;

                Collection<Urcb> urcbs = ln.getUrcbs();
                if (urcbs != null) {
                    for (Urcb urcb : urcbs) {
                        if (processRcbDataSet(urcb, dsIndex, urcbDataSetField, model)) linked++;
                        else cleared++;
                    }
                }
                Collection<Brcb> brcbs = ln.getBrcbs();
                if (brcbs != null) {
                    for (Brcb brcb : brcbs) {
                        if (processRcbDataSet(brcb, dsIndex, brcbDataSetField, model)) linked++;
                        else cleared++;
                    }
                }
            }
        }

        if (linked > 0 || cleared > 0) {
            System.out.println("[SERVER] RCB re-link: " + linked + " enlazados, " + cleared + " sin DataSet válido (limpiados)");
        }
    }

    /**
     * Intenta resolver el DataSet de un RCB en el modelo fusionado.
     * Si lo encuentra, actualiza Rcb.dataSet via reflexión.
     * Si no, limpia el atributo datSet para que el cliente no lo solicite.
     * @return true si se resolvió correctamente, false si se limpió
     */
    private boolean processRcbDataSet(Rcb rcb, Map<String, DataSet> dsIndex,
                                       java.lang.reflect.Field dataSetField, ServerModel model) {
        BdaVisibleString datSetAttr = rcb.getDatSet();
        if (datSetAttr == null) return true;

        String datSetVal = datSetAttr.getStringValue();
        if (datSetVal == null || datSetVal.isEmpty()) return true;

        // Buscar por referencia exacta y variantes
        DataSet found = dsIndex.get(datSetVal);
        if (found == null) {
            // Intentar reemplazando '$' por '.' (o viceversa) en el último separador
            String alt = datSetVal.contains("$")
                ? datSetVal.replaceAll("\\$(?=[^$]+$)", ".")
                : datSetVal.replaceAll("\\.(?=[^./]+$)", "\\$");
            found = dsIndex.get(alt);
        }
        if (found == null) {
            // Búsqueda por sufijo (el datSet puede ser relativo sin prefijo IED)
            String normalized = datSetVal.replace('$', '.');
            for (Map.Entry<String, DataSet> entry : dsIndex.entrySet()) {
                if (entry.getKey().endsWith("/" + normalized) || entry.getKey().endsWith("." + normalized)) {
                    found = entry.getValue();
                    break;
                }
            }
        }

        if (found != null) {
            // Actualizar el campo Rcb.dataSet via reflexión si es que era null
            if (dataSetField != null) {
                try {
                    Object current = dataSetField.get(rcb);
                    if (current == null) {
                        dataSetField.set(rcb, found);
                    }
                } catch (Exception e) {
                    // ignored — datSet string value is still correct
                }
            }
            return true;
        }

        // No se encontró el DataSet — limpiar para evitar error en retrieveModel()
        System.out.println("[SERVER] datSet '" + datSetVal + "' no encontrado en modelo — limpiando RCB " + rcb.getName());
        datSetAttr.setValue("");
        if (dataSetField != null) {
            try { dataSetField.set(rcb, null); } catch (Exception ignored) {}
        }
        return false;
    }

    /**
     * Carga un IED específico por índice (sobre la lista de IEDs únicos).
     * Usa el ServerModel ya fusionado que incluye todos los AccessPoints del IED.
     */
    public boolean loadSclFileWithIED(String sclPath, int iedIndex) {
        try {
            // Usar el cache si el archivo ya fue parseado
            if (mergedModels != null && sclPath.equals(currentSclPath)) {
                if (iedIndex >= 0 && iedIndex < mergedModels.size()) {
                    serverModel = mergedModels.get(iedIndex);
                    indexAttributes(serverModel);
                    System.out.println("[SERVER] Loaded merged IED index " + iedIndex + " from cache");
                    System.out.println("[SERVER] LDs found: " + serverModel.getChildren().size());
                    debugPrintModelInfo(serverModel);
                    return true;
                }
            }

            // Si no hay cache, parsear de nuevo (llamada directa sin selección previa)
            return loadSclFile(sclPath, iedIndex);
        } catch (Exception e) {
            System.err.println("[ERROR] " + e.getMessage());
            return false;
        }
    }

    /**
     * Pre-procesa el SCL expandiendo elementos con atributo count > 1.
     * Elementos SDO/DA/BDA con count="N" se reemplazan por N elementos individuales
     * con nombres indexados (p.ej. phsAHar01..phsAHar50 para count=50).
     * Retorna la ruta del archivo expandido (temp) o la original si no hay arrays.
     */
    private String expandSclArrays(String sclPath) {
        if (listener != null) listener.onLog("[SCL] Iniciando expansión de arrays en: " + new File(sclPath).getName());
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);  // preservar namespace xmlns="http://www.iec.ch/61850/2003/SCL"
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new File(sclPath));

            int totalExpanded = 0;

            // Expandir SDO, DA y BDA con atributo count
            for (String tag : new String[]{"SDO", "DA", "BDA"}) {
                // getElementsByTagNameNS("*", tag) encuentra elementos con cualquier namespace
                NodeList nodes = doc.getElementsByTagNameNS("*", tag);
                List<Element> toExpand = new ArrayList<>();
                for (int i = 0; i < nodes.getLength(); i++) {
                    Element el = (Element) nodes.item(i);
                    String countStr = el.getAttribute("count").trim();
                    if (!countStr.isEmpty()) {
                        try {
                            int count = Integer.parseInt(countStr);
                            if (count > 1) toExpand.add(el);
                        } catch (NumberFormatException ignore) {}
                    }
                }

                for (Element el : toExpand) {
                    int count = Integer.parseInt(el.getAttribute("count").trim());
                    String name = el.getAttribute("name");
                    org.w3c.dom.Node parent = el.getParentNode();
                    String nsUri = el.getNamespaceURI();  // preservar namespace del elemento

                    // Determinar padding: mínimo 2 dígitos
                    int digits = Math.max(2, String.valueOf(count).length());

                    org.w3c.dom.NamedNodeMap attrs = el.getAttributes();

                    for (int i = 0; i < count; i++) {
                        String indexedName = name + String.format("%0" + digits + "d", i);
                        // Crear elemento con mismo namespace que el original
                        Element newEl = (nsUri != null)
                            ? doc.createElementNS(nsUri, tag)
                            : doc.createElement(tag);
                        // Copiar todos los atributos excepto count
                        for (int a = 0; a < attrs.getLength(); a++) {
                            org.w3c.dom.Attr attr = (org.w3c.dom.Attr) attrs.item(a);
                            String attrName = attr.getName();
                            if (!attrName.equals("count") && !attrName.startsWith("xmlns")) {
                                newEl.setAttribute(attrName, attr.getValue());
                            }
                        }
                        newEl.setAttribute("name", indexedName);
                        parent.insertBefore(newEl, el);
                    }
                    parent.removeChild(el);
                    totalExpanded++;
                }
            }

            if (listener != null) listener.onLog("[SCL] Arrays con count encontrados: " + totalExpanded);

            // Parche de EnumTypes incompletos (hay familias que omiten ordinales estándar)
            int patchedEnums = patchMissingEnumOrdinals(doc);
            if (patchedEnums > 0) {
                if (listener != null)
                    listener.onLog("[SCL] " + patchedEnums
                        + " EnumVal sintéticos agregados (el archivo trae EnumTypes incompletos)");
                System.out.println("[SERVER] Enum patch: " + patchedEnums + " EnumVal entries added");
            }

            // <Val/> sin contenido: la librería intenta convertir "" al tipo del atributo y
            // aborta la carga entera ("invalid INT32U configured value: "). Hay archivos que
            // traen cientos. Quitarlos equivale a que el DAI no declare valor inicial, que es
            // justamente lo que el archivo quiere decir.
            int emptyVals = dropEmptyVals(doc);
            if (emptyVals > 0) {
                if (listener != null)
                    listener.onLog("[SCL] " + emptyVals + " <Val> vacíos descartados");
                System.out.println("[SERVER] Empty <Val> dropped: " + emptyVals);
            }

            // RptEnabled max fuera de 1..99: la librería aborta. Hay archivos que declaran
            // max="0", que además es inútil: ningún cliente podría habilitar ese RCB.
            int rptFixed = patchReportControlMax(doc);
            if (rptFixed > 0) {
                if (listener != null)
                    listener.onLog("[SCL] " + rptFixed + " RptEnabled con max fuera de rango corregidos");
                System.out.println("[SERVER] RptEnabled max fixed: " + rptFixed);
            }

            if (totalExpanded == 0 && patchedEnums == 0 && emptyVals == 0 && rptFixed == 0) return sclPath;

            // Escribir a archivo temporal preservando namespace
            File tempFile = File.createTempFile("ied_expanded_", ".cid");
            tempFile.deleteOnExit();
            TransformerFactory tf = TransformerFactory.newInstance();
            Transformer transformer = tf.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            transformer.transform(new DOMSource(doc), new StreamResult(tempFile));

            if (listener != null) listener.onLog("[SCL] " + totalExpanded + " arrays expandidos → " + tempFile.getName());
            System.out.println("[SERVER] SCL array expansion: " + totalExpanded + " arrays → " + tempFile.getAbsolutePath());
            return tempFile.getAbsolutePath();

        } catch (Exception e) {
            if (listener != null) listener.onLog("[SCL] ERROR en expansión: " + e.getMessage());
            System.err.println("[SERVER] SCL array expansion failed: " + e.getMessage());
            return sclPath;
        }
    }

    /** Tope de valores iniciales inválidos que se descartan antes de darse por vencido. */
    private static final int MAX_VALORES_DESCARTADOS = 200;

    /** ¿Entra el ordinal en un byte con signo? Decide si sintetizarlo ensancharía el EnumType. */
    private static boolean cabeEnByte(String ord) {
        try { return cabeEnByte(Integer.parseInt(ord.trim())); }
        catch (NumberFormatException e) { return false; }
    }

    private static boolean cabeEnByte(int n) {
        return n >= Byte.MIN_VALUE && n <= Byte.MAX_VALUE;
    }

    /**
     * Parsea un SCL descartando, uno por uno, los valores iniciales que la librería no sabe
     * convertir, en vez de perder el archivo entero por uno solo.
     *
     * El caso que lo motivó: un atributo booleano cuyo &lt;Val&gt; es una referencia a otro objeto
     * —convención interna de algún fabricante, no válida en SCL—. La librería aborta la carga
     * completa con "invalid boolean configured value: ...", y con eso se pierden todos los IEDs
     * del archivo por un valor inicial. La herramienta de referencia del mercado abre esos
     * mismos archivos mostrando un indicador de errores: degrada en vez de abortar, que es el
     * comportamiento a copiar.
     *
     * Perder el archivo por esto no tiene sentido: es un VALOR INICIAL, no la estructura del
     * modelo. El atributo queda con el valor por defecto de su tipo, que es la degradación
     * correcta.
     *
     * No es específico de ningún fabricante: el valor culpable viene en el mensaje de la propia
     * excepción, así que el mecanismo sirve para cualquier archivo con el mismo defecto.
     *
     * Portado del simulador; ver HALLAZGOS_DESDE_EL_SIMULADOR.md, hallazgo 2.
     */
    private List<ServerModel> parseSclTolerante(File archivo) throws SclParseException, IOException {
        byte[] actual = java.nio.file.Files.readAllBytes(archivo.toPath());
        int descartados = 0;

        while (true) {
            try {
                return SclParser.parse(new java.io.ByteArrayInputStream(actual));
            } catch (SclParseException e) {
                String malo = extraerValorInvalido(e.getMessage());
                if (malo == null || descartados >= MAX_VALORES_DESCARTADOS) throw e;

                byte[] siguiente = dropValsConTexto(actual, malo);
                if (siguiente == null) throw e;   // no había ninguno: no insistir

                descartados++;
                actual = siguiente;
                String aviso = "[SCL] valor inicial inválido descartado: \"" + malo
                        + "\" (" + descartados + ")";
                if (listener != null) listener.onLog(aviso);
                System.out.println("[SERVER] " + aviso);
            }
        }
    }

    /**
     * Saca del mensaje de la excepción el valor que no se pudo convertir.
     * Formato de la librería: "invalid &lt;tipo&gt; configured value: &lt;valor&gt;".
     */
    private String extraerValorInvalido(String mensaje) {
        if (mensaje == null) return null;
        final String marca = "configured value: ";
        int i = mensaje.indexOf(marca);
        if (i < 0) return null;
        String valor = mensaje.substring(i + marca.length()).trim();
        return valor.isEmpty() ? null : valor;
    }

    /**
     * Devuelve el XML sin los &lt;Val&gt; cuyo texto sea exactamente el dado, o null si no había
     * ninguno — para no reintentar en vano y quedarse en el lazo.
     */
    private byte[] dropValsConTexto(byte[] xml, String texto) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            Document doc = factory.newDocumentBuilder()
                    .parse(new java.io.ByteArrayInputStream(xml));

            NodeList vals = doc.getElementsByTagNameNS("*", "Val");
            if (vals.getLength() == 0) vals = doc.getElementsByTagName("Val");

            List<Element> aBorrar = new ArrayList<>();
            for (int i = 0; i < vals.getLength(); i++) {
                Element val = (Element) vals.item(i);
                if (texto.equals(val.getTextContent().trim())) aBorrar.add(val);
            }
            if (aBorrar.isEmpty()) return null;

            for (Element val : aBorrar) {
                org.w3c.dom.Node parent = val.getParentNode();
                if (parent != null) parent.removeChild(val);
            }

            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.INDENT, "no");
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            transformer.transform(new DOMSource(doc), new StreamResult(out));
            return out.toByteArray();

        } catch (Exception e) {
            System.err.println("[SERVER] dropValsConTexto falló: " + e.getMessage());
            return null;
        }
    }

    /**
     * Quita los &lt;Val&gt; sin contenido.
     *
     * Un &lt;Val/&gt; vacío hace que la librería intente convertir "" al tipo del atributo y aborte
     * la carga entera. Quitarlo equivale a que el DAI no declare valor inicial —que es lo que
     * el archivo quiere decir— y el atributo queda con el valor por defecto de su tipo.
     *
     * Portado del simulador; ver HALLAZGOS_DESDE_EL_SIMULADOR.md, hallazgo 3.
     */
    private int dropEmptyVals(Document doc) {
        NodeList vals = doc.getElementsByTagNameNS("*", "Val");
        if (vals.getLength() == 0) vals = doc.getElementsByTagName("Val");

        // NodeList es viva: hay que juntar primero y borrar después.
        List<Element> aBorrar = new ArrayList<>();
        for (int i = 0; i < vals.getLength(); i++) {
            Element val = (Element) vals.item(i);
            String texto = val.getTextContent();
            if (texto == null || texto.trim().isEmpty()) aBorrar.add(val);
        }
        for (Element val : aBorrar) {
            org.w3c.dom.Node parent = val.getParentNode();
            if (parent != null) parent.removeChild(val);
        }
        return aBorrar.size();
    }

    /**
     * Deja el atributo max de RptEnabled dentro del rango que la librería exige (1..99).
     *
     * Detalle que cuesta encontrar: el atributo culpable está en &lt;RptEnabled&gt;, NO en
     * &lt;ReportControl&gt;. Parchear ReportControl no cambia nada. Un max de 0 significa que
     * ningún cliente puede habilitar ese bloque de reporte, así que corregirlo a 1 no pierde
     * nada que el archivo quisiera expresar.
     *
     * Portado del simulador; ver HALLAZGOS_DESDE_EL_SIMULADOR.md, hallazgo 4.
     */
    private int patchReportControlMax(Document doc) {
        NodeList rcs = doc.getElementsByTagNameNS("*", "ReportControl");
        if (rcs.getLength() == 0) rcs = doc.getElementsByTagName("ReportControl");

        int patched = 0;
        for (int i = 0; i < rcs.getLength(); i++) {
            Element rc = (Element) rcs.item(i);

            Element rptEnabled = null;
            org.w3c.dom.NodeList children = rc.getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                org.w3c.dom.Node child = children.item(j);
                if (child.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE
                        && "RptEnabled".equals(child.getLocalName() != null
                                ? child.getLocalName() : child.getNodeName())) {
                    rptEnabled = (Element) child;
                    break;
                }
            }

            if (rptEnabled == null) {
                String nsUri = rc.getNamespaceURI();
                rptEnabled = (nsUri != null && !nsUri.isEmpty())
                        ? doc.createElementNS(nsUri, "RptEnabled")
                        : doc.createElement("RptEnabled");
                rptEnabled.setAttribute("max", "1");
                rc.appendChild(rptEnabled);
                patched++;
                continue;
            }

            int max = 0;
            try {
                String raw = rptEnabled.getAttribute("max").trim();
                if (!raw.isEmpty()) max = Integer.parseInt(raw);
            } catch (NumberFormatException ignored) {}

            if (max < 1 || max > 99) {
                rptEnabled.setAttribute("max", "1");
                patched++;
            }
        }
        return patched;
    }

    /**
     * Completa los EnumType a los que les faltan valores que el propio documento usa.
     *
     * La versión anterior comparaba por ORDINAL —"¿este EnumType ya define el ord 1?"— y eso
     * no cubre el caso más común en archivos reales. Verificado sobre el bytecode de
     * SclParser, no sobre el mensaje de error: para resolver un &lt;Val&gt; recorre los EnumVal
     * comparando contra EnumVal.getId(), o sea el TEXTO del elemento, y usa el ord del que
     * coincida. No hay fallback por ordinal. Entonces un archivo cuyos EnumVal son etiquetas
     * ("on", "blocked") y cuyos DAI traen &lt;Val&gt;1&lt;/Val&gt; seguía sin cargar: el ordinal 1 ya
     * estaba definido —como "on"— así que el parche lo salteaba.
     *
     * Ahora se compara por texto. Y hay una segunda mitad: los valores SIMBÓLICOS. Un
     * EnumType truncado puede no declarar "status-only" aunque el documento lo use cientos de
     * veces. Para sintetizarlo hace falta su ordinal y no se puede inventar —el ord del
     * EnumVal que coincide ES el valor que se guarda—, así que se aprende del propio
     * documento recorriendo todos los EnumVal de todos los EnumType.
     *
     * Dos salvaguardas, sin las cuales esto haría daño:
     *   - un texto que aparezca con ordinales CONTRADICTORIOS en distintos EnumType se
     *     descarta: sin ordinal unívoco no se puede sintetizar nada correcto;
     *   - sólo se sintetizan textos que el documento defina en algún lado. Los demás textos
     *     no numéricos que viven en &lt;Val&gt; ("IEC 61850-7-4:2007", "false", marcas de tiempo)
     *     pertenecen a atributos que no son enumerados y no hay que tocarlos.
     *
     * Portado del simulador de IED para Android, donde se probó contra 72 archivos reales de
     * ocho fabricantes. Ver HALLAZGOS_DESDE_EL_SIMULADOR.md, hallazgos 1 y 1b.
     */
    private int patchMissingEnumOrdinals(Document doc) {
        NodeList enumTypes = doc.getElementsByTagNameNS("*", "EnumType");
        if (enumTypes.getLength() == 0) enumTypes = doc.getElementsByTagName("EnumType");
        if (enumTypes.getLength() == 0) return 0;

        // Paso 1: aprender del documento qué ordinal le corresponde a cada texto.
        Map<String, String> textoAOrd = new HashMap<>();
        Set<String> ambiguos = new HashSet<>();
        for (int i = 0; i < enumTypes.getLength(); i++) {
            org.w3c.dom.NodeList children = enumTypes.item(i).getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                org.w3c.dom.Node child = children.item(j);
                if (child.getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) continue;
                String ord = ((Element) child).getAttribute("ord").trim();
                if (ord.isEmpty()) continue;
                String texto = child.getTextContent().trim();
                if (texto.isEmpty()) continue;
                String previo = textoAOrd.put(texto, ord);
                if (previo != null && !previo.equals(ord)) ambiguos.add(texto);
            }
        }
        for (String a : ambiguos) textoAOrd.remove(a);

        // Paso 2: juntar los textos usados en <Val> para los que se conoce un ordinal.
        // Un texto numérico es su propio ordinal; uno simbólico sólo sirve si el documento
        // lo define en algún lado.
        Map<String, String> usados = new LinkedHashMap<>();
        NodeList valNodes = doc.getElementsByTagNameNS("*", "Val");
        if (valNodes.getLength() == 0) valNodes = doc.getElementsByTagName("Val");
        for (int i = 0; i < valNodes.getLength(); i++) {
            String texto = valNodes.item(i).getTextContent().trim();
            if (texto.isEmpty() || usados.containsKey(texto)) continue;
            try {
                Integer.parseInt(texto);
                usados.put(texto, texto);
            } catch (NumberFormatException e) {
                String ord = textoAOrd.get(texto);
                if (ord != null) usados.put(texto, ord);
            }
        }
        if (usados.isEmpty()) return 0;

        // Paso 3: completar cada EnumType con los textos que le falten.
        int patchCount = 0;
        for (int i = 0; i < enumTypes.getLength(); i++) {
            Element enumType = (Element) enumTypes.item(i);
            String nsUri = enumType.getNamespaceURI();

            // Textos ya presentes, que es por lo que la librería busca. Y de paso el mayor
            // ordinal que este EnumType ya declara, que hace falta para no ensancharlo.
            Set<String> definedTexts = new HashSet<>();
            boolean yaEsAncho = false;
            org.w3c.dom.NodeList children = enumType.getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                org.w3c.dom.Node child = children.item(j);
                if (child.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                    definedTexts.add(child.getTextContent().trim());
                    String o = ((Element) child).getAttribute("ord").trim();
                    if (!o.isEmpty()) {
                        try { if (!cabeEnByte(Integer.parseInt(o))) yaEsAncho = true; }
                        catch (NumberFormatException ignore) {}
                    }
                }
            }

            for (Map.Entry<String, String> e : usados.entrySet()) {
                if (definedTexts.contains(e.getKey())) continue;
                // NO ensanchar el EnumType. SclParser elige el ancho del entero según el rango
                // de ordinales, y la propia librería lee ctlModel con un instanceof BdaInt8 al
                // atender un mando: verificado en el bytecode de ServerAssociation. Si el
                // parche mete un ordinal grande, el atributo pasa a BdaInt16, la librería no lo
                // reconoce y responde "ctlModel is not set" — o sea que el parche que existe
                // para que el archivo CARGUE terminaba rompiendo el MANDO.
                //
                // Los ordinales de los enumerados de IEC 61850 son chicos. Los <Val> grandes
                // del documento —contadores, tiempos, un 1000000 medido en un archivo real—
                // pertenecen a atributos que no son enumerados, así que descartarlos no pierde
                // ningún caso legítimo. Si el EnumType YA declara un ordinal fuera del byte,
                // entonces ya es ancho y agregar no cambia nada: ahí no se filtra.
                if (!yaEsAncho && !cabeEnByte(e.getValue())) continue;
                // El ord va aunque ya esté tomado por una etiqueta: el ord del EnumVal que
                // coincide por texto es el que termina siendo el valor, así que con
                // cualquier otro se guardaría un valor equivocado. Quedan dos EnumVal con el
                // mismo ord y distinto texto ("on" y "1"), y el bucle de la librería los
                // tolera — devuelve el primero que coincida, y ambos dan el mismo ordinal.
                Element synth = (nsUri != null && !nsUri.isEmpty())
                    ? doc.createElementNS(nsUri, "EnumVal")
                    : doc.createElement("EnumVal");
                synth.setAttribute("ord", e.getValue());
                synth.setTextContent(e.getKey());
                enumType.appendChild(synth);
                patchCount++;
            }
        }
        return patchCount;
    }

    /**
     * Carga el modelo desde un archivo SCL (igual que la APK)
     */
    public boolean loadSclFile(String sclPath) {
        return loadSclFile(sclPath, 0); // Por defecto cargar el primer IED
    }

    /**
     * Carga el modelo desde un archivo SCL, seleccionando un IED específico
     */
    public boolean loadSclFile(String sclPath, int iedIndex) {
        try {
            System.out.println("[SERVER] Loading SCL file: " + sclPath);
            File sclFile = new File(sclPath);
            if (!sclFile.exists()) {
                System.err.println("[ERROR] File not found: " + sclPath);
                if (listener != null) listener.onError("File not found: " + sclPath);
                return false;
            }

            System.out.println("[SERVER] File size: " + sclFile.length() + " bytes");
            System.out.println("[SERVER] Parsing SCL (this may take a moment for large files)...");

            // Pre-procesar: expandir arrays SCL (SDO/DA/BDA con count > 1)
            String expandedPath = expandSclArrays(sclPath);
            File expandedFile = new File(expandedPath);

            // Parsear SCL. Tolerante, igual que getAvailableIEDs(): si las dos rutas no
            // descartaran lo mismo, el selector mostraría un IED que después no se puede
            // levantar.
            long startTime = System.currentTimeMillis();
            {
                List<ServerModel> models = parseSclTolerante(expandedFile);

                long parseTime = System.currentTimeMillis() - startTime;
                System.out.println("[SERVER] SCL parsed in " + parseTime + "ms");

                if (models == null || models.isEmpty()) {
                    System.err.println("[ERROR] No server models found in SCL file");
                    if (listener != null) listener.onError("No server models found in SCL file");
                    return false;
                }

                System.out.println("[SERVER] Found " + models.size() + " IED(s) in SCL file");

                // Seleccionar el IED especificado
                if (iedIndex >= 0 && iedIndex < models.size()) {
                    serverModel = models.get(iedIndex);
                    System.out.println("[SERVER] Selected IED index: " + iedIndex);
                } else {
                    serverModel = models.get(0);
                    System.out.println("[SERVER] Using first IED (index 0)");
                }

                // Guardar cache para futuras selecciones
                parsedModels = models;
                currentSclPath = sclPath;
                loadedIedIndex = iedIndex;
            }

            // Indexar atributos para actualizacion
            System.out.println("[SERVER] Indexing attributes...");
            indexAttributes(serverModel);

            System.out.println("[SERVER] Model loaded from: " + sclPath);
            System.out.println("[SERVER] LDs found: " + serverModel.getChildren().size());

            // Mostrar información detallada del modelo para debugging
            debugPrintModelInfo(serverModel);

            return true;

        } catch (SclParseException e) {
            System.err.println("[ERROR] SCL parse error: " + e.getMessage());
            e.printStackTrace();
            if (listener != null) listener.onError("SCL parse error: " + e.getMessage());
            return false;
        } catch (IOException e) {
            System.err.println("[ERROR] IO error: " + e.getMessage());
            e.printStackTrace();
            if (listener != null) listener.onError("IO error: " + e.getMessage());
            return false;
        } catch (RuntimeException e) {
            // La libreria lanza excepciones NO declaradas ante ciertos archivos validos. El
            // caso conocido: el atributo count de un SDO/DA/BDA puede ser el nombre de otro DA
            // —array de tamano dinamico, permitido por IEC 61850-6— y SclParser le hace
            // Integer.parseInt, con lo que sale una NumberFormatException. Sin esta rama se
            // propaga fuera del metodo y tumba al llamador en vez de devolver false.
            //
            // Se informa como fallo de PROCESAMIENTO y no como archivo invalido: el archivo
            // puede estar perfectamente bien y ser la libreria la que no lo contempla. Es el
            // mismo criterio con el que el comparador SCL dejo de repetir el texto crudo del
            // parser, y con el que las notas de la v4.12 encuadraron la FC fuera de catalogo.
            System.err.println("[ERROR] SCL processing error: " + e);
            e.printStackTrace();
            if (listener != null) {
                listener.onError("SCL processing error: " + e.getClass().getSimpleName()
                        + (e.getMessage() != null ? ": " + e.getMessage() : ""));
            }
            return false;
        }
    }

    /**
     * Retorna el número de IEDs disponibles en el último archivo parseado
     */
    public int getIEDCount() {
        return parsedModels != null ? parsedModels.size() : 0;
    }

    /**
     * Retorna el ServerModel fusionado para el IED en el índice dado.
     * Debe llamarse después de getAvailableIEDs() para que mergedModels esté poblado.
     */
    public ServerModel getMergedModel(int index) {
        if (mergedModels != null && index >= 0 && index < mergedModels.size()) {
            return mergedModels.get(index);
        }
        return null;
    }

    /**
     * Carga el modelo desde un InputStream
     */
    public boolean loadSclStream(InputStream inputStream) {
        try {
            List<ServerModel> models = SclParser.parse(inputStream);

            if (models == null || models.isEmpty()) {
                System.err.println("[ERROR] No server models found");
                return false;
            }

            serverModel = models.get(0);
            indexAttributes(serverModel);

            System.out.println("[SERVER] Model loaded from stream");
            return true;

        } catch (SclParseException e) {
            System.err.println("[ERROR] SCL parse error: " + e.getMessage());
            return false;
        } catch (RuntimeException e) {
            // Misma razon que en loadSclFile: la libreria puede lanzar excepciones no
            // declaradas ante archivos validos, y sin esta rama se propagan al llamador.
            System.err.println("[ERROR] SCL processing error: " + e);
            return false;
        }
    }

    /**
     * Indexa los atributos del modelo para actualizacion posterior
     */
    private void indexAttributes(ServerModel model) {
        attributeRefs.clear();

        for (ModelNode ld : model.getChildren()) {
            indexNode(ld, "");
        }

        System.out.println("[SERVER] Attributes indexed: " + attributeRefs.size());
    }

    private void indexNode(ModelNode node, String prefix) {
        String ref = prefix.isEmpty() ? node.getName() : prefix + "." + node.getName();

        if (node instanceof BasicDataAttribute) {
            attributeRefs.put(ref, (BasicDataAttribute) node);
        }

        // Null check - some nodes return null instead of empty collection
        Collection<ModelNode> children = node.getChildren();
        if (children != null) {
            for (ModelNode child : children) {
                indexNode(child, ref);
            }
        }
    }

    /**
     * Inyecta los atributos de la placa del IED (del XML del SCL) en los nodos FC=DC del modelo
     * servido, para que clientes que lean LLN0.NamPlt via MMS obtengan los datos reales.
     * Se llama tras cargar el SCL y antes de iniciar el servidor.
     *
     * @param vendor    atributo manufacturer del elemento IED
     * @param type      atributo type del elemento IED
     * @param configRev atributo configVersion del elemento IED
     */
    public void injectNameplate(String vendor, String type, String configRev) {
        if (serverModel == null || serverModel.getChildren() == null) return;
        // Recorrer todos los LDs buscando LLN0 → NamPlt → vendor/d/configRev
        for (ModelNode ld : serverModel.getChildren()) {
            if (ld.getChildren() == null) continue;
            for (ModelNode ln : ld.getChildren()) {
                if (!"LLN0".equals(ln.getName())) continue;
                if (ln.getChildren() == null) continue;
                for (ModelNode doNode : ln.getChildren()) {
                    if (!"NamPlt".equals(doNode.getName())) continue;
                    if (doNode.getChildren() == null) continue;
                    for (ModelNode da : doNode.getChildren()) {
                        if (!(da instanceof BdaVisibleString)) continue;
                        BdaVisibleString bda = (BdaVisibleString) da;
                        String n = da.getName();
                        String val = null;
                        if ("vendor".equals(n))    val = vendor;
                        else if ("d".equals(n))    val = type;
                        else if ("configRev".equals(n) && configRev != null && !configRev.isEmpty()) val = configRev;
                        if (val != null && !val.isEmpty()) {
                            bda.setValue(val.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            System.out.println("[Nameplate] " + ld.getName() + "/LLN0.NamPlt." + n + " = " + val);
                        }
                    }
                }
            }
        }
    }

    /**
     * Fixes relative DataSet references in RCBs to full MMS references.
     * SclParser stores datSet="TestDS" but iec61850bean server needs
     * "TestIEDLD0/LLN0$TestDS" to match the DataSet when setValues() is called.
     */
    private void fixRcbDataSetReferences(ServerModel model) {
        // Log actual DataSet keys in the model
        System.out.println("[RCB] DataSets in model:");
        for (com.beanit.iec61850bean.DataSet ds : model.getDataSets()) {
            System.out.println("[RCB]   key candidate: " + ds.getReferenceStr());
        }
        for (ModelNode ld : model.getChildren()) {
            for (ModelNode ln : ld.getChildren()) {
                for (ModelNode child : ln.getChildren()) {
                    if (!(child instanceof com.beanit.iec61850bean.Urcb) &&
                        !(child instanceof com.beanit.iec61850bean.Brcb)) continue;
                    com.beanit.iec61850bean.Rcb rcb = (com.beanit.iec61850bean.Rcb) child;
                    if (rcb.getDatSet() == null) continue;
                    String dsRef = rcb.getDatSet().getStringValue();
                    if (dsRef == null || dsRef.isEmpty()) continue;
                    // Already full reference (contains "/" and "$")
                    if (dsRef.contains("/") && dsRef.contains("$")) continue;
                    // Already full reference with "." notation
                    if (dsRef.contains("/") && dsRef.contains(".")) continue;
                    String fullRef = ld.getName() + "/" + ln.getName() + "$" + dsRef;
                    rcb.getDatSet().setValue(fullRef.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    System.out.println("[RCB] DataSet ref fixed: " + dsRef + " -> " + fullRef);
                }
            }
        }
    }

    /**
     * Inicia el servidor (igual que la APK)
     */
    public boolean start(int port) {
        if (running) {
            System.out.println("[WARN] Server already running");
            return true;
        }

        if (serverModel == null) {
            System.err.println("[ERROR] No model loaded");
            if (listener != null) listener.onError("No model loaded");
            return false;
        }

        this.port = port;

        try {
            // Fix relative DataSet refs in RCBs before starting (e.g. "TestDS" -> "TestIEDLD0/LLN0$TestDS")
            fixRcbDataSetReferences(serverModel);

            // Crear ServerSap igual que la APK: (port, backlog, bindAddress, model, socketFactory)
            serverSap = new ServerSap(port, 0, null, serverModel, null);

            // Iniciar escucha pasando this como ServerEventListener
            serverSap.startListening(this);

            running = true;
            System.out.println("[SERVER] Server started on port " + port);

            if (listener != null) listener.onServerStarted(port);

            return true;

        } catch (IOException e) {
            System.err.println("[ERROR] Error starting server: " + e.getMessage());
            if (listener != null) listener.onError("Error starting server: " + e.getMessage());
            return false;
        }
    }

    /**
     * Detiene el servidor
     */
    public void stop() {
        if (serverSap != null) {
            serverSap.stop();
            serverSap = null;
        }
        running = false;
        System.out.println("[SERVER] Server stopped");

        if (listener != null) listener.onServerStopped();
    }

    public boolean isRunning() {
        return running;
    }

    public ServerModel getServerModel() {
        return serverModel;
    }

    /** Ruta del archivo SCL cargado (original, sin expandir). */
    public String getSclPath() { return currentSclPath; }

    /** Índice del IED cargado (para SclFileProcessor.parseIEDByIndex). */
    public int getLoadedIedIndex() { return loadedIedIndex; }

    /**
     * Actualiza un valor en el modelo (igual que la APK)
     */
    public boolean setDataValue(String nodeRef, String value) {
        if (serverModel == null) {
            System.err.println("[WARN] Cannot set value - no model loaded");
            return false;
        }

        try {
            // Extraer FC de la referencia si existe
            Fc fc = extractFc(nodeRef);
            String cleanRef = cleanReference(nodeRef);

            System.out.println("[SERVER] Setting: " + cleanRef + " [" + fc + "] = " + value);

            ModelNode node = serverModel.findModelNode(cleanRef, fc);

            // Si no se encuentra, probar con otros FCs
            if (node == null) {
                Fc[] fcsToTry = {Fc.ST, Fc.MX, Fc.CO, Fc.CF, Fc.SP, Fc.SG};
                for (Fc tryFc : fcsToTry) {
                    if (tryFc != fc) {
                        node = serverModel.findModelNode(cleanRef, tryFc);
                        if (node != null) {
                            fc = tryFc;
                            break;
                        }
                    }
                }
            }

            if (node instanceof BasicDataAttribute) {
                BasicDataAttribute bda = (BasicDataAttribute) node;

                // Notificar a clientes via reports (solo si el servidor está activo)
                // setValues() requires BDAs from getModelCopy() (which have mirror→original).
                // Passing original BDAs directly causes NPE (mirror=null on originals).
                if (running && serverSap != null) {
                    try {
                        // Get a fresh copy; its BDAs have mirror pointing to the originals
                        com.beanit.iec61850bean.ServerModel copyModel = serverSap.getModelCopy();
                        BasicDataAttribute copyBda = (BasicDataAttribute) copyModel.findModelNode(cleanRef, fc);
                        if (copyBda != null) {
                            // Set new value on COPY (setValues compares copy vs original to detect change)
                            setBasicDataAttributeValue(copyBda, value);
                            List<BasicDataAttribute> changedData = new ArrayList<>();
                            changedData.add(copyBda);
                            serverSap.setValues(changedData);
                            System.out.println("[SERVER] Clients notified via reports for " + cleanRef);
                        } else {
                            // Fallback: update original directly (no report)
                            setBasicDataAttributeValue(bda, value);
                        }
                    } catch (Exception e) {
                        System.out.println("[SERVER] setValues error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                        setBasicDataAttributeValue(bda, value);
                    }
                } else {
                    setBasicDataAttributeValue(bda, value);
                }

                String newValue = bda.getValueString();
                System.out.println("[SERVER] Value set: " + cleanRef + " [" + fc + "] = " + newValue);

                return true;
            } else {
                System.err.println("[ERROR] Node not found: " + cleanRef);
                return false;
            }

        } catch (Exception e) {
            System.err.println("[ERROR] Setting value: " + e.getMessage());
            return false;
        }
    }

    private Fc extractFc(String nodeRef) {
        if (nodeRef.contains("$")) {
            String[] parts = nodeRef.split("\\$");
            if (parts.length >= 2) {
                String lastPart = parts[parts.length - 1];
                Fc fc = tryParseFc(lastPart);
                if (fc != null) return fc;

                fc = tryParseFc(parts[1]);
                if (fc != null) return fc;
            }
        }
        return Fc.ST;
    }

    private Fc tryParseFc(String fcStr) {
        try {
            return Fc.valueOf(fcStr);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String cleanReference(String nodeRef) {
        if (nodeRef.contains("$")) {
            String[] parts = nodeRef.split("\\$");
            String lastPart = parts[parts.length - 1];
            if (tryParseFc(lastPart) != null) {
                return parts[0];
            }

            if (parts.length >= 3) {
                StringBuilder sb = new StringBuilder(parts[0]);
                for (int i = 2; i < parts.length; i++) {
                    sb.append(".").append(parts[i]);
                }
                return sb.toString();
            }
        }
        return nodeRef;
    }

    /**
     * Establece valor en BDA (igual que la APK)
     */
    private void setBasicDataAttributeValue(BasicDataAttribute bda, String value) {
        try {
            if (bda instanceof BdaBoolean) {
                ((BdaBoolean) bda).setValue(Boolean.parseBoolean(value) || "1".equals(value));
            } else if (bda instanceof BdaInt8) {
                ((BdaInt8) bda).setValue(Byte.parseByte(value));
            } else if (bda instanceof BdaInt16) {
                ((BdaInt16) bda).setValue(Short.parseShort(value));
            } else if (bda instanceof BdaInt32) {
                ((BdaInt32) bda).setValue(Integer.parseInt(value));
            } else if (bda instanceof BdaInt64) {
                ((BdaInt64) bda).setValue(Long.parseLong(value));
            } else if (bda instanceof BdaFloat32) {
                ((BdaFloat32) bda).setFloat(Float.parseFloat(value));
            } else if (bda instanceof BdaFloat64) {
                ((BdaFloat64) bda).setDouble(Double.parseDouble(value));
            } else if (bda instanceof BdaVisibleString) {
                ((BdaVisibleString) bda).setValue(value);
            } else if (bda instanceof BdaDoubleBitPos) {
                setDbposValue((BdaDoubleBitPos) bda, value);
            } else if (bda instanceof BdaCheck) {
                BdaCheck check = (BdaCheck) bda;
                check.setSynchrocheck("true".equalsIgnoreCase(value) || "1".equals(value));
            } else if (bda instanceof BdaTapCommand) {
                setTapCommandValue((BdaTapCommand) bda, value);
            } else if (bda instanceof BdaInt8U) {
                try {
                    ((BdaInt8U) bda).setValue(Short.parseShort(value.trim()));
                } catch (NumberFormatException e2) {
                    System.err.println("[ERROR] BdaInt8U value must be integer: " + value);
                }
            } else if (bda instanceof BdaInt16U) {
                try {
                    ((BdaInt16U) bda).setValue(Integer.parseInt(value.trim()));
                } catch (NumberFormatException e2) {
                    System.err.println("[ERROR] BdaInt16U value must be integer: " + value);
                }
            }
        } catch (Exception e) {
            System.err.println("[ERROR] Setting value: " + e.getMessage());
        }
    }

    private void setDbposValue(BdaDoubleBitPos dbpos, String value) {
        try {
            String lowerValue = value.toLowerCase().trim();

            BdaDoubleBitPos.DoubleBitPos newPos;
            if (lowerValue.equals("off") || lowerValue.equals("01") || lowerValue.equals("1")) {
                newPos = BdaDoubleBitPos.DoubleBitPos.OFF;
            } else if (lowerValue.equals("on") || lowerValue.equals("10") || lowerValue.equals("2")) {
                newPos = BdaDoubleBitPos.DoubleBitPos.ON;
            } else if (lowerValue.equals("intermediate") || lowerValue.equals("00") || lowerValue.equals("0")) {
                newPos = BdaDoubleBitPos.DoubleBitPos.INTERMEDIATE_STATE;
            } else if (lowerValue.equals("bad") || lowerValue.equals("11") || lowerValue.equals("3")) {
                newPos = BdaDoubleBitPos.DoubleBitPos.BAD_STATE;
            } else {
                newPos = BdaDoubleBitPos.DoubleBitPos.INTERMEDIATE_STATE;
            }

            // Usar setValue con byte array directamente para evitar problemas con mirror
            byte[] bytes = new byte[1];
            switch (newPos) {
                case INTERMEDIATE_STATE: bytes[0] = 0x00; break;
                case OFF: bytes[0] = 0x40; break;  // 01 in MSB
                case ON: bytes[0] = (byte) 0x80; break;  // 10 in MSB
                case BAD_STATE: bytes[0] = (byte) 0xC0; break;  // 11 in MSB
            }
            dbpos.setValue(bytes);
            System.out.println("[SERVER] DoubleBitPos set to: " + newPos);
        } catch (Exception e) {
            System.err.println("[ERROR] Setting DoubleBitPos: " + e.getMessage());
        }
    }

    private void setTapCommandValue(BdaTapCommand tap, String value) {
        String lowerValue = value.toLowerCase().trim();

        if (lowerValue.equals("stop") || lowerValue.equals("0")) {
            tap.setTapCommand(BdaTapCommand.TapCommand.STOP);
        } else if (lowerValue.equals("lower") || lowerValue.equals("1")) {
            tap.setTapCommand(BdaTapCommand.TapCommand.LOWER);
        } else if (lowerValue.equals("higher") || lowerValue.equals("2")) {
            tap.setTapCommand(BdaTapCommand.TapCommand.HIGHER);
        } else if (lowerValue.equals("reserved") || lowerValue.equals("3")) {
            tap.setTapCommand(BdaTapCommand.TapCommand.RESERVED);
        }
    }

    /**
     * Obtiene un atributo por referencia
     */
    public BasicDataAttribute getAttribute(String reference) {
        return attributeRefs.get(reference);
    }

    /**
     * Obtiene todas las referencias de atributos
     */
    public Set<String> getAttributeReferences() {
        return attributeRefs.keySet();
    }

    public int getPort() {
        return port;
    }

    // ServerEventListener implementation

    @Override
    public void serverStoppedListening(ServerSap serverSap) {
        System.out.println("[SERVER] Server stopped listening");
        running = false;
        if (listener != null) listener.onServerStopped();
    }

    @Override
    public List<ServiceError> write(List<BasicDataAttribute> bdas) {
        System.out.println("[SERVER] Client writing " + bdas.size() + " values");

        for (BasicDataAttribute bda : bdas) {
            String ref = bda.getReference().toString();
            String value = bda.getValueString();
            System.out.println("[SERVER] Write: " + ref + " = " + value);

            if (listener != null) {
                listener.onClientWrite(ref, value);
            }
        }

        return null; // No errors
    }

    /**
     * Imprime información detallada del modelo para debugging
     */
    private void debugPrintModelInfo(ServerModel model) {
        if (model == null) {
            System.out.println("[DEBUG] ServerModel is NULL!");
            return;
        }

        System.out.println("\n========== MODEL DEBUG INFO ==========");

        for (ModelNode ldNode : model.getChildren()) {
            LogicalDevice ld = (LogicalDevice) ldNode;
            System.out.println("[DEBUG] LD: " + ld.getName());

            int lnCount = 0;
            for (ModelNode lnNode : ld.getChildren()) {
                lnCount++;
                if (lnNode instanceof LogicalNode) {
                    LogicalNode ln = (LogicalNode) lnNode;
                    System.out.println("[DEBUG]   LN: " + ln.getName());

                    // Mostrar Reports (URCBs)
                    try {
                        Collection<Urcb> urcbs = ln.getUrcbs();
                        if (urcbs != null && !urcbs.isEmpty()) {
                            System.out.println("[DEBUG]     URCBs: " + urcbs.size());
                            for (Urcb urcb : urcbs) {
                                System.out.println("[DEBUG]       URCB: " + urcb.getName());
                            }
                        }
                    } catch (Exception e) {
                        // Ignorar si no hay URCBs
                    }

                    // Mostrar Reports (BRCBs)
                    try {
                        Collection<Brcb> brcbs = ln.getBrcbs();
                        if (brcbs != null && !brcbs.isEmpty()) {
                            System.out.println("[DEBUG]     BRCBs: " + brcbs.size());
                            for (Brcb brcb : brcbs) {
                                System.out.println("[DEBUG]       BRCB: " + brcb.getName());
                            }
                        }
                    } catch (Exception e) {
                        // Ignorar si no hay BRCBs
                    }
                }
            }
            System.out.println("[DEBUG]   Total LNs in LD: " + lnCount);
        }
        System.out.println("=======================================\n");
    }
}
