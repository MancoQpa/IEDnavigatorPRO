package com.iednavigator;

import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.*;

/**
 * Analiza un archivo SCL (idealmente SCD multi-IED) y construye el mapa de
 * suscripciones GOOSE: cruza los GSEControl (publicadores) con los
 * Inputs/ExtRef y los LN LGOS (suscriptores).
 *
 * Fuentes de suscripción:
 *  - ExtRef con serviceType="GOOSE" o con srcCBName (IEC 61850-6 Ed.2)
 *  - LGOS: DOI GoCBRef / DAI setSrcRef (supervisión de suscripción Ed.2)
 */
public class GooseMapAnalyzer {

    public static class Publisher {
        public String iedName, ldInst, cbName, datSet, appId;
        public String mac = "", appidHex = "";
        public List<String> members = new ArrayList<>();
        public int subscriberCount = 0;
        String key() { return iedName + "|" + ldInst + "|" + cbName; }
        public String ref() { return iedName + " " + ldInst + "/" + cbName; }
    }

    public static class Subscription {
        public String pubIed = "", pubLd = "", pubCb = "";   // publicador (puede quedar vacío si no resuelto)
        public String subscriberIed;                          // IED que recibe
        public String dataRef = "";                           // dato publicado referenciado (ExtRef)
        public String target = "";                            // intAddr o LN LGOS destino
        public String via;                                    // "ExtRef" | "LGOS"
        public boolean resolved = false;                      // publicador encontrado en el archivo
        public String pubRef() {
            if (pubIed.isEmpty()) return "(no resuelto)";
            return pubIed + " " + pubLd + "/" + pubCb;
        }
    }

    public static class Result {
        public List<Publisher> publishers = new ArrayList<>();
        public List<Subscription> subscriptions = new ArrayList<>();
        public List<String> iedNames = new ArrayList<>();
    }

    public static Result analyze(File sclFile) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        Document doc = factory.newDocumentBuilder().parse(sclFile);

        Result res = new Result();
        Map<String, Publisher> pubByKey = new LinkedHashMap<>();

        // ─── 1. Publicadores: GSEControl por IED ───
        NodeList ieds = doc.getElementsByTagName("IED");
        for (int i = 0; i < ieds.getLength(); i++) {
            Element ied = (Element) ieds.item(i);
            String iedName = ied.getAttribute("name");
            res.iedNames.add(iedName);

            NodeList gcbs = ied.getElementsByTagName("GSEControl");
            for (int j = 0; j < gcbs.getLength(); j++) {
                Element g = (Element) gcbs.item(j);
                Publisher p = new Publisher();
                p.iedName = iedName;
                p.ldInst = parentLdInst(g);
                p.cbName = g.getAttribute("name");
                p.datSet = g.getAttribute("datSet");
                p.appId = g.getAttribute("appID");
                pubByKey.put(p.key(), p);
            }

            // Miembros del dataset de cada GoCB (para fallback de matching ExtRef Ed.1)
            NodeList dss = ied.getElementsByTagName("DataSet");
            for (int j = 0; j < dss.getLength(); j++) {
                Element ds = (Element) dss.item(j);
                String dsLd = parentLdInst(ds);
                String dsName = ds.getAttribute("name");
                for (Publisher p : pubByKey.values()) {
                    if (!p.iedName.equals(iedName) || !p.ldInst.equals(dsLd)
                        || !p.datSet.equals(dsName)) continue;
                    NodeList fcdas = ds.getElementsByTagName("FCDA");
                    for (int k = 0; k < fcdas.getLength(); k++) {
                        Element f = (Element) fcdas.item(k);
                        String m = f.getAttribute("ldInst") + "/" + f.getAttribute("prefix")
                            + f.getAttribute("lnClass") + f.getAttribute("lnInst")
                            + "." + f.getAttribute("doName");
                        p.members.add(m);
                    }
                }
            }
        }

        // ─── 2. Direcciones de red (Communication > ConnectedAP > GSE) ───
        NodeList aps = doc.getElementsByTagName("ConnectedAP");
        for (int i = 0; i < aps.getLength(); i++) {
            Element ap = (Element) aps.item(i);
            String apIed = ap.getAttribute("iedName");
            NodeList gses = ap.getElementsByTagName("GSE");
            for (int j = 0; j < gses.getLength(); j++) {
                Element gse = (Element) gses.item(j);
                Publisher p = pubByKey.get(apIed + "|" + gse.getAttribute("ldInst")
                    + "|" + gse.getAttribute("cbName"));
                if (p == null) continue;
                Map<String, String> info = SclFileProcessor.parseGseElement(gse);
                if (info.containsKey("mac"))   p.mac = info.get("mac");
                if (info.containsKey("appid")) p.appidHex = info.get("appid");
            }
        }

        // ─── 3. Suscriptores: ExtRef ───
        for (int i = 0; i < ieds.getLength(); i++) {
            Element ied = (Element) ieds.item(i);
            String subscriberIed = ied.getAttribute("name");
            NodeList extRefs = ied.getElementsByTagName("ExtRef");
            for (int j = 0; j < extRefs.getLength(); j++) {
                Element er = (Element) extRefs.item(j);
                String service = er.getAttribute("serviceType");
                String srcCb = er.getAttribute("srcCBName");
                if (!service.equalsIgnoreCase("GOOSE") && srcCb.isEmpty()) continue;
                if (!service.isEmpty() && !service.equalsIgnoreCase("GOOSE")) continue; // SMV, Report...

                Subscription s = new Subscription();
                s.via = "ExtRef";
                s.subscriberIed = subscriberIed;
                s.pubIed = er.getAttribute("iedName");
                String dataRef = er.getAttribute("ldInst") + "/" + er.getAttribute("prefix")
                    + er.getAttribute("lnClass") + er.getAttribute("lnInst")
                    + "." + er.getAttribute("doName");
                String daName = er.getAttribute("daName");
                if (!daName.isEmpty()) dataRef += "." + daName;
                s.dataRef = dataRef;
                s.target = !er.getAttribute("intAddr").isEmpty()
                    ? er.getAttribute("intAddr") : er.getAttribute("desc");

                if (!srcCb.isEmpty()) {
                    // Ed.2: referencia explícita al GoCB origen
                    s.pubLd = er.getAttribute("srcLDInst");
                    if (s.pubLd.isEmpty()) s.pubLd = er.getAttribute("ldInst");
                    s.pubCb = srcCb;
                    Publisher p = pubByKey.get(s.pubIed + "|" + s.pubLd + "|" + s.pubCb);
                    if (p != null) { s.resolved = true; p.subscriberCount++; }
                } else {
                    // Ed.1: localizar el GoCB cuyo dataset contiene el dato referenciado
                    String memberNoDa = er.getAttribute("ldInst") + "/" + er.getAttribute("prefix")
                        + er.getAttribute("lnClass") + er.getAttribute("lnInst")
                        + "." + er.getAttribute("doName");
                    for (Publisher p : pubByKey.values()) {
                        if (!p.iedName.equals(s.pubIed)) continue;
                        for (String m : p.members) {
                            if (m.equals(memberNoDa) || memberNoDa.startsWith(m)) {
                                s.pubLd = p.ldInst; s.pubCb = p.cbName;
                                s.resolved = true; p.subscriberCount++;
                                break;
                            }
                        }
                        if (s.resolved) break;
                    }
                }
                res.subscriptions.add(s);
            }

            // ─── 4. Suscriptores: LGOS (GoCBRef.setSrcRef) ───
            NodeList lns = ied.getElementsByTagName("LN");
            for (int j = 0; j < lns.getLength(); j++) {
                Element ln = (Element) lns.item(j);
                if (!"LGOS".equals(ln.getAttribute("lnClass"))) continue;
                String goCbRef = findLgosGoCbRef(ln);
                if (goCbRef == null || goCbRef.isEmpty()) continue;

                Subscription s = new Subscription();
                s.via = "LGOS";
                s.subscriberIed = subscriberIed;
                s.target = parentLdInst(ln) + "/" + ln.getAttribute("prefix")
                    + "LGOS" + ln.getAttribute("inst");
                s.dataRef = goCbRef;

                // Formato: IEDNameLDInst/LLN0$GO$cbName (o con '.' en vez de '$')
                String norm = goCbRef.replace('$', '.');
                int slash = norm.indexOf('/');
                int goIdx = norm.indexOf(".GO.");
                if (slash > 0 && goIdx > slash) {
                    String ldPart = norm.substring(0, slash);   // IEDName+LDInst concatenados
                    s.pubCb = norm.substring(goIdx + 4);
                    for (Publisher p : pubByKey.values()) {
                        if (ldPart.equals(p.iedName + p.ldInst) && s.pubCb.equals(p.cbName)) {
                            s.pubIed = p.iedName; s.pubLd = p.ldInst;
                            s.resolved = true; p.subscriberCount++;
                            break;
                        }
                    }
                    if (!s.resolved) {
                        // Mostrar al menos lo parseado aunque el publicador no esté en el archivo
                        s.pubIed = ldPart;
                    }
                }
                res.subscriptions.add(s);
            }
        }

        res.publishers.addAll(pubByKey.values());
        return res;
    }

    /** Busca DOI[name=GoCBRef] > DAI[name=setSrcRef] > Val dentro de un LN LGOS. */
    private static String findLgosGoCbRef(Element ln) {
        NodeList dois = ln.getElementsByTagName("DOI");
        for (int i = 0; i < dois.getLength(); i++) {
            Element doi = (Element) dois.item(i);
            if (!"GoCBRef".equals(doi.getAttribute("name"))) continue;
            NodeList dais = doi.getElementsByTagName("DAI");
            for (int j = 0; j < dais.getLength(); j++) {
                Element dai = (Element) dais.item(j);
                if (!"setSrcRef".equals(dai.getAttribute("name"))) continue;
                NodeList vals = dai.getElementsByTagName("Val");
                if (vals.getLength() > 0) return vals.item(0).getTextContent().trim();
            }
        }
        return null;
    }

    private static String parentLdInst(Element el) {
        Node parent = el.getParentNode();
        while (parent != null) {
            if (parent instanceof Element && "LDevice".equals(((Element) parent).getTagName())) {
                return ((Element) parent).getAttribute("inst");
            }
            parent = parent.getParentNode();
        }
        return "?";
    }
}
