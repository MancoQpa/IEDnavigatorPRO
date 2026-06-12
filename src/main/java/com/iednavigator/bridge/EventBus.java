package com.iednavigator.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.websocket.WsContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Distribuye eventos a los clientes WebSocket con envelope {type, seq, ts, payload}.
 *
 * - valueChanged: coalescido por referencia y enviado en batch cada FLUSH_MS.
 * - emit(): envío inmediato (conexión cerrada, server started, errores...).
 *
 * Backpressure: si la cola de batch supera MAX_PENDING se descartan los más
 * antiguos (drop-oldest) — nunca se bloquea el hilo productor.
 */
public final class EventBus {

    private static final long FLUSH_MS = 100;
    private static final int MAX_PENDING = 5000;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Set<WsContext> sessions = ConcurrentHashMap.newKeySet();
    private final AtomicLong seq = new AtomicLong();

    /** Cambios de valor pendientes, coalescidos por referencia. */
    private final Map<String, Map<String, Object>> pendingValues = new LinkedHashMap<>();
    private final Object pendingLock = new Object();

    private final ScheduledExecutorService flusher =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "eventbus-flusher");
                t.setDaemon(true);
                return t;
            });

    public EventBus() {
        flusher.scheduleAtFixedRate(this::flushValues, FLUSH_MS, FLUSH_MS, TimeUnit.MILLISECONDS);
    }

    public void register(WsContext ctx) {
        sessions.add(ctx);
    }

    public void unregister(WsContext ctx) {
        sessions.remove(ctx);
    }

    public int sessionCount() {
        return sessions.size();
    }

    /** Encola un cambio de valor (coalescido por ref, batch cada 100 ms). */
    public void valueChanged(String ref, String fc, String value, String type) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("ref", ref);
        change.put("fc", fc);
        change.put("value", value);
        change.put("type", type);
        change.put("ts", System.currentTimeMillis());
        synchronized (pendingLock) {
            if (pendingValues.size() >= MAX_PENDING && !pendingValues.containsKey(ref)) {
                // drop-oldest
                String oldest = pendingValues.keySet().iterator().next();
                pendingValues.remove(oldest);
            }
            pendingValues.put(ref, change);
        }
    }

    /** Envío inmediato de un evento puntual. */
    public void emit(String type, Object payload) {
        broadcast(envelope(type, payload));
    }

    private void flushValues() {
        List<Map<String, Object>> batch;
        synchronized (pendingLock) {
            if (pendingValues.isEmpty()) return;
            batch = new ArrayList<>(pendingValues.values());
            pendingValues.clear();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("changes", batch);
        broadcast(envelope("client.valueChanged", payload));
    }

    private String envelope(String type, Object payload) {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("type", type);
        env.put("seq", seq.incrementAndGet());
        env.put("ts", System.currentTimeMillis());
        env.put("payload", payload);
        try {
            return mapper.writeValueAsString(env);
        } catch (Exception e) {
            return "{\"type\":\"error\",\"payload\":{\"message\":\"serialization error\"}}";
        }
    }

    private void broadcast(String json) {
        for (WsContext ctx : sessions) {
            try {
                if (ctx.session.isOpen()) {
                    ctx.send(json);
                } else {
                    sessions.remove(ctx);
                }
            } catch (Exception e) {
                sessions.remove(ctx);
            }
        }
    }

    public void shutdown() {
        flusher.shutdownNow();
    }
}
