package com.iednavigator;

import com.beanit.iec61850bean.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Genera un archivo SCL (CID) a partir del {@link ServerModel} recuperado del IED por MMS.
 *
 * <h3>Para qué sirve y para qué no</h3>
 * El servicio de archivos MMS ({@code listFiles}/{@code downloadFile}) sólo entrega el CID si
 * el IED lo guarda como archivo, y muchos equipos no lo hacen. Este exportador cubre ese hueco:
 * reconstruye un SCL desde el modelo de datos que el propio IED expuso al conectarse.
 *
 * Lo que MMS SÍ permite reconstruir:
 * <ul>
 *   <li>Jerarquía completa LD → LN → DO → DA/BDA, con su Functional Constraint y tipo básico</li>
 *   <li>Valores actuales de los atributos (se emiten como {@code DAI/Val})</li>
 *   <li>DataSets con sus FCDA</li>
 *   <li>Bloques de control de reporte (URCB no bufferizados y BRCB bufferizados)</li>
 *   <li>La dirección de comunicación, porque es la que usamos para conectarnos</li>
 * </ul>
 *
 * Lo que MMS NO expone y por lo tanto NO puede reproducirse fielmente:
 * <ul>
 *   <li>Los identificadores originales de {@code LNodeType}/{@code DOType}/{@code DAType}:
 *       se sintetizan a partir de la estructura observada</li>
 *   <li>El atributo {@code cdc} de cada {@code DOType}: se infiere de la forma del DO
 *       (ver {@link #inferCdc}); los casos dudosos quedan contados en el reporte</li>
 *   <li>Las etiquetas de los {@code EnumType}: por MMS un enumerado viaja como entero, así que
 *       los DA enumerados se emiten con su tipo básico y se pierde el diccionario de valores</li>
 *   <li>La sección GOOSE ({@code GSEControl} y direcciones multicast) y los {@code SampledValueControl}:
 *       no forman parte del modelo MMS</li>
 *   <li>Secciones {@code Private} del fabricante, {@code Substation} y el cableado {@code SMV}</li>
 * </ul>
 *
 * Es decir: el resultado sirve para documentar, para alimentar el modo simulador y para comparar
 * con {@link SclCompare}, pero no reemplaza al CID de ingeniería del fabricante.
 */
public final class SclExporter {

    /** Namespace SCL (IEC 61850-6). */
    private static final String NS = "http://www.iec.ch/61850/2003/SCL";

    /** Resultado de una exportación: el XML más las advertencias de fidelidad. */
    public static class Result {
        public final String xml;
        public final int logicalDevices, logicalNodes, dataObjects, dataAttributes;
        public final int dataSets, reportControls;
        public final int lnodeTypes, doTypes, daTypes;
        /** DOs cuyo cdc no pudo determinarse con confianza (se emitió el mejor candidato). */
        public final List<String> uncertainCdc;
        /** DataSets nombrados por algún ReportControl que el archivo no declara. */
        public final List<String> datSetsColgados;
        public final String iedName;

        Result(String xml, String iedName, int lds, int lns, int dos, int das,
               int dataSets, int rcbs, int lnTypes, int doTypes, int daTypes,
               List<String> uncertainCdc, List<String> datSetsColgados) {
            this.xml = xml; this.iedName = iedName;
            this.datSetsColgados = datSetsColgados;
            this.logicalDevices = lds; this.logicalNodes = lns;
            this.dataObjects = dos; this.dataAttributes = das;
            this.dataSets = dataSets; this.reportControls = rcbs;
            this.lnodeTypes = lnTypes; this.doTypes = doTypes; this.daTypes = daTypes;
            this.uncertainCdc = uncertainCdc;
        }
    }

    // ── Estado de una exportación ────────────────────────────────────────────
    private final ServerModel model;
    private final String ipAddress;
    private final String manufacturer;
    private final String deviceType;
    private final String configVersion;

    /** firma estructural → id sintetizado, para deduplicar plantillas. */
    private final Map<String, String> lnTypeIds = new LinkedHashMap<>();
    private final Map<String, String> doTypeIds = new LinkedHashMap<>();
    private final Map<String, String> daTypeIds = new LinkedHashMap<>();
    /** id → XML ya renderizado de la plantilla. */
    private final Map<String, String> lnTypeXml = new LinkedHashMap<>();
    private final Map<String, String> doTypeXml = new LinkedHashMap<>();
    private final Map<String, String> daTypeXml = new LinkedHashMap<>();

    private final List<String> uncertainCdc = new ArrayList<>();
    /** DataSets que los ReportControl nombran pero que el modelo no trae. */
    private final java.util.Set<String> datSetsColgados = new java.util.LinkedHashSet<>();
    private int nLd, nLn, nDo, nDa, nDataSet, nRcb;

    /** Nombre de IED impuesto por quien llama; si es null se deduce de los nombres de LD. */
    private final String forcedIedName;

    private SclExporter(ServerModel model, String ipAddress, String iedName,
                        String manufacturer, String deviceType, String configVersion) {
        this.model = model;
        this.ipAddress = (ipAddress != null && !ipAddress.isBlank()) ? ipAddress : "0.0.0.0";
        this.forcedIedName = blankToNull(iedName);
        this.manufacturer = blankToNull(manufacturer);
        this.deviceType = blankToNull(deviceType);
        this.configVersion = blankToNull(configVersion);
    }

    /**
     * Exporta el modelo a SCL.
     *
     * @param model         modelo recuperado del IED (no null)
     * @param ipAddress     IP con la que se estableció la asociación; se emite en Communication
     * @param iedName       nombre del IED. Si es null se deduce del prefijo común de los nombres
     *                      de Logical Device, que es cómo MMS los codifica (iedName+ldInst).
     *                      Con un solo LD esa deducción es imposible, así que conviene pasarlo.
     * @param manufacturer  fabricante leído del nameplate, o null
     * @param deviceType    modelo del equipo leído del nameplate, o null
     * @param configVersion revisión de configuración, o null
     */
    public static Result export(ServerModel model, String ipAddress, String iedName,
                                String manufacturer, String deviceType, String configVersion) {
        if (model == null) throw new IllegalArgumentException("model == null");
        return new SclExporter(model, ipAddress, iedName, manufacturer, deviceType, configVersion)
                .build();
    }

    /**
     * Deduce el nombre del IED sin exportar, para prellenar el diálogo de guardado.
     * Devuelve null si no se puede deducir (un solo Logical Device).
     */
    public static String suggestIedName(ServerModel model) {
        if (model == null) return null;
        List<LogicalDevice> lds = new ArrayList<>();
        for (ModelNode n : model.getChildren()) {
            if (n instanceof LogicalDevice) lds.add((LogicalDevice) n);
        }
        String s = new SclExporter(model, null, null, null, null, null).deriveIedName(lds);
        return "IED".equals(s) ? null : s;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Construcción del documento
    // ═════════════════════════════════════════════════════════════════════════
    private Result build() {
        List<LogicalDevice> lds = new ArrayList<>();
        for (ModelNode n : model.getChildren()) {
            if (n instanceof LogicalDevice) lds.add((LogicalDevice) n);
        }

        String iedName = (forcedIedName != null) ? forcedIedName : deriveIedName(lds);

        // El cuerpo del IED se construye primero: al recorrerlo se van poblando las plantillas.
        StringBuilder ied = new StringBuilder();
        ied.append("  <IED name=\"").append(esc(iedName)).append("\"");
        if (manufacturer  != null) ied.append(" manufacturer=\"").append(esc(manufacturer)).append("\"");
        if (deviceType    != null) ied.append(" type=\"").append(esc(deviceType)).append("\"");
        if (configVersion != null) ied.append(" configVersion=\"").append(esc(configVersion)).append("\"");
        ied.append(">\n");
        appendServices(ied);
        ied.append("    <AccessPoint name=\"AP1\">\n");
        ied.append("      <Server>\n");
        ied.append("        <Authentication/>\n");

        for (LogicalDevice ld : lds) {
            appendLogicalDevice(ied, ld, iedName);
        }

        ied.append("      </Server>\n");
        ied.append("    </AccessPoint>\n");
        ied.append("  </IED>\n");

        // Documento completo
        StringBuilder sb = new StringBuilder(1 << 16);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<SCL xmlns=\"").append(NS).append("\" version=\"2007\" revision=\"B\">\n");
        appendHeader(sb, iedName);
        appendCommunication(sb, iedName);
        sb.append(ied);
        appendDataTypeTemplates(sb);
        sb.append("</SCL>\n");

        return new Result(sb.toString(), iedName, nLd, nLn, nDo, nDa, nDataSet, nRcb,
                          lnTypeXml.size(), doTypeXml.size(), daTypeXml.size(), uncertainCdc,
                          new ArrayList<>(datSetsColgados));
    }

    private void appendHeader(StringBuilder sb, String iedName) {
        sb.append("  <Header id=\"").append(esc(iedName))
          .append("\" version=\"1\" revision=\"1\" toolID=\"IEDNavigator PRO\"")
          .append(" nameStructure=\"IEDName\">\n");
        sb.append("    <Text>Reconstruido desde el modelo de datos MMS del IED. ")
          .append("No contiene la seccion GOOSE ni los EnumType originales; los cdc son inferidos.</Text>\n");
        sb.append("    <History>\n");
        sb.append("      <Hitem version=\"1\" revision=\"1\" when=\"")
          .append(esc(java.time.ZonedDateTime.now()
                  .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)))
          .append("\" who=\"IEDNavigator PRO\" what=\"Exportado desde modelo MMS en linea\"/>\n");
        sb.append("    </History>\n");
        sb.append("  </Header>\n");
    }

    /**
     * Sección Services. Se declara sólo lo que se pudo constatar contra el equipo real: los
     * servicios que se usaron para armar el modelo, y {@code resvTms} según si los BRCB del
     * modelo exponen realmente el atributo ResvTms (es lo que decide si un lector de SCL lo
     * reconstruye). No se inventan capacidades que no se hayan observado.
     */
    private void appendServices(StringBuilder sb) {
        boolean resvTms = anyBrcbHasResvTms();
        sb.append("    <Services nameLength=\"64\">\n");
        sb.append("      <DynAssociation/>\n");
        sb.append("      <GetDirectory/>\n");
        sb.append("      <GetDataObjectDefinition/>\n");
        sb.append("      <GetDataSetValue/>\n");
        sb.append("      <ReadWrite/>\n");
        sb.append("      <GetCBValues/>\n");
        sb.append("      <ReportSettings cbName=\"Conf\" datSet=\"Dyn\" rptID=\"Dyn\"")
          .append(" optFields=\"Dyn\" bufTime=\"Dyn\" trgOps=\"Dyn\" intgPd=\"Dyn\" resvTms=\"")
          .append(resvTms).append("\"/>\n");
        sb.append("    </Services>\n");
    }

    /** ¿Algún BRCB del modelo expone ResvTms? (atributo de Ed.2, no lo traen todos los equipos) */
    private boolean anyBrcbHasResvTms() {
        try {
            Collection<Brcb> brcbs = model.getBrcbs();
            if (brcbs == null) return false;
            for (Brcb b : brcbs) {
                if (b.getChildren() == null) continue;
                for (ModelNode c : b.getChildren()) {
                    if ("ResvTms".equals(c.getName())) return true;
                }
            }
        } catch (Exception ignore) {}
        return false;
    }

    private void appendCommunication(StringBuilder sb, String iedName) {
        sb.append("  <Communication>\n");
        sb.append("    <SubNetwork name=\"SN1\" type=\"8-MMS\">\n");
        sb.append("      <ConnectedAP iedName=\"").append(esc(iedName)).append("\" apName=\"AP1\">\n");
        sb.append("        <Address>\n");
        sb.append("          <P type=\"IP\">").append(esc(ipAddress)).append("</P>\n");
        // Los selectores OSI no se pueden leer del modelo; se emiten los valores por defecto
        // de IEC 61850-8-1, que es lo que usa la mayoria de los equipos.
        sb.append("          <P type=\"OSI-TSEL\">0001</P>\n");
        sb.append("          <P type=\"OSI-SSEL\">0001</P>\n");
        sb.append("          <P type=\"OSI-PSEL\">00000001</P>\n");
        sb.append("        </Address>\n");
        sb.append("      </ConnectedAP>\n");
        sb.append("    </SubNetwork>\n");
        sb.append("  </Communication>\n");
    }

    // ── Logical Device ───────────────────────────────────────────────────────
    private void appendLogicalDevice(StringBuilder sb, LogicalDevice ld, String iedName) {
        nLd++;
        String ldName = ld.getName();
        // En MMS el nombre del LD es iedName+ldInst concatenado; SCL los guarda separados.
        String ldInst = ldName.startsWith(iedName) && ldName.length() > iedName.length()
                      ? ldName.substring(iedName.length())
                      : ldName;

        sb.append("        <LDevice inst=\"").append(esc(ldInst)).append("\">\n");

        // LLN0 primero (SCL exige LN0 antes de los LN)
        List<LogicalNode> lns = new ArrayList<>();
        LogicalNode ln0 = null;
        for (ModelNode n : ld.getChildren()) {
            if (!(n instanceof LogicalNode)) continue;
            LogicalNode ln = (LogicalNode) n;
            if ("LLN0".equalsIgnoreCase(ln.getName())) ln0 = ln; else lns.add(ln);
        }

        if (ln0 != null) appendLogicalNode(sb, ln0, ldInst, iedName, true);
        for (LogicalNode ln : lns) appendLogicalNode(sb, ln, ldInst, iedName, false);

        sb.append("        </LDevice>\n");
    }

    // ── Logical Node ─────────────────────────────────────────────────────────
    private void appendLogicalNode(StringBuilder sb, LogicalNode ln,
                                   String ldInst, String iedName, boolean isLn0) {
        nLn++;
        String name = ln.getName();
        LnName parts = splitLnName(name);

        // Los DO del LN, agrupados por nombre (un mismo DO aparece una vez por cada FC).
        // Se excluyen los bloques de control: en el modelo MMS los RCB llegan como
        // FcDataObject con FC=RP/BR, pero en SCL NO se declaran como DO del LNodeType — van
        // como elementos <ReportControl>, y el parser reconstruye su estructura. Emitirlos en
        // los dos lados hace que al releer el archivo choquen los nombres.
        Map<String, List<FcDataObject>> byDo = new LinkedHashMap<>();
        for (ModelNode child : ln.getChildren()) {
            if (!(child instanceof FcDataObject)) continue;
            FcDataObject fcdo = (FcDataObject) child;
            if (isControlBlockFc(fcdo.getFc())) continue;
            byDo.computeIfAbsent(child.getName(), k -> new ArrayList<>()).add(fcdo);
        }

        String lnType = registerLnType(parts.lnClass, byDo);

        String tag = isLn0 ? "LN0" : "LN";
        sb.append("          <").append(tag);
        if (!isLn0 && !parts.prefix.isEmpty()) sb.append(" prefix=\"").append(esc(parts.prefix)).append("\"");
        sb.append(" lnClass=\"").append(esc(parts.lnClass)).append("\"");
        sb.append(" inst=\"").append(esc(isLn0 ? "" : parts.inst)).append("\"");
        sb.append(" lnType=\"").append(esc(lnType)).append("\">\n");

        // DataSets y ReportControl viven bajo el LN que los declara (típicamente LLN0)
        appendDataSets(sb, ln, ldInst, iedName);
        appendReportControls(sb, ln);

        // Valores instanciados
        for (Map.Entry<String, List<FcDataObject>> e : byDo.entrySet()) {
            appendDoiValues(sb, e.getKey(), e.getValue());
        }

        sb.append("          </").append(tag).append(">\n");
    }

    /** Emite los DAI con los valores leídos del IED, si hay alguno con valor. */
    private void appendDoiValues(StringBuilder sb, String doName, List<FcDataObject> instances) {
        StringBuilder inner = new StringBuilder();
        for (FcDataObject fcdo : instances) {
            collectDais(inner, fcdo, "", 14);
        }
        if (inner.length() == 0) return;
        sb.append("            <DOI name=\"").append(esc(doName)).append("\">\n");
        sb.append(inner);
        sb.append("            </DOI>\n");
    }

    /**
     * Recorre un DO/DA emitiendo DAI para los atributos con valor legible.
     * Los atributos estructurados se emiten con SDI anidado, como exige SCL.
     */
    private void collectDais(StringBuilder sb, ModelNode node, String path, int indent) {
        if (node.getChildren() == null) return;
        for (ModelNode child : node.getChildren()) {
            String ind = " ".repeat(indent);
            if (child instanceof BasicDataAttribute) {
                BasicDataAttribute bda = (BasicDataAttribute) child;
                String v = safeValue(bda);
                if (v == null) continue;
                sb.append(ind).append("  <DAI name=\"").append(esc(bda.getName())).append("\">")
                  .append("<Val>").append(esc(v)).append("</Val></DAI>\n");
            } else if (child instanceof ConstructedDataAttribute) {
                StringBuilder nested = new StringBuilder();
                collectDais(nested, child, path + child.getName() + ".", indent + 2);
                if (nested.length() > 0) {
                    sb.append(ind).append("  <SDI name=\"").append(esc(child.getName())).append("\">\n");
                    sb.append(nested);
                    sb.append(ind).append("  </SDI>\n");
                }
            }
        }
    }

    /**
     * Valor emitible de un BDA. Se omiten los tipos que no tienen representación textual
     * estable en SCL (marcas de tiempo, quality, structs de control) para no generar un
     * archivo que ninguna herramienta pueda releer.
     */
    private String safeValue(BasicDataAttribute bda) {
        BdaType t = bda.getBasicType();
        if (t == null) return null;
        switch (t) {
            case BOOLEAN:
            case INT8: case INT16: case INT32: case INT64:
            case INT8U: case INT16U: case INT32U:
            case FLOAT32: case FLOAT64:
            case VISIBLE_STRING: case UNICODE_STRING:
                break;
            default:
                return null;   // TIMESTAMP, QUALITY, CHECK, OCTET_STRING, ENTRY_TIME, ...
        }
        try {
            String v = bda.getValueString();
            if (v == null || v.isEmpty() || "null".equals(v)) return null;
            // Los enteros llegan a veces con el nombre decodificado; SCL espera el crudo.
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    // ── DataSets ─────────────────────────────────────────────────────────────
    private void appendDataSets(StringBuilder sb, LogicalNode ln, String ldInst, String iedName) {
        Collection<DataSet> all = model.getDataSets();
        if (all == null) return;
        String lnRefPrefix = ln.getReference().toString() + ".";
        for (DataSet ds : all) {
            String ref = ds.getReferenceStr();          // "LD/LLN0.dsName"
            if (ref == null || !ref.startsWith(lnRefPrefix)) continue;
            nDataSet++;
            String dsName = ref.substring(lnRefPrefix.length());
            sb.append("            <DataSet name=\"").append(esc(dsName)).append("\">\n");
            for (FcModelNode member : ds) {
                appendFcda(sb, member, iedName);
            }
            sb.append("            </DataSet>\n");
        }
    }

    private void appendFcda(StringBuilder sb, FcModelNode member, String iedName) {
        String ref = member.getReference().toString();   // "LD/prefixCLASSinst.DO[.DA...]"
        int slash = ref.indexOf('/');
        if (slash < 0) return;
        String ldFull = ref.substring(0, slash);
        String ldInst = ldFull.startsWith(iedName) && ldFull.length() > iedName.length()
                      ? ldFull.substring(iedName.length()) : ldFull;
        String rest = ref.substring(slash + 1);
        String[] seg = rest.split("\\.");
        if (seg.length < 1) return;

        LnName ln = splitLnName(seg[0]);
        sb.append("              <FCDA ldInst=\"").append(esc(ldInst)).append("\"");
        if (!ln.prefix.isEmpty()) sb.append(" prefix=\"").append(esc(ln.prefix)).append("\"");
        sb.append(" lnClass=\"").append(esc(ln.lnClass)).append("\"");
        if (!ln.inst.isEmpty()) sb.append(" lnInst=\"").append(esc(ln.inst)).append("\"");
        if (seg.length >= 2) sb.append(" doName=\"").append(esc(seg[1])).append("\"");
        if (seg.length >= 3) {
            StringBuilder da = new StringBuilder(seg[2]);
            for (int i = 3; i < seg.length; i++) da.append('.').append(seg[i]);
            sb.append(" daName=\"").append(esc(da.toString())).append("\"");
        }
        sb.append(" fc=\"").append(member.getFc()).append("\"/>\n");
    }

    // ── Report Control Blocks ────────────────────────────────────────────────
    private void appendReportControls(StringBuilder sb, LogicalNode ln) {
        appendRcbGroup(sb, ln.getUrcbs(), false);
        appendRcbGroup(sb, ln.getBrcbs(), true);
    }

    private void appendRcbGroup(StringBuilder sb, Collection<? extends ModelNode> rcbs, boolean buffered) {
        if (rcbs == null) return;
        for (ModelNode rcb : rcbs) {
            nRcb++;
            String name = rcb.getName();
            String datSet = childString(rcb, "DatSet");
            String rptId  = childString(rcb, "RptID");
            String confRev = childString(rcb, "ConfRev");

            sb.append("            <ReportControl name=\"").append(esc(name)).append("\"");
            if (rptId != null && !rptId.isEmpty()) sb.append(" rptID=\"").append(esc(rptId)).append("\"");
            // datSet en SCL es el nombre simple del DataSet, sin la referencia completa
            if (datSet != null && !datSet.isEmpty()) {
                String simple = datSet;
                int dot = simple.lastIndexOf('.');
                if (dot >= 0) simple = simple.substring(dot + 1);
                int dollar = simple.lastIndexOf('$');
                if (dollar >= 0) simple = simple.substring(dollar + 1);
                sb.append(" datSet=\"").append(esc(simple)).append("\"");
                // El equipo dice a que DataSet apunta el RCB, pero si ese DataSet no
                // esta en el modelo tampoco se emite <DataSet> y la referencia queda
                // colgando. Un archivo asi carga, pero el reporting no funciona: hay
                // que decirlo en vez de dejarlo pasar en silencio.
                if (!tieneDataSet(simple)) datSetsColgados.add(simple);
            }
            sb.append(" confRev=\"").append(esc(confRev != null && !confRev.isEmpty() ? confRev : "1")).append("\"");
            sb.append(" buffered=\"").append(buffered).append("\"");
            String intgPd = childString(rcb, "IntgPd");
            if (intgPd != null && !intgPd.isEmpty()) sb.append(" intgPd=\"").append(esc(intgPd)).append("\"");
            sb.append(">\n");
            sb.append("              <TrgOps dchg=\"true\" qchg=\"true\" dupd=\"false\" period=\"")
              .append(intgPd != null && !intgPd.isEmpty() && !"0".equals(intgPd)).append("\"/>\n");
            sb.append("              <OptFields seqNum=\"true\" timeStamp=\"true\" dataSet=\"true\" reasonCode=\"true\"")
              .append(" dataRef=\"false\" entryID=\"").append(buffered).append("\" configRef=\"true\"/>\n");
            sb.append("            </ReportControl>\n");
        }
    }

    /**
     * ¿Este Functional Constraint corresponde a un bloque de control y no a datos?
     * RP = report control block no bufferizado, BR = bufferizado. En SCL van como
     * {@code <ReportControl>}, no como DO del LNodeType.
     */
    /** ¿El modelo trae un DataSet con ese nombre simple? */
    private boolean tieneDataSet(String nombreSimple) {
        Collection<DataSet> all = model.getDataSets();
        if (all == null) return false;
        for (DataSet ds : all) {
            String ref = ds.getReferenceStr();
            if (ref == null) continue;
            int dot = ref.lastIndexOf('.');
            String simple = (dot >= 0) ? ref.substring(dot + 1) : ref;
            if (simple.equals(nombreSimple)) return true;
        }
        return false;
    }

    private static boolean isControlBlockFc(Fc fc) {
        return fc == Fc.RP || fc == Fc.BR;
    }

    private String childString(ModelNode parent, String childName) {
        try {
            ModelNode c = parent.getChild(childName);
            if (c instanceof BasicDataAttribute) return ((BasicDataAttribute) c).getValueString();
        } catch (Exception ignore) {}
        return null;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  DataTypeTemplates: síntesis y deduplicación
    // ═════════════════════════════════════════════════════════════════════════
    private void appendDataTypeTemplates(StringBuilder sb) {
        sb.append("  <DataTypeTemplates>\n");
        for (String xml : lnTypeXml.values()) sb.append(xml);
        for (String xml : doTypeXml.values()) sb.append(xml);
        for (String xml : daTypeXml.values()) sb.append(xml);
        sb.append("  </DataTypeTemplates>\n");
    }

    /** Registra (o reutiliza) el LNodeType de un LN según su conjunto de DO. */
    private String registerLnType(String lnClass, Map<String, List<FcDataObject>> byDo) {
        // Primero los DOType, que forman parte de la firma del LNodeType
        Map<String, String> doRefs = new LinkedHashMap<>();
        for (Map.Entry<String, List<FcDataObject>> e : byDo.entrySet()) {
            doRefs.put(e.getKey(), registerDoType(e.getKey(), e.getValue()));
        }

        StringBuilder sig = new StringBuilder("LN:").append(lnClass);
        for (Map.Entry<String, String> e : doRefs.entrySet()) {
            sig.append('|').append(e.getKey()).append('=').append(e.getValue());
        }
        String key = sig.toString();
        String existing = lnTypeIds.get(key);
        if (existing != null) return existing;

        String id = uniqueId(lnClass + "_" + (lnTypeIds.size() + 1), lnTypeXml.keySet());
        lnTypeIds.put(key, id);

        StringBuilder xml = new StringBuilder();
        xml.append("    <LNodeType id=\"").append(esc(id)).append("\" lnClass=\"")
           .append(esc(lnClass)).append("\">\n");
        for (Map.Entry<String, String> e : doRefs.entrySet()) {
            xml.append("      <DO name=\"").append(esc(e.getKey())).append("\" type=\"")
               .append(esc(e.getValue())).append("\"/>\n");
        }
        xml.append("    </LNodeType>\n");
        lnTypeXml.put(id, xml.toString());
        return id;
    }

    /**
     * Registra (o reutiliza) el DOType de un DO. Las instancias son el mismo DO visto desde
     * distintos FC; se unen todos sus DA porque en SCL el DOType los declara una sola vez,
     * cada uno con su propio atributo fc.
     */
    private String registerDoType(String doName, List<FcDataObject> instances) {
        nDo++;
        // Se separan los hijos en dos familias:
        //  - sub-data-objects (SDO): hijos que son a su vez FcDataObject, p.ej. las componentes
        //    de fase phsA/phsB/phsC de un WYE, que son CMV. En SCL van como <SDO type="DOType">.
        //  - data attributes (DA): básicos o estructurados.
        // Un mismo hijo puede aparecer bajo varios FC con distinto contenido; se unen todas las
        // instancias por nombre para no perder atributos presentes sólo en uno de los FC.
        Map<String, List<FcDataObject>> sdos = new LinkedHashMap<>();
        Map<String, DaInfo> das = new LinkedHashMap<>();
        for (FcDataObject fcdo : instances) {
            Fc fc = fcdo.getFc();
            if (fcdo.getChildren() == null) continue;
            for (ModelNode child : fcdo.getChildren()) {
                if (child instanceof FcDataObject) {
                    sdos.computeIfAbsent(child.getName(), k -> new ArrayList<>())
                        .add((FcDataObject) child);
                } else {
                    das.computeIfAbsent(child.getName(), k -> new DaInfo(child, fc));
                }
            }
        }

        // Los SDO se registran recursivamente: cada uno es un DOType por derecho propio.
        Map<String, String> sdoTypes = new LinkedHashMap<>();
        for (Map.Entry<String, List<FcDataObject>> e : sdos.entrySet()) {
            sdoTypes.put(e.getKey(), registerDoType(e.getKey(), e.getValue()));
        }

        String cdc = inferCdc(doName, das, sdos.keySet());

        StringBuilder sig = new StringBuilder("DO:").append(cdc);
        for (Map.Entry<String, String> e : sdoTypes.entrySet()) {
            sig.append("|S:").append(e.getKey()).append('=').append(e.getValue());
        }
        for (Map.Entry<String, DaInfo> e : das.entrySet()) {
            sig.append('|').append(e.getKey()).append(':').append(e.getValue().signature(this));
        }
        String key = sig.toString();
        String existing = doTypeIds.get(key);
        if (existing != null) return existing;

        String id = uniqueId(cdc + "_" + doName + "_" + (doTypeIds.size() + 1), doTypeXml.keySet());
        doTypeIds.put(key, id);

        StringBuilder xml = new StringBuilder();
        xml.append("    <DOType id=\"").append(esc(id)).append("\" cdc=\"").append(esc(cdc)).append("\">\n");
        // SDO antes que DA, igual que los archivos de fabricante
        for (Map.Entry<String, String> e : sdoTypes.entrySet()) {
            xml.append("      <SDO name=\"").append(esc(e.getKey())).append("\" type=\"")
               .append(esc(e.getValue())).append("\"/>\n");
        }
        for (Map.Entry<String, DaInfo> e : das.entrySet()) {
            xml.append(e.getValue().toDaXml(this, e.getKey()));
        }
        xml.append("    </DOType>\n");
        doTypeXml.put(id, xml.toString());
        return id;
    }

    /** Registra (o reutiliza) un DAType para un atributo estructurado. */
    private String registerDaType(ModelNode cda) {
        Map<String, DaInfo> bdas = new LinkedHashMap<>();
        if (cda.getChildren() != null) {
            for (ModelNode child : cda.getChildren()) {
                Fc fc = (child instanceof FcModelNode) ? ((FcModelNode) child).getFc() : null;
                bdas.computeIfAbsent(child.getName(), k -> new DaInfo(child, fc));
            }
        }
        StringBuilder sig = new StringBuilder("DA:");
        for (Map.Entry<String, DaInfo> e : bdas.entrySet()) {
            sig.append('|').append(e.getKey()).append(':').append(e.getValue().signature(this));
        }
        String key = sig.toString();
        String existing = daTypeIds.get(key);
        if (existing != null) return existing;

        String id = uniqueId(cda.getName() + "_" + (daTypeIds.size() + 1), daTypeXml.keySet());
        daTypeIds.put(key, id);

        StringBuilder xml = new StringBuilder();
        xml.append("    <DAType id=\"").append(esc(id)).append("\">\n");
        for (Map.Entry<String, DaInfo> e : bdas.entrySet()) {
            xml.append("      <BDA name=\"").append(esc(e.getKey())).append("\" bType=\"")
               .append(esc(e.getValue().bType(this))).append("\"");
            if (e.getValue().isStruct()) {
                xml.append(" type=\"").append(esc(registerDaType(e.getValue().node))).append("\"");
            }
            xml.append("/>\n");
        }
        xml.append("    </DAType>\n");
        daTypeXml.put(id, xml.toString());
        return id;
    }

    /** Un DA/BDA observado: el nodo y el FC en que se lo vio. */
    private static final class DaInfo {
        final ModelNode node;
        final Fc fc;
        DaInfo(ModelNode node, Fc fc) { this.node = node; this.fc = fc; }

        boolean isStruct() { return node instanceof ConstructedDataAttribute; }

        String bType(SclExporter ex) {
            if (isStruct()) return "Struct";
            if (node instanceof BasicDataAttribute) {
                return ex.mapBType(((BasicDataAttribute) node).getBasicType());
            }
            return "Struct";
        }

        String signature(SclExporter ex) {
            if (!isStruct()) return bType(ex) + (fc != null ? "@" + fc : "");
            StringBuilder sb = new StringBuilder("S{");
            if (node.getChildren() != null) {
                for (ModelNode c : node.getChildren()) {
                    Fc cfc = (c instanceof FcModelNode) ? ((FcModelNode) c).getFc() : null;
                    sb.append(c.getName()).append(':')
                      .append(new DaInfo(c, cfc).signature(ex)).append(',');
                }
            }
            return sb.append('}').toString();
        }

        String toDaXml(SclExporter ex, String name) {
            StringBuilder sb = new StringBuilder();
            sb.append("      <DA name=\"").append(esc(name)).append("\"");
            if (fc != null) sb.append(" fc=\"").append(fc).append("\"");
            sb.append(" bType=\"").append(esc(bType(ex))).append("\"");
            if (isStruct()) sb.append(" type=\"").append(esc(ex.registerDaType(node))).append("\"");
            if (node instanceof BasicDataAttribute) {
                BasicDataAttribute bda = (BasicDataAttribute) node;
                if (bda.getDchg()) sb.append(" dchg=\"true\"");
                if (bda.getQchg()) sb.append(" qchg=\"true\"");
                if (bda.getDupd()) sb.append(" dupd=\"true\"");
            }
            sb.append("/>\n");
            return sb.toString();
        }
    }

    /** BdaType de iec61850bean → bType de SCL (IEC 61850-6 tabla de tipos básicos). */
    private String mapBType(BdaType t) {
        if (t == null) return "Struct";
        switch (t) {
            case BOOLEAN:        return "BOOLEAN";
            case INT8:           return "INT8";
            case INT16:          return "INT16";
            case INT32:          return "INT32";
            case INT64:          return "INT64";
            case INT128:         return "INT128";
            case INT8U:          return "INT8U";
            case INT16U:         return "INT16U";
            case INT32U:         return "INT32U";
            case FLOAT32:        return "FLOAT32";
            case FLOAT64:        return "FLOAT64";
            case OCTET_STRING:   return "Octet64";
            case VISIBLE_STRING: return "VisString255";
            case UNICODE_STRING: return "Unicode255";
            case TIMESTAMP:      return "Timestamp";
            case ENTRY_TIME:     return "EntryTime";
            case CHECK:          return "Check";
            case QUALITY:        return "Quality";
            case DOUBLE_BIT_POS: return "Dbpos";
            case TAP_COMMAND:    return "Tcmd";
            case OPTFLDS:            return "OptFlds";
            case TRIGGER_CONDITIONS: return "TrgOps";
            case REASON_FOR_INCLUSION: return "ReasonForInclusion";
            default:             return "Struct";
        }
    }

    /**
     * Infiere el cdc del DO a partir de los DA que expone (IEC 61850-7-3).
     * MMS no transmite el cdc, así que se deduce de la forma. Los casos que no encajan en
     * ningún patrón se registran en {@link Result#uncertainCdc}.
     */
    private String inferCdc(String doName, Map<String, DaInfo> das, java.util.Set<String> sdoNames) {
        // ── 1. Agregados cuyos miembros son sub-data-objects ──────────────────
        if (das.containsKey("seqT")) return "SEQ";                       // SEQ: seqT + c1/c2/c3
        if (sdoNames.contains("phsA") || sdoNames.contains("phsB") || sdoNames.contains("phsC"))
            return "WYE";
        if (sdoNames.contains("phsAB") || sdoNames.contains("phsBC") || sdoNames.contains("phsCA"))
            return "DEL";

        // ── 2. Controlables ──────────────────────────────────────────────────
        // La presencia de ctlModel basta: un DO con ctlModel=status-only sigue siendo de una
        // clase controlable (ENC/SPC/DPC), sólo que no expone Oper. Sin esto, todos los DO de
        // modo con ctlModel=status-only se clasificaban como ENS en vez de ENC.
        boolean controllable = das.containsKey("Oper") || das.containsKey("SBOw")
                            || das.containsKey("ctlVal") || das.containsKey("ctlModel");
        if (controllable) {
            String t = ctlValType(das);
            if (t == null && das.containsKey("stVal")) t = das.get("stVal").bType(this);
            if ("Dbpos".equals(t))                          return "DPC";
            if ("BOOLEAN".equals(t))
                return isDbpos(das, "stVal") ? "DPC" : "SPC";
            // En SCL los enumerados son bType="Enum", pero por MMS llegan como INT8; los
            // enteros propiamente dichos usan INT32. De ahí ENC (enum) frente a INC (entero).
            if ("INT8".equals(t) || "INT16".equals(t))      return "ENC";
            if ("INT32".equals(t) || "INT32U".equals(t))    return "INC";
            if ("FLOAT32".equals(t) || "FLOAT64".equals(t)) return "APC";
            if ("Tcmd".equals(t))                           return "BSC";
            if (isDbpos(das, "stVal"))                      return "DPC";
            return "SPC";
        }

        // ── 3. Medidas ───────────────────────────────────────────────────────
        // SAV (valor muestreado) trae instMag pero no mag, y suele declarar sVC.
        if (das.containsKey("sVC")) return "SAV";
        if (das.containsKey("instMag") && !das.containsKey("mag")) return "SAV";
        if (das.containsKey("mag"))                                return "MV";
        if (das.containsKey("cVal") || das.containsKey("instCVal")) return "CMV";
        if (das.containsKey("actVal") || das.containsKey("frVal"))  return "BCR";

        // ── 4. Estados de arranque/disparo ───────────────────────────────────
        if (das.containsKey("general")
                && (das.containsKey("dirGeneral") || das.containsKey("dirPhsA"))) return "ACD";
        if (das.containsKey("general")) return "ACT";

        // ── 5. Estados simples ───────────────────────────────────────────────
        if (das.containsKey("stVal")) {
            if (isDbpos(das, "stVal")) return "DPS";
            String t = das.get("stVal").bType(this);
            if ("BOOLEAN".equals(t))                     return "SPS";
            if ("INT8".equals(t) || "INT16".equals(t))   return "ENS";   // enumerado
            if ("INT32".equals(t) || "INT32U".equals(t)) return "INS";
            if (t != null && t.startsWith("VisString"))  return "VSS";
            return "INS";
        }

        // ── 6. Seguimiento de servicios (IEC 61850-7-2 Ed.2) ─────────────────
        if (das.containsKey("objRef") && das.containsKey("serviceType")) {
            if (das.containsKey("numOfSG") || das.containsKey("actSG")) return "STS";
            return "CST";
        }

        // ── 7. Nameplates: DPL (equipo físico) antes que LPL (nodo lógico) ───
        if (das.containsKey("hwRev") || das.containsKey("serNum")
                || das.containsKey("model") || das.containsKey("location")) return "DPL";
        if (das.containsKey("configRev") || das.containsKey("ldNs")
                || das.containsKey("lnNs") || das.containsKey("vendor")
                || das.containsKey("swRev")) return "LPL";

        // ── 8. Ajustes ───────────────────────────────────────────────────────
        if (das.containsKey("setCal"))    return "TSG";
        if (das.containsKey("setSrcRef")) return "ORG";
        if (das.containsKey("setMag"))    return "ASG";
        if (das.containsKey("setCharact")) return "CURVE";
        if (das.containsKey("setVal")) {
            String t = das.get("setVal").bType(this);
            if ("BOOLEAN".equals(t))                     return "SPG";
            if ("FLOAT32".equals(t) || "FLOAT64".equals(t)) return "ASG";
            if ("INT8".equals(t) || "INT16".equals(t))   return "ENG";   // enumerado
            if (t != null && t.startsWith("INT"))        return "ING";
            if (t != null && t.startsWith("VisString"))  return "VSG";
            return "SPG";
        }

        // ── 9. Descripción suelta ────────────────────────────────────────────
        if (das.size() == 1 && das.containsKey("val")) return "VSD";

        uncertainCdc.add(doName + " {" + String.join(",", das.keySet())
                + (sdoNames.isEmpty() ? "" : " | SDO: " + String.join(",", sdoNames)) + "}");
        // Fallback conservador: contenedor de estado sin miembro reconocible.
        return das.isEmpty() && !sdoNames.isEmpty() ? "WYE" : "INS";
    }

    private boolean isDbpos(Map<String, DaInfo> das, String name) {
        DaInfo d = das.get(name);
        return d != null && "Dbpos".equals(d.bType(this));
    }

    /** Tipo del ctlVal, esté suelto o dentro de Oper/SBOw. */
    private String ctlValType(Map<String, DaInfo> das) {
        DaInfo direct = das.get("ctlVal");
        if (direct != null && !direct.isStruct()) return direct.bType(this);
        for (String holder : new String[]{"Oper", "SBOw"}) {
            DaInfo h = das.get(holder);
            if (h == null || h.node.getChildren() == null) continue;
            for (ModelNode c : h.node.getChildren()) {
                if ("ctlVal".equals(c.getName()) && c instanceof BasicDataAttribute) {
                    return mapBType(((BasicDataAttribute) c).getBasicType());
                }
            }
        }
        return null;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Nombres: MMS → SCL
    // ═════════════════════════════════════════════════════════════════════════
    /**
     * Deduce el nombre del IED. En MMS los LD se llaman iedName+ldInst, así que el prefijo
     * común más largo entre todos los LD es el nombre del IED. Con un solo LD no hay forma
     * de separarlos y se usa un nombre genérico, dejando el LD entero como ldInst.
     */
    private String deriveIedName(List<LogicalDevice> lds) {
        if (lds.size() < 2) return "IED";
        String common = lds.get(0).getName();
        for (int i = 1; i < lds.size(); i++) {
            common = commonPrefix(common, lds.get(i).getName());
            if (common.isEmpty()) break;
        }
        // Un prefijo común de 1-2 caracteres es probablemente casualidad, no el nombre del IED.
        if (common.length() < 3) return "IED";
        // Si el prefijo se come TODO el nombre de algún LD, ese LD quedaría sin inst: retroceder.
        for (LogicalDevice ld : lds) {
            if (ld.getName().length() == common.length()) return "IED";
        }
        return common;
    }

    private static String commonPrefix(String a, String b) {
        int n = Math.min(a.length(), b.length()), i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) i++;
        return a.substring(0, i);
    }

    /** prefijo + clase + instancia de un nombre de LN. */
    static final class LnName {
        final String prefix, lnClass, inst;
        LnName(String prefix, String lnClass, String inst) {
            this.prefix = prefix; this.lnClass = lnClass; this.inst = inst;
        }
    }

    /**
     * Separa un nombre de LN en prefijo/clase/instancia. Usa el diccionario para localizar la
     * clase IEC 61850 dentro del nombre, lo que cubre los prefijos de fabricante
     * ({@code POTT_PSCH1} → prefijo {@code POTT_}, clase {@code PSCH}, inst {@code 1}).
     */
    static LnName splitLnName(String name) {
        if (name == null || name.isBlank()) return new LnName("", "GGIO", "1");
        String n = name.trim();
        if ("LLN0".equalsIgnoreCase(n)) return new LnName("", "LLN0", "");

        // instancia = dígitos finales
        int end = n.length();
        while (end > 0 && Character.isDigit(n.charAt(end - 1))) end--;
        String inst = n.substring(end);
        String base = n.substring(0, end);

        String cls = Iec61850Dictionary.inferLnClass(base);
        if (cls != null) {
            int idx = base.toUpperCase().lastIndexOf(cls);
            if (idx >= 0) {
                return new LnName(base.substring(0, idx), base.substring(idx), inst);
            }
        }
        // Sin clase reconocible: las clases IEC tienen 4 caracteres, se toman los últimos 4.
        if (base.length() > 4) {
            return new LnName(base.substring(0, base.length() - 4),
                              base.substring(base.length() - 4), inst);
        }
        return new LnName("", base, inst);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Utilidades
    // ═════════════════════════════════════════════════════════════════════════
    /** Asegura un id único y válido como NCName de XML. */
    private static String uniqueId(String base, java.util.Set<String> taken) {
        String s = base.replaceAll("[^A-Za-z0-9_.-]", "_");
        if (s.isEmpty() || Character.isDigit(s.charAt(0))) s = "T" + s;
        String id = s;
        int n = 2;
        while (taken.contains(id)) id = s + "_" + (n++);
        return id;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** Escapa texto y valores de atributo para XML. */
    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&':  sb.append("&amp;");  break;
                case '<':  sb.append("&lt;");   break;
                case '>':  sb.append("&gt;");   break;
                case '"':  sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default:
                    // XML 1.0 no admite caracteres de control salvo tab/LF/CR
                    if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') sb.append(' ');
                    else sb.append(c);
            }
        }
        return sb.toString();
    }
}
