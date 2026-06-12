package com.iednavigator;

import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.File;
import java.util.*;
import java.util.function.Consumer;

/**
 * Procesa archivos SCL (ICD/CID/SCD) y extrae estructuras de datos IEC 61850.
 * Extraído de IEDNavigatorApp.java — Fase 2 de refactorización.
 *
 * Métodos originales movidos aquí:
 *   parseSclDataTypeTemplates, parseGoCBsFromScl (×2),
 *   parseDataSetsFromIED, parseReportsFromIED.
 */
public class SclFileProcessor {

    /** Agrupa todos los resultados de un parsing SCL. */
    public static class SclParsingResult {
        public String iedName = "";
        public String[] iedNameplate = {"", "", "", ""}; // manufacturer, type, desc, configVersion
        public List<SclGoCB>    goCBs     = new ArrayList<>();
        public List<SclDataSet> dataSets  = new ArrayList<>();
        List<SclReport>  reports   = new ArrayList<>();
        Map<String, LinkedHashMap<Integer, String>> enumTypes  = new HashMap<>();
        Map<String, String>              daEnumType      = new HashMap<>();
        Map<String, Map<String, String>> lnTypeDoTypes   = new HashMap<>();
        Map<String, String>              lnClassToLnType = new HashMap<>();
    }

    // -----------------------------------------------------------------------
    // Métodos públicos
    // -----------------------------------------------------------------------

    /**
     * Parsea el primer IED del archivo SCL (sin filtro de índice).
     * Equivalente al original parseGoCBsFromScl(File sclFile) de IEDNavigatorApp.
     */
    public static SclParsingResult parseFirstIED(File sclFile, Consumer<String> log) {
        SclParsingResult result = new SclParsingResult();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(sclFile);

            // Obtener nombre del IED (solo se procesa el primero del archivo)
            Element iedEl = null;
            NodeList iedNodes = doc.getElementsByTagName("IED");
            if (iedNodes.getLength() > 0) {
                iedEl = (Element) iedNodes.item(0);
                result.iedName = iedEl.getAttribute("name");
                result.iedNameplate = new String[] {
                    iedEl.getAttribute("manufacturer"),
                    iedEl.getAttribute("type"),
                    iedEl.getAttribute("desc"),
                    iedEl.getAttribute("configVersion")
                };
            }

            // Parsear DataTypeTemplates para lookup de enumeraciones
            parseSclDataTypeTemplates(doc, result);

            // Buscar GSEControl SOLO dentro del primer IED (un SCD/CID multi-IED
            // contiene GoCBs de otros IEDs que no deben mezclarse)
            NodeList gseControls = iedEl != null
                ? iedEl.getElementsByTagName("GSEControl")
                : doc.getElementsByTagName("GSEControl");
            if (log != null) log.accept("Encontrados " + gseControls.getLength() + " elementos GSEControl");

            // Mapa para obtener info de Communication/GSE
            Map<String, Map<String, String>> gseInfo = new HashMap<>();

            // Parsear Communication/GSE solo de los ConnectedAP del primer IED
            NodeList connectedAPs = doc.getElementsByTagName("ConnectedAP");
            for (int c = 0; c < connectedAPs.getLength(); c++) {
                Element connAP = (Element) connectedAPs.item(c);
                if (!result.iedName.isEmpty()
                        && !result.iedName.equals(connAP.getAttribute("iedName"))) continue;
                NodeList gseNodes = connAP.getElementsByTagName("GSE");
                for (int i = 0; i < gseNodes.getLength(); i++) {
                    Element gse = (Element) gseNodes.item(i);
                    String ldInst = gse.getAttribute("ldInst");
                    String cbName = gse.getAttribute("cbName");
                    String key = ldInst + "/" + cbName;
                    gseInfo.put(key, parseGseElement(gse));
                }
            }

            // Procesar cada GSEControl
            for (int i = 0; i < gseControls.getLength(); i++) {
                Element gseCtrl = (Element) gseControls.item(i);

                SclGoCB gcb = new SclGoCB();
                gcb.cbName = gseCtrl.getAttribute("name");
                gcb.datSet = gseCtrl.getAttribute("datSet");

                // GSEControl "appID" attribute is actually the goID string
                String sclAppId = gseCtrl.getAttribute("appID");
                gcb.goID = sclAppId;

                String confRevStr = gseCtrl.getAttribute("confRev");
                if (!confRevStr.isEmpty()) {
                    try {
                        gcb.confRev = Integer.parseInt(confRevStr);
                    } catch (NumberFormatException e) {
                        gcb.confRev = 1;
                    }
                }

                // Obtener LDevice inst del padre
                org.w3c.dom.Node parent = gseCtrl.getParentNode();
                while (parent != null) {
                    if (parent instanceof Element) {
                        Element parentEl = (Element) parent;
                        if ("LN0".equals(parentEl.getTagName())) {
                            gcb.lnClass = "LLN0";
                        } else if ("LDevice".equals(parentEl.getTagName())) {
                            gcb.ldInst = parentEl.getAttribute("inst");
                            break;
                        }
                    }
                    parent = parent.getParentNode();
                }

                if (gcb.lnClass == null) {
                    gcb.lnClass = "LLN0"; // Default
                }

                // Obtener info de Communication/GSE (APPID, MAC, VLAN, MinTime/MaxTime)
                String key = gcb.ldInst + "/" + gcb.cbName;
                if (gseInfo.containsKey(key)) {
                    applyGseInfo(gcb, gseInfo.get(key));
                }

                result.goCBs.add(gcb);
                if (log != null) log.accept("  GoCB: " + gcb.toString() + " appID=" + gcb.appID + " goID=" + gcb.goID);
            }

        } catch (Exception e) {
            if (log != null) log.accept("Error parseando GoCBs del SCL: " + e.getMessage());
            e.printStackTrace();
        }
        return result;
    }

    /**
     * Parsea un IED específico por índice en un SCL multi-IED.
     * Equivalente al original parseGoCBsFromScl(File sclFile, int iedIndex) de IEDNavigatorApp.
     */
    public static SclParsingResult parseIEDByIndex(File sclFile, int iedIndex, Consumer<String> log) {
        SclParsingResult result = new SclParsingResult();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(sclFile);

            // Obtener lista de IEDs
            NodeList ieds = doc.getElementsByTagName("IED");
            if (iedIndex >= ieds.getLength()) {
                if (log != null) log.accept("Índice de IED inválido: " + iedIndex);
                return parseFirstIED(sclFile, log); // Fallback
            }

            Element selectedIED = (Element) ieds.item(iedIndex);
            String iedName = selectedIED.getAttribute("name");
            result.iedName = iedName;
            result.iedNameplate = new String[] {
                selectedIED.getAttribute("manufacturer"),
                selectedIED.getAttribute("type"),
                selectedIED.getAttribute("desc"),
                selectedIED.getAttribute("configVersion")
            };
            if (log != null) log.accept("Parseando SCL para IED: " + iedName);

            // Parsear DataTypeTemplates para lookup de enumeraciones
            parseSclDataTypeTemplates(doc, result);

            // Parsear DataSets del IED seleccionado
            parseDataSetsFromIED(selectedIED, result);
            if (log != null) log.accept("DataSets encontrados: " + result.dataSets.size());

            // Parsear ReportControl del IED seleccionado
            parseReportsFromIED(selectedIED, result);
            if (log != null) log.accept("Reports encontrados: " + result.reports.size());

            // Mapa para obtener info de Communication/GSE para este IED
            Map<String, Map<String, String>> gseInfo = new HashMap<>();

            // Parsear Communication/GSE solo para el IED seleccionado
            NodeList connectedAPs = doc.getElementsByTagName("ConnectedAP");
            for (int c = 0; c < connectedAPs.getLength(); c++) {
                Element connAP = (Element) connectedAPs.item(c);
                if (!iedName.equals(connAP.getAttribute("iedName"))) continue;

                NodeList gseNodes = connAP.getElementsByTagName("GSE");
                for (int i = 0; i < gseNodes.getLength(); i++) {
                    Element gse = (Element) gseNodes.item(i);
                    String ldInst = gse.getAttribute("ldInst");
                    String cbName = gse.getAttribute("cbName");
                    String key = ldInst + "/" + cbName;
                    gseInfo.put(key, parseGseElement(gse));
                }
            }

            // Buscar GSEControl solo dentro del IED seleccionado
            NodeList gseControls = selectedIED.getElementsByTagName("GSEControl");
            if (log != null) log.accept("Encontrados " + gseControls.getLength() + " GSEControl para " + iedName);

            for (int i = 0; i < gseControls.getLength(); i++) {
                Element gseCtrl = (Element) gseControls.item(i);

                SclGoCB gcb = new SclGoCB();
                gcb.cbName = gseCtrl.getAttribute("name");
                gcb.datSet = gseCtrl.getAttribute("datSet");

                // GSEControl "appID" attribute is actually the goID string (IEC 61850 SCL naming)
                String sclAppId = gseCtrl.getAttribute("appID");
                gcb.goID = sclAppId;

                String confRevStr = gseCtrl.getAttribute("confRev");
                if (!confRevStr.isEmpty()) {
                    try { gcb.confRev = Integer.parseInt(confRevStr); }
                    catch (NumberFormatException e) { gcb.confRev = 1; }
                }

                // Obtener LDevice inst del padre
                org.w3c.dom.Node parent = gseCtrl.getParentNode();
                while (parent != null) {
                    if (parent instanceof Element) {
                        Element parentEl = (Element) parent;
                        if ("LN0".equals(parentEl.getTagName())) {
                            gcb.lnClass = "LLN0";
                        } else if ("LDevice".equals(parentEl.getTagName())) {
                            gcb.ldInst = parentEl.getAttribute("inst");
                            break;
                        }
                    }
                    parent = parent.getParentNode();
                }

                if (gcb.lnClass == null) gcb.lnClass = "LLN0";

                // Obtener info de Communication/GSE (APPID, MAC, VLAN, MinTime/MaxTime)
                String key = gcb.ldInst + "/" + gcb.cbName;
                if (gseInfo.containsKey(key)) {
                    applyGseInfo(gcb, gseInfo.get(key));
                }

                result.goCBs.add(gcb);
                if (log != null) log.accept("  GoCB: " + gcb.toString() + " appID=" + gcb.appID + " goID=" + gcb.goID);
            }

        } catch (Exception e) {
            if (log != null) log.accept("Error parseando GoCBs: " + e.getMessage());
            e.printStackTrace();
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Helpers de parsing de Communication/GSE (compartidos con GoosePanel)
    // -----------------------------------------------------------------------

    /**
     * Extrae los parámetros de un elemento {@code <GSE>} de la sección Communication:
     * MAC-Address, APPID, VLAN-ID, VLAN-PRIORITY (hijos {@code <P>} de Address)
     * y MinTime/MaxTime (hijos directos del GSE, en ms).
     */
    static Map<String, String> parseGseElement(Element gse) {
        Map<String, String> info = new HashMap<>();
        NodeList pNodes = gse.getElementsByTagName("P");
        for (int j = 0; j < pNodes.getLength(); j++) {
            Element p = (Element) pNodes.item(j);
            String type = p.getAttribute("type");
            String value = p.getTextContent().trim();
            if ("MAC-Address".equals(type)) {
                info.put("mac", value);
            } else if ("APPID".equals(type)) {
                info.put("appid", value);
            } else if ("VLAN-ID".equals(type)) {
                info.put("vlanid", value);
            } else if ("VLAN-PRIORITY".equals(type)) {
                info.put("vlanprio", value);
            }
        }
        NodeList minTimes = gse.getElementsByTagName("MinTime");
        if (minTimes.getLength() > 0) {
            info.put("mintime", minTimes.item(0).getTextContent().trim());
        }
        NodeList maxTimes = gse.getElementsByTagName("MaxTime");
        if (maxTimes.getLength() > 0) {
            info.put("maxtime", maxTimes.item(0).getTextContent().trim());
        }
        return info;
    }

    /**
     * Aplica al GoCB la info extraída con {@link #parseGseElement(Element)}.
     * VLAN-ID es hexadecimal según IEC 61850-6; VLAN-PRIORITY y tiempos son decimales.
     */
    static void applyGseInfo(SclGoCB gcb, Map<String, String> info) {
        gcb.macAddress = info.get("mac");
        if (info.containsKey("appid")) {
            gcb.appID = info.get("appid");
        }
        if (info.containsKey("vlanid")) {
            try { gcb.vlanId = Integer.parseInt(info.get("vlanid"), 16); }
            catch (NumberFormatException ignore) {}
        }
        if (info.containsKey("vlanprio")) {
            try { gcb.vlanPriority = Integer.parseInt(info.get("vlanprio")); }
            catch (NumberFormatException ignore) {}
        }
        if (info.containsKey("mintime")) {
            try { gcb.minTime = Integer.parseInt(info.get("mintime")); }
            catch (NumberFormatException ignore) {}
        }
        if (info.containsKey("maxtime")) {
            try { gcb.maxTime = Integer.parseInt(info.get("maxtime")); }
            catch (NumberFormatException ignore) {}
        }
    }

    // -----------------------------------------------------------------------
    // Métodos privados estáticos (helpers de parsing)
    // -----------------------------------------------------------------------

    /**
     * Parsea DataTypeTemplates del DOM SCL.
     * Rellena enumTypes, daEnumType, lnTypeDoTypes, lnClassToLnType en result.
     */
    private static void parseSclDataTypeTemplates(Document doc, SclParsingResult result) {
        result.enumTypes.clear();
        result.daEnumType.clear();
        result.lnTypeDoTypes.clear();
        result.lnClassToLnType.clear();

        // Parse EnumType definitions
        NodeList enumTypes = doc.getElementsByTagName("EnumType");
        for (int i = 0; i < enumTypes.getLength(); i++) {
            Element et = (Element) enumTypes.item(i);
            String id = et.getAttribute("id");
            LinkedHashMap<Integer, String> vals = new LinkedHashMap<>();
            NodeList enumVals = et.getElementsByTagName("EnumVal");
            for (int j = 0; j < enumVals.getLength(); j++) {
                Element ev = (Element) enumVals.item(j);
                try {
                    int ord = Integer.parseInt(ev.getAttribute("ord").trim());
                    vals.put(ord, ev.getTextContent().trim());
                } catch (NumberFormatException ignore) {}
            }
            if (!vals.isEmpty()) result.enumTypes.put(id, vals);
        }

        // Parse DOType → DA (bType=Enum) → EnumType id
        NodeList doTypes = doc.getElementsByTagName("DOType");
        for (int i = 0; i < doTypes.getLength(); i++) {
            Element dot = (Element) doTypes.item(i);
            String doTypeId = dot.getAttribute("id");
            NodeList das = dot.getElementsByTagName("DA");
            for (int j = 0; j < das.getLength(); j++) {
                Element da = (Element) das.item(j);
                if ("Enum".equals(da.getAttribute("bType"))) {
                    String daName = da.getAttribute("name");
                    String enumType = da.getAttribute("type");
                    if (!daName.isEmpty() && !enumType.isEmpty()) {
                        result.daEnumType.put(doTypeId + "." + daName, enumType);
                    }
                }
            }
        }

        // Parse LNodeType → DO → DOType
        NodeList lnTypes = doc.getElementsByTagName("LNodeType");
        for (int i = 0; i < lnTypes.getLength(); i++) {
            Element lnt = (Element) lnTypes.item(i);
            String lnTypeId = lnt.getAttribute("id");
            String lnClass = lnt.getAttribute("lnClass");
            result.lnClassToLnType.putIfAbsent(lnClass, lnTypeId);
            Map<String, String> doMap = new HashMap<>();
            NodeList doEls = lnt.getElementsByTagName("DO");
            for (int j = 0; j < doEls.getLength(); j++) {
                Element doEl = (Element) doEls.item(j);
                String doName = doEl.getAttribute("name");
                String doType = doEl.getAttribute("type");
                if (!doName.isEmpty() && !doType.isEmpty()) doMap.put(doName, doType);
            }
            result.lnTypeDoTypes.put(lnTypeId, doMap);
        }
    }

    /**
     * Parsea DataSets dentro de un elemento IED.
     */
    private static void parseDataSetsFromIED(Element iedElement, SclParsingResult result) {
        NodeList dataSets = iedElement.getElementsByTagName("DataSet");

        for (int i = 0; i < dataSets.getLength(); i++) {
            Element dsElement = (Element) dataSets.item(i);

            SclDataSet ds = new SclDataSet();
            ds.name = dsElement.getAttribute("name");
            ds.desc = dsElement.getAttribute("desc");

            // Obtener LDevice padre
            org.w3c.dom.Node parent = dsElement.getParentNode();
            while (parent != null) {
                if (parent instanceof Element) {
                    Element parentEl = (Element) parent;
                    if ("LN0".equals(parentEl.getTagName()) || "LN".equals(parentEl.getTagName())) {
                        ds.lnClass = parentEl.getAttribute("lnClass");
                        if (ds.lnClass == null || ds.lnClass.isEmpty()) {
                            ds.lnClass = "LLN0";
                        }
                    } else if ("LDevice".equals(parentEl.getTagName())) {
                        ds.ldInst = parentEl.getAttribute("inst");
                        break;
                    }
                }
                parent = parent.getParentNode();
            }

            // Obtener miembros (FCDA)
            NodeList fcdas = dsElement.getElementsByTagName("FCDA");
            for (int j = 0; j < fcdas.getLength(); j++) {
                Element fcda = (Element) fcdas.item(j);
                StringBuilder member = new StringBuilder();

                String ldInst = fcda.getAttribute("ldInst");
                String prefix = fcda.getAttribute("prefix");
                String lnClass = fcda.getAttribute("lnClass");
                String lnInst = fcda.getAttribute("lnInst");
                String doName = fcda.getAttribute("doName");
                String daName = fcda.getAttribute("daName");
                String fc = fcda.getAttribute("fc");

                if (ldInst != null && !ldInst.isEmpty()) member.append(ldInst).append("/");
                if (prefix != null && !prefix.isEmpty()) member.append(prefix);
                member.append(lnClass);
                if (lnInst != null && !lnInst.isEmpty()) member.append(lnInst);
                member.append(".").append(doName);
                if (daName != null && !daName.isEmpty()) member.append(".").append(daName);
                member.append(" [").append(fc).append("]");

                ds.members.add(member.toString());
            }

            result.dataSets.add(ds);
        }
    }

    /**
     * Parsea ReportControl dentro de un elemento IED.
     */
    private static void parseReportsFromIED(Element iedElement, SclParsingResult result) {
        NodeList reports = iedElement.getElementsByTagName("ReportControl");

        for (int i = 0; i < reports.getLength(); i++) {
            Element rptElement = (Element) reports.item(i);

            SclReport rpt = new SclReport();
            rpt.name = rptElement.getAttribute("name");
            rpt.rptID = rptElement.getAttribute("rptID");
            rpt.datSet = rptElement.getAttribute("datSet");
            rpt.buffered = "true".equals(rptElement.getAttribute("buffered"));

            String confRevStr = rptElement.getAttribute("confRev");
            if (confRevStr != null && !confRevStr.isEmpty()) {
                try { rpt.confRev = Integer.parseInt(confRevStr); }
                catch (NumberFormatException e) { rpt.confRev = 1; }
            }

            // Obtener LDevice padre
            org.w3c.dom.Node parent = rptElement.getParentNode();
            while (parent != null) {
                if (parent instanceof Element) {
                    Element parentEl = (Element) parent;
                    if ("LN0".equals(parentEl.getTagName()) || "LN".equals(parentEl.getTagName())) {
                        rpt.lnClass = parentEl.getAttribute("lnClass");
                        if (rpt.lnClass == null || rpt.lnClass.isEmpty()) {
                            rpt.lnClass = "LLN0";
                        }
                    } else if ("LDevice".equals(parentEl.getTagName())) {
                        rpt.ldInst = parentEl.getAttribute("inst");
                        break;
                    }
                }
                parent = parent.getParentNode();
            }

            result.reports.add(rpt);
        }
    }
}
