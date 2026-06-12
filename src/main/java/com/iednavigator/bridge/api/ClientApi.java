package com.iednavigator.bridge.api;

import com.beanit.iec61850bean.Fc;
import com.beanit.iec61850bean.FcModelNode;
import com.beanit.iec61850bean.ModelNode;
import com.beanit.iec61850bean.ServerModel;
import com.iednavigator.IEC61850Client;
import com.iednavigator.bridge.HeadlessPoller;
import com.iednavigator.bridge.ModelSerializer;
import com.iednavigator.bridge.SessionManager;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;

import java.util.List;
import java.util.Map;

/** Endpoints /api/v1/client/* : conexión MMS, modelo, lectura/escritura, watchlist. */
public final class ClientApi {

    private final SessionManager sessions;
    private final ModelSerializer modelSerializer = new ModelSerializer();

    public ClientApi(SessionManager sessions) {
        this.sessions = sessions;
    }

    // ── DTOs de request ───────────────────────────────────────────────────

    public static class ConnectRequest {
        public String host;
        public int port = 102;
        public Integer timeoutMs;
    }

    public static class ReadRequest {
        public String ref;
        public String fc;
    }

    public static class WriteRequest {
        public String ref;
        public String fc;
        public String value;
    }

    public static class WatchlistRequest {
        public List<String> refs;
        public int intervalMs = 1000;
    }

    public static class OperateRequest {
        public String ref;            // ref del DO de control o del nodo Oper
        public String value;          // ctlVal como string ("on"/"off", "true", float, etc.)
        public boolean test;
        public String orIdent;
        public boolean synchroCheck;
        public boolean interlockCheck;
    }

    public static class CancelRequest {
        public String ref;
        public String orIdent;
    }

    public static class BlockingRequest {
        public String ref;            // ref del DO (se resuelve .blkEna [BL])
        public boolean block;
    }

    // ── Handlers ──────────────────────────────────────────────────────────

    public void connect(Context ctx) throws Exception {
        ConnectRequest req = ctx.bodyAsClass(ConnectRequest.class);
        if (req.host == null || req.host.isEmpty()) {
            throw new BadRequestResponse("host requerido");
        }
        sessions.connect(req.host, req.port, req.timeoutMs);
        IEC61850Client client = sessions.requireClient();
        ctx.json(Map.of(
                "connected", true,
                "host", client.getHost(),
                "port", client.getPort(),
                "iedName", client.getIedName()
        ));
    }

    public void disconnect(Context ctx) {
        sessions.disconnect();
        ctx.json(Map.of("connected", false));
    }

    public void status(Context ctx) {
        boolean connected = sessions.isClientConnected();
        if (connected) {
            IEC61850Client client = sessions.getClient();
            ctx.json(Map.of(
                    "connected", true,
                    "host", client.getHost(),
                    "port", client.getPort(),
                    "iedName", client.getIedName()
            ));
        } else {
            ctx.json(Map.of("connected", false));
        }
    }

    public void model(Context ctx) {
        IEC61850Client client = sessions.requireClient();
        if (client.getServerModel() == null) {
            throw new BadRequestResponse("Modelo no disponible");
        }
        ctx.json(modelSerializer.serialize(client.getServerModel(), client.getIedName()));
    }

    public void read(Context ctx) throws Exception {
        ReadRequest req = ctx.bodyAsClass(ReadRequest.class);
        Fc fc = parseFc(req.fc);
        String value = sessions.requireClient().readValue(req.ref, fc);
        ctx.json(Map.of("ref", req.ref, "fc", req.fc, "value", value == null ? "" : value));
    }

    public void write(Context ctx) throws Exception {
        WriteRequest req = ctx.bodyAsClass(WriteRequest.class);
        Fc fc = parseFc(req.fc);
        sessions.requireClient().writeValue(req.ref, fc, req.value);
        ctx.json(Map.of("ref", req.ref, "fc", req.fc, "written", true));
    }

    public void setWatchlist(Context ctx) {
        WatchlistRequest req = ctx.bodyAsClass(WatchlistRequest.class);
        sessions.requireClient(); // valida conexión
        HeadlessPoller poller = sessions.getPoller();
        poller.setWatchlist(req.refs, req.intervalMs);
        ctx.json(Map.of(
                "refs", poller.getWatchlist(),
                "intervalMs", poller.getIntervalMs()
        ));
    }

    public void getWatchlist(Context ctx) {
        HeadlessPoller poller = sessions.getPoller();
        if (poller == null) {
            ctx.json(Map.of("refs", List.of(), "intervalMs", 0));
            return;
        }
        ctx.json(Map.of("refs", poller.getWatchlist(), "intervalMs", poller.getIntervalMs()));
    }

    public void nameplate(Context ctx) {
        ctx.json(sessions.requireClient().readDeviceNameplate());
    }

    // ── Control (SBO / direct) ────────────────────────────────────────────

    private static final String[] CTL_MODEL_NAMES = {
            "status-only", "direct-normal-security", "sbo-normal-security",
            "direct-enhanced-security", "sbo-enhanced-security"
    };

    /** Resuelve el nodo Oper (FC=CO) a partir de la ref del DO o del propio Oper. */
    private FcModelNode resolveOperNode(IEC61850Client client, String ref) {
        ServerModel model = client.getServerModel();
        if (model == null) throw new BadRequestResponse("Modelo no disponible");
        if (ref == null || ref.isEmpty()) throw new BadRequestResponse("ref requerido");

        ModelNode node = model.findModelNode(ref, Fc.CO);
        if (node == null && !ref.endsWith(".Oper")) {
            node = model.findModelNode(ref + ".Oper", Fc.CO);
        }
        if (node instanceof FcModelNode) {
            FcModelNode fcn = (FcModelNode) node;
            if ("Oper".equals(fcn.getName())) return fcn;
            if (fcn.getChildren() != null) {
                for (ModelNode child : fcn.getChildren()) {
                    if ("Oper".equals(child.getName()) && child instanceof FcModelNode) {
                        return (FcModelNode) child;
                    }
                }
            }
        }
        throw new BadRequestResponse("Nodo Oper no encontrado para: " + ref);
    }

    /** Ref del DO propietario a partir de la ref del Oper. */
    private static String doRefOf(FcModelNode operNode) {
        String operRef = operNode.getReference().toString();
        int lastDot = operRef.lastIndexOf('.');
        return lastDot > 0 ? operRef.substring(0, lastDot) : operRef;
    }

    /** GET /client/control-info?ref= — info para construir el diálogo de control. */
    public void controlInfo(Context ctx) {
        IEC61850Client client = sessions.requireClient();
        FcModelNode operNode = resolveOperNode(client, ctx.queryParam("ref"));
        int ctlModel = client.getCtlModelValue(operNode);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("operRef", operNode.getReference().toString());
        out.put("ctlModel", ctlModel);
        out.put("ctlModelName", CTL_MODEL_NAMES[Math.min(Math.max(ctlModel, 0), 4)]);
        out.put("ctlValType", client.getOperCtlValType(operNode));
        out.put("sbo", ctlModel == 2 || ctlModel == 4);
        out.put("blockable", client.findBlkEnaNode(doRefOf(operNode)) != null);
        ctx.json(out);
    }

    public void operate(Context ctx) throws Exception {
        OperateRequest req = ctx.bodyAsClass(OperateRequest.class);
        if (req.value == null || req.value.isEmpty()) {
            throw new BadRequestResponse("value requerido");
        }
        IEC61850Client client = sessions.requireClient();
        FcModelNode operNode = resolveOperNode(client, req.ref);
        IEC61850Client.ControlResult cr = client.operateControl(
                operNode, req.value, req.test, req.orIdent, req.synchroCheck, req.interlockCheck);
        ctx.json(controlResultJson(cr));
    }

    public void cancel(Context ctx) throws Exception {
        CancelRequest req = ctx.bodyAsClass(CancelRequest.class);
        IEC61850Client client = sessions.requireClient();
        FcModelNode operNode = resolveOperNode(client, req.ref);
        IEC61850Client.ControlResult cr = client.cancelControl(operNode, req.orIdent);
        ctx.json(controlResultJson(cr));
    }

    public void blocking(Context ctx) throws Exception {
        BlockingRequest req = ctx.bodyAsClass(BlockingRequest.class);
        IEC61850Client client = sessions.requireClient();
        if (req.ref == null || req.ref.isEmpty()) throw new BadRequestResponse("ref requerido");
        FcModelNode blkNode = client.findBlkEnaNode(req.ref);
        if (blkNode == null) {
            throw new BadRequestResponse("blkEna [BL] no encontrado en: " + req.ref);
        }
        client.setBlocking(blkNode, req.block);
        ctx.json(Map.of(
                "ref", blkNode.getReference().toString(),
                "blocked", req.block
        ));
    }

    private static Map<String, Object> controlResultJson(IEC61850Client.ControlResult cr) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("success", cr.success);
        out.put("ctlModel", cr.ctlModel);
        out.put("ctlModelName", cr.ctlModelName);
        if (cr.error != null) out.put("error", cr.error);
        if (cr.lastApplError != null) out.put("lastApplError", cr.lastApplError);
        return out;
    }

    static Fc parseFc(String fc) {
        if (fc == null || fc.isEmpty()) {
            throw new BadRequestResponse("fc requerido");
        }
        try {
            return Fc.valueOf(fc.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestResponse("FC inválido: " + fc);
        }
    }
}
