package com.iednavigator;

import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.*;

/**
 * Motor de comparación de archivos SCL (ICD/CID/SCD).
 * Genera un "snapshot" plano de cada archivo (clave → valor) y calcula las
 * diferencias por categoría. Pensado para puesta en servicio: detectar qué
 * cambió entre dos revisiones de la configuración de un IED o subestación.
 *
 * Categorías comparadas:
 *   IED            — atributos del IED (manufacturer, type, configVersion...)
 *   LN             — inventario de Logical Nodes por LDevice (lnType)
 *   DataSet        — existencia y miembros FCDA
 *   GoCB           — GSEControl (datSet, confRev, appID/goID)
 *   Report         — ReportControl (rptID, datSet, confRev, buffered, intgPd)
 *   Communication  — IP del ConnectedAP y parámetros GSE (MAC, APPID, VLAN, tiempos)
 *   Valores        — valores instanciados DAI/Val (ajustes)
 */
public class SclCompare {

    /** Cuánto se mira del archivo antes de decidir que no es XML. */
    private static final int SNIFF_BYTES = 512;

    /**
     * Revisa un archivo antes de dárselo al parser y devuelve el problema en términos del
     * archivo, o {@code null} si parece XML utilizable.
     *
     * Sin esto, un archivo perdido llega al parser XML y lo que sale al registro es el texto
     * crudo del parser: «El contenido no está permitido en el prólogo». Eso describe lo que le
     * pasó al parser, no lo que le pasa al archivo, y manda a buscar un bug donde no lo hay —
     * quedó cuatro veces en el registro del 09-08 antes de que se entendiera que los archivos
     * estaban en cero.
     *
     * El caso de los ceros merece nombre propio: tamaño correcto y contenido íntegramente
     * nulo es la firma de una copia desde pendrive o carpeta de red donde se reservó el
     * espacio y nunca se volcaron los datos. No se recupera; hay que reexportar del equipo o
     * recopiar del medio original, y eso es lo que conviene que diga el mensaje.
     *
     * @return clave i18n ya resuelta con sus argumentos, o null si no hay nada que objetar
     */
    static String describeFileProblem(File f) {
        if (f == null || !f.isFile()) {
            return I18n.t("scl.file.unreadable", f == null ? "?" : f.getName());
        }
        long len = f.length();
        if (len == 0) {
            return I18n.t("scl.file.empty", f.getName());
        }
        try (java.io.InputStream in = new java.io.BufferedInputStream(new java.io.FileInputStream(f))) {
            // Primer byte distinto de cero: corta apenas lo encuentra, así un archivo sano
            // cuesta una sola lectura y sólo se recorre entero el que efectivamente está nulo.
            byte[] buf = new byte[8192];
            boolean todoCeros = true;
            byte[] inicio = new byte[SNIFF_BYTES];
            int inicioLen = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                if (inicioLen < SNIFF_BYTES) {
                    int copiar = Math.min(n, SNIFF_BYTES - inicioLen);
                    System.arraycopy(buf, 0, inicio, inicioLen, copiar);
                    inicioLen += copiar;
                }
                for (int i = 0; i < n; i++) {
                    if (buf[i] != 0) { todoCeros = false; break; }
                }
                if (!todoCeros) break;
            }
            if (todoCeros) {
                return I18n.t("scl.file.zeros", f.getName(), String.valueOf(len));
            }
            if (!pareceXml(inicio, inicioLen)) {
                return I18n.t("scl.file.notxml", f.getName());
            }
        } catch (java.io.IOException e) {
            return I18n.t("scl.file.unreadable", f.getName());
        }
        return null;
    }

    /** ¿El primer carácter con contenido es '<'? Salta BOM y espacios, que son válidos antes del prólogo. */
    private static boolean pareceXml(byte[] inicio, int len) {
        int i = 0;
        if (len >= 3 && (inicio[0] & 0xFF) == 0xEF && (inicio[1] & 0xFF) == 0xBB && (inicio[2] & 0xFF) == 0xBF) {
            i = 3;   // BOM UTF-8
        }
        for (; i < len; i++) {
            byte b = inicio[i];
            if (b == ' ' || b == '\t' || b == '\r' || b == '\n') continue;
            return b == '<';
        }
        return false;   // sólo espacios en los primeros bytes
    }

    public static class Difference {
        public final String category;
        public final String element;
        public final String valueA;   // null = no existe en A
        public final String valueB;   // null = no existe en B
        Difference(String category, String element, String valueA, String valueB) {
            this.category = category;
            this.element = element;
            this.valueA = valueA;
            this.valueB = valueB;
        }
        public String status() {
            if (valueA == null) return "Solo en B";
            if (valueB == null) return "Solo en A";
            return "Diferente";
        }
    }

    public static final String[] CATEGORIES =
        {"IED", "LN", "DataSet", "GoCB", "Report", "Communication", "Valores"};

    /** Compara dos archivos SCL y devuelve la lista de diferencias. */
    static List<Difference> compare(File fileA, File fileB) throws Exception {
        return compare(fileA, fileB, false);
    }

    /**
     * Compara dos archivos SCL. Si ignoreIedName=true, los nombres de IED se
     * sustituyen por alias posicionales (IED#1, IED#2...) para poder comparar
     * configuraciones idénticas donde solo cambió el nombre del IED.
     */
    public static List<Difference> compare(File fileA, File fileB, boolean ignoreIedName) throws Exception {
        Map<String, String> snapA = snapshot(fileA, ignoreIedName);
        Map<String, String> snapB = snapshot(fileB, ignoreIedName);

        List<Difference> diffs = new ArrayList<>();
        Set<String> allKeys = new TreeSet<>();
        allKeys.addAll(snapA.keySet());
        allKeys.addAll(snapB.keySet());

        for (String key : allKeys) {
            String a = snapA.get(key);
            String b = snapB.get(key);
            if (Objects.equals(a, b)) continue;
            int sep = key.indexOf('|');
            String category = key.substring(0, sep);
            String element = key.substring(sep + 1);
            diffs.add(new Difference(category, element, a, b));
        }
        return diffs;
    }

    /** Construye el snapshot plano clave→valor de un archivo SCL. */
    static Map<String, String> snapshot(File sclFile) throws Exception {
        return snapshot(sclFile, false);
    }

    static Map<String, String> snapshot(File sclFile, boolean ignoreIedName) throws Exception {
        Map<String, String> snap = new LinkedHashMap<>();
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(sclFile);

        // Mapa nombre real → alias (posicional) cuando se ignora el nombre
        Map<String, String> nameAlias = new HashMap<>();
        NodeList ieds = doc.getElementsByTagName("IED");
        for (int i = 0; i < ieds.getLength(); i++) {
            Element ied = (Element) ieds.item(i);
            String realName = ied.getAttribute("name");
            String iedName = ignoreIedName ? "IED#" + (i + 1) : realName;
            nameAlias.put(realName, iedName);
            snapshotIedAttributes(snap, ied, iedName);
            snapshotLNodes(snap, ied, iedName);
            snapshotDataSets(snap, ied, iedName);
            snapshotGoCBs(snap, ied, iedName);
            snapshotReports(snap, ied, iedName);
            snapshotDaiValues(snap, ied, iedName);
        }
        snapshotCommunication(snap, doc, ignoreIedName ? nameAlias : null);

        // Normalizar también los valores: appID/rptID suelen embeber el nombre del IED
        if (ignoreIedName && !nameAlias.isEmpty()) {
            for (Map.Entry<String, String> e : snap.entrySet()) {
                String v = e.getValue();
                for (Map.Entry<String, String> alias : nameAlias.entrySet()) {
                    if (!alias.getKey().isEmpty() && v.contains(alias.getKey())) {
                        v = v.replace(alias.getKey(), alias.getValue());
                    }
                }
                e.setValue(v);
            }
        }
        return snap;
    }

    // ────────────────────────────────────────────────────────────────────────

    private static void snapshotIedAttributes(Map<String, String> snap, Element ied, String iedName) {
        String[] attrs = {"manufacturer", "type", "desc", "configVersion", "originalSclVersion"};
        for (String a : attrs) {
            String v = ied.getAttribute(a);
            if (!v.isEmpty()) snap.put("IED|" + iedName + " @" + a, v);
        }
    }

    private static void snapshotLNodes(Map<String, String> snap, Element ied, String iedName) {
        NodeList lds = ied.getElementsByTagName("LDevice");
        for (int i = 0; i < lds.getLength(); i++) {
            Element ld = (Element) lds.item(i);
            String ldInst = ld.getAttribute("inst");
            NodeList children = ld.getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                if (!(children.item(j) instanceof Element)) continue;
                Element ln = (Element) children.item(j);
                String tag = ln.getTagName();
                if (!tag.equals("LN") && !tag.equals("LN0")) continue;
                String name = ln.getAttribute("prefix") + ln.getAttribute("lnClass") + ln.getAttribute("inst");
                snap.put("LN|" + iedName + " " + ldInst + "/" + name, ln.getAttribute("lnType"));
            }
        }
    }

    private static void snapshotDataSets(Map<String, String> snap, Element ied, String iedName) {
        NodeList dss = ied.getElementsByTagName("DataSet");
        for (int i = 0; i < dss.getLength(); i++) {
            Element ds = (Element) dss.item(i);
            String dsPath = iedName + " " + parentLdInst(ds) + "/" + ds.getAttribute("name");
            snap.put("DataSet|" + dsPath, "existe");
            NodeList fcdas = ds.getElementsByTagName("FCDA");
            for (int j = 0; j < fcdas.getLength(); j++) {
                Element f = (Element) fcdas.item(j);
                String member = f.getAttribute("ldInst") + "/" + f.getAttribute("prefix")
                    + f.getAttribute("lnClass") + f.getAttribute("lnInst")
                    + "." + f.getAttribute("doName");
                String daName = f.getAttribute("daName");
                if (!daName.isEmpty()) member += "." + daName;
                member += " [" + f.getAttribute("fc") + "]";
                snap.put("DataSet|" + dsPath + " ∋ " + member, "miembro");
            }
        }
    }

    private static void snapshotGoCBs(Map<String, String> snap, Element ied, String iedName) {
        NodeList gcbs = ied.getElementsByTagName("GSEControl");
        for (int i = 0; i < gcbs.getLength(); i++) {
            Element g = (Element) gcbs.item(i);
            String base = "GoCB|" + iedName + " " + parentLdInst(g) + "." + g.getAttribute("name");
            putIfNotEmpty(snap, base + " @datSet",  g.getAttribute("datSet"));
            putIfNotEmpty(snap, base + " @confRev", g.getAttribute("confRev"));
            putIfNotEmpty(snap, base + " @appID",   g.getAttribute("appID"));
        }
    }

    private static void snapshotReports(Map<String, String> snap, Element ied, String iedName) {
        NodeList rpts = ied.getElementsByTagName("ReportControl");
        for (int i = 0; i < rpts.getLength(); i++) {
            Element r = (Element) rpts.item(i);
            String base = "Report|" + iedName + " " + parentLdInst(r) + "." + r.getAttribute("name");
            putIfNotEmpty(snap, base + " @rptID",    r.getAttribute("rptID"));
            putIfNotEmpty(snap, base + " @datSet",   r.getAttribute("datSet"));
            putIfNotEmpty(snap, base + " @confRev",  r.getAttribute("confRev"));
            putIfNotEmpty(snap, base + " @buffered", r.getAttribute("buffered"));
            putIfNotEmpty(snap, base + " @intgPd",   r.getAttribute("intgPd"));
        }
    }

    private static void snapshotCommunication(Map<String, String> snap, Document doc,
                                              Map<String, String> nameAlias) {
        NodeList aps = doc.getElementsByTagName("ConnectedAP");
        for (int i = 0; i < aps.getLength(); i++) {
            Element ap = (Element) aps.item(i);
            String apIed = ap.getAttribute("iedName");
            if (nameAlias != null && nameAlias.containsKey(apIed)) apIed = nameAlias.get(apIed);
            String apPath = apIed + "/" + ap.getAttribute("apName");

            // Dirección IP del AP (P type="IP" dentro de <Address> directo del AP)
            NodeList addrs = ap.getElementsByTagName("Address");
            for (int j = 0; j < addrs.getLength(); j++) {
                Element addr = (Element) addrs.item(j);
                if (!(addr.getParentNode() instanceof Element)) continue;
                if (!"ConnectedAP".equals(((Element) addr.getParentNode()).getTagName())) continue;
                NodeList ps = addr.getElementsByTagName("P");
                for (int k = 0; k < ps.getLength(); k++) {
                    Element p = (Element) ps.item(k);
                    String type = p.getAttribute("type");
                    if (type.equals("IP") || type.equals("IP-SUBNET") || type.equals("IP-GATEWAY")) {
                        snap.put("Communication|" + apPath + " @" + type, p.getTextContent().trim());
                    }
                }
            }

            // Parámetros GSE (reutiliza el parser de SclFileProcessor)
            NodeList gses = ap.getElementsByTagName("GSE");
            for (int j = 0; j < gses.getLength(); j++) {
                Element gse = (Element) gses.item(j);
                String gsePath = apPath + " GSE " + gse.getAttribute("ldInst") + "/" + gse.getAttribute("cbName");
                Map<String, String> info = SclFileProcessor.parseGseElement(gse);
                String[][] keys = {{"mac", "MAC"}, {"appid", "APPID"}, {"vlanid", "VLAN-ID"},
                                   {"vlanprio", "VLAN-PRIORITY"}, {"mintime", "MinTime"}, {"maxtime", "MaxTime"}};
                for (String[] k : keys) {
                    if (info.containsKey(k[0])) {
                        snap.put("Communication|" + gsePath + " @" + k[1], info.get(k[0]));
                    }
                }
            }
        }
    }

    /** Valores instanciados: LDevice > LN/LN0 > DOI > (SDI)* > DAI > Val */
    private static void snapshotDaiValues(Map<String, String> snap, Element ied, String iedName) {
        NodeList lds = ied.getElementsByTagName("LDevice");
        for (int i = 0; i < lds.getLength(); i++) {
            Element ld = (Element) lds.item(i);
            String ldInst = ld.getAttribute("inst");
            NodeList children = ld.getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                if (!(children.item(j) instanceof Element)) continue;
                Element ln = (Element) children.item(j);
                String tag = ln.getTagName();
                if (!tag.equals("LN") && !tag.equals("LN0")) continue;
                String lnName = ln.getAttribute("prefix") + ln.getAttribute("lnClass") + ln.getAttribute("inst");
                String basePath = iedName + " " + ldInst + "/" + lnName;
                NodeList lnChildren = ln.getChildNodes();
                for (int k = 0; k < lnChildren.getLength(); k++) {
                    if (!(lnChildren.item(k) instanceof Element)) continue;
                    Element doi = (Element) lnChildren.item(k);
                    if (!"DOI".equals(doi.getTagName())) continue;
                    walkDai(snap, doi, basePath + "." + doi.getAttribute("name"));
                }
            }
        }
    }

    private static void walkDai(Map<String, String> snap, Element parent, String path) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element)) continue;
            Element el = (Element) children.item(i);
            String tag = el.getTagName();
            if ("SDI".equals(tag)) {
                walkDai(snap, el, path + "." + el.getAttribute("name"));
            } else if ("DAI".equals(tag)) {
                String daiPath = path + "." + el.getAttribute("name");
                NodeList vals = el.getElementsByTagName("Val");
                if (vals.getLength() > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (int v = 0; v < vals.getLength(); v++) {
                        if (sb.length() > 0) sb.append(" | ");
                        sb.append(vals.item(v).getTextContent().trim());
                    }
                    snap.put("Valores|" + daiPath, sb.toString());
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────

    /** Sube por el DOM hasta encontrar el LDevice padre y devuelve su inst. */
    private static String parentLdInst(Element el) {
        org.w3c.dom.Node parent = el.getParentNode();
        while (parent != null) {
            if (parent instanceof Element && "LDevice".equals(((Element) parent).getTagName())) {
                return ((Element) parent).getAttribute("inst");
            }
            parent = parent.getParentNode();
        }
        return "?";
    }

    private static void putIfNotEmpty(Map<String, String> snap, String key, String value) {
        if (value != null && !value.isEmpty()) snap.put(key, value);
    }
}
