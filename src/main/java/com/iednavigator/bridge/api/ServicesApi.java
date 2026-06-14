package com.iednavigator.bridge.api;

import com.beanit.iec61850bean.BasicDataAttribute;
import com.beanit.iec61850bean.BdaReasonForInclusion;
import com.beanit.iec61850bean.Brcb;
import com.beanit.iec61850bean.DataSet;
import com.beanit.iec61850bean.FcModelNode;
import com.beanit.iec61850bean.FileInformation;
import com.beanit.iec61850bean.ModelNode;
import com.beanit.iec61850bean.Rcb;
import com.beanit.iec61850bean.Report;
import com.beanit.iec61850bean.ServerModel;
import com.beanit.iec61850bean.Urcb;
import com.iednavigator.IEC61850Client;
import com.iednavigator.IEC61850Server;
import com.iednavigator.ModelReportGenerator;
import com.iednavigator.bridge.EventBus;
import com.iednavigator.bridge.SessionManager;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Endpoints Fase 4: Reports (RCBs), Setting Groups, DataSets,
 * servicios de fichero MMS (descarga CID) y export HTML del modelo.
 */
public final class ServicesApi {

    private final SessionManager sessions;
    private final EventBus eventBus;

    /** Refs de RCBs habilitados por el bridge (estado local de la sesión). */
    private final Set<String> enabledRcbs = ConcurrentHashMap.newKeySet();

    public ServicesApi(SessionManager sessions, EventBus eventBus) {
        this.sessions = sessions;
        this.eventBus = eventBus;
    }

    /** Limpieza al desconectar (la llama BridgeServer/SessionManager si procede). */
    public void resetState() {
        enabledRcbs.clear();
    }

    // ── DTOs ──────────────────────────────────────────────────────────────

    public static class RcbRequest {
        public String ref;
    }

    public static class DataSetReadRequest {
        public String ref;
    }

    public static class SgSelectRequest {
        public String ld;
        public int group;
    }

    // ── Reports / RCBs ────────────────────────────────────────────────────

    /** GET /client/rcbs — lista URCBs y BRCBs del modelo con su estado. */
    public void listRcbs(Context ctx) {
        IEC61850Client client = sessions.requireClient();
        ServerModel model = requireModel(client);
        boolean refresh = "true".equals(ctx.queryParam("refresh"));

        List<Map<String, Object>> out = new ArrayList<>();
        for (Urcb u : model.getUrcbs()) out.add(rcbJson(client, u, "URCB", refresh));
        for (Brcb b : model.getBrcbs()) out.add(rcbJson(client, b, "BRCB", refresh));
        ctx.json(Map.of("rcbs", out));
    }

    /** POST /client/rcbs/enable {ref} — habilita reporting y empuja client.report por WS. */
    public void enableRcb(Context ctx) throws Exception {
        RcbRequest req = ctx.bodyAsClass(RcbRequest.class);
        IEC61850Client client = sessions.requireClient();
        Rcb rcb = findRcb(client, req.ref);
        client.enableReporting(rcb, report -> eventBus.emit("client.report", reportJson(client, report)));
        enabledRcbs.add(req.ref);
        ctx.json(Map.of("ref", req.ref, "enabled", true));
    }

    /** POST /client/rcbs/disable {ref}. */
    public void disableRcb(Context ctx) throws Exception {
        RcbRequest req = ctx.bodyAsClass(RcbRequest.class);
        IEC61850Client client = sessions.requireClient();
        Rcb rcb = findRcb(client, req.ref);
        client.disableReporting(rcb);
        enabledRcbs.remove(req.ref);
        ctx.json(Map.of("ref", req.ref, "enabled", false));
    }

    private Rcb findRcb(IEC61850Client client, String ref) {
        if (ref == null || ref.isEmpty()) throw new BadRequestResponse("ref requerido");
        ServerModel model = requireModel(client);
        Rcb rcb = model.getUrcb(ref);
        if (rcb == null) rcb = model.getBrcb(ref);
        if (rcb == null) throw new NotFoundResponse("RCB no encontrado: " + ref);
        return rcb;
    }

    private Map<String, Object> rcbJson(IEC61850Client client, Rcb rcb, String type, boolean refresh) {
        if (refresh) {
            try {
                client.getAssociation().getRcbValues(rcb);
            } catch (Exception ignored) {
            }
        }
        String ref = rcb.getReference().toString();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ref", ref);
        m.put("name", rcb.getName());
        m.put("type", type);
        m.put("rptId", str(rcb.getRptId()));
        m.put("datSet", str(rcb.getDatSet()));
        m.put("rptEna", rcb.getRptEna() != null && rcb.getRptEna().getValue());
        m.put("enabledByBridge", enabledRcbs.contains(ref));
        m.put("trgOps", rcb.getTrgOps() != null ? rcb.getTrgOps().getValueString() : "");
        m.put("intgPd", rcb.getIntgPd() != null ? rcb.getIntgPd().getValue() : 0);
        m.put("bufTm", rcb.getBufTm() != null ? rcb.getBufTm().getValue() : 0);
        m.put("confRev", rcb.getConfRev() != null ? rcb.getConfRev().getValue() : 0);
        if (rcb instanceof Urcb) {
            Urcb u = (Urcb) rcb;
            m.put("resv", u.getResv() != null && u.getResv().getValue());
        }
        return m;
    }

    private Map<String, Object> reportJson(IEC61850Client client, Report report) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rptId", report.getRptId());
        m.put("sqNum", report.getSqNum());
        m.put("dataSetRef", report.getDataSetRef());
        m.put("bufOvfl", report.getBufOvfl());
        m.put("confRev", report.getConfRev());
        m.put("timeOfEntry", report.getTimeOfEntry() != null
                ? report.getTimeOfEntry().getTimestampValue() : null);
        m.put("ts", System.currentTimeMillis());

        List<Map<String, Object>> entries = new ArrayList<>();
        List<FcModelNode> values = report.getValues();
        List<BdaReasonForInclusion> reasons = report.getReasonCodes();
        if (values != null) {
            for (int i = 0; i < values.size(); i++) {
                FcModelNode node = values.get(i);
                String reason = (reasons != null && i < reasons.size() && reasons.get(i) != null)
                        ? reasonString(reasons.get(i)) : "";
                for (BasicDataAttribute bda : collectLeaves(node)) {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("ref", bda.getReference().toString());
                    e.put("fc", bda.getFc() != null ? bda.getFc().toString() : "");
                    e.put("value", client.formatValue(bda));
                    e.put("reason", reason);
                    entries.add(e);
                }
            }
        }
        m.put("entries", entries);
        return m;
    }

    // ── DataSets ──────────────────────────────────────────────────────────

    /**
     * Busca un DataSet en el modelo del servidor (más completo que el modelo MMS del cliente).
     * Retorna null si el servidor no está corriendo o no tiene el DataSet.
     */
    private DataSet findInServerModel(String ref) {
        IEC61850Server srv = sessions.getServer();
        if (srv == null) return null;
        ServerModel srvModel = srv.getServerModel();
        if (srvModel == null) return null;
        for (DataSet ds : srvModel.getDataSets()) {
            if (ds.getReferenceStr().equals(ref)) return ds;
        }
        return null;
    }

    /** GET /client/datasets — lista de DataSets del modelo con sus miembros. */
    public void listDataSets(Context ctx) {
        IEC61850Client client = sessions.requireClient();
        ServerModel model = requireModel(client);

        // El modelo recuperado vía MMS puede ser incompleto para SCDs grandes.
        // Si hay un servidor corriendo, su modelo (parseado directo del SCL) es autoritativo.
        IEC61850Server srv = sessions.getServer();
        ServerModel srvModel = (srv != null) ? srv.getServerModel() : null;
        Iterable<DataSet> source = (srvModel != null && !srvModel.getDataSets().isEmpty())
                ? srvModel.getDataSets()
                : model.getDataSets();

        List<Map<String, Object>> out = new ArrayList<>();
        for (DataSet ds : source) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ref", ds.getReferenceStr());
            m.put("deletable", ds.isDeletable());
            List<Map<String, Object>> members = new ArrayList<>();
            for (FcModelNode member : ds.getMembers()) {
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("ref", member.getReference().toString());
                mm.put("fc", member.getFc() != null ? member.getFc().toString() : "");
                members.add(mm);
            }
            m.put("members", members);
            out.add(m);
        }
        ctx.json(Map.of("datasets", out));
    }

    /** POST /client/dataset/read {ref} — GetDataSetValues y valores hoja. */
    public void readDataSet(Context ctx) throws Exception {
        DataSetReadRequest req = ctx.bodyAsClass(DataSetReadRequest.class);
        if (req.ref == null || req.ref.isEmpty()) throw new BadRequestResponse("ref requerido");
        IEC61850Client client = sessions.requireClient();
        DataSet ds;
        try {
            ds = client.readDataSetValues(req.ref);
        } catch (Exception e) {
            // El DataSet no está en el modelo MMS del cliente; buscar en modelo del servidor
            DataSet serverDs = findInServerModel(req.ref);
            if (serverDs == null) throw e;
            ds = client.readDataSetValues(serverDs);
        }

        List<Map<String, Object>> values = new ArrayList<>();
        for (FcModelNode member : ds.getMembers()) {
            for (BasicDataAttribute bda : collectLeaves(member)) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("ref", bda.getReference().toString());
                v.put("fc", bda.getFc() != null ? bda.getFc().toString() : "");
                v.put("value", client.formatValue(bda));
                v.put("type", client.getValueType(bda));
                values.add(v);
            }
        }
        ctx.json(Map.of("ref", ds.getReferenceStr(), "values", values));
    }

    // ── Setting Groups ────────────────────────────────────────────────────

    /** GET /client/sg — SGCBs de todos los LDs que lo tengan. */
    public void listSettingGroups(Context ctx) {
        IEC61850Client client = sessions.requireClient();
        ServerModel model = requireModel(client);
        List<Map<String, Object>> out = new ArrayList<>();
        for (ModelNode ld : model.getChildren()) {
            int[] sg = client.readSGCBValues(ld.getName());
            if (sg != null) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ld", ld.getName());
                m.put("actSG", sg[0]);
                m.put("numOfSGs", sg[1]);
                out.add(m);
            }
        }
        ctx.json(Map.of("settingGroups", out));
    }

    /** POST /client/sg/select {ld, group} — SelectActiveSG. */
    public void selectSettingGroup(Context ctx) throws Exception {
        SgSelectRequest req = ctx.bodyAsClass(SgSelectRequest.class);
        if (req.ld == null || req.ld.isEmpty()) throw new BadRequestResponse("ld requerido");
        if (req.group < 1) throw new BadRequestResponse("group debe ser >= 1");
        IEC61850Client client = sessions.requireClient();
        client.selectActiveSG(req.ld, req.group);
        int[] sg = client.readSGCBValues(req.ld);
        ctx.json(Map.of(
                "ld", req.ld,
                "actSG", sg != null ? sg[0] : req.group,
                "numOfSGs", sg != null ? sg[1] : 0
        ));
    }

    // ── Servicios de fichero MMS ──────────────────────────────────────────

    /** GET /client/files?dir= — listado de directorio del IED. */
    public void listFiles(Context ctx) throws Exception {
        String dir = ctx.queryParam("dir");
        if (dir == null) dir = "";
        IEC61850Client client = sessions.requireClient();
        List<Map<String, Object>> out = new ArrayList<>();
        for (FileInformation fi : client.listFiles(dir)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", fi.getFilename());
            m.put("size", fi.getFileSize());
            m.put("lastModified", fi.getLastModified() != null
                    ? fi.getLastModified().getTimeInMillis() : null);
            out.add(m);
        }
        ctx.json(Map.of("dir", dir, "files", out));
    }

    /** GET /client/files/scl — busca CID/ICD/SCD/SCL en directorios habituales. */
    public void findSclFiles(Context ctx) throws Exception {
        IEC61850Client client = sessions.requireClient();
        ctx.json(Map.of("files", client.findSclFiles()));
    }

    /** GET /client/files/download?path= — descarga binaria del fichero. */
    public void downloadFile(Context ctx) throws Exception {
        String path = ctx.queryParam("path");
        if (path == null || path.isEmpty()) throw new BadRequestResponse("path requerido");
        IEC61850Client client = sessions.requireClient();
        byte[] data = client.downloadFile(path);
        String filename = path.substring(path.lastIndexOf('/') + 1);
        ctx.contentType("application/octet-stream")
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .result(data);
    }

    // ── Export HTML del modelo ────────────────────────────────────────────

    /** GET /client/export/model-html?values=true — reporte HTML autocontenido. */
    public void exportModelHtml(Context ctx) throws Exception {
        IEC61850Client client = sessions.requireClient();
        ServerModel model = requireModel(client);
        boolean includeValues = "true".equals(ctx.queryParam("values"));

        Map<String, String> np = client.readDeviceNameplate();
        String[] nameplate = np.isEmpty() ? null : new String[]{
                np.getOrDefault("vendor", ""),
                np.getOrDefault("phy.model", ""),
                np.getOrDefault("d", ""),
                np.getOrDefault("configRev", "")
        };

        File tmp = File.createTempFile("ied-model-", ".html");
        try {
            ModelReportGenerator.generate(tmp, model,
                    client.getHost() + ":" + client.getPort(), nameplate, includeValues);
            String html = new String(Files.readAllBytes(tmp.toPath()), StandardCharsets.UTF_8);
            ctx.contentType("text/html; charset=utf-8").result(html);
        } finally {
            if (!tmp.delete()) tmp.deleteOnExit();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static ServerModel requireModel(IEC61850Client client) {
        ServerModel model = client.getServerModel();
        if (model == null) throw new BadRequestResponse("Modelo no disponible");
        return model;
    }

    private static List<BasicDataAttribute> collectLeaves(ModelNode node) {
        List<BasicDataAttribute> out = new ArrayList<>();
        collectLeaves(node, out);
        return out;
    }

    private static void collectLeaves(ModelNode node, List<BasicDataAttribute> out) {
        if (node instanceof BasicDataAttribute) {
            out.add((BasicDataAttribute) node);
        } else if (node.getChildren() != null) {
            for (ModelNode child : node.getChildren()) {
                collectLeaves(child, out);
            }
        }
    }

    private static String reasonString(BdaReasonForInclusion r) {
        List<String> parts = new ArrayList<>();
        if (r.isDataChange()) parts.add("dchg");
        if (r.isQualityChange()) parts.add("qchg");
        if (r.isDataUpdate()) parts.add("dupd");
        if (r.isIntegrity()) parts.add("integrity");
        if (r.isGeneralInterrogation()) parts.add("gi");
        if (r.isApplicationTrigger()) parts.add("app-trigger");
        return String.join(",", parts);
    }

    private static String str(com.beanit.iec61850bean.BdaVisibleString bda) {
        return bda != null ? bda.getStringValue() : "";
    }
}
