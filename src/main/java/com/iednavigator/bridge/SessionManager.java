package com.iednavigator.bridge;

import com.iednavigator.IEC61850Client;
import com.iednavigator.IEC61850Server;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ciclo de vida de las sesiones cliente/servidor IEC 61850 del bridge.
 * Una sesión cliente y una sesión servidor a la vez (igual que el Swing).
 */
public final class SessionManager {

    private final EventBus eventBus;

    private volatile IEC61850Client client;
    private volatile HeadlessPoller poller;
    private volatile IEC61850Server server;

    public SessionManager(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    // ── Cliente ───────────────────────────────────────────────────────────

    public synchronized void connect(String host, int port, Integer timeoutMs) throws IOException {
        if (client != null && client.isConnected()) {
            throw new IOException("Ya hay una conexión activa. Desconecte primero.");
        }
        IEC61850Client newClient = new IEC61850Client();
        if (timeoutMs != null && timeoutMs > 0) {
            newClient.setConnectionTimeoutMs(timeoutMs);
        }
        newClient.setValueChangeListener(new IEC61850Client.ValueChangeListener() {
            @Override
            public void onValueChanged(String reference, String value, String type) {
                eventBus.valueChanged(reference, null, value, type);
            }

            @Override
            public void onError(String reference, String error) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("ref", reference);
                p.put("error", error);
                eventBus.emit("client.error", p);
            }

            @Override
            public void onConnectionClosed(String reason) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("reason", reason);
                eventBus.emit("client.connectionClosed", p);
                stopPoller();
            }
        });

        boolean ok = newClient.connect(host, port);
        if (!ok) {
            throw new IOException("No se pudo conectar a " + host + ":" + port);
        }
        this.client = newClient;
        this.poller = new HeadlessPoller(newClient, eventBus);
    }

    public synchronized void disconnect() {
        stopPoller();
        if (client != null) {
            client.disconnect();
            client = null;
        }
    }

    private void stopPoller() {
        if (poller != null) {
            poller.stop();
            poller = null;
        }
    }

    /** Cliente activo; lanza IllegalStateException si no hay conexión. */
    public IEC61850Client requireClient() {
        IEC61850Client c = client;
        if (c == null || !c.isConnected()) {
            throw new IllegalStateException("No hay conexión cliente activa");
        }
        return c;
    }

    public IEC61850Client getClient() {
        return client;
    }

    public HeadlessPoller getPoller() {
        return poller;
    }

    public boolean isClientConnected() {
        IEC61850Client c = client;
        return c != null && c.isConnected();
    }

    // ── Servidor ──────────────────────────────────────────────────────────

    public synchronized IEC61850Server getOrCreateServer() {
        if (server == null) {
            server = new IEC61850Server();
        }
        return server;
    }

    public IEC61850Server getServer() {
        return server;
    }

    public synchronized void shutdown() {
        disconnect();
        if (server != null && server.isRunning()) {
            server.stop();
            server = null;
        }
    }
}
