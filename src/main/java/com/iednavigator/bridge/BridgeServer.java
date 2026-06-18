package com.iednavigator.bridge;

import com.iednavigator.bridge.api.ClientApi;
import com.iednavigator.bridge.api.GooseApi;
import com.iednavigator.bridge.api.ServerApi;
import com.iednavigator.bridge.api.SclToolsApi;
import com.iednavigator.bridge.api.ServicesApi;
import com.iednavigator.bridge.api.SvApi;
import com.iednavigator.bridge.api.SystemApi;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.UnauthorizedResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bootstrap del servidor HTTP/WS del bridge (Javalin).
 * Bind exclusivo en 127.0.0.1; autenticación por token
 * (header X-Bridge-Token en REST, query param token en WS).
 */
public final class BridgeServer {

    private final String token;
    private final int watchdogSeconds;

    private final EventBus eventBus = new EventBus();
    private final SessionManager sessions = new SessionManager(eventBus);
    private final AtomicLong lastActivity = new AtomicLong(System.currentTimeMillis());

    private Javalin app;
    private ScheduledExecutorService watchdog;
    private GooseApi gooseApi;
    private SvApi svApi;

    public BridgeServer(String token, int watchdogSeconds) {
        this.token = token;
        this.watchdogSeconds = watchdogSeconds;
    }

    /** Arranca el servidor y devuelve el puerto real. */
    public int start(int requestedPort) {
        SystemApi systemApi = new SystemApi(this::scheduleShutdown);
        ClientApi clientApi = new ClientApi(sessions);
        ServicesApi servicesApi = new ServicesApi(sessions, eventBus);
        ServerApi serverApi = new ServerApi(sessions, eventBus);
        gooseApi = new GooseApi(sessions, eventBus);
        svApi = new SvApi(eventBus);
        SclToolsApi sclToolsApi = new SclToolsApi();
        serverApi.setModelChangedHook(gooseApi::propagateFromModel);

        app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            config.plugins.enableCors(cors -> cors.add(it -> it.anyHost()));
        });

        // Autenticación + watchdog
        app.before("/api/*", this::authenticate);

        // ── Sistema ──
        app.get("/api/v1/system/info", systemApi::info);
        app.get("/api/v1/system/portcheck", systemApi::portCheck);
        app.post("/api/v1/system/portrelease", systemApi::portRelease);
        app.post("/api/v1/system/shutdown", systemApi::shutdown);
        app.post("/api/v1/system/ping", ctx -> ctx.json(Map.of("pong", System.currentTimeMillis())));

        // ── Cliente ──
        app.post("/api/v1/client/connect", clientApi::connect);
        app.post("/api/v1/client/disconnect", ctx -> {
            servicesApi.resetState();
            clientApi.disconnect(ctx);
        });
        app.get("/api/v1/client/status", clientApi::status);
        app.get("/api/v1/client/model", clientApi::model);
        app.post("/api/v1/client/read", clientApi::read);
        app.post("/api/v1/client/write", clientApi::write);
        app.put("/api/v1/client/watchlist", clientApi::setWatchlist);
        app.get("/api/v1/client/watchlist", clientApi::getWatchlist);
        app.get("/api/v1/client/nameplate", clientApi::nameplate);
        app.get("/api/v1/client/control-info", clientApi::controlInfo);
        app.post("/api/v1/client/operate", clientApi::operate);
        app.post("/api/v1/client/cancel", clientApi::cancel);
        app.post("/api/v1/client/blocking", clientApi::blocking);

        // ── Fase 4: Reports / SG / DataSets / Ficheros / Export ──
        app.get("/api/v1/client/rcbs", servicesApi::listRcbs);
        app.post("/api/v1/client/rcbs/enable", servicesApi::enableRcb);
        app.post("/api/v1/client/rcbs/disable", servicesApi::disableRcb);
        app.get("/api/v1/client/datasets", servicesApi::listDataSets);
        app.post("/api/v1/client/dataset/read", servicesApi::readDataSet);
        app.get("/api/v1/client/sg", servicesApi::listSettingGroups);
        app.post("/api/v1/client/sg/select", servicesApi::selectSettingGroup);
        app.get("/api/v1/client/files", servicesApi::listFiles);
        app.get("/api/v1/client/files/scl", servicesApi::findSclFiles);
        app.get("/api/v1/client/files/download", servicesApi::downloadFile);
        app.get("/api/v1/client/export/model-html", servicesApi::exportModelHtml);
        app.get("/api/v1/export/model-html", servicesApi::exportModelHtml);  // alias for server mode

        // ── Servidor simulado (Fase 5) ──
        app.post("/api/v1/server/scl/parse", serverApi::parseScl);
        app.post("/api/v1/server/load", serverApi::load);
        app.post("/api/v1/server/start", serverApi::start);
        app.post("/api/v1/server/stop", serverApi::stop);
        app.get("/api/v1/server/status", serverApi::status);
        app.get("/api/v1/server/model", serverApi::model);
        app.post("/api/v1/server/value", serverApi::setValue);

        // ── GOOSE / SV (Fase 5) ──
        app.get("/api/v1/net/interfaces", gooseApi::listInterfaces);
        app.post("/api/v1/goose/scl/load", gooseApi::loadScl);
        app.get("/api/v1/goose/gocbs", gooseApi::listGoCBs);
        app.post("/api/v1/goose/publish", gooseApi::publish);
        app.post("/api/v1/goose/stop", gooseApi::stopPublishing);
        app.post("/api/v1/goose/value", gooseApi::setValue);
        app.post("/api/v1/goose/subscribe", gooseApi::subscribe);
        app.post("/api/v1/goose/unsubscribe", gooseApi::unsubscribe);
        app.get("/api/v1/goose/status", gooseApi::status);
        app.post("/api/v1/goose/udp/start", gooseApi::udpStart);
        app.post("/api/v1/goose/udp/stop", gooseApi::udpStop);
        app.post("/api/v1/sv/subscribe", svApi::subscribe);
        app.post("/api/v1/sv/unsubscribe", svApi::unsubscribe);
        app.get("/api/v1/sv/status", svApi::status);

        // ── Utilidades SCL (Fase 5) ──
        app.post("/api/v1/scl/compare", sclToolsApi::compare);
        app.post("/api/v1/scl/goose-map", sclToolsApi::gooseMap);

        // ── Diccionario IEC 61850 ──
        app.get("/api/v1/dictionary/{token}", ctx -> {
            Map<String, String> entry =
                    com.iednavigator.Iec61850Dictionary.describe(ctx.pathParam("token"));
            if (entry == null) {
                ctx.status(404).json(Map.of("error", "sin entrada en el diccionario"));
            } else {
                ctx.json(entry);
            }
        });

        // ── WebSocket push ──
        app.ws("/ws", ws -> {
            ws.onConnect(ctx -> {
                String t = ctx.queryParam("token");
                if (!token.equals(t)) {
                    ctx.session.close(4401, "token inválido");
                    return;
                }
                ctx.session.setIdleTimeout(java.time.Duration.ofHours(24));
                eventBus.register(ctx);
                touch();
            });
            ws.onClose(ctx -> eventBus.unregister(ctx));
            ws.onError(ctx -> eventBus.unregister(ctx));
            ws.onMessage(ctx -> touch()); // pings de la UI
        });

        // ── Manejo de errores uniforme ──
        app.exception(IllegalStateException.class, (e, ctx) -> error(ctx, 409, e.getMessage()));
        app.exception(IOException.class, (e, ctx) -> error(ctx, 502, e.getMessage()));
        app.exception(Exception.class, (e, ctx) -> error(ctx, 500, e.toString()));

        app.start("127.0.0.1", requestedPort);

        if (watchdogSeconds > 0) {
            startWatchdog();
        }

        Runtime.getRuntime().addShutdownHook(new Thread(sessions::shutdown));
        return app.port();
    }

    private void authenticate(Context ctx) {
        // El preflight CORS (OPTIONS) nunca lleva headers custom; lo gestiona el plugin CORS.
        if (ctx.method() == io.javalin.http.HandlerType.OPTIONS) {
            return;
        }
        String header = ctx.header("X-Bridge-Token");
        if (!token.equals(header)) {
            throw new UnauthorizedResponse("X-Bridge-Token inválido o ausente");
        }
        touch();
    }

    private void touch() {
        lastActivity.set(System.currentTimeMillis());
    }

    private void startWatchdog() {
        watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "bridge-watchdog");
            t.setDaemon(true);
            return t;
        });
        watchdog.scheduleAtFixedRate(() -> {
            long idleMs = System.currentTimeMillis() - lastActivity.get();
            boolean hasWsClients = eventBus.sessionCount() > 0;
            if (!hasWsClients && idleMs > watchdogSeconds * 1000L) {
                System.out.println("[Bridge] Watchdog: sin actividad ni clientes WS en "
                        + watchdogSeconds + "s — apagando");
                scheduleShutdown();
            }
        }, watchdogSeconds, 5, TimeUnit.SECONDS);
    }

    /** Apagado limpio con pequeña demora para que la respuesta HTTP salga. */
    private void scheduleShutdown() {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
            }
            try {
                if (gooseApi != null) gooseApi.shutdown();
                if (svApi != null) svApi.shutdown();
                sessions.shutdown();
                eventBus.shutdown();
                if (app != null) app.stop();
            } finally {
                System.exit(0);
            }
        }, "bridge-shutdown");
        t.setDaemon(true);
        t.start();
    }

    private static void error(Context ctx, int status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message == null ? "error interno" : message);
        ctx.status(status).json(body);
    }
}
