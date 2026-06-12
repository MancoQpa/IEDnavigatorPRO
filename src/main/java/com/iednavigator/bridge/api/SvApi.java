package com.iednavigator.bridge.api;

import com.iednavigator.bridge.EventBus;
import com.iednavigator.native_lib.NativeSVSubscriber;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Endpoints /api/v1/sv/* : suscripción a Sampled Values vía libiec61850 (JNA).
 * Los mensajes se baten a 10 Hz hacia el WS (evento sv.batch); con backpressure
 * drop-oldest para no bloquear nunca el hilo de captura nativo.
 */
public final class SvApi {

    private static final long FLUSH_MS = 100;
    private static final int MAX_BATCH = 100;

    private final EventBus eventBus;

    private NativeSVSubscriber subscriber;
    private volatile String interfaceId;
    private volatile int appId;

    private final List<Map<String, Object>> pending = new ArrayList<>();
    private final Object pendingLock = new Object();

    private final ScheduledExecutorService flusher =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sv-flusher");
                t.setDaemon(true);
                return t;
            });
    private ScheduledFuture<?> flushTask;

    public SvApi(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    // ── DTOs ──────────────────────────────────────────────────────────────

    public static class SubscribeRequest {
        public String interfaceId;
        public int appId = 0x4000;
    }

    // ── Handlers ──────────────────────────────────────────────────────────

    /** POST /sv/subscribe {interfaceId, appId}. */
    public synchronized void subscribe(Context ctx) {
        SubscribeRequest req = ctx.bodyAsClass(SubscribeRequest.class);
        if (req.interfaceId == null || req.interfaceId.isEmpty()) {
            throw new BadRequestResponse("interfaceId requerido");
        }
        if (!NativeSVSubscriber.isNativeLibraryAvailable()) {
            throw new IllegalStateException(
                    "iec61850.dll no disponible: funciones SV deshabilitadas");
        }
        if (subscriber != null && subscriber.isRunning()) {
            throw new IllegalStateException("Ya hay una suscripción SV activa en " + interfaceId);
        }

        NativeSVSubscriber sub = new NativeSVSubscriber();
        sub.setLogListener(msg -> eventBus.emit("sv.log", Map.of("message", msg)));
        sub.setMessageListener(this::enqueue);
        if (!sub.start(req.interfaceId, req.appId)) {
            throw new BadRequestResponse("No se pudo iniciar la captura SV en " + req.interfaceId);
        }
        subscriber = sub;
        interfaceId = req.interfaceId;
        appId = req.appId;
        flushTask = flusher.scheduleAtFixedRate(this::flush, FLUSH_MS, FLUSH_MS, TimeUnit.MILLISECONDS);
        ctx.json(statusJson());
    }

    /** POST /sv/unsubscribe. */
    public synchronized void unsubscribe(Context ctx) {
        stop();
        ctx.json(statusJson());
    }

    /** GET /sv/status. */
    public void status(Context ctx) {
        ctx.json(statusJson());
    }

    public synchronized void shutdown() {
        stop();
        flusher.shutdownNow();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void stop() {
        if (flushTask != null) {
            flushTask.cancel(false);
            flushTask = null;
        }
        if (subscriber != null) {
            subscriber.stop();
            subscriber = null;
        }
        interfaceId = null;
        synchronized (pendingLock) {
            pending.clear();
        }
    }

    private void enqueue(NativeSVSubscriber.SVMessage msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("svId", msg.svId);
        m.put("smpCnt", msg.smpCnt);
        m.put("confRev", msg.confRev);
        m.put("appId", msg.appId);
        if (msg.hasSmpRate) m.put("smpRate", msg.smpRate);
        m.put("ts", System.currentTimeMillis());
        List<Map<String, Object>> samples = new ArrayList<>();
        for (NativeSVSubscriber.DataSample s : msg.samples) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("index", s.index);
            e.put("name", s.name);
            e.put("value", s.value);
            e.put("quality", s.quality);
            samples.add(e);
        }
        m.put("samples", samples);

        synchronized (pendingLock) {
            if (pending.size() >= MAX_BATCH) {
                pending.remove(0); // drop-oldest
            }
            pending.add(m);
        }
    }

    private void flush() {
        List<Map<String, Object>> batch;
        synchronized (pendingLock) {
            if (pending.isEmpty()) return;
            batch = new ArrayList<>(pending);
            pending.clear();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messages", batch);
        eventBus.emit("sv.batch", payload);
    }

    private Map<String, Object> statusJson() {
        NativeSVSubscriber sub = subscriber;
        boolean running = sub != null && sub.isRunning();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", running);
        m.put("nativeAvailable", NativeSVSubscriber.isNativeLibraryAvailable());
        if (running) {
            m.put("interfaceId", interfaceId);
            m.put("appId", appId);
            m.put("asduCount", sub.getAsduCount());
            m.put("sampleCount", sub.getSampleCount());
        }
        return m;
    }
}
