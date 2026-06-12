package com.iednavigator.bridge;

import com.beanit.iec61850bean.BasicDataAttribute;
import com.beanit.iec61850bean.Fc;
import com.beanit.iec61850bean.FcModelNode;
import com.beanit.iec61850bean.ModelNode;
import com.beanit.iec61850bean.ServerModel;
import com.iednavigator.IEC61850Client;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Reimplementación headless de PollingManager (sin JTree).
 *
 * Pollea las referencias de la watchlist ("ld/ln.do.da$FC"), lee sus
 * valores via MMS y publica los cambios (diff contra el último valor
 * conocido) al EventBus, que los envía batched por WebSocket.
 */
public final class HeadlessPoller {

    private final IEC61850Client client;
    private final EventBus eventBus;

    private final Set<String> watchlist = new CopyOnWriteArraySet<>();
    private final Map<String, String> lastValues = new ConcurrentHashMap<>();

    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "headless-poller");
                t.setDaemon(true);
                return t;
            });
    private ScheduledFuture<?> task;
    private volatile int intervalMs = 1000;

    public HeadlessPoller(IEC61850Client client, EventBus eventBus) {
        this.client = client;
        this.eventBus = eventBus;
    }

    /**
     * Reemplaza la watchlist completa y (re)inicia el polling.
     * Refs con formato "ld/ln.do.da$FC". Lista vacía detiene el polling.
     */
    public synchronized void setWatchlist(List<String> refs, int newIntervalMs) {
        watchlist.clear();
        if (refs != null) watchlist.addAll(refs);
        if (newIntervalMs > 0) this.intervalMs = newIntervalMs;

        stopTask();
        if (!watchlist.isEmpty()) {
            task = executor.scheduleAtFixedRate(this::pollOnce, 0, intervalMs, TimeUnit.MILLISECONDS);
        }
    }

    public List<String> getWatchlist() {
        return List.copyOf(watchlist);
    }

    public int getIntervalMs() {
        return intervalMs;
    }

    public synchronized void stop() {
        stopTask();
        watchlist.clear();
        lastValues.clear();
    }

    private void stopTask() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
    }

    private void pollOnce() {
        if (!client.isConnected()) return;
        ServerModel model = client.getServerModel();
        if (model == null) return;

        for (String fullRef : watchlist) {
            try {
                int idx = fullRef.lastIndexOf('$');
                if (idx < 0) continue;
                String ref = fullRef.substring(0, idx);
                Fc fc = Fc.valueOf(fullRef.substring(idx + 1));

                ModelNode node = model.findModelNode(ref, fc);
                if (!(node instanceof FcModelNode)) continue;

                client.readNodeValues((FcModelNode) node);
                diffAndPublish(node, fc);
            } catch (Exception e) {
                // continuar con las demás referencias
            }
        }
    }

    private void diffAndPublish(ModelNode node, Fc fc) {
        List<BasicDataAttribute> bdas = node.getBasicDataAttributes();
        if (bdas == null) return;
        for (BasicDataAttribute bda : bdas) {
            String ref = bda.getReference().toString();
            String value = client.formatValue(bda);
            if (value == null) value = "";
            String prev = lastValues.put(ref, value);
            if (!value.equals(prev)) {
                eventBus.valueChanged(ref, fc != null ? fc.toString() : null,
                        value, client.getValueType(bda));
            }
        }
    }
}
