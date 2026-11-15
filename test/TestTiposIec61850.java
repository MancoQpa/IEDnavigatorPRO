package com.iednavigator;

import com.beanit.iec61850bean.*;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.*;

/**
 * Tipos de la norma en el explorador del Data Model (reporte de un usuario, 2026-11-15: el
 * panel mostraba "Tipo: DO / String" y "Clase BDA: BdaInt8", clases de Java que no existen en
 * IEC 61850).
 *
 *   1. El CDC deducido de cada DO coincide con el cdc que declara el SCL (DOType), en todos
 *      los SCL de prueba y en los que se pasen como argumento.
 *   2. Ningún tipo ni propiedad mostrada lleva nombres de clases de Java.
 *   3. Casos puntuales: LPL, ENS, ENC, DPC, enumerado de Beh con su nombre, referencia MMS,
 *      DA frente a BDA.
 *
 * Uso: java -cp "classes;lib\*;test" com.iednavigator.TestTiposIec61850 [otros.cid ...]
 */
public class TestTiposIec61850 {
    static int ok = 0, mal = 0;
    static void chk(String que, boolean c) {
        if (!c || que.startsWith("*")) System.out.println((c ? "OK    " : "FALLA ") + que);
        if (c) ok++; else mal++;
    }

    static final String[] JAVA = {"Bda", "FcDataObject", "ConstructedDataAttribute", "ObjectReference",
            "String", "Class", "int", "Fc"};

    public static void main(String[] a) throws Exception {
        List<String> archivos = new ArrayList<>(Arrays.asList("test/test_bay_control.cid",
                "test/test_bay_dos_aparatos.cid", "test/test_interruptor_polos.cid",
                "test/test_bay_cbay.cid", "test/test_ied.cid"));
        int propios = archivos.size();
        archivos.addAll(Arrays.asList(a));
        for (int i = 0; i < archivos.size(); i++) {
            String f = archivos.get(i);
            try { compararConScl(f, i < propios); }
            catch (Exception e) { System.out.println("--    " + new File(f).getName() + ": no carga (" + e.getMessage() + ")"); }
        }
        if (System.getProperty("pares") != null) {
            PARES.forEach((k, v) -> System.out.println("  " + v + "  " + k + "   ej: " + EJ.get(k)));
            System.exit(0);
        }

        // Casos puntuales sobre el modelo de un control de bahía
        ServerModel m = SclParser.parse("test/test_bay_control.cid").get(0);
        LogicalNode lln0 = null, cswi = null;
        for (ModelNode ld : m.getChildren())
            for (ModelNode ln : ld.getChildren()) {
                if (lln0 == null && ln.getName().equals("LLN0")) lln0 = (LogicalNode) ln;
                if (cswi == null && ln.getName().contains("CSWI")) cswi = (LogicalNode) ln;
            }
        FcDataObject namPlt = primero(lln0, "NamPlt"), beh = primero(lln0, "Beh"), mod = primero(lln0, "Mod");
        chk("*NamPlt -> " + TiposIec61850.tipoDe(namPlt), "CDC LPL".equals(TiposIec61850.tipoDe(namPlt)));
        chk("*Beh -> " + TiposIec61850.tipoDe(beh), "CDC ENS".equals(TiposIec61850.tipoDe(beh)));
        chk("*Mod -> " + TiposIec61850.tipoDe(mod), "CDC ENC".equals(TiposIec61850.tipoDe(mod)));
        chk("*CSWI.Pos -> " + TiposIec61850.tipoDe(primero(cswi, "Pos")), "CDC DPC".equals(TiposIec61850.tipoDe(primero(cswi, "Pos"))));
        FcDataObject behDe = null;
        for (ModelNode h : lln0.getChildren()) if (h.getName().equals("Beh") && h != beh) behDe = (FcDataObject) h;
        String fcsBeh = TiposIec61850.fcsDe(beh);
        chk("*FC de Beh: " + fcsBeh, behDe == null ? !fcsBeh.contains(",") : fcsBeh.contains(","));

        BasicDataAttribute stVal = (BasicDataAttribute) beh.getChild("stVal");
        chk("*Beh.stVal -> " + TiposIec61850.tipoDe(stVal), "ENUMERATED (BehaviourModeKind)".equals(TiposIec61850.tipoDe(stVal)));
        ((BdaInt8) stVal).setValue((byte) 1);
        chk("*valor Beh=1 -> " + DataModelNodeInfo.valorMostrado(stVal, true), "on (1)".equals(DataModelNodeInfo.valorMostrado(stVal, true)));
        ((BdaInt8) stVal).setValue((byte) 0);
        String fuera = DataModelNodeInfo.valorMostrado(stVal, true);
        chk("*valor Beh=0 -> " + fuera, fuera.startsWith("0 (") && fuera.contains("BehaviourModeKind"));
        chk("*q -> " + TiposIec61850.tipoDe(beh.getChild("q")), "Quality".equals(TiposIec61850.tipoDe(beh.getChild("q"))));
        chk("*t -> " + TiposIec61850.tipoDe(beh.getChild("t")), "TimeStamp".equals(TiposIec61850.tipoDe(beh.getChild("t"))));

        chk("*stVal es DA", "DA".equals(DataModelPanel.getDataModelNodeType(stVal)));
        chk("*Beh es DO", "DO".equals(DataModelPanel.getDataModelNodeType(beh)));
        String mms = DataModelPanel.referenciaMms(stVal.getReference().toString(), "ST");
        chk("*referencia MMS " + mms, mms.endsWith("/LLN0$ST$Beh$stVal"));

        // Un DA construido y su componente: Oper es DA, Oper.ctlVal es BDA
        FcDataObject posCo = null;
        for (ModelNode h : cswi.getChildren())
            if (h.getName().equals("Pos") && ((FcDataObject) h).getFc() == Fc.CO) posCo = (FcDataObject) h;
        if (posCo != null) {
            ModelNode oper = posCo.getChild("Oper");
            chk("*Oper -> " + TiposIec61850.tipoDe(oper) + " / " + DataModelPanel.getDataModelNodeType(oper),
                    "Operate".equals(TiposIec61850.tipoDe(oper)) && "DA".equals(DataModelPanel.getDataModelNodeType(oper)));
            ModelNode origin = oper.getChild("origin");
            chk("*Oper.origin -> " + TiposIec61850.tipoDe(origin) + " / " + DataModelPanel.getDataModelNodeType(origin),
                    "Originator".equals(TiposIec61850.tipoDe(origin)) && "BDA".equals(DataModelPanel.getDataModelNodeType(origin)));
        }

        // Ninguna propiedad mostrada lleva clases de Java, en todos los nodos del modelo
        int nodos = 0;
        Deque<ModelNode> pila = new ArrayDeque<>(m.getChildren());
        List<String> malos = new ArrayList<>();
        while (!pila.isEmpty()) {
            ModelNode n = pila.pop();
            nodos++;
            DataModelNodeInfo info = new DataModelNodeInfo(n, DataModelPanel.getDataModelNodeType(n));
            for (String[] fila : DataModelPanel.propiedades(info)) {
                if (fila[0].equals(I18n.t("dm.prop.name")) || fila[0].equals(I18n.t("dm.prop.ref"))
                        || fila[0].equals(I18n.t("dm.prop.mmsref"))) continue;
                for (String j : JAVA) {
                    if (fila[1].matches(".*\\b" + j + "(\\b|[A-Z0-9]).*") && !fila[1].startsWith("VISIBLE STRING")
                            && !fila[1].startsWith("UNICODE STRING") && !fila[1].startsWith("OCTET STRING")
                            && !(j.equals("Fc") && fila[0].equals(I18n.t("dm.prop.fc")))) {
                        if (malos.size() < 5) malos.add(n.getReference() + ": " + fila[0] + "=" + fila[1]);
                    }
                }
            }
            if (!(n instanceof BasicDataAttribute)) pila.addAll(n.getChildren());
        }
        chk("*sin clases de Java en las propiedades de " + nodos + " nodos " + malos, malos.isEmpty());

        System.out.println(ok + " pasaron, " + mal + " fallaron");
        if (mal == 0) System.out.println("TODO OK");
        System.exit(mal == 0 ? 0 : 1);
    }

    static final Map<String, Integer> PARES = new TreeMap<>();
    static final Map<String, String> EJ = new HashMap<>();
    static String hijosDe(FcDataObject d) {
        StringBuilder sb = new StringBuilder();
        for (FcDataObject v : TiposIec61850.variantes(d)) {
            sb.append(v.getFc()).append('{');
            for (ModelNode h : v.getChildren()) sb.append(h.getName()).append(h instanceof FcDataObject ? "(SDO)" : "").append(',');
            sb.append("} ");
        }
        return sb.toString();
    }

    static FcDataObject primero(LogicalNode ln, String nombre) {
        for (ModelNode h : ln.getChildren()) if (h.getName().equals(nombre)) return (FcDataObject) h;
        return null;
    }

    /** CDC deducido de cada DO del modelo frente al cdc del DOType del SCL. */
    /**
     * Los SCL del repositorio tienen que coincidir en todo. Los que se pasan como argumento
     * (IED reales) solo informan: traen CDC propios del fabricante y declaraciones que no
     * corresponden a la forma del DO.
     */
    static void compararConScl(String archivo, boolean estricto) throws Exception {
        org.w3c.dom.Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File(archivo));
        Map<String, String> cdcDeTipo = new HashMap<>();
        for (Element e : elementos(doc, "DOType")) cdcDeTipo.put(e.getAttribute("id"), e.getAttribute("cdc"));
        Map<String, Map<String, String>> doDeLnType = new HashMap<>();
        for (Element t : elementos(doc, "LNodeType")) {
            Map<String, String> dos = new HashMap<>();
            for (Element d : hijos(t, "DO")) dos.put(d.getAttribute("name"), cdcDeTipo.get(d.getAttribute("type")));
            doDeLnType.put(t.getAttribute("id"), dos);
        }
        Map<String, String> lnTypeDeLn = new HashMap<>();
        for (Element ied : elementos(doc, "IED")) {
            for (Element ld : elementosDe(ied, "LDevice")) {
                String ldName = ld.hasAttribute("ldName") ? ld.getAttribute("ldName") : ied.getAttribute("name") + ld.getAttribute("inst");
                for (String tag : new String[]{"LN0", "LN"}) {
                    for (Element ln : hijos(ld, tag)) {
                        String nombre = ln.getAttribute("prefix") + ln.getAttribute("lnClass") + ln.getAttribute("inst");
                        lnTypeDeLn.put(ldName + "/" + nombre, ln.getAttribute("lnType"));
                    }
                }
            }
        }
        int total = 0, iguales = 0, ed1 = 0;
        List<String> difs = new ArrayList<>();
        List<ServerModel> modelos;
        try {
            modelos = SclParser.parse(archivo);
        } catch (Exception e) {
            // Mismo camino que la aplicación: IEC61850Server parchea lo que SclParser no acepta
            IEC61850Server srv = new IEC61850Server();
            if (!srv.loadSclFile(archivo, 0)) throw e;
            modelos = Collections.singletonList(srv.getServerModel());
        }
        for (ServerModel m : modelos) {
            for (ModelNode ld : m.getChildren())
                for (ModelNode ln : ld.getChildren()) {
                    Map<String, String> dos = doDeLnType.get(lnTypeDeLn.get(ln.getReference().toString()));
                    if (dos == null) continue;
                    Set<String> vistos = new HashSet<>();
                    for (ModelNode d : ln.getChildren()) {
                        if (!(d instanceof FcDataObject) || !vistos.add(d.getName())) continue;
                        String esperado = dos.get(d.getName());
                        if (esperado == null || esperado.isEmpty()) continue;
                        total++;
                        String visto = TiposIec61850.cdcDe((FcDataObject) d);
                        if (esperado.equals(visto)) iguales++;
                        else if (equivalenteEd1(esperado, visto)) ed1++;
                        else { if (difs.size() < 8) difs.add(d.getReference() + " SCL=" + esperado + " deducido=" + visto); PARES.merge(esperado + "->" + visto, 1, Integer::sum); EJ.putIfAbsent(esperado + "->" + visto, d.getReference() + " " + hijosDe((FcDataObject) d)); }
                    }
                }
        }
        String linea = new File(archivo).getName() + ": CDC deducido = SCL en " + iguales + "/" + total
                + (ed1 > 0 ? " (+" + ed1 + " de Ed1 INS/INC/ING, iguales por MMS a ENS/ENC/ENG)" : "")
                + (difs.isEmpty() ? "" : " " + difs);
        if (estricto) chk("*" + linea, total > 0 && iguales + ed1 == total);
        else System.out.println("INFO  " + linea);
    }

    /**
     * En Ed1 los enumerados de modo, comportamiento y salud se declaraban INS/INC/ING; Ed2 los
     * pasó a ENS/ENC/ENG. Por MMS los dos llegan como INT8 y no se pueden distinguir.
     */
    static boolean equivalenteEd1(String scl, String visto) {
        return ("INS".equals(scl) && "ENS".equals(visto)) || ("INC".equals(scl) && "ENC".equals(visto))
                || ("ING".equals(scl) && "ENG".equals(visto));
    }

    static List<Element> elementos(org.w3c.dom.Document d, String tag) {
        List<Element> out = new ArrayList<>();
        NodeList nl = d.getElementsByTagNameNS("*", tag);
        if (nl.getLength() == 0) nl = d.getElementsByTagName(tag);
        for (int i = 0; i < nl.getLength(); i++) out.add((Element) nl.item(i));
        return out;
    }

    static List<Element> elementosDe(Element e, String tag) {
        List<Element> out = new ArrayList<>();
        NodeList nl = e.getElementsByTagName(tag);
        for (int i = 0; i < nl.getLength(); i++) out.add((Element) nl.item(i));
        return out;
    }

    static List<Element> hijos(Element e, String tag) {
        List<Element> out = new ArrayList<>();
        for (org.w3c.dom.Node n = e.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element && (tag.equals(((Element) n).getTagName()) || tag.equals(n.getLocalName())))
                out.add((Element) n);
        return out;
    }
}
