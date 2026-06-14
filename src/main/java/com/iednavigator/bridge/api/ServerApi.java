package com.iednavigator.bridge.api;

import com.iednavigator.IEC61850Server;
import com.iednavigator.bridge.EventBus;
import com.iednavigator.bridge.ModelSerializer;
import com.iednavigator.bridge.SessionManager;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Endpoints /api/v1/server/* : simulación de IED desde ficheros SCL. */
public final class ServerApi {

    private final SessionManager sessions;
    private final EventBus eventBus;
    private final ModelSerializer modelSerializer = new ModelSerializer();

    /** Estado de la sesión servidor (ruta SCL e IED cargados). */
    private volatile String loadedPath;
    private volatile String loadedIedName;
    private volatile int loadedIedIndex = -1;

    /** Hook invocado tras cada cambio del modelo (sync modelo → GOOSE). */
    private volatile Runnable modelChangedHook;

    public void setModelChangedHook(Runnable hook) {
        this.modelChangedHook = hook;
    }

    private void fireModelChanged() {
        Runnable hook = modelChangedHook;
        if (hook != null) hook.run();
    }

    public ServerApi(SessionManager sessions, EventBus eventBus) {
        this.sessions = sessions;
        this.eventBus = eventBus;
    }

    // ── DTOs ──────────────────────────────────────────────────────────────

    public static class ParseRequest {
        public String path;
    }

    public static class LoadRequest {
        public String path;
        public int iedIndex = 0;
    }

    public static class StartRequest {
        public int port = 102;
    }

    public static class ValueRequest {
        public String ref;
        public String value;
    }

    // ── Handlers ──────────────────────────────────────────────────────────

    /** POST /server/scl/parse {path} — lista los IEDs disponibles en el SCL. */
    public void parseScl(Context ctx) {
        ParseRequest req = ctx.bodyAsClass(ParseRequest.class);
        File f = requireFile(req.path);
        IEC61850Server server = serverWithListener();
        List<String> ieds = server.getAvailableIEDs(f.getAbsolutePath());
        if (ieds.isEmpty()) {
            throw new BadRequestResponse("El fichero no contiene IEDs válidos: " + req.path);
        }
        ctx.json(Map.of("path", f.getAbsolutePath(), "ieds", ieds));
    }

    /** POST /server/load {path, iedIndex} — carga el modelo del IED elegido. */
    public void load(Context ctx) {
        LoadRequest req = ctx.bodyAsClass(LoadRequest.class);
        File f = requireFile(req.path);
        IEC61850Server server = serverWithListener();
        if (server.isRunning()) {
            throw new IllegalStateException("Detenga el servidor antes de cargar otro SCL");
        }
        List<String> ieds = server.getAvailableIEDs(f.getAbsolutePath());
        if (req.iedIndex < 0 || req.iedIndex >= ieds.size()) {
            throw new BadRequestResponse("iedIndex fuera de rango (0.." + (ieds.size() - 1) + ")");
        }
        if (!server.loadSclFileWithIED(f.getAbsolutePath(), req.iedIndex)) {
            throw new BadRequestResponse("No se pudo cargar el SCL: " + req.path);
        }
        loadedPath = f.getAbsolutePath();
        loadedIedName = ieds.get(req.iedIndex);
        loadedIedIndex = req.iedIndex;
        ctx.json(statusJson(server));
    }

    /** POST /server/start {port}. */
    public void start(Context ctx) {
        StartRequest req = ctx.bodyAsClass(StartRequest.class);
        IEC61850Server server = sessions.getServer();
        if (server == null || server.getServerModel() == null) {
            throw new IllegalStateException("Cargue un SCL antes de arrancar el servidor");
        }
        if (server.isRunning()) {
            throw new IllegalStateException("El servidor ya está en marcha en el puerto " + server.getPort());
        }
        if (!server.start(req.port)) {
            throw new BadRequestResponse("No se pudo arrancar en el puerto " + req.port
                    + " (¿requiere permisos de administrador o está ocupado?)");
        }
        ctx.json(statusJson(server));
    }

    /** POST /server/stop. */
    public void stop(Context ctx) {
        IEC61850Server server = sessions.getServer();
        if (server != null && server.isRunning()) {
            server.stop();
        }
        ctx.json(statusJson(server));
    }

    /** GET /server/status. */
    public void status(Context ctx) {
        ctx.json(statusJson(sessions.getServer()));
    }

    /** GET /server/model — árbol del modelo cargado. */
    public void model(Context ctx) {
        IEC61850Server server = sessions.getServer();
        if (server == null || server.getServerModel() == null) {
            throw new BadRequestResponse("No hay modelo cargado");
        }
        ctx.json(modelSerializer.serialize(server.getServerModel(),
                loadedIedName != null ? loadedIedName : "IED"));
    }

    /** POST /server/value {ref, value} — actualiza un valor del modelo simulado. */
    public void setValue(Context ctx) {
        ValueRequest req = ctx.bodyAsClass(ValueRequest.class);
        if (req.ref == null || req.ref.isEmpty()) throw new BadRequestResponse("ref requerido");
        IEC61850Server server = sessions.getServer();
        if (server == null || server.getServerModel() == null) {
            throw new BadRequestResponse("No hay modelo cargado");
        }
        if (!server.setDataValue(req.ref, req.value)) {
            throw new BadRequestResponse("No se pudo escribir " + req.ref
                    + " (¿referencia inválida o tipo incompatible?)");
        }
        eventBus.valueChanged(req.ref, null, req.value, "server");
        fireModelChanged();
        ctx.json(Map.of("ref", req.ref, "value", req.value, "written", true));
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Obtiene el servidor de la sesión, cableando el listener → EventBus una sola vez. */
    private IEC61850Server serverWithListener() {
        boolean isNew = sessions.getServer() == null;
        IEC61850Server server = sessions.getOrCreateServer();
        if (isNew) {
            server.setServerListener(new IEC61850Server.ServerListener() {
                @Override
                public void onServerStarted(int port) {
                    eventBus.emit("server.started", Map.of("port", port));
                }

                @Override
                public void onServerStopped() {
                    eventBus.emit("server.stopped", Map.of());
                }

                @Override
                public void onClientWrite(String nodeRef, String value) {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("ref", nodeRef);
                    p.put("value", value);
                    eventBus.emit("server.clientWrite", p);
                    eventBus.valueChanged(nodeRef, null, value, "server");
                    fireModelChanged();
                }

                @Override
                public void onError(String message) {
                    eventBus.emit("server.error", Map.of("message", message == null ? "" : message));
                }

                @Override
                public void onLog(String message) {
                    eventBus.emit("server.log", Map.of("message", message == null ? "" : message));
                }
            });
        }
        return server;
    }

    private Map<String, Object> statusJson(IEC61850Server server) {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean running = server != null && server.isRunning();
        m.put("running", running);
        m.put("modelLoaded", server != null && server.getServerModel() != null);
        if (running) m.put("port", server.getPort());
        if (loadedPath != null) m.put("sclPath", loadedPath);
        if (loadedIedName != null) m.put("iedName", loadedIedName);
        if (loadedIedIndex >= 0) m.put("iedIndex", loadedIedIndex);
        return m;
    }

    private static File requireFile(String path) {
        if (path == null || path.isEmpty()) throw new BadRequestResponse("path requerido");
        File f = new File(path);
        if (!f.isFile()) throw new BadRequestResponse("Fichero no encontrado: " + path);
        return f;
    }
}
