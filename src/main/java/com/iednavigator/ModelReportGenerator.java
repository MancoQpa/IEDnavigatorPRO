package com.iednavigator;

import com.beanit.iec61850bean.*;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Genera un reporte HTML autocontenido del modelo IED explorado
 * (modo cliente o servidor). Imprimible a PDF desde el navegador
 * (Ctrl+P → Guardar como PDF).
 *
 * Secciones: resumen, nameplate, inventario LD/LN, DataSets, RCBs
 * y opcionalmente los valores actuales de todos los atributos hoja.
 */
class ModelReportGenerator {

    /**
     * @param outFile       archivo .html de salida
     * @param model         modelo del IED (cliente conectado o servidor simulado)
     * @param sourceDesc    descripción de la fuente (IP:puerto o nombre de archivo SCL)
     * @param nameplate     [manufacturer, type, desc, configVersion] o null
     * @param includeValues incluir sección con valores actuales (puede ser extensa)
     */
    static void generate(File outFile, ServerModel model, String sourceDesc,
                         String[] nameplate, boolean includeValues) throws Exception {

        // ─── Recolección de datos ───
        int lnCount = 0, doCount = 0, leafCount = 0;
        List<ModelNode> lds = new ArrayList<>(model.getChildren());
        for (ModelNode ld : lds) {
            for (ModelNode ln : ld.getChildren()) {
                lnCount++;
                if (ln.getChildren() != null) doCount += ln.getChildren().size();
            }
        }
        Collection<DataSet> dataSets = model.getDataSets();

        // RCBs (cubre modo servidor: hijos del LN; y cliente: getUrcbs/getBrcbs)
        List<String[]> rcbs = new ArrayList<>(); // {ref, tipo, dataset, trgOps, intgPd}
        Set<String> seenRcbs = new HashSet<>();
        for (ModelNode ld : lds) {
            for (ModelNode ln : ld.getChildren()) {
                if (!(ln instanceof LogicalNode)) continue;
                LogicalNode logicalNode = (LogicalNode) ln;
                List<Rcb> all = new ArrayList<>();
                if (logicalNode.getUrcbs() != null) all.addAll(logicalNode.getUrcbs());
                if (logicalNode.getBrcbs() != null) all.addAll(logicalNode.getBrcbs());
                for (ModelNode child : ln.getChildren()) {
                    if (child instanceof Rcb) all.add((Rcb) child);
                }
                for (Rcb rcb : all) {
                    String ref = ld.getName() + "/" + ln.getName() + "." + rcb.getName();
                    if (!seenRcbs.add(ref)) continue;
                    rcbs.add(new String[]{
                        ref,
                        rcb instanceof Brcb ? "BRCB" : "URCB",
                        rcb.getDatSet() != null ? rcb.getDatSet().getStringValue() : "",
                        trgOpsString(rcb.getTrgOps()),
                        rcb.getIntgPd() != null ? String.valueOf(rcb.getIntgPd().getValue()) : ""
                    });
                }
            }
        }

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        // ─── HTML ───
        try (PrintWriter pw = new PrintWriter(outFile, StandardCharsets.UTF_8)) {
            pw.println("<!DOCTYPE html><html lang='es'><head><meta charset='UTF-8'>");
            pw.println("<title>Reporte IED - " + esc(sourceDesc) + "</title>");
            pw.println("<style>");
            pw.println("body{font-family:Segoe UI,Arial,sans-serif;margin:24px;color:#222;font-size:13px}");
            pw.println("h1{color:#2E86AB;border-bottom:2px solid #2E86AB;padding-bottom:4px;font-size:22px}");
            pw.println("h2{color:#2E86AB;margin-top:28px;font-size:16px}");
            pw.println("table{border-collapse:collapse;width:100%;margin:8px 0}");
            pw.println("th{background:#2E86AB;color:#fff;text-align:left;padding:4px 8px;font-size:12px}");
            pw.println("td{border:1px solid #ccc;padding:3px 8px;font-size:12px}");
            pw.println("tr:nth-child(even){background:#f4f8fa}");
            pw.println(".mono{font-family:Consolas,monospace}");
            pw.println(".summary{display:flex;gap:24px;flex-wrap:wrap;margin:12px 0}");
            pw.println(".card{border:1px solid #ccc;border-radius:6px;padding:8px 16px;text-align:center}");
            pw.println(".card .num{font-size:24px;font-weight:bold;color:#2E86AB}");
            pw.println(".meta{color:#666;font-size:11px}");
            pw.println("@media print{body{margin:8mm}h2{page-break-after:avoid}tr{page-break-inside:avoid}}");
            pw.println("</style></head><body>");

            pw.println("<h1>Reporte de modelo IEC 61850</h1>");
            pw.println("<p class='meta'>Fuente: <b>" + esc(sourceDesc) + "</b> &nbsp;|&nbsp; Generado: "
                + sdf.format(new Date()) + " &nbsp;|&nbsp; IED Navigator PRO</p>");

            // Resumen
            pw.println("<div class='summary'>");
            card(pw, lds.size(), "Logical Devices");
            card(pw, lnCount, "Logical Nodes");
            card(pw, doCount, "Data Objects");
            card(pw, dataSets.size(), "DataSets");
            card(pw, rcbs.size(), "RCBs");
            pw.println("</div>");

            // Nameplate
            if (nameplate != null) {
                pw.println("<h2>Nameplate</h2><table>");
                String[] labels = {"Fabricante", "Modelo/Tipo", "Descripción", "Config. versión"};
                pw.println("<tr>");
                for (String l : labels) pw.println("<th>" + l + "</th>");
                pw.println("</tr><tr>");
                for (int i = 0; i < labels.length; i++) {
                    String v = i < nameplate.length && nameplate[i] != null ? nameplate[i] : "—";
                    pw.println("<td>" + esc(v) + "</td>");
                }
                pw.println("</tr></table>");
            }

            // Inventario LD/LN
            pw.println("<h2>Inventario de Logical Devices y Logical Nodes</h2>");
            for (ModelNode ld : lds) {
                pw.println("<h3 class='mono'>" + esc(ld.getName()) + "</h3><table>");
                pw.println("<tr><th>Logical Node</th><th>Data Objects</th></tr>");
                for (ModelNode ln : ld.getChildren()) {
                    StringBuilder dos = new StringBuilder();
                    if (ln.getChildren() != null) {
                        for (ModelNode child : ln.getChildren()) {
                            if (dos.length() > 0) dos.append(", ");
                            dos.append(child.getName());
                        }
                    }
                    pw.println("<tr><td class='mono'>" + esc(ln.getName()) + "</td><td class='mono'>"
                        + esc(dos.toString()) + "</td></tr>");
                }
                pw.println("</table>");
            }

            // DataSets
            pw.println("<h2>DataSets (" + dataSets.size() + ")</h2>");
            if (dataSets.isEmpty()) {
                pw.println("<p class='meta'>No hay DataSets en el modelo.</p>");
            }
            for (DataSet ds : dataSets) {
                pw.println("<h3 class='mono'>" + esc(ds.getReferenceStr()) + "</h3><table>");
                pw.println("<tr><th>#</th><th>Miembro</th><th>FC</th></tr>");
                int n = 1;
                for (FcModelNode member : ds.getMembers()) {
                    pw.println("<tr><td>" + (n++) + "</td><td class='mono'>"
                        + esc(member.getReference().toString()) + "</td><td>"
                        + esc(member.getFc() != null ? member.getFc().toString() : "") + "</td></tr>");
                }
                pw.println("</table>");
            }

            // RCBs
            pw.println("<h2>Report Control Blocks (" + rcbs.size() + ")</h2>");
            if (rcbs.isEmpty()) {
                pw.println("<p class='meta'>No se encontraron RCBs en el modelo.</p>");
            } else {
                pw.println("<table><tr><th>Referencia</th><th>Tipo</th><th>DataSet</th>"
                    + "<th>TrgOps</th><th>IntgPd</th></tr>");
                for (String[] r : rcbs) {
                    pw.println("<tr><td class='mono'>" + esc(r[0]) + "</td><td>" + r[1]
                        + "</td><td class='mono'>" + esc(r[2]) + "</td><td>" + esc(r[3])
                        + "</td><td>" + esc(r[4]) + "</td></tr>");
                }
                pw.println("</table>");
            }

            // Valores actuales (opcional)
            if (includeValues) {
                pw.println("<h2>Valores actuales (" + countLeaves(model) + " atributos)</h2>");
                pw.println("<table><tr><th>Referencia</th><th>FC</th><th>Tipo</th><th>Valor</th></tr>");
                for (ModelNode ld : lds) {
                    for (ModelNode ln : ld.getChildren()) {
                        writeLeafValues(pw, ln);
                    }
                }
                pw.println("</table>");
            }

            pw.println("<p class='meta'>Para PDF: imprimir esta página desde el navegador (Ctrl+P → Guardar como PDF).</p>");
            pw.println("</body></html>");
        }
    }

    private static void writeLeafValues(PrintWriter pw, ModelNode node) {
        if (node instanceof BasicDataAttribute) {
            BasicDataAttribute bda = (BasicDataAttribute) node;
            String fc = bda.getFc() != null ? bda.getFc().toString() : "";
            String type = bda.getBasicType() != null ? bda.getBasicType().toString() : "";
            String val;
            try { val = bda.getValueString(); } catch (Exception e) { val = ""; }
            pw.println("<tr><td class='mono'>" + esc(bda.getReference().toString())
                + "</td><td>" + esc(fc) + "</td><td>" + esc(type)
                + "</td><td class='mono'>" + esc(val) + "</td></tr>");
            return;
        }
        if (node.getChildren() != null) {
            for (ModelNode child : node.getChildren()) writeLeafValues(pw, child);
        }
    }

    private static int countLeaves(ModelNode node) {
        if (node instanceof BasicDataAttribute) return 1;
        int n = 0;
        if (node.getChildren() != null) {
            for (ModelNode child : node.getChildren()) n += countLeaves(child);
        }
        return n;
    }

    private static String trgOpsString(BdaTriggerConditions tc) {
        if (tc == null) return "";
        StringBuilder sb = new StringBuilder();
        if (tc.isDataChange())           sb.append("dchg ");
        if (tc.isQualityChange())        sb.append("qchg ");
        if (tc.isDataUpdate())           sb.append("dupd ");
        if (tc.isIntegrity())            sb.append("intg ");
        if (tc.isGeneralInterrogation()) sb.append("gi ");
        return sb.toString().trim();
    }

    private static void card(PrintWriter pw, int num, String label) {
        pw.println("<div class='card'><div class='num'>" + num + "</div><div>" + label + "</div></div>");
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
