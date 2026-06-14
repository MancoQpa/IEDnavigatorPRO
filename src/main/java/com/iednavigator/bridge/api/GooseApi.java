package com.iednavigator.bridge.api;

import com.iednavigator.GooseModelSync;
import com.iednavigator.GoosePublisher;
import com.iednavigator.GooseSubscriber;
import com.iednavigator.GooseUdpBridge;
import com.iednavigator.IEC61850Server;
import com.iednavigator.SclDataSet;
import com.iednavigator.SclFileProcessor;
import com.iednavigator.SclGoCB;
import com.iednavigator.bridge.EventBus;
import com.iednavigator.bridge.SessionManager;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import org.pcap4j.core.PcapAddress;
import org.pcap4j.core.PcapNetworkInterface;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Endpoints /api/v1/net/* y /api/v1/goose/* : interfaces de red,
 * publicación GOOSE desde GoCBs del SCL (con sync bidireccional al modelo
 * de servidor simulado) y suscripción GOOSE por pcap4j.
 */
public final class GooseApi {

    /** Supresión de heartbeats: por gocbRef, mínimo entre emisiones sin cambio de stNum. */
    private static final long COALESCE_MS = 250;

    private final SessionManager sessions;
    private final EventBus eventBus;

    // ── Estado SCL/GoCBs ──
    private volatile List<SclGoCB> goCBs = new ArrayList<>();
    private volatile List<SclDataSet> dataSets = new ArrayList<>();
    private volatile String iedName;
    private volatile String sclPath;

    // ── Publishers activos por índice de GoCB ──
    private final Map<Integer, GoosePublisher> publishers = new ConcurrentHashMap<>();

    // ── Subscriber pcap ──
    private GooseSubscriber subscriber;
    private volatile String subscriberInterface;

    // ── Puente GOOSE sobre UDP (redes WiFi/enrutadas) ──
    private GooseUdpBridge udpBridge;
    private volatile boolean udpReceiving;
    private volatile boolean udpSending;
    private volatile String udpTargetIp;

    /** Coalescing por clave (source|gocbRef): [lastEmitMs, lastStNum]. */
    private final Map<String, long[]> lastEmit = new ConcurrentHashMap<>();

    public GooseApi(SessionManager sessions, EventBus eventBus) {
        this.sessions = sessions;
        this.eventBus = eventBus;
    }

    // ── DTOs ──────────────────────────────────────────────────────────────

    public static class LoadRequest {
        public String path;
        public int iedIndex = 0;
    }

    public static class PublishRequest {
        public int index = -1;          // -1 = todos
        public String interfaceName;    // nombre pcap o "loopback"
    }

    public static class StopRequest {
        public int index = -1;          // -1 = todos
    }

    public static class ValueRequest {
        public int index;
        public int dataIndex;
        public Object value;
    }

    public static class SubscribeRequest {
        public String interfaceName;
    }

    public static class UdpStartRequest {
        public boolean receive;
        public boolean send;
        public String targetIp; // null/vacío = broadcast
    }

    // ── Interfaces de red ─────────────────────────────────────────────────

    /** GET /net/interfaces. */
    public void listInterfaces(Context ctx) {
        List<Map<String, Object>> out = new ArrayList<>();
        boolean pcapOk = false;
        try {
            for (PcapNetworkInterface nif : GooseSubscriber.getNetworkInterfaces()) {
                pcapOk = true;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", nif.getName());
                m.put("description", nif.getDescription());
                List<String> addrs = new ArrayList<>();
                for (PcapAddress a : nif.getAddresses()) {
                    if (a.getAddress() != null) addrs.add(a.getAddress().getHostAddress());
                }
                m.put("addresses", addrs);
                out.add(m);
            }
        } catch (Throwable t) {
            // Npcap ausente: degradación limpia con lista vacía
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("npcapAvailable", pcapOk);
        body.put("interfaces", out);
        ctx.json(body);
    }

    // ── GoCBs del SCL ─────────────────────────────────────────────────────

    /** POST /goose/scl/load {path, iedIndex} — parsea GoCBs y DataSets del SCL. */
    public void loadScl(Context ctx) {
        LoadRequest req = ctx.bodyAsClass(LoadRequest.class);
        if (req.path == null || req.path.isEmpty()) throw new BadRequestResponse("path requerido");
        File f = new File(req.path);
        if (!f.isFile()) throw new BadRequestResponse("Fichero no encontrado: " + req.path);

        stopAllPublishers();

        SclFileProcessor.SclParsingResult r =
                SclFileProcessor.parseIEDByIndex(f, req.iedIndex, null);
        goCBs = r.goCBs;
        dataSets = r.dataSets;
        iedName = r.iedName;
        sclPath = f.getAbsolutePath();
        ctx.json(stateJson());
    }

    /** GET /goose/gocbs. */
    public void listGoCBs(Context ctx) {
        ctx.json(stateJson());
    }

    // ── Publicación ───────────────────────────────────────────────────────

    /** POST /goose/publish {index, interfaceName} — index -1 publica todos. */
    public void publish(Context ctx) {
        PublishRequest req = ctx.bodyAsClass(PublishRequest.class);
        if (goCBs.isEmpty()) throw new IllegalStateException("No hay GoCBs cargados (use goose/scl/load)");
        if (req.interfaceName == null || req.interfaceName.isEmpty()) {
            throw new BadRequestResponse("interfaceName requerido (nombre pcap o 'loopback')");
        }

        boolean loopback = "loopback".equalsIgnoreCase(req.interfaceName);
        PcapNetworkInterface nif = loopback ? null : findInterface(req.interfaceName);

        List<Integer> targets = new ArrayList<>();
        if (req.index < 0) {
            for (int i = 0; i < goCBs.size(); i++) targets.add(i);
        } else {
            if (req.index >= goCBs.size()) throw new BadRequestResponse("index fuera de rango");
            targets.add(req.index);
        }

        List<Integer> started = new ArrayList<>();
        for (int idx : targets) {
            stopPublisher(idx);
            SclGoCB gcb = goCBs.get(idx);
            GoosePublisher pub = GooseModelSync.configurePublisher(gcb, idx, dataSets);
            // Heartbeat rápido para UI responsiva (500ms máx.);
            // SCL maxTime puede ser alto (ej. 2000ms), lo limitamos.
            pub.setHeartbeatInterval(Math.min(pub.getHeartbeatInterval(), 500));
            pub.setLogListener(msg -> eventBus.emit("goose.log",
                    Map.of("message", "[GoCB#" + idx + "] " + msg)));
            pub.setPublishListener(pm -> emitPublishedMessage(idx, gcb, pm));

            boolean initOk = loopback || pub.init(nif);
            if (initOk) {
                pub.startPublishing();
                publishers.put(idx, pub);
                started.add(idx);
            } else {
                pub.close();
                eventBus.emit("goose.log",
                        Map.of("message", "ERROR: no se pudo inicializar publisher para " + gcb.cbName));
            }
        }
        if (started.isEmpty()) {
            throw new BadRequestResponse("No se pudo iniciar ningún publisher en " + req.interfaceName);
        }
        ctx.json(stateJson());
    }

    /** POST /goose/stop {index} — index -1 detiene todos. */
    public void stopPublishing(Context ctx) {
        StopRequest req = ctx.bodyAsClass(StopRequest.class);
        if (req.index < 0) {
            stopAllPublishers();
        } else {
            stopPublisher(req.index);
        }
        ctx.json(stateJson());
    }

    /** POST /goose/value {index, dataIndex, value} — escribe y publica cambio de estado. */
    public void setValue(Context ctx) {
        ValueRequest req = ctx.bodyAsClass(ValueRequest.class);
        GoosePublisher pub = publishers.get(req.index);
        if (pub == null || !pub.isPublishing()) {
            throw new IllegalStateException("El GoCB #" + req.index + " no está publicando");
        }
        List<GoosePublisher.DataValue> values = pub.getDataValues();
        if (req.dataIndex < 0 || req.dataIndex >= values.size()) {
            throw new BadRequestResponse("dataIndex fuera de rango");
        }

        Object typed = coerce(req.value, values.get(req.dataIndex).type);
        pub.setDataValue(req.dataIndex, typed);
        pub.publishStateChange();
        forwardOverUdp(pub);

        // GOOSE → modelo del servidor simulado
        IEC61850Server server = sessions.getServer();
        if (server != null && server.getServerModel() != null && req.index < goCBs.size()) {
            String modelRef = GooseModelSync.syncPublisherToServerModel(
                    server, iedName, goCBs.get(req.index), dataSets, pub, req.dataIndex);
            if (modelRef != null) {
                String strValue = GooseModelSync.convertPublisherValueToString(
                        pub.getDataValues().get(req.dataIndex));
                eventBus.valueChanged(modelRef, null, strValue, "goose");
            }
        }
        ctx.json(stateJson());
    }

    // ── Suscripción pcap ──────────────────────────────────────────────────

    /** POST /goose/subscribe {interfaceName}. */
    public synchronized void subscribe(Context ctx) {
        SubscribeRequest req = ctx.bodyAsClass(SubscribeRequest.class);
        if (req.interfaceName == null || req.interfaceName.isEmpty()) {
            throw new BadRequestResponse("interfaceName requerido");
        }
        if (subscriber != null && subscriber.isRunning()) {
            throw new IllegalStateException("Ya hay una suscripción GOOSE activa en " + subscriberInterface);
        }
        PcapNetworkInterface nif = findInterface(req.interfaceName);
        GooseSubscriber sub = new GooseSubscriber();
        sub.setLogListener(msg -> eventBus.emit("goose.log", Map.of("message", "[SUB] " + msg)));
        sub.setMessageListener(msg -> emitNetworkMessage(msg, "network"));
        if (!sub.start(nif)) {
            throw new BadRequestResponse("No se pudo iniciar la captura en " + req.interfaceName);
        }
        subscriber = sub;
        subscriberInterface = req.interfaceName;
        ctx.json(stateJson());
    }

    /** POST /goose/unsubscribe. */
    public synchronized void unsubscribe(Context ctx) {
        if (subscriber != null) {
            subscriber.stop();
            subscriber = null;
            subscriberInterface = null;
        }
        ctx.json(stateJson());
    }

    /** GET /goose/status. */
    public void status(Context ctx) {
        ctx.json(stateJson());
    }

    // ── Puente GOOSE-UDP ──────────────────────────────────────────────────

    /** POST /goose/udp/start {receive, send, targetIp} — GOOSE sobre UDP (WiFi/enrutado). */
    public synchronized void udpStart(Context ctx) {
        UdpStartRequest req = ctx.bodyAsClass(UdpStartRequest.class);
        if (!req.receive && !req.send) {
            throw new BadRequestResponse("Indique receive y/o send");
        }
        if (udpBridge == null) {
            udpBridge = new GooseUdpBridge();
            udpBridge.setLogListener(msg ->
                    eventBus.emit("goose.log", Map.of("message", "[UDP] " + msg)));
            udpBridge.setMessageListener(msg -> emitNetworkMessage(msg, "udp"));
        }
        if (req.receive && !udpBridge.isReceiving()) {
            if (!udpBridge.startReceiving()) {
                throw new IllegalStateException("No se pudo iniciar el receptor UDP (¿puerto "
                        + GooseUdpBridge.DEFAULT_PORT + " en uso?)");
            }
            udpReceiving = true;
        }
        if (req.send) {
            String target = (req.targetIp == null || req.targetIp.isEmpty()) ? null : req.targetIp;
            if (!udpBridge.initSender(target)) {
                throw new IllegalStateException("No se pudo inicializar el emisor UDP");
            }
            udpSending = true;
            udpTargetIp = target;
        }
        ctx.json(stateJson());
    }

    /** POST /goose/udp/stop. */
    public synchronized void udpStop(Context ctx) {
        stopUdp();
        ctx.json(stateJson());
    }

    private void stopUdp() {
        if (udpBridge != null) {
            udpBridge.close();
            udpBridge = null;
        }
        udpReceiving = false;
        udpSending = false;
        udpTargetIp = null;
    }

    /** Reenvía el frame del publisher por UDP si el emisor está activo. */
    private void forwardOverUdp(GoosePublisher pub) {
        GooseUdpBridge bridge = udpBridge;
        if (udpSending && bridge != null) {
            bridge.send(pub);
        }
    }

    // ── Sync modelo → GOOSE (hook desde ServerApi) ────────────────────────

    /** Propaga cambios del modelo de servidor a los publishers activos. */
    public void propagateFromModel() {
        IEC61850Server server = sessions.getServer();
        if (server == null || server.getServerModel() == null || publishers.isEmpty()) return;
        for (Map.Entry<Integer, GoosePublisher> e : publishers.entrySet()) {
            GoosePublisher pub = e.getValue();
            int idx = e.getKey();
            if (!pub.isPublishing() || idx >= goCBs.size()) continue;
            boolean changed = GooseModelSync.propagateModelToPublisher(
                    server.getServerModel(), iedName, goCBs.get(idx), dataSets, pub);
            if (changed) {
                pub.publishStateChange();
                forwardOverUdp(pub);
            }
        }
    }

    /** Detiene publishers y subscriber (apagado del bridge). */
    public synchronized void shutdown() {
        stopAllPublishers();
        if (subscriber != null) {
            subscriber.stop();
            subscriber = null;
            subscriberInterface = null;
        }
        stopUdp();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void stopPublisher(int idx) {
        GoosePublisher pub = publishers.remove(idx);
        if (pub != null) {
            try {
                pub.stopPublishing();
                pub.close();
            } catch (Exception ignore) {
            }
        }
    }

    private void stopAllPublishers() {
        for (Integer idx : new ArrayList<>(publishers.keySet())) {
            stopPublisher(idx);
        }
    }

    private static PcapNetworkInterface findInterface(String name) {
        for (PcapNetworkInterface nif : GooseSubscriber.getNetworkInterfaces()) {
            if (name.equals(nif.getName())) return nif;
        }
        throw new BadRequestResponse("Interfaz no encontrada: " + name
                + " (¿Npcap instalado?)");
    }

    /** Convierte el valor JSON al tipo del DataValue GOOSE. */
    private static Object coerce(Object raw, GoosePublisher.DataValue.Type type) {
        String s = String.valueOf(raw);
        switch (type) {
            case BOOLEAN:
                if (raw instanceof Boolean) return raw;
                return s.equalsIgnoreCase("true") || s.equals("1") || s.equalsIgnoreCase("on");
            case DBPOS:
                switch (s.toUpperCase()) {
                    case "ON": return 2;
                    case "OFF": return 1;
                    case "INTERMEDIATE": return 0;
                    case "BAD": return 3;
                    default:
                        try { return Integer.parseInt(s); }
                        catch (NumberFormatException e) { return 1; }
                }
            case INTEGER:
            case UNSIGNED:
            case BITSTRING:
                if (raw instanceof Number) return ((Number) raw).intValue();
                try { return Integer.parseInt(s); }
                catch (NumberFormatException e) { return 0; }
            case FLOAT:
                if (raw instanceof Number) return ((Number) raw).floatValue();
                try { return Float.parseFloat(s); }
                catch (NumberFormatException e) { return 0.0f; }
            default:
                return s;
        }
    }

    /** ¿Debe emitirse este mensaje, o se suprime como heartbeat repetido? */
    private boolean shouldEmit(String key, int stNum) {
        long now = System.currentTimeMillis();
        long[] prev = lastEmit.get(key);
        if (prev != null && prev[1] == stNum && now - prev[0] < COALESCE_MS) {
            return false;
        }
        lastEmit.put(key, new long[] {now, stNum});
        return true;
    }

    private void emitPublishedMessage(int idx, SclGoCB gcb, GoosePublisher.PublishedMessage pm) {
        // Emitir TODOS los mensajes locales sin filtro — igual que la GUI Swing original.
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("source", "local");
        p.put("gcbIndex", idx);
        p.put("gocbRef", pm.gocbRef);
        p.put("goId", pm.goId);
        p.put("datSet", pm.datSet);
        p.put("appId", pm.appId);
        p.put("stNum", pm.stNum);
        p.put("sqNum", pm.sqNum);
        p.put("confRev", gcb.confRev);
        p.put("srcMac", "LOCAL");
        p.put("dstMac", gcb.macAddress != null ? gcb.macAddress : "01:0C:CD:01:00:01");
        p.put("entries", dataValuesJson(pm.dataValues));
        eventBus.emit("goose.message", p);
    }

    private void emitNetworkMessage(GooseSubscriber.GooseMessage msg, String source) {
        if (!shouldEmit(source + "|" + msg.gocbRef, msg.stNum)) return;
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("source", source);
        p.put("gocbRef", msg.gocbRef);
        p.put("goId", msg.goId);
        p.put("datSet", msg.datSet);
        p.put("appId", msg.appId);
        p.put("stNum", msg.stNum);
        p.put("sqNum", msg.sqNum);
        p.put("confRev", msg.confRev);
        p.put("test", msg.test);
        p.put("srcMac", msg.srcMac);
        p.put("dstMac", msg.dstMac);
        List<Map<String, Object>> entries = new ArrayList<>();
        for (GooseSubscriber.DataEntry de : msg.dataEntries) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("index", de.index);
            e.put("type", de.type);
            e.put("value", de.value == null ? null : String.valueOf(de.value));
            entries.add(e);
        }
        p.put("entries", entries);
        eventBus.emit("goose.message", p);
    }

    private static List<Map<String, Object>> dataValuesJson(List<GoosePublisher.DataValue> values) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (values == null) return out;
        int i = 0;
        for (GoosePublisher.DataValue dv : values) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("index", i++);
            e.put("name", dv.name);
            e.put("type", dv.type.name());
            e.put("value", dv.value == null ? null : String.valueOf(dv.value));
            out.add(e);
        }
        return out;
    }

    private Map<String, Object> stateJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (sclPath != null) m.put("sclPath", sclPath);
        if (iedName != null) m.put("iedName", iedName);
        m.put("subscribing", subscriber != null && subscriber.isRunning());
        if (subscriberInterface != null) m.put("subscriberInterface", subscriberInterface);

        Map<String, Object> udp = new LinkedHashMap<>();
        GooseUdpBridge bridge = udpBridge;
        udp.put("receiving", udpReceiving && bridge != null && bridge.isReceiving());
        udp.put("sending", udpSending);
        udp.put("port", GooseUdpBridge.DEFAULT_PORT);
        if (udpTargetIp != null) udp.put("targetIp", udpTargetIp);
        if (bridge != null) {
            udp.put("sentCount", bridge.getSentCount());
            udp.put("receivedCount", bridge.getReceivedCount());
        }
        m.put("udp", udp);

        List<Map<String, Object>> list = new ArrayList<>();
        List<SclGoCB> cbs = goCBs;
        for (int i = 0; i < cbs.size(); i++) {
            SclGoCB gcb = cbs.get(i);
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("index", i);
            g.put("ldInst", gcb.ldInst);
            g.put("cbName", gcb.cbName);
            g.put("goId", gcb.goID);
            g.put("datSet", gcb.datSet);
            g.put("appId", gcb.appID);
            g.put("confRev", gcb.confRev);
            g.put("macAddress", gcb.macAddress);
            g.put("vlanId", gcb.vlanId);
            g.put("minTime", gcb.minTime);
            g.put("maxTime", gcb.maxTime);
            GoosePublisher pub = publishers.get(i);
            boolean publishing = pub != null && pub.isPublishing();
            g.put("publishing", publishing);
            if (publishing) {
                g.put("stNum", pub.getStNum());
                g.put("sqNum", pub.getSqNum());
                g.put("dataValues", dataValuesJson(pub.getDataValues()));
            } else {
                g.put("dataValues", dataValuesJson(
                        GooseModelSync.buildDataValuesFromDataSet(gcb, dataSets)));
            }
            list.add(g);
        }
        m.put("gocbs", list);
        return m;
    }
}
