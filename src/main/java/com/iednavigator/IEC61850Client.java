package com.iednavigator;

import com.beanit.iec61850bean.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.*;
import java.util.Collection;
import java.util.concurrent.*;
import javax.net.SocketFactory;

/**
 * Cliente IEC 61850 usando iec61850bean
 * Basado en el codigo de la APK Android que funciona correctamente
 */
public class IEC61850Client implements ClientEventListener {

    private ClientSap clientSap;
    private ClientAssociation association;
    private ServerModel serverModel;
    private String host;
    private int port;
    private boolean connected = false;

    // true mientras connect() está en curso. Contra IEDs que rechazan retrieveModel()
    // (Siemens/NARI) las asociaciones intermedias se rompen y beanit dispara
    // associationClosed() por cada una; sin este flag la GUI mostraba "Desconectado"
    // en medio de una conexión que iba a terminar bien.
    private volatile boolean connectInProgress = false;

    // Cache de valores leidos
    private final Map<String, CachedValue> valueCache = new ConcurrentHashMap<>();

    // Listener para cambios
    private ValueChangeListener valueChangeListener;

    // Contador de ctlNum — se incrementa en cada operación de control (IEC 61850-7-3 §20.2)
    private int ctlNumCounter = 0;

    // Selección SBO pendiente (flujo manual de dos pasos: SELECT → EXECUTE desde el diálogo).
    // Guarda el ctlNum reservado y el valor comandado para que el OPERATE coincida con el SBOw.
    private PendingSelect pendingSelect;

    // origin.orCat con el que se emiten las órdenes de control (IEC 61850-7-3, orCategory).
    // Default 3 = remote-control. Debe coincidir con la autoridad de mando configurada en el
    // IED, o éste rechaza la orden (típicamente con AddCause=blocked-by-switching-hierarchy
    // o no-access-authority).
    private int controlOrCat = 3;

    /** orCat actual con el que se emiten las órdenes de control (IEC 61850-7-3 orCategory). */
    public int getControlOrCat() { return controlOrCat; }

    /** Fija el orCat de las órdenes de control. Valores válidos: 0-8; fuera de rango se ignora. */
    public void setControlOrCat(int orCat) {
        if (orCat >= 0 && orCat <= 8) this.controlOrCat = orCat;
    }

    /** Mapa orCategory → nombre estándar, para poblar el selector de la GUI. */
    public static Map<Integer, String> getOrCategoryMap() {
        return java.util.Collections.unmodifiableMap(OR_CATEGORY_MAP);
    }

    public interface ValueChangeListener {
        void onValueChanged(String reference, String value, String type);
        void onError(String reference, String error);
        void onConnectionClosed(String reason);
        // Diagnóstico de reconexión/fallback (antes solo iba a System.out/err — invisible
        // en la GUI). Ver retryRetrieveModelWithoutDataSets()/retrieveModelManually().
        void onLog(String message);
    }

    public static class CachedValue {
        public String value;
        public String type;
        public long timestamp;
        public FcModelNode node;

        public CachedValue(String value, String type, FcModelNode node) {
            this.value = value;
            this.type = type;
            this.timestamp = System.currentTimeMillis();
            this.node = node;
        }
    }

    public void setValueChangeListener(ValueChangeListener listener) {
        this.valueChangeListener = listener;
    }

    // Timeout de conexión en milisegundos (configurable desde la GUI)
    private int connectionTimeoutMs = 10000;

    // Asociación conservada cuando retrieveModel() falla con DataSet error (fallback SCL)
    private ClientAssociation pendingAssociation = null;

    public void setConnectionTimeoutMs(int ms) {
        this.connectionTimeoutMs = ms;
    }

    // Executor para operaciones con timeout
    private ExecutorService connectionExecutor = Executors.newSingleThreadExecutor();

    // Espera antes de reconectar dentro de retryRetrieveModelWithoutDataSets(). Algunos IEDs
    // (confirmado con un Ingeteam Ingepac EF-ZTO real) solo aceptan una asociación MMS a la
    // vez y quedan rechazando conexiones nuevas por un rato tras un reset abrupto — reintentar
    // de inmediato solo repite el fallo.
    private static final long RECONNECT_BACKOFF_MS = 4000;

    // Ruta por la que se obtuvo el modelo vigente: "directo" si retrieveModel() funcionó al
    // primer intento, o el fallback que lo salvó. Se fija en el punto donde se consigue el
    // modelo, nunca en los llamadores, para que la ruta más específica no quede pisada.
    private volatile String modelPath = "?";

    /**
     * Registra la forma del modelo recién recuperado: cuántos Logical Devices, cuántos Logical
     * Nodes, cuántos nodos en total, por qué ruta y cuánto tardó.
     *
     * Hasta acá el conteo sólo quedaba escrito cuando corría la ruta [MANUAL]. En una conexión
     * directa exitosa no se registraba nada, así que no se podía armar una tabla comparable
     * entre equipos ni contrastar qué dispara el fallback —si la cantidad de nodos lógicos o el
     * volumen de datos por nodo—: los dos únicos casos medidos daban una respuesta ambigua y
     * ninguno de los dos había dejado el dato completo.
     *
     * Una línea por conexión, en formato fijo y armada por concatenación: sin MessageFormat de
     * por medio no hay separador de miles que vuelva ilegible un conteo de cinco cifras.
     */
    private void logModelShape(long elapsedMs) {
        if (serverModel == null) return;
        int lds = 0;
        int lns = 0;
        if (serverModel.getChildren() != null) {
            for (ModelNode ld : serverModel.getChildren()) {
                lds++;
                if (ld.getChildren() != null) lns += ld.getChildren().size();
            }
        }
        logDiag("[MODELO] " + lds + " LD, " + lns + " LN, " + countNodes(serverModel)
            + " nodos | via " + modelPath + " | " + elapsedMs + " ms");
    }

    /**
     * Emite un mensaje de diagnóstico a consola y, si hay un listener registrado, a la GUI.
     * Antes estos mensajes ([RETRY], [MANUAL], [WARN] ServiceError, etc.) solo iban a
     * System.out/err y no eran visibles para quien no tuviera una consola adjunta a la app.
     */
    private void logDiag(String msg) {
        System.out.println(msg);
        if (valueChangeListener != null) {
            try {
                valueChangeListener.onLog(msg);
            } catch (Exception ignored) {
                // No dejar que un listener roto tumbe el flujo de conexión
            }
        }
    }

    /**
     * Conecta al servidor IED con timeout
     */
    public boolean connect(String host, int port) throws IOException {
        if (connected) {
            System.out.println("[WARN] Already connected");
            return true;
        }

        this.host = host;
        this.port = port;

        // NOTA: Removida verificación isHostReachable que causaba problemas
        // con iec61850bean al hacer conexión TCP previa al puerto MMS

        connectInProgress = true;
        try {
            System.out.println("[INFO] Creating ClientSap...");
            clientSap = new ClientSap();

            // Configurar timeouts (importante para evitar bloqueos)
            clientSap.setResponseTimeout(connectionTimeoutMs);
            clientSap.setMessageFragmentTimeout(5000);  // 5 segundos

            System.out.println("[INFO] Resolving host: " + host);
            final InetAddress address = InetAddress.getByName(host);

            System.out.println("[INFO] Connecting to " + host + ":" + port + " (timeout: " + connectionTimeoutMs + "ms)...");

            // Usar Future con timeout para la conexión
            Future<ClientAssociation> future = connectionExecutor.submit(() -> {
                return clientSap.associate(address, port, null, IEC61850Client.this);
            });

            try {
                association = future.get(connectionTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new IOException("Connection timeout after " + connectionTimeoutMs + "ms");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IOException) {
                    throw (IOException) cause;
                }
                throw new IOException("Connection error: " + cause.getMessage(), cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Connection interrupted");
            }

            System.out.println("[INFO] Connected to MMS server! Retrieving model...");
            System.out.println("[INFO] This may take a moment - the model contains DataSets and Reports");

            // Retrieve the server model (structure only - NO values)
            // LAZY LOADING: igual que la APK
            // NOTA: retrieveModel() en iec61850bean solicita TODOS los DataSets definidos.
            // Si el servidor tiene Reports que referencian DataSets inexistentes, fallará.
            long modelStart = System.currentTimeMillis();
            try {
                serverModel = association.retrieveModel();
                modelPath = "directo";
                System.out.println("[INFO] Model retrieved successfully - " + countNodes(serverModel) + " nodes");
            } catch (ServiceError serviceEx) {
                logDiag("[WARN] ServiceError during model retrieval: code=" + serviceEx.getErrorCode()
                    + " message=" + serviceEx.getMessage());
                String msg = serviceEx.getMessage() != null ? serviceEx.getMessage() : "";
                // Si el error es por DataSet inexistente, intentar extraer el modelo parcial ya
                // construido por retrieveModel() antes de que updateDataSets() fallara.
                // ClientAssociation.serverModel ya tiene la estructura completa del IED.
                ServerModel partialModel = extractPartialModelFromAssociation(association);
                if (partialModel != null && partialModel.getChildren() != null
                        && !partialModel.getChildren().isEmpty()) {
                    serverModel = partialModel;
                    connected = true;
                    modelPath = "parcial-reflexión";
                    logDiag("[INFO] Modelo parcial recuperado via reflexión ("
                        + countNodes(serverModel) + " nodos). DataSets omitidos.");
                    // No lanzar excepción — continuar con modelo parcial
                } else {
                    // La conexión puede haberse roto (Connection reset) durante updateDataSets().
                    // Intentar reconectar y obtener modelo SIN DataSets.
                    logDiag("[INFO] Intentando reconexión para obtener modelo sin DataSets...");
                    ServerModel retryModel = retryRetrieveModelWithoutDataSets(address, port);
                    if (retryModel != null) {
                        serverModel = retryModel;
                        connected = true;
                        logDiag("[INFO] Modelo recuperado via reconexión ("
                            + countNodes(serverModel) + " nodos). DataSets omitidos.");
                    } else {
                        // No se pudo extraer modelo parcial → guardar asociación para fallback SCL
                        logDiag("[INFO] No se pudo recuperar modelo - conservando asociación para fallback SCL");
                        pendingAssociation = association;
                        association = null;
                        connected = false;
                        throw new IOException("SCL_FALLBACK: " + serviceEx.getErrorCode() + " - " + msg, serviceEx);
                    }
                }
            } catch (Exception modelEx) {
                if (modelEx instanceof IOException && modelEx.getMessage() != null
                        && modelEx.getMessage().startsWith("SCL_FALLBACK:")) {
                    throw (IOException) modelEx;
                }
                logDiag("[ERROR] Model retrieval failed: " + modelEx.getClass().getName()
                    + " message=" + modelEx.getMessage());
                modelEx.printStackTrace();
                // Intentar reconectar y obtener modelo sin DataSets
                ServerModel retryModel = retryRetrieveModelWithoutDataSets(address, port);
                if (retryModel != null) {
                    serverModel = retryModel;
                    connected = true;
                    logDiag("[INFO] Modelo recuperado via reconexión tras excepción ("
                        + countNodes(serverModel) + " nodos)");
                } else {
                    connected = false;
                    if (association != null) {
                        try { association.close(); } catch (Exception ex) {}
                        association = null;
                    }
                    throw new IOException("Model retrieval failed: " + modelEx.getMessage(), modelEx);
                }
            }

            connected = true;
            // Una sola línea por conexión, después de que cualquiera de las rutas haya dejado
            // el modelo puesto: así la salida es comparable entre equipos y entre rutas.
            logModelShape(System.currentTimeMillis() - modelStart);
            System.out.println("[OK] Connected to " + host + ":" + port);

            return true;

        } catch (IOException e) {
            throw e;  // Re-throw IOException as-is
        } catch (Exception e) {
            System.err.println("[ERROR] Connection failed: " + e.getMessage());
            e.printStackTrace();
            connected = false;
            if (association != null) {
                try { association.close(); } catch (Exception ex) {}
                association = null;
            }
            throw new IOException("Connection error: " + e.getMessage(), e);
        } finally {
            connectInProgress = false;
        }
    }

    /**
     * Inyecta un ServerModel parseado localmente (fallback SCL).
     * La asociación MMS que quedó en pendingAssociation se activa como conexión normal.
     * Permite leer/escribir valores individuales aunque retrieveModel() haya fallado.
     */
    public boolean attachExternalModel(ServerModel model) {
        if (pendingAssociation == null) {
            System.err.println("[ERROR] attachExternalModel: no hay pendingAssociation");
            return false;
        }
        association = pendingAssociation;
        pendingAssociation = null;
        serverModel = model;
        connected = true;
        modelPath = "scl-externo";
        System.out.println("[INFO] SCL fallback: modelo externo inyectado (" + countNodes(model) + " nodos)");
        return true;
    }

    /**
     * Cierra y descarta la asociación pendiente (cuando el usuario cancela el fallback).
     */
    public void cancelPendingAssociation() {
        if (pendingAssociation != null) {
            try { pendingAssociation.close(); } catch (Exception ex) {}
            pendingAssociation = null;
        }
    }

    /**
     * Extrae el ServerModel parcial ya construido dentro de ClientAssociation via reflexión.
     * retrieveModel() en iec61850bean construye serverModel antes de llamar a updateDataSets(),
     * por lo que el campo ya tiene la estructura completa del IED cuando updateDataSets() falla.
     */
    private ServerModel extractPartialModelFromAssociation(ClientAssociation assoc) {
        if (assoc == null) return null;
        try {
            java.lang.reflect.Field smField = assoc.getClass().getDeclaredField("serverModel");
            smField.setAccessible(true);
            Object sm = smField.get(assoc);
            if (sm instanceof ServerModel) {
                return (ServerModel) sm;
            }
        } catch (Exception e) {
            logDiag("[INFO] extractPartialModel via reflexión falló: " + e.getMessage());
        }
        return null;
    }

    /**
     * Reconecta al IED y obtiene el modelo SIN pedir DataSets.
     * Útil para IEDs Siemens/NARI que rechazan updateDataSets() con Connection reset.
     *
     * Estrategia: reconectar, intentar retrieveModel() estándar primero;
     * si falla de nuevo, construir el modelo manualmente via reflexión
     * llamando a retrieveLogicalDevices(), retrieveLogicalNodeNames(),
     * retrieveDataDefinitions() individualmente y omitiendo updateDataSets().
     */
    private ServerModel retryRetrieveModelWithoutDataSets(InetAddress address, int port) {
        ClientAssociation retryAssoc = null;
        try {
            if (association != null) {
                try { association.close(); } catch (Exception ex) {}
                association = null;
            }

            logDiag("[RETRY] Esperando " + RECONNECT_BACKOFF_MS + "ms antes de reconectar "
                + "(algunos IEDs rechazan asociaciones nuevas justo después de un reset)...");
            sleepBackoff(RECONNECT_BACKOFF_MS);

            logDiag("[RETRY] Reconectando para obtener modelo sin DataSets...");
            ClientSap retrySap = new ClientSap();
            retrySap.setResponseTimeout(connectionTimeoutMs);
            retrySap.setMessageFragmentTimeout(5000);
            retryAssoc = retrySap.associate(address, port, null, this);

            // Primero intentar retrieveModel() estándar por si fue un error transitorio
            try {
                ServerModel fullModel = retryAssoc.retrieveModel();
                association = retryAssoc;
                modelPath = "reconexión";
                logDiag("[RETRY] retrieveModel() exitoso en segundo intento");
                return fullModel;
            } catch (Exception e2) {
                logDiag("[RETRY] retrieveModel() falló otra vez: " + e2.getMessage());
                // Intentar extraer modelo parcial ya construido
                ServerModel partial = extractPartialModelFromAssociation(retryAssoc);
                if (partial != null && partial.getChildren() != null && !partial.getChildren().isEmpty()) {
                    association = retryAssoc;
                    modelPath = "reconexión-parcial";
                    logDiag("[RETRY] Modelo parcial extraído: " + countNodes(partial) + " nodos");
                    return partial;
                }
                // La asociación se rompió — reconectar para intento manual
                try { retryAssoc.close(); } catch (Exception ex) {}
                retryAssoc = null;
            }

            // Tercer intento: construir modelo manualmente via reflexión (sin DataSets)
            logDiag("[RETRY-MANUAL] Esperando " + RECONNECT_BACKOFF_MS + "ms antes de reconectar...");
            sleepBackoff(RECONNECT_BACKOFF_MS);
            logDiag("[RETRY-MANUAL] Reconectando para construcción manual del modelo...");
            ClientSap manualSap = new ClientSap();
            manualSap.setResponseTimeout(connectionTimeoutMs);
            manualSap.setMessageFragmentTimeout(5000);
            retryAssoc = manualSap.associate(address, port, null, this);
            ServerModel manualModel = retrieveModelManually(retryAssoc);
            if (manualModel != null) {
                association = retryAssoc;
                modelPath = "manual";
                return manualModel;
            }
            try { retryAssoc.close(); } catch (Exception ex) {}

        } catch (Exception e) {
            logDiag("[RETRY] Reconexión falló: " + e.getMessage());
            if (retryAssoc != null) {
                try { retryAssoc.close(); } catch (Exception ex) {}
            }
        }
        return null;
    }

    private void sleepBackoff(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Construye el ServerModel manualmente usando reflexión para llamar a los métodos
     * privados de ClientAssociation paso a paso, omitiendo updateDataSets().
     *
     * Flujo: retrieveLogicalDevices() → para cada LD: retrieveLogicalNodeNames(ld)
     *        → para cada LN: retrieveDataDefinitions(ref) → construir ServerModel
     *        → setServerModel(model)
     *
     * Errores individuales por LD/LN se saltan sin abortar toda la operación.
     */
    @SuppressWarnings("unchecked")
    private ServerModel retrieveModelManually(ClientAssociation assoc) {
        try {
            Class<?> caClass = assoc.getClass();

            // Obtener métodos privados via reflexión
            java.lang.reflect.Method mRetrieveLDs = caClass.getDeclaredMethod("retrieveLogicalDevices");
            mRetrieveLDs.setAccessible(true);

            java.lang.reflect.Method mRetrieveLNNames = caClass.getDeclaredMethod("retrieveLogicalNodeNames", String.class);
            mRetrieveLNNames.setAccessible(true);

            java.lang.reflect.Method mRetrieveDataDefs = caClass.getDeclaredMethod("retrieveDataDefinitions", ObjectReference.class);
            mRetrieveDataDefs.setAccessible(true);

            // 1) Obtener lista de Logical Devices
            List<String> ldNames = (List<String>) mRetrieveLDs.invoke(assoc);
            logDiag("[MANUAL] Logical Devices encontrados: " + ldNames.size() + " → " + ldNames);

            if (ldNames == null || ldNames.isEmpty()) {
                logDiag("[MANUAL] No se encontraron Logical Devices");
                return null;
            }

            // 2) Para cada LD, obtener sus Logical Nodes y sus definiciones
            List<LogicalDevice> logicalDevices = new ArrayList<>();
            int totalNodes = 0;

            for (String ldName : ldNames) {
                try {
                    List<String> lnNames = (List<String>) mRetrieveLNNames.invoke(assoc, ldName);
                    if (lnNames == null || lnNames.isEmpty()) {
                        logDiag("[MANUAL] LD '" + ldName + "' sin Logical Nodes — omitido");
                        continue;
                    }
                    logDiag("[MANUAL] LD '" + ldName + "': " + lnNames.size() + " LN(s)");

                    List<LogicalNode> logicalNodes = new ArrayList<>();
                    for (String lnName : lnNames) {
                        try {
                            String refStr = ldName + "/" + lnName;
                            ObjectReference ref = new ObjectReference(refStr);
                            LogicalNode ln = (LogicalNode) mRetrieveDataDefs.invoke(assoc, ref);
                            if (ln != null) {
                                logicalNodes.add(ln);
                                totalNodes++;
                            }
                        } catch (Exception lnEx) {
                            Throwable cause = lnEx instanceof java.lang.reflect.InvocationTargetException
                                    ? ((java.lang.reflect.InvocationTargetException) lnEx).getTargetException()
                                    : lnEx;
                            logDiag("[MANUAL] Error en LN '" + ldName + "/" + lnName + "': "
                                    + cause.getMessage() + " — omitido");
                        }
                    }

                    if (!logicalNodes.isEmpty()) {
                        ObjectReference ldRef = new ObjectReference(ldName);
                        LogicalDevice ld = new LogicalDevice(ldRef, logicalNodes);
                        logicalDevices.add(ld);
                    }
                } catch (Exception ldEx) {
                    Throwable cause = ldEx instanceof java.lang.reflect.InvocationTargetException
                            ? ((java.lang.reflect.InvocationTargetException) ldEx).getTargetException()
                            : ldEx;
                    logDiag("[MANUAL] Error en LD '" + ldName + "': " + cause.getMessage() + " — omitido");
                }
            }

            if (logicalDevices.isEmpty()) {
                logDiag("[MANUAL] No se pudo obtener ningún Logical Device completo");
                return null;
            }

            // 3) Construir ServerModel sin DataSets
            ServerModel model = new ServerModel(logicalDevices, null);

            // 4) Inyectar el modelo en la asociación (método público)
            assoc.setServerModel(model);

            logDiag("[MANUAL] Modelo construido manualmente: " + logicalDevices.size()
                    + " LD(s), " + totalNodes + " LN(s), sin DataSets");
            return model;

        } catch (Exception e) {
            logDiag("[MANUAL] Error construyendo modelo manualmente: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Verifica si el host es alcanzable usando un socket con timeout
     */
    private boolean isHostReachable(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            System.err.println("[WARN] Host check failed: " + e.getMessage());
            return false;
        }
    }

    private int countNodes(ServerModel model) {
        if (model == null) return 0;
        int count = 0;
        Collection<ModelNode> children = model.getChildren();
        if (children != null) {
            for (ModelNode ld : children) {
                count += countNodesRecursive(ld);
            }
        }
        return count;
    }

    private int countNodesRecursive(ModelNode node) {
        int count = 1;
        Collection<ModelNode> children = node.getChildren();
        if (children != null) {
            for (ModelNode child : children) {
                count += countNodesRecursive(child);
            }
        }
        return count;
    }

    /**
     * Desconecta del servidor
     */
    public void disconnect() {
        if (association != null) {
            try {
                association.close();
            } catch (Exception e) {
                // Ignorar
            }
            association = null;
        }

        clientSap = null;
        serverModel = null;
        connected = false;
        heartbeatNode = null;
        valueCache.clear();

        // Shutdown executor if needed
        if (connectionExecutor != null && !connectionExecutor.isShutdown()) {
            connectionExecutor.shutdownNow();
            connectionExecutor = Executors.newSingleThreadExecutor();
        }

        System.out.println("[OK] Disconnected");
    }

    public boolean isConnected() {
        return connected && association != null;
    }

    public ServerModel getServerModel() {
        return serverModel;
    }

    /**
     * Lee un valor del servidor
     */
    public String readValue(String reference, Fc fc) throws IOException {
        if (!isConnected() || serverModel == null) {
            throw new IOException("Not connected");
        }

        try {
            ModelNode node = serverModel.findModelNode(reference, fc);
            if (node instanceof FcModelNode) {
                association.getDataValues((FcModelNode) node);

                String value = formatValue(node);
                String type = getValueType(node);

                // Guardar en cache
                valueCache.put(reference, new CachedValue(value, type, (FcModelNode) node));

                // Notificar listener
                if (valueChangeListener != null) {
                    valueChangeListener.onValueChanged(reference, value, type);
                }

                return value;
            } else {
                throw new IOException("Node not found: " + reference);
            }

        } catch (ServiceError e) {
            String error = "ServiceError: " + e.getErrorCode();
            if (valueChangeListener != null) {
                valueChangeListener.onError(reference, error);
            }
            throw new IOException(error, e);
        }
    }

    /**
     * Extrae el nombre del IED desde el modelo de servidor.
     * En MMS, los dominios (LD) se nombran IEDName+LDInst. El prefijo común de todos
     * los LDs es el nombre del IED. Si hay un solo LD se devuelve su nombre completo.
     */
    public String getIedName() {
        if (serverModel == null || serverModel.getChildren() == null
                || serverModel.getChildren().isEmpty()) return "";
        List<String> ldNames = new ArrayList<>();
        for (ModelNode ld : serverModel.getChildren()) {
            ldNames.add(ld.getName());
        }
        if (ldNames.size() == 1) return ldNames.get(0);
        // Calcular prefijo común
        String prefix = ldNames.get(0);
        for (int i = 1; i < ldNames.size(); i++) {
            String s = ldNames.get(i);
            int len = Math.min(prefix.length(), s.length());
            int j = 0;
            while (j < len && prefix.charAt(j) == s.charAt(j)) j++;
            prefix = prefix.substring(0, j);
        }
        return prefix.isEmpty() ? ldNames.get(0) : prefix;
    }

    /**
     * Lee la placa de identificación del IED via FC=DC (IEC 61850-6 §9.5.4.1 — CDC LPL/DPL).
     * Retorna mapa con claves: vendor, swRev, hwRev, configRev, d, phy.vendor, phy.model, phy.serNum.
     * Las claves ausentes simplemente no están en el mapa (nodo no presente en el IED).
     */
    public Map<String, String> readDeviceNameplate() {
        Map<String, String> result = new LinkedHashMap<>();
        if (!isConnected() || serverModel == null) return result;

        // Detectar prefijo del primer LD del modelo
        String ldPrefix = "";
        if (serverModel.getChildren() != null && !serverModel.getChildren().isEmpty()) {
            ldPrefix = serverModel.getChildren().iterator().next().getName() + "/";
        }
        System.out.println("[Nameplate] Buscando con prefijo LD: '" + ldPrefix + "'");

        String[][] refs = {
            {"LLN0.NamPlt.vendor",    "vendor"},
            {"LLN0.NamPlt.swRev",     "swRev"},
            {"LLN0.NamPlt.hwRev",     "hwRev"},
            {"LLN0.NamPlt.configRev", "configRev"},
            {"LLN0.NamPlt.d",         "d"},
            {"LPHD1.PhyNam.vendor",   "phy.vendor"},
            {"LPHD1.PhyNam.model",    "phy.model"},
            {"LPHD1.PhyNam.serNum",   "phy.serNum"},
        };

        for (String[] ref : refs) {
            String fullRef = ldPrefix + ref[0];
            try {
                ModelNode node = serverModel.findModelNode(fullRef, Fc.DC);
                if (node instanceof FcModelNode) {
                    association.getDataValues((FcModelNode) node);
                    String val = formatValue(node);
                    System.out.println("[Nameplate] " + fullRef + " = '" + val + "'");
                    if (val != null && !val.isEmpty() && !val.equals("null")) {
                        result.put(ref[1], val);
                    }
                } else {
                    System.out.println("[Nameplate] " + fullRef + " → nodo no encontrado o no FC=DC (node=" + node + ")");
                }
            } catch (Exception e) {
                System.out.println("[Nameplate] " + fullRef + " → excepción: " + e.getMessage());
            }
        }
        System.out.println("[Nameplate] Resultado: " + result);
        return result;
    }

    /**
     * Lee valores de un nodo completo (DO o LN)
     */
    public void readNodeValues(FcModelNode node) throws IOException {
        if (!isConnected() || association == null) {
            throw new IOException("Not connected");
        }

        try {
            association.getDataValues(node);
        } catch (ServiceError e) {
            throw new IOException("ServiceError: " + e.getErrorCode(), e);
        }
    }

    // ==================== LATIDO DE ENLACE ====================

    /** Atributo barato que se relee para comprobar que la asociación sigue viva. */
    private volatile FcModelNode heartbeatNode;

    /**
     * Comprueba que la asociación siga viva leyendo un atributo mínimo del IED.
     *
     * El estado "conectado" es pasivo: sólo cambia cuando la librería avisa que el socket
     * falló. Si el equipo desaparece sin cerrar ordenadamente —reinicio, cable, pérdida de
     * camino— no llega ni FIN ni RST, y el hilo lector se queda esperando indefinidamente.
     * Mientras tanto, si nadie escribe sobre el socket, nada delata la caída: la interfaz
     * puede quedar en "Conectado" durante minutos. Este latido es esa escritura.
     *
     * Un {@code ServiceError} NO se toma como caída: significa que el equipo contestó,
     * aunque sea para rechazar la lectura. Sólo el fallo de transporte cierra la asociación.
     *
     * @return true si el enlace responde (o si no hay un nodo apto para probarlo, en cuyo
     *         caso no se puede afirmar que esté caído); false si se dio por caído.
     */
    public boolean heartbeat() {
        if (!isConnected() || association == null || serverModel == null) return false;

        FcModelNode node = heartbeatNode;
        if (node == null) {
            node = resolveHeartbeatNode();
            heartbeatNode = node;
        }
        if (node == null) return true;   // sin nodo de prueba no se concluye nada

        try {
            association.getDataValues(node);
            return true;
        } catch (ServiceError e) {
            // Un ServiceError normalmente significa que el equipo contestó, aunque sea para
            // rechazar la lectura: el enlace está vivo. Pero la librería reporta como
            // ServiceError también los casos en que NADIE contestó —el timeout de respuesta
            // es el código 22—, y esos sí son caída de enlace. Distinguirlos es todo el punto.
            if (!esFalloDeEnlace(e)) return true;
            IOException io = new IOException(e.getMessage(), e);
            logDiag("[LATIDO] Sin respuesta del IED: " + e.getMessage());
            associationClosed(io);
            return false;
        } catch (Exception e) {
            IOException io = (e instanceof IOException)
                ? (IOException) e : new IOException(e.getMessage(), e);
            logDiag("[LATIDO] Sin respuesta del IED: " + io.getMessage());
            associationClosed(io);
            return false;
        }
    }

    /**
     * ¿Este ServiceError dice que el equipo no está, o que no quiso responder eso?
     *
     * Los tres códigos de abajo son los únicos que no implican una respuesta del equipo. El
     * resto —ACCESS_VIOLATION, INSTANCE_NOT_AVAILABLE y compañía— llegan por la asociación,
     * así que la prueban viva.
     */
    static boolean esFalloDeEnlace(ServiceError e) {
        int c = e.getErrorCode();
        return c == ServiceError.TIMEOUT
            || c == ServiceError.CONNECTION_LOST
            || c == ServiceError.APPLICATION_UNREACHABLE;
    }

    /**
     * Elige un atributo para el latido: el primero que exista entre unos pocos candidatos
     * del LLN0 de cada Logical Device. Son atributos presentes en cualquier equipo y
     * baratos de leer; se prefiere ST sobre DC porque no todos exponen el nameplate completo.
     */
    private FcModelNode resolveHeartbeatNode() {
        if (serverModel == null || serverModel.getChildren() == null) return null;
        String[][] candidatos = {
            {"LLN0.Beh.stVal",      "ST"},
            {"LLN0.Mod.stVal",      "ST"},
            {"LLN0.Health.stVal",   "ST"},
            {"LLN0.NamPlt.configRev", "DC"},
        };
        for (ModelNode ld : serverModel.getChildren()) {
            String ldName = ld.getName();
            if (ldName == null) continue;
            for (String[] c : candidatos) {
                try {
                    ModelNode n = serverModel.findModelNode(ldName + "/" + c[0], Fc.fromString(c[1]));
                    if (n instanceof FcModelNode) return (FcModelNode) n;
                } catch (Exception ignore) { }
            }
        }
        return null;
    }

    // ── Mapas de decodificación de enums IEC 61850-7-3 / IEC 61850-7-4 ─────────

    private static final Map<Integer, String> SI_UNIT_MAP  = new LinkedHashMap<>();
    private static final Map<Integer, String> CTL_MODEL_MAP = new LinkedHashMap<>();
    private static final Map<Integer, String> HEALTH_MAP    = new LinkedHashMap<>();
    private static final Map<Integer, String> MOD_BEH_MAP   = new LinkedHashMap<>();
    private static final Map<Integer, String> RANGE_MAP     = new LinkedHashMap<>();
    private static final Map<Integer, String> DIR_MAP       = new LinkedHashMap<>();
    private static final Map<Integer, String> OR_CATEGORY_MAP  = new LinkedHashMap<>();
    private static final Map<Integer, String> AUTO_REC_ST_MAP  = new LinkedHashMap<>();
    private static final Map<Integer, String> FLT_LOOP_MAP     = new LinkedHashMap<>();
    /** UnitMultiplier (IEC 61850-7-3 / IEC 61968-9 CIM): prefijos SI. */
    private static final Map<Integer, String> MULTIPLIER_MAP   = new LinkedHashMap<>();
    /** AddCause (IEC 61850-7-3:2010 Table 9): causa de rechazo de control. */
    private static final Map<Integer, String> ADD_CAUSE_MAP    = new LinkedHashMap<>();

    static {
        // ── SIUnit::UnitSymbol (IEC 61968-9 / CIM, referenciado por IEC 61850-7-3) ──────────
        // Unidades SI base
        SI_UNIT_MAP.put(0,  "none"); SI_UNIT_MAP.put(1,  "m");
        SI_UNIT_MAP.put(2,  "kg");   SI_UNIT_MAP.put(3,  "s");
        SI_UNIT_MAP.put(4,  "A");    SI_UNIT_MAP.put(5,  "K");
        SI_UNIT_MAP.put(6,  "mol");  SI_UNIT_MAP.put(7,  "cd");
        SI_UNIT_MAP.put(8,  "K");    // alias (IEC 61850-7-3 Ed.1)
        SI_UNIT_MAP.put(9,  "rad");  SI_UNIT_MAP.put(10, "sr");
        SI_UNIT_MAP.put(11, "deg");  // grado plano (ángulo)
        // Derivadas radioactividad / termales
        SI_UNIT_MAP.put(21, "Gy");   SI_UNIT_MAP.put(22, "Bq");
        SI_UNIT_MAP.put(23, "°C");   SI_UNIT_MAP.put(24, "Sv");
        // Eléctricas fundamentales
        SI_UNIT_MAP.put(25, "F");    SI_UNIT_MAP.put(26, "C");
        SI_UNIT_MAP.put(27, "S");    SI_UNIT_MAP.put(28, "H");
        SI_UNIT_MAP.put(29, "V");    SI_UNIT_MAP.put(30, "Ω");
        SI_UNIT_MAP.put(31, "J");    SI_UNIT_MAP.put(32, "N");
        SI_UNIT_MAP.put(33, "Hz");
        // Fotometría
        SI_UNIT_MAP.put(35, "lm");   SI_UNIT_MAP.put(36, "lx");
        // Magnéticas
        SI_UNIT_MAP.put(37, "Wb");   SI_UNIT_MAP.put(38, "T");
        // Mecánica / fluidos / presión
        SI_UNIT_MAP.put(40, "Pa");   SI_UNIT_MAP.put(41, "m²");
        SI_UNIT_MAP.put(42, "m³");   SI_UNIT_MAP.put(43, "m/s");
        SI_UNIT_MAP.put(44, "m/s²"); SI_UNIT_MAP.put(45, "m³/s");
        SI_UNIT_MAP.put(48, "kg/m³");SI_UNIT_MAP.put(49, "m²/s");
        SI_UNIT_MAP.put(50, "W/(m·K)");SI_UNIT_MAP.put(51, "J/K");
        SI_UNIT_MAP.put(52, "ppm");  SI_UNIT_MAP.put(53, "1/s");
        SI_UNIT_MAP.put(54, "rad/s");SI_UNIT_MAP.put(55, "m/m");
        SI_UNIT_MAP.put(56, "%");    SI_UNIT_MAP.put(57, "Pa·s");
        SI_UNIT_MAP.put(58, "N·m");  SI_UNIT_MAP.put(59, "N/m");
        SI_UNIT_MAP.put(60, "rad/s²");
        // Potencia eléctrica
        SI_UNIT_MAP.put(61, "VA");   SI_UNIT_MAP.put(62, "W");
        SI_UNIT_MAP.put(63, "VAr");  SI_UNIT_MAP.put(64, "φ");
        SI_UNIT_MAP.put(65, "cos(φ)");SI_UNIT_MAP.put(66, "Vs");
        SI_UNIT_MAP.put(67, "V²");   SI_UNIT_MAP.put(68, "A·s");
        SI_UNIT_MAP.put(69, "A/V");  SI_UNIT_MAP.put(70, "V/Hz");
        SI_UNIT_MAP.put(71, "W/Hz");
        // Energía eléctrica
        SI_UNIT_MAP.put(72, "Wh");   SI_UNIT_MAP.put(73, "VAh");
        SI_UNIT_MAP.put(74, "VArh"); SI_UNIT_MAP.put(75, "V²h");
        SI_UNIT_MAP.put(76, "A²h");  SI_UNIT_MAP.put(77, "V²");
        SI_UNIT_MAP.put(78, "A²");   SI_UNIT_MAP.put(79, "A²s");
        // Irradiancia / densidad de energía
        SI_UNIT_MAP.put(82, "W/m²"); SI_UNIT_MAP.put(83, "J/m²");
        SI_UNIT_MAP.put(84, "J/m³"); SI_UNIT_MAP.put(85, "V²/Hz");
        SI_UNIT_MAP.put(86, "A²/Hz");SI_UNIT_MAP.put(87, "1/Hz");
        SI_UNIT_MAP.put(88, "S/m");  SI_UNIT_MAP.put(90, "H/m");
        SI_UNIT_MAP.put(91, "F/m");  SI_UNIT_MAP.put(92, "J/mol");
        SI_UNIT_MAP.put(93, "C/kg"); SI_UNIT_MAP.put(94, "Gy/s");
        // Tiempo / unidades prácticas no-SI
        SI_UNIT_MAP.put(100, "min"); SI_UNIT_MAP.put(101, "h");
        SI_UNIT_MAP.put(102, "d");   SI_UNIT_MAP.put(103, "°");
        SI_UNIT_MAP.put(106, "L");   SI_UNIT_MAP.put(108, "t");
        SI_UNIT_MAP.put(109, "bar"); SI_UNIT_MAP.put(111, "dB");

        // ctlModel (IEC 61850-7-3 Table 5)
        CTL_MODEL_MAP.put(0, "status-only");
        CTL_MODEL_MAP.put(1, "direct-normal-security");
        CTL_MODEL_MAP.put(2, "sbo-normal-security");
        CTL_MODEL_MAP.put(3, "direct-enhanced-security");
        CTL_MODEL_MAP.put(4, "sbo-enhanced-security");

        // Health (IEC 61850-7-4)
        HEALTH_MAP.put(1, "Ok");
        HEALTH_MAP.put(2, "Warning");
        HEALTH_MAP.put(3, "Alarm");

        // Mod / Beh (IEC 61850-7-4)
        MOD_BEH_MAP.put(1, "on");
        MOD_BEH_MAP.put(2, "blocked");
        MOD_BEH_MAP.put(3, "test");
        MOD_BEH_MAP.put(4, "test/blocked");
        MOD_BEH_MAP.put(5, "off");

        // range (IEC 61850-7-3)
        RANGE_MAP.put(0, "normal");
        RANGE_MAP.put(1, "high");
        RANGE_MAP.put(2, "low");
        RANGE_MAP.put(3, "high-high");
        RANGE_MAP.put(4, "low-low");

        // dir — dirección de falta (IEC 61850-7-4)
        DIR_MAP.put(0, "unknown");
        DIR_MAP.put(1, "forward");
        DIR_MAP.put(2, "backward");
        DIR_MAP.put(3, "both");

        // orCategory (IEC 61850-7-3)
        OR_CATEGORY_MAP.put(0, "not-supported");
        OR_CATEGORY_MAP.put(1, "bay-control");
        OR_CATEGORY_MAP.put(2, "station-control");
        OR_CATEGORY_MAP.put(3, "remote-control");
        OR_CATEGORY_MAP.put(4, "automatic-bay");
        OR_CATEGORY_MAP.put(5, "automatic-station");
        OR_CATEGORY_MAP.put(6, "automatic-remote");
        OR_CATEGORY_MAP.put(7, "maintenance");
        OR_CATEGORY_MAP.put(8, "process");

        // AutoRecSt (IEC 61850-7-4)
        AUTO_REC_ST_MAP.put(1, "Ready");
        AUTO_REC_ST_MAP.put(2, "InProgress");
        AUTO_REC_ST_MAP.put(3, "Successful");

        // FltLoop — bucle de falta (IEC 61850-7-4)
        FLT_LOOP_MAP.put(1, "PhA-Gnd");
        FLT_LOOP_MAP.put(2, "PhB-Gnd");
        FLT_LOOP_MAP.put(3, "PhC-Gnd");
        FLT_LOOP_MAP.put(4, "PhA-PhB");
        FLT_LOOP_MAP.put(5, "PhB-PhC");
        FLT_LOOP_MAP.put(6, "PhA-PhC");
        FLT_LOOP_MAP.put(7, "Others");

        // ── UnitMultiplier (IEC 61850-7-3 / IEC 61968-9 CIM): prefijos SI ─────────────────
        MULTIPLIER_MAP.put(-24, "y");  // yocto
        MULTIPLIER_MAP.put(-21, "z");  // zepto
        MULTIPLIER_MAP.put(-18, "a");  // atto
        MULTIPLIER_MAP.put(-15, "f");  // femto
        MULTIPLIER_MAP.put(-12, "p");  // pico
        MULTIPLIER_MAP.put(-9,  "n");  // nano
        MULTIPLIER_MAP.put(-6,  "µ");  // micro
        MULTIPLIER_MAP.put(-3,  "m");  // milli
        MULTIPLIER_MAP.put(-2,  "c");  // centi
        MULTIPLIER_MAP.put(-1,  "d");  // deci
        MULTIPLIER_MAP.put(0,   "");   // none (×1)
        MULTIPLIER_MAP.put(1,   "da"); // deca
        MULTIPLIER_MAP.put(2,   "h");  // hecto
        MULTIPLIER_MAP.put(3,   "k");  // kilo
        MULTIPLIER_MAP.put(6,   "M");  // mega
        MULTIPLIER_MAP.put(9,   "G");  // giga
        MULTIPLIER_MAP.put(12,  "T");  // tera
        MULTIPLIER_MAP.put(15,  "P");  // peta
        MULTIPLIER_MAP.put(18,  "E");  // exa
        MULTIPLIER_MAP.put(21,  "Z");  // zetta
        MULTIPLIER_MAP.put(24,  "Y");  // yotta

        // ── AddCause (IEC 61850-7-3:2010 Table 9): causa de rechazo de control ────────────
        ADD_CAUSE_MAP.put(0,  "unknown");
        ADD_CAUSE_MAP.put(1,  "not-supported");
        ADD_CAUSE_MAP.put(2,  "blocked-by-switching-hierarchy");
        ADD_CAUSE_MAP.put(3,  "select-failed");
        ADD_CAUSE_MAP.put(4,  "invalid-position");
        ADD_CAUSE_MAP.put(5,  "position-reached");
        ADD_CAUSE_MAP.put(6,  "parameter-change-in-execution");
        ADD_CAUSE_MAP.put(7,  "step-limit");
        ADD_CAUSE_MAP.put(8,  "blocked-by-mode");
        ADD_CAUSE_MAP.put(9,  "blocked-by-process");
        ADD_CAUSE_MAP.put(10, "blocked-by-interlocking");
        ADD_CAUSE_MAP.put(11, "blocked-by-synchrocheck");
        ADD_CAUSE_MAP.put(12, "command-already-in-execution");
        ADD_CAUSE_MAP.put(13, "blocked-by-health");
        ADD_CAUSE_MAP.put(14, "1-of-n-control");
        ADD_CAUSE_MAP.put(15, "abortion-by-cancel");
        ADD_CAUSE_MAP.put(16, "time-limit-over");
        ADD_CAUSE_MAP.put(17, "abortion-by-trip");
        ADD_CAUSE_MAP.put(18, "object-not-selected");
        ADD_CAUSE_MAP.put(19, "object-already-selected");
        ADD_CAUSE_MAP.put(20, "no-access-authority");
        ADD_CAUSE_MAP.put(21, "ended-with-overshoot");
        ADD_CAUSE_MAP.put(22, "abortion-due-to-deviation");
        ADD_CAUSE_MAP.put(23, "abortion-by-communication-loss");
        ADD_CAUSE_MAP.put(24, "blocked-by-command");
        ADD_CAUSE_MAP.put(25, "none-of-n-control");
        ADD_CAUSE_MAP.put(26, "inhibit");
        ADD_CAUSE_MAP.put(27, "must-be-on");
        ADD_CAUSE_MAP.put(28, "deactivation-not-possible");
    }

    /**
     * Formatea el valor segun su tipo, decodificando enumeraciones IEC 61850.
     */
    public String formatValue(ModelNode node) {
        if (node == null) return "null";
        try {
            // DoubleBitPos tiene su propio formateador
            if (node instanceof BdaDoubleBitPos) {
                return formatDoubleBitPos((BdaDoubleBitPos) node);
            }

            if (node instanceof BasicDataAttribute) {
                BasicDataAttribute bda = (BasicDataAttribute) node;
                String name = node.getName().toLowerCase();

                // Decodificar enums por nombre del DA
                // Cualquier ancho: el de un enumerado lo elige SclParser segun el rango
                // de ordinales, no es siempre Int8.
                if (ordinalDeBda(bda) != null) {
                    int v = getIntValue(bda);
                    if (name.equals("unit") || name.equals("siunit"))
                        return decodeEnum(v, SI_UNIT_MAP, bda);
                    if (name.equals("ctlmodel"))
                        return decodeEnum(v, CTL_MODEL_MAP, bda);
                    if (name.equals("health"))
                        return decodeEnum(v, HEALTH_MAP, bda);
                    if (name.equals("mod") || name.equals("beh"))
                        return decodeEnum(v, MOD_BEH_MAP, bda);
                    if (name.equals("range"))
                        return decodeEnum(v, RANGE_MAP, bda);
                    if (name.equals("dir"))
                        return decodeEnum(v, DIR_MAP, bda);
                    if (name.equals("orcategory"))
                        return decodeEnum(v, OR_CATEGORY_MAP, bda);
                    if (name.equals("autorecst"))
                        return decodeEnum(v, AUTO_REC_ST_MAP, bda);
                    if (name.equals("fltloop"))
                        return decodeEnum(v, FLT_LOOP_MAP, bda);
                    if (name.equals("multiplier"))
                        return decodeEnum(v, MULTIPLIER_MAP, bda);
                    if (name.equals("addcause"))
                        return decodeEnum(v, ADD_CAUSE_MAP, bda);
                }
                return bda.getValueString();
            }
            return node.toString();
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private int getIntValue(BasicDataAttribute bda) {
        if (bda instanceof BdaInt8)   return ((BdaInt8) bda).getValue();
        if (bda instanceof BdaInt8U)  return ((BdaInt8U) bda).getValue();
        if (bda instanceof BdaInt16)  return ((BdaInt16) bda).getValue();
        if (bda instanceof BdaInt16U) return ((BdaInt16U) bda).getValue();
        if (bda instanceof BdaInt32)  return ((BdaInt32) bda).getValue();
        return 0;
    }

    private String decodeEnum(int value, Map<Integer, String> map, BasicDataAttribute bda) {
        String text = map.get(value);
        return text != null ? text : bda.getValueString() + "(?)";
    }

    /**
     * Formatea DoubleBitPos (estado de interruptor)
     */
    private String formatDoubleBitPos(BdaDoubleBitPos node) {
        BdaDoubleBitPos.DoubleBitPos pos = node.getDoubleBitPos();
        if (pos == null) return "null";

        switch (pos) {
            case INTERMEDIATE_STATE: return "intermediate";
            case OFF: return "off";
            case ON: return "on";
            case BAD_STATE: return "bad";
            default: return pos.toString();
        }
    }

    /**
     * Obtiene el tipo de valor
     */
    public String getValueType(ModelNode node) {
        if (node instanceof BdaBoolean) return "Boolean";
        if (node instanceof BdaFloat32) return "Float32";
        if (node instanceof BdaFloat64) return "Float64";
        if (node instanceof BdaInt8) return "Int8";
        if (node instanceof BdaInt16) return "Int16";
        if (node instanceof BdaInt32) return "Int32";
        if (node instanceof BdaInt64) return "Int64";
        if (node instanceof BdaInt8U) return "Int8U";
        if (node instanceof BdaInt16U) return "Int16U";
        if (node instanceof BdaInt32U) return "Int32U";
        if (node instanceof BdaVisibleString) return "VisibleString";
        if (node instanceof BdaUnicodeString) return "UnicodeString";
        if (node instanceof BdaDoubleBitPos) return "Dbpos";
        if (node instanceof BdaBitString) return "BitString";
        if (node instanceof BdaQuality) return "Quality";
        if (node instanceof BdaTimestamp) return "Timestamp";
        if (node instanceof BdaEntryTime) return "EntryTime";
        if (node instanceof BdaOctetString) return "OctetString";
        if (node instanceof BdaCheck) return "Check";
        if (node instanceof BdaTapCommand) return "TapCommand";
        if (node instanceof ConstructedDataAttribute) return "Struct";
        return "Unknown";
    }

    /**
     * Escribe un valor al servidor
     */
    public void writeValue(String reference, Fc fc, String value) throws IOException {
        if (!isConnected() || serverModel == null) {
            throw new IOException("Not connected");
        }

        try {
            ModelNode node = serverModel.findModelNode(reference, fc);
            if (node instanceof BasicDataAttribute) {
                BasicDataAttribute bda = (BasicDataAttribute) node;
                setBasicDataAttributeValue(bda, value);

                if (node instanceof FcModelNode) {
                    association.setDataValues((FcModelNode) node);
                }

                System.out.println("[OK] Wrote value: " + reference + " = " + value);
            }
        } catch (ServiceError e) {
            throw new IOException("ServiceError: " + e.getErrorCode(), e);
        }
    }

    /**
     * Ejecuta control de interruptor (operate)
     */
    public boolean operate(FcModelNode controlNode, boolean value) throws IOException {
        if (!isConnected()) {
            throw new IOException("Not connected");
        }

        try {
            // Buscar ctlVal dentro del nodo de control
            Collection<ModelNode> children = controlNode.getChildren();
            if (children != null) {
                for (ModelNode child : children) {
                    if (child.getName().equals("ctlVal")) {
                        if (child instanceof BdaBoolean) {
                            ((BdaBoolean) child).setValue(value);
                        } else if (child instanceof BdaDoubleBitPos) {
                            ((BdaDoubleBitPos) child).setDoubleBitPos(
                                value ? BdaDoubleBitPos.DoubleBitPos.ON : BdaDoubleBitPos.DoubleBitPos.OFF
                            );
                        }
                        break;
                    }
                }
            }

            // Ejecutar operacion
            association.operate(controlNode);

            System.out.println("[OK] Control executed: " + controlNode.getReference() + " = " + value);
            return true;

        } catch (ServiceError e) {
            throw new IOException("ServiceError: " + e.getErrorCode(), e);
        }
    }

    /**
     * Establece valor en un BasicDataAttribute (igual que la APK)
     */
    private void setBasicDataAttributeValue(BasicDataAttribute bda, String value) {
        try {
            if (bda instanceof BdaBoolean) {
                ((BdaBoolean) bda).setValue(Boolean.parseBoolean(value) || "1".equals(value));
            } else if (bda instanceof BdaInt8) {
                ((BdaInt8) bda).setValue(Byte.parseByte(value));
            } else if (bda instanceof BdaInt16) {
                ((BdaInt16) bda).setValue(Short.parseShort(value));
            } else if (bda instanceof BdaInt32) {
                ((BdaInt32) bda).setValue(Integer.parseInt(value));
            } else if (bda instanceof BdaInt64) {
                ((BdaInt64) bda).setValue(Long.parseLong(value));
            } else if (bda instanceof BdaFloat32) {
                ((BdaFloat32) bda).setFloat(Float.parseFloat(value));
            } else if (bda instanceof BdaFloat64) {
                ((BdaFloat64) bda).setDouble(Double.parseDouble(value));
            } else if (bda instanceof BdaVisibleString) {
                ((BdaVisibleString) bda).setValue(value);
            } else if (bda instanceof BdaDoubleBitPos) {
                setDbposValue((BdaDoubleBitPos) bda, value);
            } else if (bda instanceof BdaCheck) {
                BdaCheck check = (BdaCheck) bda;
                check.setSynchrocheck("true".equalsIgnoreCase(value) || "1".equals(value));
            } else if (bda instanceof BdaTapCommand) {
                setTapCommandValue((BdaTapCommand) bda, value);
            }
        } catch (Exception e) {
            System.err.println("[ERROR] Setting value: " + e.getMessage());
        }
    }

    /**
     * Establece valor DoubleBitPos (igual que la APK)
     */
    private void setDbposValue(BdaDoubleBitPos dbpos, String value) {
        String lowerValue = value.toLowerCase().trim();

        if (lowerValue.equals("off") || lowerValue.equals("01") || lowerValue.equals("1")) {
            dbpos.setDoubleBitPos(BdaDoubleBitPos.DoubleBitPos.OFF);
        } else if (lowerValue.equals("on") || lowerValue.equals("10") || lowerValue.equals("2")) {
            dbpos.setDoubleBitPos(BdaDoubleBitPos.DoubleBitPos.ON);
        } else if (lowerValue.equals("intermediate") || lowerValue.equals("00") || lowerValue.equals("0")) {
            dbpos.setDoubleBitPos(BdaDoubleBitPos.DoubleBitPos.INTERMEDIATE_STATE);
        } else if (lowerValue.equals("bad") || lowerValue.equals("11") || lowerValue.equals("3")) {
            dbpos.setDoubleBitPos(BdaDoubleBitPos.DoubleBitPos.BAD_STATE);
        }
    }

    /**
     * Establece valor TapCommand (igual que la APK)
     */
    private void setTapCommandValue(BdaTapCommand tap, String value) {
        String lowerValue = value.toLowerCase().trim();

        if (lowerValue.equals("stop") || lowerValue.equals("0")) {
            tap.setTapCommand(BdaTapCommand.TapCommand.STOP);
        } else if (lowerValue.equals("lower") || lowerValue.equals("1")) {
            tap.setTapCommand(BdaTapCommand.TapCommand.LOWER);
        } else if (lowerValue.equals("higher") || lowerValue.equals("2")) {
            tap.setTapCommand(BdaTapCommand.TapCommand.HIGHER);
        } else if (lowerValue.equals("reserved") || lowerValue.equals("3")) {
            tap.setTapCommand(BdaTapCommand.TapCommand.RESERVED);
        }
    }

    /**
     * Obtiene valor del cache
     */
    public CachedValue getCachedValue(String reference) {
        return valueCache.get(reference);
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public ClientAssociation getAssociation() {
        return association;
    }

    // ==================== LECTURA PREVIA A EXPORTAR UN CID ====================

    /** Avance de la lectura del modelo, para poder informarla mientras corre. */
    public interface ProgresoLectura {
        void avance(int hechos, int total);
    }

    /** Cuántos objetos se pudieron leer del IED y cuántos no. */
    public static final class LecturaModelo {
        public final int leidos, fallidos, total;
        public LecturaModelo(int leidos, int fallidos, int total) {
            this.leidos = leidos; this.fallidos = fallidos; this.total = total;
        }
    }

    /**
     * Lee del IED los valores de todo el modelo, para que un CID reconstruido
     * lleve la configuración real del equipo y no ceros.
     *
     * Hace falta porque retrieveModel() trae la ESTRUCTURA pero no los VALORES:
     * cada atributo queda en su valor por defecto —0, false, cadena vacía— hasta
     * que alguien lo lee. Exportar el modelo recién recuperado producía un CID
     * sintácticamente válido y semánticamente vacío: medido sobre un PCS-9611S
     * real, 37.994 DAI en cero contra 7 con valor, los 763 ctlModel en 0
     * (o sea, todo el equipo declarado status-only) y los ReportControl sin
     * datSet ni rptID. Ese archivo cargado en un simulador da un IED que no
     * acepta ningún mando.
     *
     * Se lee por Data Object, no por atributo: es un viaje MMS por DO en vez de
     * uno por cada BDA. Y se tolera el fallo individual —hay equipos que niegan
     * la lectura de algunos objetos— porque abortar entero por uno solo es lo
     * que hace getAllDataValues() de la librería, y con eso no se exporta nada.
     *
     * No se leen FC=CO ni FC=SE: los primeros son las estructuras de mando, que
     * no tienen valor que leer, y los segundos exigen seleccionar antes el grupo
     * de ajustes. Es el mismo criterio que aplica getAllDataValues().
     *
     * Puede tardar: es un viaje de ida y vuelta por Data Object. NO llamarla
     * desde el hilo de la interfaz.
     */
    public LecturaModelo leerValoresParaExportar(ProgresoLectura progreso) {
        ServerModel m = serverModel;
        if (m == null || association == null) return new LecturaModelo(0, 0, 0);

        List<FcModelNode> objetivos = new ArrayList<>();
        for (ModelNode ld : m.getChildren()) {
            for (ModelNode ln : ld.getChildren()) {
                for (ModelNode hijo : ln.getChildren()) {
                    if (!(hijo instanceof FcModelNode)) continue;
                    FcModelNode fcdo = (FcModelNode) hijo;
                    Fc fc = fcdo.getFc();
                    if (fc == Fc.CO || fc == Fc.SE) continue;
                    objetivos.add(fcdo);
                }
            }
        }

        int leidos = 0, fallidos = 0, i = 0;
        for (FcModelNode fcdo : objetivos) {
            try {
                association.getDataValues(fcdo);
                leidos++;
            } catch (Exception e) {
                fallidos++;
            }
            i++;
            if (progreso != null && (i % 200 == 0 || i == objetivos.size())) {
                progreso.avance(i, objetivos.size());
            }
        }
        return new LecturaModelo(leidos, fallidos, objetivos.size());
    }

    // ==================== REPORTS ====================

    // Interface para notificar reportes
    public interface ReportListener {
        void onReportReceived(Report report);
    }

    private ReportListener externalReportListener;

    /**
     * Habilita un Report Control Block.
     * Solo escribe rptEna y trgOps para evitar ServiceError en servidores que no
     * permiten modificar datSet, optFlds o bufTm mientras el RCB se habilita.
     */
    public void enableReporting(Rcb rcb, ReportListener listener) throws IOException {
        if (!isConnected() || association == null) {
            throw new IOException("Not connected");
        }

        try {
            externalReportListener = listener;

            // Leer valores actuales del RCB desde el servidor
            association.getRcbValues(rcb);

            if (rcb instanceof Urcb) {
                Urcb urcb = (Urcb) rcb;
                association.reserveUrcb(urcb);
                enableRcb(urcb);
            } else if (rcb instanceof Brcb) {
                Brcb brcb = (Brcb) rcb;
                enableRcb(brcb);
            }

        } catch (ServiceError e) {
            throw new IOException("Error enabling report: " + e.getErrorCode(), e);
        }
    }

    /**
     * Habilita un RCB con un único Write de rptEna=true.
     * NO escribir trgOps antes — el servidor iec61850bean limpia la reserva entre Writes,
     * y si la reserva se pierde antes del Write de rptEna, el enable es ignorado silenciosamente.
     * Los trgOps quedan como están en el servidor (configurados desde el SCL/CID).
     */
    private void enableRcb(Rcb rcb) throws ServiceError, IOException {
        // Use the official enableReporting() API which calls setDataValues(rptEnaBda)
        // directly — this triggers the correct RptEna handler in ServerAssociation.
        association.enableReporting(rcb);

        try {
            association.getRcbValues(rcb);
        } catch (ServiceError e) {
            System.out.println("[WARN] getRcbValues post-enable: " + e.getMessage());
        }
        boolean enabled = rcb.getRptEna() != null && rcb.getRptEna().getValue();
        System.out.println("[" + (enabled ? "OK" : "INFO") + "] RCB " + rcb.getName()
            + " rptEna local=" + enabled);
    }

    /**
     * Deshabilita un Report Control Block.
     * El tercer parámetro de setRcbValues (rptEna=true) indica que SE DEBE ESCRIBIR
     * el campo rptEna; el valor a escribir es false (configurado en rcb.getRptEna().setValue(false)).
     */
    public void disableReporting(Rcb rcb) throws IOException {
        if (!isConnected() || association == null) {
            throw new IOException("Not connected");
        }

        try {
            association.disableReporting(rcb);

            if (rcb instanceof Urcb) {
                try { association.cancelUrcbReservation((Urcb) rcb); } catch (Exception ignore) {}
            }

            System.out.println("[OK] RCB disabled: " + rcb.getName());

        } catch (ServiceError e) {
            throw new IOException("Error disabling report: " + e.getErrorCode(), e);
        }
    }

    // ClientEventListener implementation

    @Override
    public void newReport(Report report) {
        // Notificar al listener externo (panel de Reports)
        if (externalReportListener != null) {
            externalReportListener.onReportReceived(report);
        }

        // Notificar cambios de valores
        List<FcModelNode> values = report.getValues();
        if (values != null && valueChangeListener != null) {
            for (FcModelNode node : values) {
                if (node instanceof BasicDataAttribute) {
                    BasicDataAttribute bda = (BasicDataAttribute) node;
                    String ref = bda.getReference().toString();
                    String val = bda.getValueString();
                    valueChangeListener.onValueChanged(ref, val, getValueType(bda));
                }
            }
        }
    }

    @Override
    public void associationClosed(IOException e) {
        // Durante connect() los reintentos internos (retryRetrieveModelWithoutDataSets,
        // retrieveModelManually) rompen asociaciones intermedias a propósito. Esos cierres
        // no deben tocar el estado (pisarían la asociación nueva del reintento) ni
        // notificar a la GUI ("Desconectado" fantasma mientras aún se está conectando).
        if (connectInProgress) {
            System.out.println("[INFO] Association closed durante conexión en curso (reintento) — ignorado"
                + (e != null ? ": " + e.getMessage() : ""));
            return;
        }

        System.out.println("[WARN] Association closed" + (e != null ? ": " + e.getMessage() : ""));
        connected = false;
        serverModel = null;
        association = null;
        heartbeatNode = null;   // pertenece al modelo que se acaba de descartar

        if (valueChangeListener != null) {
            valueChangeListener.onConnectionClosed(e != null ? e.getMessage() : "Connection closed");
        }
    }

    // ==================== FILE SERVICES ====================

    /**
     * Lista los archivos disponibles en el IED
     */
    public List<FileInformation> listFiles(String directory) throws IOException {
        if (!isConnected() || association == null) {
            throw new IOException("Not connected");
        }

        try {
            List<FileInformation> files = association.getFileDirectory(directory);
            System.out.println("[INFO] Files in '" + directory + "': " + (files != null ? files.size() : 0));
            return files != null ? files : new ArrayList<>();
        } catch (ServiceError e) {
            throw new IOException("Error listing files: " + e.getErrorCode(), e);
        }
    }

    /**
     * Busca archivos SCL/CID/ICD en el IED
     */
    /** Profundidad maxima del barrido recursivo de directorios. */
    private static final int SCL_SEARCH_MAX_DEPTH = 4;
    /** Tope de directorios a listar, para no castigar al IED en arboles grandes. */
    private static final int SCL_SEARCH_MAX_DIRS = 200;

    public List<String> findSclFiles() throws IOException {
        // Barrido recursivo desde la raiz. Cubre los ZIV, que publican el CID activo en
        // <NombreIED><LD>/SCL/validated/, ruta que ninguna lista fija de directorios acierta.
        List<String> sclFiles = walkForSclFiles(new String[]{""});

        // Solo si la raiz no dio nada se prueban los directorios historicos. No se usan como
        // semilla junto con la raiz porque algunos IEDs (el ZIV entre ellos) aceptan cualquier
        // prefijo inexistente y devuelven el contenido de la raiz: al recorrer ocho semillas
        // el mismo CID aparecia 37 veces bajo rutas ficticias como /icd/COMTRADE/SCL/validated/.
        if (sclFiles.isEmpty()) {
            sclFiles = walkForSclFiles(new String[]{"/", "/config", "/CONFIG", "/scl", "/SCL", "/icd", "/ICD"});
        }

        // Un CID en .../notvalidated/ es un archivo en espera de validacion, no el activo:
        // se deja al final para que la seleccion automatica no lo prefiera.
        sclFiles.sort(Comparator.comparingInt(p -> p.toLowerCase().contains("notvalidated") ? 1 : 0));

        return sclFiles;
    }

    /** Recorre el arbol de archivos del IED desde las semillas dadas y junta los SCL/CID/ICD/SCD. */
    private List<String> walkForSclFiles(String[] seedDirs) {
        List<String> sclFiles = new ArrayList<>();

        Set<String> visited = new HashSet<>();
        Deque<String> pending = new ArrayDeque<>(Arrays.asList(seedDirs));
        Map<String, Integer> depth = new HashMap<>();
        for (String seed : seedDirs) depth.put(seed, 0);

        int listed = 0;
        while (!pending.isEmpty() && listed < SCL_SEARCH_MAX_DIRS) {
            String dir = pending.poll();
            if (!visited.add(dir)) continue;

            List<FileInformation> files;
            try {
                files = listFiles(dir);
                listed++;
            } catch (Exception e) {
                // Directorio inexistente o sin permiso: se ignora y se sigue
                System.out.println("[DEBUG] Cannot list " + dir + ": " + e.getMessage());
                continue;
            }
            if (files == null) continue;

            int currentDepth = depth.getOrDefault(dir, 0);
            for (FileInformation fi : files) {
                String entry = fi.getFilename();
                if (entry == null || entry.isEmpty()) continue;

                String fullPath = resolveIedPath(dir, entry);

                // Los IEDs marcan los directorios con '/' final en el nombre
                if (entry.endsWith("/")) {
                    if (currentDepth + 1 <= SCL_SEARCH_MAX_DEPTH && !visited.contains(fullPath)) {
                        depth.put(fullPath, currentDepth + 1);
                        pending.add(fullPath);
                    }
                    continue;
                }

                String name = entry.toLowerCase();
                if (name.endsWith(".cid") || name.endsWith(".icd") ||
                    name.endsWith(".scd") || name.endsWith(".scl")) {
                    if (!sclFiles.contains(fullPath)) {
                        sclFiles.add(fullPath);
                        System.out.println("[INFO] Found SCL file: " + fullPath);
                    }
                }
            }
        }

        if (listed >= SCL_SEARCH_MAX_DIRS) {
            System.out.println("[WARN] Busqueda de SCL detenida en " + SCL_SEARCH_MAX_DIRS + " directorios");
        }

        return sclFiles;
    }

    /**
     * Compone la ruta de una entrada devuelta por getFileDirectory().
     * Algunos IEDs devuelven el nombre relativo al directorio consultado y otros la ruta
     * completa; este metodo cubre ambos casos sin duplicar el prefijo.
     */
    private String resolveIedPath(String dir, String entry) {
        if (dir == null || dir.isEmpty() || "/".equals(dir)) {
            return entry;
        }
        if (entry.startsWith(dir)) {
            return entry;
        }
        return dir.endsWith("/") ? dir + entry : dir + "/" + entry;
    }

    /**
     * Descarga un archivo del IED
     */
    public byte[] downloadFile(String filename) throws IOException {
        if (!isConnected() || association == null) {
            throw new IOException("Not connected");
        }

        System.out.println("[INFO] Downloading file: " + filename);

        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        final IOException[] error = new IOException[1];
        final boolean[] done = new boolean[1];

        try {
            association.getFile(filename, new GetFileListener() {
                @Override
                public boolean dataReceived(byte[] data, boolean moreFollows) {
                    try {
                        baos.write(data);
                        System.out.println("[DEBUG] Received " + data.length + " bytes, more=" + moreFollows);
                        if (!moreFollows) {
                            done[0] = true;
                        }
                        return true; // Continue receiving
                    } catch (Exception e) {
                        error[0] = new IOException("Error writing data", e);
                        return false;
                    }
                }
            });

            if (error[0] != null) {
                throw error[0];
            }

            byte[] result = baos.toByteArray();
            System.out.println("[OK] Downloaded " + result.length + " bytes");
            return result;

        } catch (ServiceError e) {
            throw new IOException("Error downloading file: " + e.getErrorCode(), e);
        }
    }

    /**
     * Descarga y guarda un archivo SCL del IED
     */
    public File downloadAndSaveSclFile(String remotePath, File localDir) throws IOException {
        byte[] data = downloadFile(remotePath);

        // Extraer nombre del archivo
        String filename = remotePath;
        int lastSlash = remotePath.lastIndexOf('/');
        if (lastSlash >= 0) {
            filename = remotePath.substring(lastSlash + 1);
        }

        // Guardar archivo
        File localFile = new File(localDir, filename);
        try (FileOutputStream fos = new FileOutputStream(localFile)) {
            fos.write(data);
        }

        System.out.println("[OK] Saved to: " + localFile.getAbsolutePath());
        return localFile;
    }

    // ── Setting Group Control Block (SGCB) ───────────────────────────────────

    /**
     * Lee los valores actuales del SGCB de un LD dado.
     * Busca LLN0.SGCB.actSG y LLN0.SGCB.numOfSGs en el modelo.
     * @param ldName nombre del LD, ej: "LD0"
     * @return array {actSG, numOfSGs} o null si el SGCB no existe en el modelo
     */
    public int[] readSGCBValues(String ldName) {
        if (serverModel == null) return null;
        try {
            int actSg = 1, numSgs = 1;
            boolean found = false;

            // Buscar el nodo SGCB como FcDataObject bajo LLN0
            ModelNode lln0 = serverModel.findModelNode(ldName + "/LLN0", null);
            if (lln0 == null) return null;

            for (ModelNode child : lln0.getChildren()) {
                if (!"SGCB".equalsIgnoreCase(child.getName())) continue;
                found = true;
                // Intentar leer los valores del SGCB
                if (child instanceof FcModelNode) {
                    try { association.getDataValues((FcModelNode) child); } catch (Exception ignored) {}
                }
                for (ModelNode attr : child.getChildren()) {
                    String attrName = attr.getName().toLowerCase();
                    if (attr instanceof FcModelNode) {
                        try { association.getDataValues((FcModelNode) attr); } catch (Exception ignored) {}
                    }
                    if (attr instanceof BasicDataAttribute) {
                        int val = getIntValue((BasicDataAttribute) attr);
                        if (attrName.equals("actsg"))   actSg  = val;
                        if (attrName.equals("numofsgs")) numSgs = val;
                    }
                }
                break;
            }
            return found ? new int[]{actSg, numSgs} : null;
        } catch (Exception e) {
            System.err.println("[SGCB] Error leyendo " + ldName + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Escribe el grupo activo en SGCB.actSG via setDataValues (SelectActiveSG en MMS).
     * ADVERTENCIA: cambia el comportamiento de la protección en tiempo real.
     * @param ldName     nombre del LD, ej: "LD0"
     * @param groupNumber número de grupo a activar (1..numOfSGs)
     */
    public void selectActiveSG(String ldName, int groupNumber) throws IOException {
        if (!isConnected() || serverModel == null) throw new IOException("Not connected");
        try {
            ModelNode lln0 = serverModel.findModelNode(ldName + "/LLN0", null);
            if (lln0 == null) throw new IOException("LLN0 no encontrado en " + ldName);

            for (ModelNode child : lln0.getChildren()) {
                if (!"SGCB".equalsIgnoreCase(child.getName())) continue;
                for (ModelNode attr : child.getChildren()) {
                    if (!"actsg".equalsIgnoreCase(attr.getName())) continue;
                    // Cualquier ancho. Con el instanceof angosto, un actSG que llegara
                    // mas ancho no se escribia y se enviaba igual el valor viejo: el grupo
                    // de ajustes no cambiaba y nada lo decia.
                    if (attr instanceof BdaInt8U) {
                        ((BdaInt8U) attr).setValue((short) groupNumber);
                    } else if (attr instanceof BdaInt8) {
                        ((BdaInt8) attr).setValue((byte) groupNumber);
                    } else if (attr instanceof BasicDataAttribute) {
                        setBasicDataAttributeValue((BasicDataAttribute) attr,
                            String.valueOf(groupNumber));
                    }
                    if (attr instanceof FcModelNode) {
                        association.setDataValues((FcModelNode) attr);
                        System.out.println("[SGCB] actSG=" + groupNumber + " escrito en " + ldName);
                        return;
                    }
                }
                // Si actSG no está como nodo hijo independiente, escribir el SGCB completo
                if (child instanceof FcModelNode) {
                    association.setDataValues((FcModelNode) child);
                    return;
                }
            }
            throw new IOException("SGCB.actSG no encontrado en " + ldName + "/LLN0");
        } catch (ServiceError e) {
            throw new IOException("ServiceError SelectActiveSG: " + e.getErrorCode(), e);
        }
    }

    // ── Gap 10: FC=BL Blocking ───────────────────────────────────────────────

    /**
     * Busca el nodo blkEna (FC=BL) de un DO dado su referencia base.
     * Retorna null si el DO no soporta bloqueo.
     */
    /**
     * Lee todos los valores de un DataSet en una sola petición MMS (GetDataSetValues).
     * Los valores quedan actualizados en los miembros del DataSet del modelo.
     *
     * @param dsRef referencia del DataSet (p. ej. "IED1LD0/LLN0.dsMeas")
     * @return el DataSet con valores actualizados
     * @throws IOException si no hay conexión, el DataSet no existe o el servidor devuelve error
     */
    public DataSet readDataSetValues(String dsRef) throws IOException {
        if (!isConnected() || serverModel == null) {
            throw new IOException("No conectado a ningún IED");
        }
        DataSet dataSet = serverModel.getDataSet(dsRef);
        if (dataSet == null) {
            for (DataSet ds : serverModel.getDataSets()) {
                if (ds.getReferenceStr().equals(dsRef)) { dataSet = ds; break; }
            }
        }
        if (dataSet == null) {
            throw new IOException("DataSet no encontrado: " + dsRef);
        }
        List<ServiceError> errors = association.getDataSetValues(dataSet);
        if (errors != null) {
            int errCount = 0;
            for (ServiceError se : errors) if (se != null) errCount++;
            if (errCount > 0) {
                System.out.println("[DataSet] " + dsRef + ": " + errCount + " de "
                        + errors.size() + " miembros con error de servicio");
            }
        }
        return dataSet;
    }

    /**
     * Lee valores de un DataSet ya conocido (objeto externo, p.ej. del modelo del servidor).
     * Útil cuando el DataSet no está en el modelo MMS del cliente pero sí en el servidor.
     */
    public DataSet readDataSetValues(DataSet dataSet) throws IOException {
        if (!isConnected()) throw new IOException("No conectado a ningún IED");
        List<ServiceError> errors = association.getDataSetValues(dataSet);
        if (errors != null) {
            int errCount = 0;
            for (ServiceError se : errors) if (se != null) errCount++;
            if (errCount > 0) {
                System.out.println("[DataSet] " + dataSet.getReferenceStr() + ": "
                        + errCount + " de " + errors.size() + " miembros con error");
            }
        }
        return dataSet;
    }

    public FcModelNode findBlkEnaNode(String doReference) {
        if (serverModel == null) return null;
        // Intentar construir ref: doRef.blkEna con Fc.BL
        try {
            com.beanit.iec61850bean.ModelNode node =
                serverModel.findModelNode(doReference + ".blkEna", Fc.BL);
            if (node instanceof FcModelNode) return (FcModelNode) node;
        } catch (Exception ignore) {}
        return null;
    }

    /**
     * Activa o desactiva el bloqueo (blkEna) de un DO.
     * Cuando blkEna=true el IED congela el valor del DO y deja de actualizarlo.
     */
    public void setBlocking(FcModelNode blkEnaNode, boolean block) throws IOException {
        if (association == null) throw new IOException("No conectado");
        try {
            if (blkEnaNode instanceof BdaBoolean) {
                ((BdaBoolean) blkEnaNode).setValue(block);
            }
            association.setDataValues(blkEnaNode);
        } catch (ServiceError e) {
            throw new IOException("ServiceError setBlocking: " + e.getErrorCode(), e);
        }
    }

    // ==================== CONTROL (SBO + DIRECT) ====================

    /**
     * Resultado de una operación de control.
     * Contiene el modelo de control usado (ctlModel), resultado y error detallado.
     */
    public static class ControlResult {
        public final boolean success;
        public final int ctlModel;           // 0-4
        public final String ctlModelName;    // "direct-normal-security", "sbo-normal-security", etc.
        public final String error;           // null si success=true
        public final String lastApplError;   // del nodo LastApplError del IED; puede ser null
        /** AddCause numérico extraído de LastApplError (IEC 61850-7-3 Tabla 9); -1 si no se obtuvo. */
        public final int addCause;
        /**
         * Código de ServiceError devuelto por el IED; -1 si el rechazo no vino por esa vía.
         * No todos los equipos publican LastApplError al rechazar un control: se confirmó en
         * campo un IED que rechaza el SBOw con ACCESS_VIOLATION (3) y sin AddCause. En ese
         * caso este código es la única pista que da el equipo.
         */
        public final int serviceError;

        private ControlResult(boolean success, int ctlModel, String ctlModelName,
                               String error, String lastApplError, int addCause, int serviceError) {
            this.success = success;
            this.ctlModel = ctlModel;
            this.ctlModelName = ctlModelName;
            this.error = error;
            this.lastApplError = lastApplError;
            this.addCause = addCause;
            this.serviceError = serviceError;
        }

        /** Nombre estándar del AddCause, o null si no se obtuvo. */
        public String addCauseName() {
            return addCause >= 0 ? ADD_CAUSE_MAP.get(addCause) : null;
        }

        /** Nombre del ServiceError según iec61850bean, o null si no hubo código. */
        public String serviceErrorName() {
            return serviceError >= 0 ? SERVICE_ERROR_MAP.get(serviceError) : null;
        }

        /**
         * Explicación accionable de por qué el IED rechazó la orden, traducida al idioma
         * activo.
         *
         * Prioriza el AddCause, que es la causa que el estándar reserva para los controles.
         * Si el IED no lo publicó, cae al significado del ServiceError, que es más genérico
         * pero sigue orientando (p. ej. ACCESS_VIOLATION suele ser enclavamiento o autoridad).
         * Retorna null si no hay ninguna de las dos.
         */
        public String diagnosis() {
            String txt = lookup("ctl.addcause.", addCause);
            if (txt != null) return txt;
            return lookup("ctl.svcerr.", serviceError);
        }

        /** true si la explicación proviene del ServiceError y no del AddCause. */
        public boolean diagnosisIsFallback() {
            return lookup("ctl.addcause.", addCause) == null
                && lookup("ctl.svcerr.", serviceError) != null;
        }

        private static String lookup(String prefix, int code) {
            if (code < 0) return null;
            String key = prefix + code;
            String txt = I18n.t(key);
            return (txt == null || txt.equals(key)) ? null : txt;
        }

        static ControlResult ok(int ctlModel, String ctlModelName) {
            return new ControlResult(true, ctlModel, ctlModelName, null, null, -1, -1);
        }

        static ControlResult fail(int ctlModel, String ctlModelName,
                                   String error, String lastApplError) {
            return new ControlResult(false, ctlModel, ctlModelName, error, lastApplError, -1, -1);
        }

        static ControlResult failWith(int ctlModel, String ctlModelName,
                                   String error, ApplError ae) {
            return failWith(ctlModel, ctlModelName, error, ae, -1);
        }

        static ControlResult failWith(int ctlModel, String ctlModelName,
                                   String error, ApplError ae, int serviceError) {
            return new ControlResult(false, ctlModel, ctlModelName, error,
                ae != null ? ae.raw : null, ae != null ? ae.addCause : -1, serviceError);
        }
    }

    /** ServiceError de iec61850bean: código → nombre de la constante. */
    private static final Map<Integer, String> SERVICE_ERROR_MAP = new LinkedHashMap<>();
    static {
        SERVICE_ERROR_MAP.put(1,  "instance-not-available");
        SERVICE_ERROR_MAP.put(2,  "instance-in-use");
        SERVICE_ERROR_MAP.put(3,  "access-violation");
        SERVICE_ERROR_MAP.put(4,  "access-not-allowed-in-current-state");
        SERVICE_ERROR_MAP.put(5,  "parameter-value-inappropriate");
        SERVICE_ERROR_MAP.put(6,  "parameter-value-inconsistent");
        SERVICE_ERROR_MAP.put(7,  "class-not-supported");
        SERVICE_ERROR_MAP.put(8,  "instance-locked-by-other-client");
        SERVICE_ERROR_MAP.put(9,  "control-must-be-selected");
        SERVICE_ERROR_MAP.put(10, "type-conflict");
        SERVICE_ERROR_MAP.put(11, "failed-due-to-communications-constraint");
        SERVICE_ERROR_MAP.put(12, "failed-due-to-server-constraint");
        SERVICE_ERROR_MAP.put(13, "application-unreachable");
        SERVICE_ERROR_MAP.put(14, "connection-lost");
        SERVICE_ERROR_MAP.put(22, "timeout");
        SERVICE_ERROR_MAP.put(23, "unknown");
    }

    /** Contenido del nodo LastApplError: texto crudo + AddCause numérico (-1 si no se pudo leer). */
    static class ApplError {
        final String raw;
        final int addCause;
        ApplError(String raw, int addCause) { this.raw = raw; this.addCause = addCause; }
    }

    /**
     * Lee el valor de ctlModel del DO propietario del nodo Oper.
     * Busca "DO.ctlModel" con FC=CF primero, luego FC=SP.
     * Retorna 1 (direct-normal-security) si no se encuentra.
     */
    public int getCtlModelValue(FcModelNode operNode) {
        if (serverModel == null) return 1;
        String operRef = operNode.getReference().toString();
        int lastDot = operRef.lastIndexOf('.');
        if (lastDot < 0) return 1;
        String doRef = operRef.substring(0, lastDot);
        // Cualquier ancho de entero, no sólo BdaInt8/BdaInt8U. Preguntar por un ancho fijo
        // hacía que un ctlModel que llegara como BdaInt16 no matcheara y se cayera al 1 de
        // abajo: el cliente informaba direct-with-normal-security sobre puntos que en el
        // modelo eran status-only o SBO. Medido contra un modelo real: los 84 puntos
        // controlables se leían como 1 cuando la distribución real era {0, 1, 3}.
        //
        // No es cosmético: ese valor decide por qué rama sale la orden. Con un 1 falso se
        // manda directo un punto que exige selección previa, o se ofrece operar uno que es
        // de sólo lectura.
        for (Fc fc : new Fc[]{Fc.CF, Fc.SP}) {
            try {
                ModelNode node = serverModel.findModelNode(doRef + ".ctlModel", fc);
                if (!(node instanceof FcModelNode)) continue;
                try { association.getDataValues((FcModelNode) node); } catch (Exception ignore) {}
                Integer v = ordinalDeBda(node);
                if (v != null) return v & 0xFF;
            } catch (Exception ignore) {}
        }
        // El modelo no declara ctlModel: se asume direct-with-normal-security, que es lo que
        // hacía antes. Es una suposición, pero acá sí está justificada — no hay dato.
        return 1;
    }

    /**
     * Detecta el tipo de ctlVal del nodo Oper (para mostrar el control adecuado en la UI).
     * Retorna el nombre del tipo: "Boolean", "DoubleBitPos", "Float32", "TapCommand", etc.
     */
    public String getOperCtlValType(FcModelNode operNode) {
        if (operNode.getChildren() == null) return "Boolean";
        for (ModelNode child : operNode.getChildren()) {
            if ("ctlVal".equals(child.getName())) {
                return getValueType(child);
            }
        }
        return "Boolean";
    }

    /**
     * Establece el ctlVal del nodo Oper a partir de un string.
     * Delega a setBasicDataAttributeValue() que ya maneja todos los tipos BDA.
     */
    public void setOperCtlVal(FcModelNode operNode, String value) {
        if (operNode.getChildren() == null) return;
        for (ModelNode child : operNode.getChildren()) {
            if ("ctlVal".equals(child.getName()) && child instanceof BasicDataAttribute) {
                setBasicDataAttributeValue((BasicDataAttribute) child, value);
                return;
            }
        }
    }

    /**
     * Rellena los campos de la estructura Oper excepto ctlVal (que se setea por separado):
     *   - origin.orCat = 3 (remote-control)
     *   - origin.orIdent = orIdent (UTF-8 bytes)
     *   - ctlNum = ++ctlNumCounter
     *   - T = hora actual
     *   - Test = testFlag
     * Los campos no presentes en el modelo se ignoran silenciosamente.
     */
    private void fillControlStructure(FcModelNode operNode, boolean testFlag, String orIdent) {
        fillControlStructure(operNode, testFlag, orIdent, false, false);
    }

    /**
     * Rellena los campos de la estructura Oper excepto ctlVal (que se setea por separado):
     *   - origin.orCat = 3 (remote-control)
     *   - origin.orIdent = orIdent (UTF-8 bytes)
     *   - ctlNum = ++ctlNumCounter (0-255 circular)
     *   - T = hora actual
     *   - Test = testFlag
     *   - Check.synchroChk = synchroCheck   (sincronismo: tensión, ángulo y frecuencia)
     *   - Check.interlkChk = interlockCheck  (enclavamiento lógico del IED)
     * Los campos no presentes en el modelo se ignoran silenciosamente.
     */
    private void fillControlStructure(FcModelNode operNode, boolean testFlag, String orIdent,
                                       boolean synchroCheck, boolean interlockCheck) {
        fillControlStructure(operNode, testFlag, orIdent, synchroCheck, interlockCheck, -1);
    }

    /**
     * Variante con ctlNum explícito. Si ctlNumOverride >= 0 se usa ese valor (imprescindible
     * para el SBO enhanced, donde SBOw y Oper deben llevar el MISMO ctlNum); si es -1 se
     * autoincrementa el contador interno.
     */
    private void fillControlStructure(FcModelNode operNode, boolean testFlag, String orIdent,
                                       boolean synchroCheck, boolean interlockCheck,
                                       int ctlNumOverride) {
        fillControlStructure(operNode, testFlag, orIdent, synchroCheck, interlockCheck,
                             ctlNumOverride, controlOrCat);
    }

    /**
     * Variante con orCat explícito. Necesaria en el SBO de dos pasos: el OPERATE debe repetir
     * el mismo origin del SELECT aunque el usuario haya cambiado el selector entremedio.
     */
    private void fillControlStructure(FcModelNode operNode, boolean testFlag, String orIdent,
                                       boolean synchroCheck, boolean interlockCheck,
                                       int ctlNumOverride, int orCat) {
        if (operNode.getChildren() == null) return;
        for (ModelNode child : operNode.getChildren()) {
            String name = child.getName();
            if ("origin".equals(name)) {
                if (child.getChildren() == null) continue;
                for (ModelNode oc : child.getChildren()) {
                    if ("orCat".equals(oc.getName())) {
                        // orCat configurable (default 3 = remote-control). Segun el modelo puede
                        // ser BdaInt8U o BdaInt8 (enum INT8): antes solo se cubria BdaInt8U y
                        // quedaba en 0 (= "not-supported"), lo que NARI rechaza con
                        // addCause=Not-supported.
                        // El nivel correcto depende de la autoridad de mando configurada en el
                        // IED: los SIPROTEC 5 con autoridad "Estacion" exigen 2 (station-control)
                        // y rechazan el 3, y viceversa.
                        if (oc instanceof BdaInt8U) {
                            ((BdaInt8U) oc).setValue((short) orCat);
                        } else if (oc instanceof BasicDataAttribute) {
                            setBasicDataAttributeValue((BasicDataAttribute) oc,
                                String.valueOf(orCat));
                        }
                    } else if ("orIdent".equals(oc.getName()) && oc instanceof BdaOctetString) {
                        byte[] b = (orIdent != null && !orIdent.isEmpty())
                            ? orIdent.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                            : new byte[0];
                        ((BdaOctetString) oc).setValue(b);
                    }
                }
            } else if ("ctlNum".equals(name) && ordinalDeBda(child) != null) {
                // Cualquier ancho: el ctlNum es lo que correlaciona el SBOw con el Oper en
                // SBO reforzado. Si el instanceof no matchea, no se escribe y el segundo
                // paso llega con un numero que el IED no espera.
                int valorCtlNum;
                if (ctlNumOverride >= 0) {
                    valorCtlNum = ctlNumOverride & 0xFF;
                } else {
                    ctlNumCounter = (ctlNumCounter + 1) & 0xFF;
                    valorCtlNum = ctlNumCounter;
                }
                // El cast fijo a BdaInt8U reventaba si el guard dejaba pasar otro ancho: se
                // escribe por el camino que ya resuelve el tipo.
                if (child instanceof BdaInt8U) ((BdaInt8U) child).setValue((short) valorCtlNum);
                else if (child instanceof BasicDataAttribute)
                    setBasicDataAttributeValue((BasicDataAttribute) child, String.valueOf(valorCtlNum));
            } else if ("T".equals(name) && child instanceof BdaTimestamp) {
                ((BdaTimestamp) child).setCurrentTime();
            } else if ("Test".equals(name) && child instanceof BdaBoolean) {
                ((BdaBoolean) child).setValue(testFlag);
            } else if ("Check".equals(name) && child instanceof BdaCheck) {
                ((BdaCheck) child).setSynchrocheck(synchroCheck);
                ((BdaCheck) child).setInterlockCheck(interlockCheck);
            }
        }
    }

    /**
     * Lee el nodo LastApplError del DO (si existe) y retorna una descripción textual.
     * Se llama tras un control fallido para obtener la causa específica del IED.
     */
    private String readLastApplError(FcModelNode operNode) {
        ApplError ae = readApplError(operNode);
        return ae != null ? ae.raw : null;
    }

    /**
     * Igual que {@link #readLastApplError} pero además extrae el AddCause numérico
     * (IEC 61850-7-3 Tabla 9), que es lo que permite dar un diagnóstico concreto del rechazo.
     */
    private ApplError readApplError(FcModelNode operNode) {
        if (serverModel == null || association == null) return null;
        try {
            String operRef = operNode.getReference().toString(); // "LD/LN.DO.Oper"
            // LastApplError es un DO a NIVEL DE LN (LD/LN.LastApplError), no bajo el DO de
            // control. Se prueba primero el nivel LN y, como respaldo, el nivel DO.
            java.util.List<String> candidates = new java.util.ArrayList<>();
            int firstDot = operRef.indexOf('.');
            if (firstDot > 0) candidates.add(operRef.substring(0, firstDot) + ".LastApplError"); // LN
            int lastDot = operRef.lastIndexOf('.');
            if (lastDot > 0) candidates.add(operRef.substring(0, lastDot) + ".LastApplError");   // DO (fallback)

            for (String ref : candidates) {
                for (Fc fc : new Fc[]{Fc.CO, Fc.ST, Fc.MX, Fc.SP, Fc.CF, Fc.DC}) {
                    ModelNode laeNode = serverModel.findModelNode(ref, fc);
                    if (!(laeNode instanceof FcModelNode)) continue;
                    try { association.getDataValues((FcModelNode) laeNode); } catch (Exception ignore) {}
                    StringBuilder sb = new StringBuilder();
                    int addCause = -1;
                    if (laeNode.getChildren() != null) {
                        for (ModelNode child : laeNode.getChildren()) {
                            if ("origin".equals(child.getName())) continue; // estructura, no informativa
                            if ("AddCause".equalsIgnoreCase(child.getName())
                                    && child instanceof BasicDataAttribute) {
                                addCause = getIntValue((BasicDataAttribute) child);
                            }
                            String v = formatValue(child);
                            if (v != null && !v.isEmpty()) {
                                sb.append(child.getName()).append("=").append(v).append(" ");
                            }
                        }
                    }
                    if (sb.length() > 0) return new ApplError(sb.toString().trim(), addCause);
                }
            }
        } catch (Exception ignore) {}
        return null;
    }

    // ==================== PREFLIGHT DE CONTROL ====================

    /**
     * Una condición del IED que puede impedir que la orden prospere.
     * {@code blocking=true} indica que, tal como está, el IED va a rechazar el comando.
     */
    public static class PreflightCheck {
        public final String reference;   // referencia leída, p.ej. "LD/CSWI1.Loc.stVal"
        public final String labelKey;    // clave i18n del nombre legible del chequeo
        public final String value;       // valor leído, ya formateado
        public final boolean blocking;   // true = va a rechazar la orden
        public final String hintKey;     // clave i18n de la explicación; null si no aplica

        /** Argumento opcional de la clave i18n, para etiquetas que nombran al aparato. */
        public final String labelArg;

        PreflightCheck(String reference, String labelKey, String value,
                       boolean blocking, String hintKey) {
            this(reference, labelKey, value, blocking, hintKey, null);
        }

        PreflightCheck(String reference, String labelKey, String value,
                       boolean blocking, String hintKey, String labelArg) {
            this.reference = reference; this.labelKey = labelKey; this.value = value;
            this.blocking = blocking; this.hintKey = hintKey; this.labelArg = labelArg;
        }
        public String label() {
            return (labelArg == null) ? I18n.t(labelKey) : I18n.t(labelKey, labelArg);
        }
        public String hint()  {
            if (hintKey == null) return null;
            String t = I18n.t(hintKey);
            return (t == null || t.equals(hintKey)) ? null : t;
        }
    }

    /**
     * Lee del IED las condiciones que gobiernan la aceptación de una orden de mando y
     * reporta cuáles la bloquearían, ANTES de enviar el SELECT/OPERATE.
     *
     * Se inspeccionan (los que existan en el modelo; los ausentes se omiten en silencio):
     *   - Mod / Beh / Health del LN de control y del LLN0 de su Logical Device
     *   - Loc, LocKey, LocSta del LN de control  → autoridad de mando (local/estación/remoto)
     *   - EnaOpn / EnaCls del CILO del mismo LD  → enclavamiento
     *   - BlkOpn / BlkCls del LN de control      → bloqueo de apertura/cierre
     *
     * @param operNode nodo Oper del DO de control
     * @param ctlValStr valor que se pretende comandar; se usa para elegir entre los chequeos
     *                  de apertura y los de cierre. Puede ser null (se evalúan ambos).
     */
    public java.util.List<PreflightCheck> preflightControl(FcModelNode operNode, String ctlValStr) {
        java.util.List<PreflightCheck> out = new java.util.ArrayList<>();
        if (serverModel == null || association == null || operNode == null) return out;

        String operRef = operNode.getReference().toString();       // "LD/LN.DO.Oper"
        int slash = operRef.indexOf('/');
        int firstDot = operRef.indexOf('.');
        if (slash < 0 || firstDot < 0) return out;
        String ldName = operRef.substring(0, slash);               // "LD"
        String lnRef  = operRef.substring(0, firstDot);            // "LD/LN"

        // ¿Es una orden de apertura o de cierre? (false/off → abrir; true/on → cerrar)
        Boolean closing = null;
        if (ctlValStr != null) {
            String v = ctlValStr.trim().toLowerCase();
            if (v.equals("true") || v.equals("on") || v.equals("1"))       closing = Boolean.TRUE;
            else if (v.equals("false") || v.equals("off") || v.equals("0")) closing = Boolean.FALSE;
        }

        // ── Modo / comportamiento / salud, en el LN de control y en el LLN0 del LD ──
        for (String owner : new String[]{lnRef, ldName + "/LLN0"}) {
            boolean isLn0 = owner.endsWith("/LLN0");
            addEnumCheck(out, owner + ".Beh.stVal",
                isLn0 ? "ctl.pre.beh.ld" : "ctl.pre.beh.ln",
                MOD_BEH_MAP, new int[]{1}, "ctl.pre.hint.beh");
            addEnumCheck(out, owner + ".Health.stVal",
                isLn0 ? "ctl.pre.health.ld" : "ctl.pre.health.ln",
                HEALTH_MAP, new int[]{1, 2}, "ctl.pre.hint.health");
        }

        // ── Autoridad de mando ──
        addBoolCheck(out, lnRef + ".Loc.stVal",    "ctl.pre.loc",    false, "ctl.pre.hint.loc");
        addBoolCheck(out, lnRef + ".LocKey.stVal", "ctl.pre.lockey", false, "ctl.pre.hint.lockey");

        // ── Autoridad y bloqueo a nivel de vano (CBAY) ──
        // En algunas familias la autoridad de mando no está en el LN que se opera sino en un
        // nodo de vano, a otro nivel: el CSWI no declara Loc y el preflight lo omitía en
        // silencio —correcto ante un atributo ausente— informando menos condiciones de las
        // que realmente gobiernan la orden. Se busca el CBAY del mismo Logical Device.
        for (String bay : findLnRefsByClass(ldName, "CBAY")) {
            addBoolCheck(out, bay + ".Loc.stVal",    "ctl.pre.loc.bay", false, "ctl.pre.hint.loc.bay");
            addBoolCheck(out, bay + ".BlkCmd.stVal", "ctl.pre.blkcmd",  false, "ctl.pre.hint.blkcmd");
            // Rem se informa sin marcarlo bloqueante: que el vano declare el mando remoto
            // deshabilitado es dato para quien opera, pero no está verificado que sea
            // exactamente el complemento de Loc en todas las implementaciones, y marcar
            // bloqueante lo que no se comprobó es lo que llena de falsas alarmas el informe.
            PreflightCheck rem = readBool(bay + ".Rem.stVal", "ctl.pre.rem", null, null);
            if (rem != null) out.add(rem);
        }
        // LocSta=true significa autoridad a nivel ESTACIÓN: entonces el orCat correcto es 2,
        // no 3 (remote-control). Solo se marca como bloqueante si no coincide con el orCat actual.
        PreflightCheck locSta = readBool(lnRef + ".LocSta.stVal", "ctl.pre.locsta",
            /*blockingWhen*/ null, null);
        if (locSta != null) {
            boolean staOn = "true".equalsIgnoreCase(locSta.value);
            boolean mismatch = (staOn && controlOrCat != 2) || (!staOn && controlOrCat == 2);
            out.add(new PreflightCheck(locSta.reference, "ctl.pre.locsta", locSta.value,
                mismatch, mismatch ? "ctl.pre.hint.locsta" : null));
        }

        // ── Enclavamiento y bloqueos: se lee todo el LD, pero sólo bloquea lo del aparato ──
        // No se sacan lecturas: en un modelo de miles de nodos son baratas y la información
        // sirve. Lo que cambia es la clasificación — el CILO de un vecino responde «¿se puede
        // mover el vecino?», que no es la pregunta, así que pasa a contexto informativo. Así
        // nada se oculta y el cambio es reversible mirando una sola línea.
        GrupoAparato grupo = agruparPorAparato(ldName, lnRef);

        for (String cilo : findLnRefsByClass(ldName, "CILO")) {
            boolean propio = (grupo == null) || grupo.cilos.contains(cilo);
            // blockingWhenFalse: TRUE para el propio (EnaXxx=false bloquea); null para el
            // vecino, que es "informar sin marcar nunca". Pasar FALSE seria decir "bloquea
            // cuando es true", que es exactamente al reves.
            Boolean bloquea = propio ? Boolean.TRUE : null;
            String etiqueta = propio ? null : "ctl.pre.ctx.ena";
            String pista    = propio ? "ctl.pre.hint.ena" : null;
            if (closing == null || closing == Boolean.FALSE)
                agregar(out, readBool(cilo + ".EnaOpn.stVal",
                        propio ? "ctl.pre.enaopn" : etiqueta, bloquea, pista),
                        propio ? null : nombreDeAparato(cilo));
            if (closing == null || closing == Boolean.TRUE)
                agregar(out, readBool(cilo + ".EnaCls.stVal",
                        propio ? "ctl.pre.enacls" : etiqueta, bloquea, pista),
                        propio ? null : nombreDeAparato(cilo));
        }

        // ── Bloqueo explícito de apertura/cierre ──
        // Viven en el aparato (XCBR/XSWI), no en el CSWI que se opera: ni el modelo
        // SIPROTEC 4 (Ed.1) ni el SIPROTEC 5 los declaran en CSWI. Se mira igual el LN
        // operado, por si se opera el XCBR directamente o el equipo sí los expone ahí.
        java.util.LinkedHashSet<String> blkOwners = new java.util.LinkedHashSet<>();
        blkOwners.add(lnRef);
        blkOwners.addAll(findLnRefsByClass(ldName, "XCBR"));
        blkOwners.addAll(findLnRefsByClass(ldName, "XSWI"));
        for (String owner : blkOwners) {
            boolean propio = (grupo == null) || owner.equals(lnRef) || grupo.aparatos.contains(owner);
            Boolean bloquea = propio ? Boolean.FALSE : null;   // FALSE = bloquea cuando es true
            String pista = propio ? "ctl.pre.hint.blk" : null;
            String arg   = propio ? null : nombreDeAparato(owner);
            if (closing == null || closing == Boolean.FALSE)
                agregar(out, readBool(owner + ".BlkOpn.stVal",
                        propio ? "ctl.pre.blkopn" : "ctl.pre.ctx.blk", bloquea, pista), arg);
            if (closing == null || closing == Boolean.TRUE)
                agregar(out, readBool(owner + ".BlkCls.stVal",
                        propio ? "ctl.pre.blkcls" : "ctl.pre.ctx.blk", bloquea, pista), arg);
        }

        // ── Posición actual = posición comandada ──
        // Un equipo rechaza la orden si el aparato ya está donde se lo quiere llevar.
        // En posición intermedia o inválida no se evalúa: ahí la maniobra sí tiene sentido.
        PreflightCheck setActual = checkSetEqualsActual(operRef, ctlValStr);
        if (setActual != null) out.add(setActual);

        // ── Estado del vano: posición de los aparatos vecinos ──
        // Es el control compensatorio de haber acotado el enclavamiento al CILO propio. Al
        // hacerlo se confía en que la ecuación del IED contempla a los vecinos —una tierra
        // cerrada debe impedir el cierre del interruptor, y eso lo resuelve el equipo—. Si
        // una ingeniería tuviera esa ecuación incompleta, ya no lo agarraríamos de rebote.
        // Mostrar la posición real de los vecinos es lo que cubre ese hueco.
        //
        // El rol de cada uno sale de la clase del LN y de SwTyp, que son normativos. NUNCA
        // del número del prefijo: está medido que los conjuntos de prefijos no coinciden
        // entre ingenierías. Si SwTyp no se puede leer, se muestra el nombre pelado.
        if (grupo != null) {
            for (String vecino : aparatosVecinos(ldName, grupo)) {
                PreflightCheck pos = readPos(vecino + ".Pos.stVal", "ctl.pre.ctx.pos");
                if (pos != null) out.add(new PreflightCheck(pos.reference, pos.labelKey, pos.value,
                        false, null, nombreDeAparato(vecino)));
            }
        }

        return out;
    }

    /** Agrega el chequeo si se pudo leer, con un argumento opcional para su etiqueta. */
    private void agregar(java.util.List<PreflightCheck> out, PreflightCheck c, String labelArg) {
        if (c == null) return;
        out.add(labelArg == null ? c
                : new PreflightCheck(c.reference, c.labelKey, c.value, c.blocking, c.hintKey, labelArg));
    }

    /**
     * Nombre legible del aparato al que pertenece un LN: el prefijo más lo que declara el
     * modelo sobre su tipo. "Q8XSWI1" con SwTyp=3 → "Q8 (seccionador de puesta a tierra)".
     * Si el tipo no se puede leer, sólo el prefijo: no se inventa el rol.
     */
    String nombreDeAparato(String lnRef) {
        String lnName = lnRef.substring(lnRef.indexOf('/') + 1);
        String prefijo = prefijoDeLn(lnName);
        if (prefijo == null || prefijo.isEmpty()) prefijo = lnName;

        // XCBR es interruptor por definición de la norma; no hace falta preguntarle nada más.
        if (claseDeLn(lnName, "XCBR")) return prefijo + " (" + I18n.t("swtyp.breaker") + ")";

        Integer swTyp = readEnumOrdinal(lnRef + ".SwTyp.stVal");
        if (swTyp == null) return prefijo;
        String clave;
        switch (swTyp) {
            case 1:  clave = "swtyp.1"; break;
            case 2:  clave = "swtyp.2"; break;
            case 3:  clave = "swtyp.3"; break;
            case 4:  clave = "swtyp.4"; break;
            default: return prefijo;                 // enumerado fuera de catálogo: no inventar
        }
        return prefijo + " (" + I18n.t(clave) + ")";
    }

    /**
     * Valor entero de un BDA, sea cual sea el ancho con que la librería lo haya instanciado.
     *
     * Un atributo con bType="Enum" no llega siempre como BdaInt8: SclParser elige el ancho
     * según el rango de ordinales del EnumType. Preguntar por un ancho fijo es la fuente de
     * una familia entera de defectos de este proyecto —un desplegable que no aparece, un valor
     * que no se pinta, un mando que se rutea mal—, y todos comparten la misma forma: nada
     * falla, simplemente el instanceof no matchea y se sigue de largo con un valor por
     * defecto.
     *
     * @return el valor, o {@code null} si el nodo no es un entero — que es distinto de cero.
     */
    static Integer ordinalDeBda(ModelNode n) {
        if (n instanceof BdaInt8)   return (int) ((BdaInt8) n).getValue();
        if (n instanceof BdaInt8U)  return (int) ((BdaInt8U) n).getValue();
        if (n instanceof BdaInt16)  return (int) ((BdaInt16) n).getValue();
        if (n instanceof BdaInt16U) return ((BdaInt16U) n).getValue();
        if (n instanceof BdaInt32)  return ((BdaInt32) n).getValue();
        if (n instanceof BdaInt32U) return (int) ((BdaInt32U) n).getValue();
        if (n instanceof BdaInt64)  return (int) ((BdaInt64) n).getValue();
        return null;
    }

    /** Ordinal de un atributo enumerado, o null si no se puede leer. */
    private Integer readEnumOrdinal(String ref) {
        for (Fc fc : new Fc[]{Fc.ST, Fc.CF, Fc.DC}) {
            try {
                ModelNode n = serverModel.findModelNode(ref, fc);
                if (!(n instanceof FcModelNode)) continue;
                try { association.getDataValues((FcModelNode) n); } catch (Exception ignore) {}
                Integer v = ordinalDeBda(n);
                if (v != null) return v;
            } catch (Exception ignore) {}
        }
        return null;
    }

    /** Posición de un aparato, normalizada, para mostrarla como contexto. */
    private PreflightCheck readPos(String ref, String labelKey) {
        try {
            ModelNode n = serverModel.findModelNode(ref, Fc.ST);
            if (!(n instanceof FcModelNode)) return null;
            try { association.getDataValues((FcModelNode) n); } catch (Exception ignore) {}
            String v;
            if (n instanceof BdaDoubleBitPos)   v = formatDoubleBitPos((BdaDoubleBitPos) n);
            else if (n instanceof BdaBoolean)   v = ((BdaBoolean) n).getValue() ? "on" : "off";
            else return null;
            return new PreflightCheck(ref, labelKey, v, false, null);
        } catch (Exception ignore) {
            return null;
        }
    }

    /**
     * ¿El aparato ya está en la posición que se pretende comandar?
     *
     * Es un rechazo que no depende del enclavamiento —los equipos lo aplican también con
     * la verificación desactivada— así que conviene anticiparlo: es gratis y evita leer un
     * código de error genérico cuando la causa es que no había nada que maniobrar.
     *
     * @return el chequeo, o {@code null} si no aplica (valor no binario, sin stVal legible,
     *         o posición intermedia/inválida, donde la comparación no tiene sentido)
     */
    private PreflightCheck checkSetEqualsActual(String operRef, String ctlValStr) {
        String wanted = normalizeOnOff(ctlValStr);
        if (wanted == null || serverModel == null || association == null) return null;
        try {
            int lastDot = operRef.lastIndexOf('.');
            if (lastDot < 0) return null;
            String doRef = operRef.substring(0, lastDot);

            // Sólo para posición de aparato. En una orden de pulso —reposición de LED,
            // disparo por GGIO— que el valor ya coincida no impide nada, y avisar ahí
            // sería una falsa alarma.
            int slash = operRef.indexOf('/');
            int firstDot = operRef.indexOf('.');
            if (slash < 0 || firstDot < 0) return null;
            String lnUp = operRef.substring(slash + 1, firstDot).toUpperCase();
            String doName = doRef.substring(firstDot + 1).toUpperCase();
            boolean isSwitchPos = doName.equals("POS")
                || lnUp.contains("CSWI") || lnUp.contains("XCBR") || lnUp.contains("XSWI");
            if (!isSwitchPos) return null;
            ModelNode stDo = serverModel.findModelNode(doRef, Fc.ST);
            ModelNode stVal = (stDo != null) ? stDo.getChild("stVal") : null;
            if (!(stVal instanceof FcModelNode) || !(stVal instanceof BasicDataAttribute)) return null;
            try { association.getDataValues((FcModelNode) stVal); } catch (Exception ignore) {}
            String actual = normalizeStVal(stVal);
            if (actual == null) return null;
            if (!actual.equals("on") && !actual.equals("off")) return null;  // intermediate / bad
            boolean same = actual.equals(wanted);
            return new PreflightCheck(doRef + ".stVal", "ctl.pre.setactual", actual,
                                      same, same ? "ctl.pre.hint.setactual" : null);
        } catch (Exception ignore) {}
        return null;
    }

    /**
     * ¿El enclavamiento del IED está bloqueando la maniobra que se pretende comandar?
     *
     * Lectura puntual del CILO del mismo Logical Device: sólo el permiso que corresponde al
     * sentido comandado ({@code EnaCls} para cerrar, {@code EnaOpn} para abrir). Es mucho más
     * liviano que {@link #preflightControl} porque sirve para decidir en el momento de enviar.
     *
     * Se lee del modelo y NO depende de los bits {@code Check} de la orden, que es justamente
     * el punto: un IED puede aceptar la maniobra si el cliente no pide la verificación de
     * enclavamiento, aunque el CILO no la habilite.
     *
     * @return {@code TRUE} si el enclavamiento no habilita la maniobra, {@code FALSE} si la
     *         habilita, y {@code null} si el modelo no expone el CILO o no se pudo leer.
     */
    public Boolean interlockBlocking(FcModelNode operNode, String ctlValStr) {
        if (serverModel == null || association == null || operNode == null) return null;
        String operRef = operNode.getReference().toString();
        int slash = operRef.indexOf('/');
        if (slash < 0) return null;
        String ldName = operRef.substring(0, slash);

        // Sentido comandado: true/on/1 → cerrar; false/off/0 → abrir.
        Boolean closing = null;
        if (ctlValStr != null) {
            String v = ctlValStr.trim().toLowerCase();
            if (v.equals("true") || v.equals("on") || v.equals("1"))        closing = Boolean.TRUE;
            else if (v.equals("false") || v.equals("off") || v.equals("0")) closing = Boolean.FALSE;
        }
        if (closing == null) return null;      // sin sentido definido no hay permiso que mirar

        String daName = closing ? "EnaCls" : "EnaOpn";
        Boolean result = null;

        // Sólo el CILO del aparato que se opera. El CILO del vecino responde «¿se puede
        // mover el vecino?», que es otra pregunta: en un vano energizado el de la puesta a
        // tierra está en false con toda razón, y con el alcance por LD eso disparaba el aviso
        // de bypass en cada cierre. Lo que sí captura la preocupación real —¿puedo cerrar el
        // interruptor con la tierra puesta?— es el CILO del propio interruptor, donde el IED
        // resuelve la ecuación incluyendo la posición de la tierra. Ése es el que se lee.
        //
        // Si no se puede agrupar con confianza se cae al Logical Device entero, que es el
        // comportamiento anterior y el correcto cuando el LD es el aparato.
        int lnDot = operRef.indexOf('.');
        GrupoAparato grupo = (lnDot > 0) ? agruparPorAparato(ldName, operRef.substring(0, lnDot)) : null;
        java.util.List<String> cilos = (grupo != null && !grupo.cilos.isEmpty())
                ? grupo.cilos : findLnRefsByClass(ldName, "CILO");

        for (String cilo : cilos) {
            PreflightCheck c = readBool(cilo + "." + daName + ".stVal",
                                        closing ? "ctl.pre.enacls" : "ctl.pre.enaopn",
                                        Boolean.TRUE, null);
            if (c == null) continue;
            boolean permite = "true".equalsIgnoreCase(c.value);
            if (!permite) return Boolean.TRUE;  // basta un CILO que no habilite
            result = Boolean.FALSE;
        }
        return result;
    }

    // ───────────────────────────────────────────────────────────────────────────────────────
    //  Agrupamiento por aparato
    // ───────────────────────────────────────────────────────────────────────────────────────

    /**
     * Los nodos lógicos que pertenecen al mismo aparato que el LN operado.
     *
     * POR QUÉ HACE FALTA. El enclavamiento y los bloqueos se acotaban por Logical Device.
     * Eso es correcto cuando el LD *es* el aparato —hay ingenierías con un LD por vano— pero
     * hay familias donde varios aparatos comparten un mismo LD: interruptor, seccionadores y
     * puesta a tierra, todos en el mismo. Ahí, operar el interruptor leía el CILO de todos
     * ellos.
     *
     * En el preflight eso sólo ensuciaba una lista, porque es informativo. Pero
     * {@link #interlockBlocking} tiene el mismo alcance y SÍ gobierna comportamiento: corta
     * en el primer CILO que no habilite, y el de una puesta a tierra en un vano energizado
     * está en `false` con toda razón —cerrar la tierra ahí está prohibido—. Resultado: el
     * aviso de «vas a operar sin verificación de enclavamiento» saltaba en cada cierre.
     *
     * Y ése es justo el diálogo que no puede volverse rutina: si aparece siempre, el
     * operador aprende a darle a continuar sin leer, y el día que el aviso es real ya no lo
     * ve. Eso es peor que no tenerlo.
     *
     * POR QUÉ EL PREFIJO, Y QUÉ SE AFIRMA CON ÉL. Hay que separar dos preguntas:
     *
     *   - «¿qué aparato es?» → se responde con la clase del LN y con {@code XSWI.SwTyp}, que
     *     es normativo y portable. NUNCA con el número del prefijo: está medido sobre dos
     *     ingenierías distintas que los conjuntos de prefijos no coinciden.
     *   - «¿qué LN son del mismo aparato?» → acá sí entra el prefijo, y es legítimo: agrupar
     *     `Q8CSWI1` con `Q8XSWI1` y `Q8CILO1` no afirma que 8 signifique tierra, sólo que
     *     los tres hablan del mismo aparato. Es una afirmación mucho más débil.
     *
     * No es un atajo: el vínculo normativo entre LN y equipo primario vive en la sección
     * `Substation` del SCL, y al conectarse en vivo por MMS esa sección no existe — el equipo
     * entrega el modelo de datos, no el unifilar. El prefijo es el único hilo disponible en
     * el momento en que hay que decidir.
     *
     * @return el grupo del aparato operado, o {@code null} si no se puede agrupar con
     *         confianza, en cuyo caso el llamador usa el Logical Device entero, que es el
     *         comportamiento anterior.
     */
    GrupoAparato agruparPorAparato(String ldName, String lnRef) {
        try {
            if (serverModel == null) return null;
            String lnName = lnRef.substring(lnRef.indexOf('/') + 1);
            String prefijo = prefijoDeLn(lnName);
            if (prefijo == null || prefijo.isEmpty()) return null;   // sin prefijo: no agrupar

            ModelNode ld = serverModel.getChild(ldName);
            if (ld == null || ld.getChildren() == null) return null;

            // Si en el LD hay un solo aparato, agrupar no cambia nada y el alcance por LD ya
            // es correcto. Se mantiene el camino anterior para no alterar lo que funciona.
            java.util.Set<String> prefijosDeAparato = new java.util.TreeSet<>();
            for (ModelNode ln : ld.getChildren()) {
                String n = ln.getName();
                if (n == null) continue;
                if (claseDeLn(n, "CSWI") || claseDeLn(n, "XCBR") || claseDeLn(n, "XSWI")) {
                    String p = prefijoDeLn(n);
                    if (p != null && !p.isEmpty()) prefijosDeAparato.add(p);
                }
            }
            if (prefijosDeAparato.size() < 2) return null;

            GrupoAparato g = new GrupoAparato(prefijo);
            for (ModelNode ln : ld.getChildren()) {
                String n = ln.getName();
                if (n == null || !prefijo.equals(prefijoDeLn(n))) continue;   // prefijo EXACTO
                String ref = ldName + "/" + n;
                if (claseDeLn(n, "CILO")) g.cilos.add(ref);
                else if (claseDeLn(n, "XCBR") || claseDeLn(n, "XSWI")) g.aparatos.add(ref);
                else if (claseDeLn(n, "CSWI")) g.cswis.add(ref);
            }

            // El grupo tiene que cerrar: un aparato y a lo sumo un CILO. Si no cierra, el
            // agrupamiento no es de fiar y es preferible el alcance de siempre.
            if (g.aparatos.size() != 1 || g.cilos.size() > 1) return null;
            return g;
        } catch (Exception ignore) {
            return null;
        }
    }

    /** Los LN de un LD que son aparatos (XCBR/XSWI) y NO pertenecen al grupo dado. */
    java.util.List<String> aparatosVecinos(String ldName, GrupoAparato propio) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            ModelNode ld = serverModel.getChild(ldName);
            if (ld == null || ld.getChildren() == null) return out;
            for (ModelNode ln : ld.getChildren()) {
                String n = ln.getName();
                if (n == null) continue;
                if (!claseDeLn(n, "XCBR") && !claseDeLn(n, "XSWI")) continue;
                String p = prefijoDeLn(n);
                if (propio != null && propio.prefijo.equals(p)) continue;
                out.add(ldName + "/" + n);
            }
        } catch (Exception ignore) {}
        return out;
    }

    /** ¿El nombre de LN es de esta clase? Exige la clase seguida sólo de dígitos. */
    static boolean claseDeLn(String lnName, String lnClass) {
        int i = lnName.toUpperCase().indexOf(lnClass);
        if (i < 0) return false;
        String resto = lnName.substring(i + lnClass.length());
        if (resto.isEmpty()) return true;
        for (int k = 0; k < resto.length(); k++) if (!Character.isDigit(resto.charAt(k))) return false;
        return true;
    }

    /**
     * Lo que precede a la clase en el nombre del LN: "Q0CSWI1" → "Q0", "CSWI1" → "".
     * Devuelve null si el nombre no corresponde a ninguna de las clases de aparato.
     */
    static String prefijoDeLn(String lnName) {
        if (lnName == null) return null;
        String up = lnName.toUpperCase();
        for (String cls : new String[]{"CSWI", "XCBR", "XSWI", "CILO"}) {
            int i = up.indexOf(cls);
            if (i < 0) continue;
            String resto = lnName.substring(i + cls.length());
            boolean soloDigitos = true;
            for (int k = 0; k < resto.length(); k++)
                if (!Character.isDigit(resto.charAt(k))) { soloDigitos = false; break; }
            if (soloDigitos) return lnName.substring(0, i);
        }
        return null;
    }

    /** Los LN de un mismo aparato dentro de un Logical Device. */
    static class GrupoAparato {
        final String prefijo;
        final java.util.List<String> cswis    = new java.util.ArrayList<>();
        final java.util.List<String> aparatos = new java.util.ArrayList<>();  // XCBR o XSWI
        final java.util.List<String> cilos    = new java.util.ArrayList<>();
        GrupoAparato(String prefijo) { this.prefijo = prefijo; }
    }

    /** Referencias "LD/prefijoCLASEinst" de todos los LN de una clase dentro de un LD. */
    private java.util.List<String> findLnRefsByClass(String ldName, String lnClass) {
        java.util.List<String> refs = new java.util.ArrayList<>();
        try {
            ModelNode ld = serverModel.getChild(ldName);
            if (ld == null || ld.getChildren() == null) return refs;
            for (ModelNode ln : ld.getChildren()) {
                String n = ln.getName();
                // El nombre del LN es prefijo+clase+instancia; basta con que contenga la clase.
                if (n != null && n.toUpperCase().contains(lnClass)) refs.add(ldName + "/" + n);
            }
        } catch (Exception ignore) {}
        return refs;
    }

    /** Lee un DA booleano y lo agrega a la lista si existe en el modelo. */
    private void addBoolCheck(java.util.List<PreflightCheck> out, String ref, String labelKey,
                              boolean blockingWhenFalse, String hintKey) {
        PreflightCheck c = readBool(ref, labelKey, blockingWhenFalse, hintKey);
        if (c != null) out.add(c);
    }

    /**
     * @param blockingWhenFalse si es null no se evalúa como bloqueante (solo informativo);
     *                          true  → bloquea cuando el valor es false (permisos: EnaOpn/EnaCls);
     *                          false → bloquea cuando el valor es true  (bloqueos: Loc/BlkOpn/BlkCls).
     */
    private PreflightCheck readBool(String ref, String labelKey,
                                    Boolean blockingWhenFalse, String hintKey) {
        for (Fc fc : new Fc[]{Fc.ST, Fc.MX}) {
            try {
                ModelNode n = serverModel.findModelNode(ref, fc);
                if (!(n instanceof FcModelNode)) continue;
                try { association.getDataValues((FcModelNode) n); } catch (Exception ignore) {}
                if (!(n instanceof BdaBoolean)) continue;
                boolean v = ((BdaBoolean) n).getValue();
                boolean blocking = false;
                if (blockingWhenFalse != null) {
                    blocking = blockingWhenFalse ? !v : v;
                }
                return new PreflightCheck(ref, labelKey, String.valueOf(v),
                                          blocking, blocking ? hintKey : null);
            } catch (Exception ignore) {}
        }
        return null;
    }

    /**
     * Lee un DA entero enumerado y marca bloqueante si su valor no está entre los aceptables.
     *
     * Un valor que NO figura en el mapa del enumerado (típicamente 0, que no es válido ni para
     * Mod/Beh ni para Health) se trata como desconocido y NO se marca bloqueante: no se puede
     * distinguir "el equipo no lo implementa" de "está realmente mal", y marcarlo llenaría el
     * informe de falsas alarmas. Sólo bloquea un valor válido que esté fuera del conjunto
     * aceptable —por ejemplo Health=3 (Alarm) o Beh=5 (off)—, que sí es información real.
     */
    private void addEnumCheck(java.util.List<PreflightCheck> out, String ref, String labelKey,
                              Map<Integer, String> map, int[] okValues, String hintKey) {
        for (Fc fc : new Fc[]{Fc.ST, Fc.CF, Fc.SP}) {
            try {
                ModelNode n = serverModel.findModelNode(ref, fc);
                if (!(n instanceof FcModelNode)) continue;
                try { association.getDataValues((FcModelNode) n); } catch (Exception ignore) {}
                if (!(n instanceof BasicDataAttribute)) continue;
                int v = getIntValue((BasicDataAttribute) n);
                String name = map.get(v);
                if (name == null) {
                    // Valor fuera del enumerado: se informa sin marcarlo como bloqueante.
                    out.add(new PreflightCheck(ref, labelKey,
                            I18n.t("ctl.pre.unknownval", v), false, null));
                    return;
                }
                boolean ok = false;
                for (int okv : okValues) if (v == okv) { ok = true; break; }
                out.add(new PreflightCheck(ref, labelKey, name, !ok, !ok ? hintKey : null));
                return;
            } catch (Exception ignore) {}
        }
    }

    /**
     * Busca el nodo Cancel (FC=CO) como hermano de Oper dentro del mismo DO de control.
     * Retorna null si no existe en el modelo (IEDs con ctlModel=1/3 no lo incluyen).
     */
    private FcModelNode findCancelNode(FcModelNode operNode) {
        ModelNode parent = operNode.getParent();
        if (parent == null) return null;
        ModelNode cancel = parent.getChild("Cancel");
        if (cancel instanceof FcModelNode) return (FcModelNode) cancel;
        return null;
    }

    /**
     * Cancela un SELECT pendiente en el IED (aplica a ctlModel=2 y ctlModel=4).
     * Escribe al nodo Cancel (FC=CO, hermano de Oper) con los campos de identificación.
     *
     * Nota: según IEC 61850-7-2 §20.8, el ctlNum del CANCEL debería coincidir con el
     * del SELECT original. Esta implementación envía un ctlNum nuevo; si el IED es
     * estricto y rechaza, el SELECT expirará según su SBO_Timeout interno.
     *
     * @param operNode nodo Oper del DO de control
     * @param orIdent  identificador del operador (puede ser null)
     */
    public ControlResult cancelControl(FcModelNode operNode, String orIdent) throws IOException {
        if (!isConnected()) throw new IOException("Not connected");

        int ctlModel = getCtlModelValue(operNode);
        String ctlModelName = CTL_MODEL_MAP.getOrDefault(ctlModel, "unknown(" + ctlModel + ")");

        if (ctlModel != 2 && ctlModel != 4) {
            return ControlResult.fail(ctlModel, ctlModelName,
                "CANCEL solo aplica a ctlModel SBO (2 o 4); este nodo es: " + ctlModelName, null);
        }

        FcModelNode cancelNode = findCancelNode(operNode);
        if (cancelNode == null) {
            return ControlResult.fail(ctlModel, ctlModelName,
                "Nodo Cancel no encontrado en el modelo del IED", null);
        }

        // Poblar Cancel: origin + ctlNum + T; sin ctlVal (Cancel no altera el proceso).
        // Si hay una selección pendiente de este nodo (enhanced SBO), el CANCEL debe llevar
        // el MISMO ctlNum del SELECT (IEC 61850-7-2 §20.8); si no, se autoincrementa.
        PendingSelect ps = pendingSelect;
        if (ps != null && ps.operNode == operNode && ps.ctlNum >= 0) {
            fillControlStructure(cancelNode, ps.testFlag, orIdent, false, false, ps.ctlNum, ps.orCat);
        } else {
            fillControlStructure(cancelNode, false, orIdent);
        }

        try {
            association.setDataValues(cancelNode);
            System.out.println("[SBO] CANCEL enviado: " + operNode.getReference());
            if (ps != null && ps.operNode == operNode) pendingSelect = null;
            return ControlResult.ok(ctlModel, ctlModelName);
        } catch (ServiceError e) {
            ApplError ae = readApplError(operNode);
            System.out.println("[ERROR] CANCEL rechazado: ServiceError " + e.getErrorCode()
                + (ae != null ? " | LastApplError: " + ae.raw : ""));
            return ControlResult.failWith(ctlModel, ctlModelName,
                "CANCEL ServiceError: " + e.getErrorCode(), ae, e.getErrorCode());
        }
    }

    /**
     * Operación de control unificada: detecta ctlModel y ejecuta el flujo correcto.
     *
     * Flujo:
     *   ctlModel 0 (status-only) → error inmediato, no se envía nada al IED
     *   ctlModel 1 (direct-normal-security) → operate()
     *   ctlModel 2 (sbo-normal-security)    → select() → operate()
     *   ctlModel 3 (direct-enhanced-security) → operate() con campos enhanced
     *   ctlModel 4 (sbo-enhanced-security)  → selectWithValue() → operate()
     *     (iec61850bean select() acepta el Oper completo con ctlVal ya seteado,
     *      lo que equivale a SELECT-WITH-VALUE sobre el nodo SBOw)
     *
     * @param operNode      nodo Oper (FC=CO) obtenido del ServerModel
     * @param ctlValStr     valor de control como string ("true"/"false", "on"/"off", float, etc.)
     * @param testFlag      si true, el IED registra el evento pero no actúa en hardware
     * @param orIdent       identificador del operador (cadena libre, puede ser null)
     * @param synchroCheck  activar verificación de sincronismo (tensión, ángulo, frecuencia)
     * @param interlockCheck activar verificación de enclavamiento lógico del IED
     */
    public ControlResult operateControl(FcModelNode operNode, String ctlValStr,
                                         boolean testFlag, String orIdent,
                                         boolean synchroCheck, boolean interlockCheck) throws IOException {
        if (!isConnected()) throw new IOException("Not connected");

        int ctlModel = getCtlModelValue(operNode);
        String ctlModelName = CTL_MODEL_MAP.getOrDefault(ctlModel, "unknown(" + ctlModel + ")");

        if (ctlModel == 0) {
            return ControlResult.fail(ctlModel, ctlModelName,
                "Nodo status-only (ctlModel=0): no acepta comandos", null);
        }

        // iec61850bean: select()/operate() esperan el DO de control (padre de Oper);
        // internamente hacen getChild("SBO") / getChild("Oper").
        FcModelNode controlDo = (operNode.getParent() instanceof FcModelNode)
            ? (FcModelNode) operNode.getParent() : operNode;

        // ctlModel=4 (sbo-enhanced-security): iec61850bean 1.9.0 NO implementa el
        // select-with-value (su select() sólo LEE el atributo SBO del SBO normal, y su
        // operate() fuerza ctlNum=1). Se hace el handshake enhanced a mano.
        if (ctlModel == 4) {
            return operateEnhancedSbo(operNode, controlDo, ctlValStr, testFlag, orIdent,
                                      synchroCheck, interlockCheck, ctlModelName);
        }

        // Preparar estructura Oper completa (ctlModel 1/2/3)
        setOperCtlVal(operNode, ctlValStr);
        fillControlStructure(operNode, testFlag, orIdent, synchroCheck, interlockCheck);

        try {
            if (ctlModel == 2) {
                // SBO normal: beanit select() LEE el atributo SBO (VisibleString)
                System.out.println("[SBO] SELECT → " + operNode.getReference());
                boolean selected = association.select(controlDo);
                if (!selected) {
                    ApplError ae = readApplError(operNode);
                    System.out.println("[SBO] SELECT rechazado. LastApplError: " + (ae != null ? ae.raw : null));
                    return ControlResult.failWith(ctlModel, ctlModelName,
                        "SELECT rechazado por el IED", ae);
                }
                System.out.println("[SBO] SELECT aceptado. Enviando OPERATE...");
            }

            association.operate(controlDo);
            System.out.println("[OK] OPERATE ejecutado: " + operNode.getReference()
                + " = " + ctlValStr + (testFlag ? " [TEST MODE]" : ""));
            return ControlResult.ok(ctlModel, ctlModelName);

        } catch (ServiceError e) {
            ApplError ae = readApplError(operNode);
            System.out.println("[ERROR] OPERATE falló: ServiceError " + e.getErrorCode()
                + (ae != null ? " | LastApplError: " + ae.raw : ""));
            return ControlResult.failWith(ctlModel, ctlModelName,
                "ServiceError: " + e.getErrorCode(), ae, e.getErrorCode());
        }
    }

    /**
     * SBO enhanced (ctlModel=4) implementado a mano, porque iec61850bean 1.9.0 no lo soporta:
     * su select() sólo lee el atributo SBO (SBO normal) y su operate() fuerza ctlNum=1.
     *
     * Flujo IEC 61850-7-2 §20 (select-with-value / enhanced security):
     *   1. Escribir SBOw con {ctlVal, origin, ctlNum=N, T, Test, Check}  → SELECT-WITH-VALUE
     *   2. Escribir Oper con los MISMOS {ctlVal, origin, ctlNum=N, Test, Check} y T nuevo → OPERATE
     * ctlVal, origin, ctlNum, Test y Check deben COINCIDIR entre SBOw y Oper; sólo T difiere.
     * Se usa setDataValues() directo (no association.operate(), que reescribiría ctlNum=1).
     */
    private ControlResult operateEnhancedSbo(FcModelNode operNode, FcModelNode controlDo,
                                             String ctlValStr, boolean testFlag, String orIdent,
                                             boolean synchroCheck, boolean interlockCheck,
                                             String ctlModelName) throws IOException {
        ModelNode sbowNode = controlDo.getChild("SBOw");
        if (!(sbowNode instanceof FcModelNode)) {
            return ControlResult.fail(4, ctlModelName,
                "Nodo SBOw no encontrado: el modelo no expone select-with-value", null);
        }
        FcModelNode sbow = (FcModelNode) sbowNode;

        // ctlNum único para todo el ciclo SELECT+OPERATE
        ctlNumCounter = (ctlNumCounter + 1) & 0xFF;
        int ctlNum = ctlNumCounter;

        // 1) SELECT-WITH-VALUE: escribir SBOw
        setOperCtlVal(sbow, ctlValStr);
        fillControlStructure(sbow, testFlag, orIdent, synchroCheck, interlockCheck, ctlNum);
        try {
            System.out.println("[SBOe] SELECT-WITH-VALUE (SBOw ctlNum=" + ctlNum + ") → "
                + sbow.getReference());
            association.setDataValues(sbow);
            System.out.println("[SBOe] SELECT-WITH-VALUE aceptado. Enviando OPERATE...");
        } catch (ServiceError e) {
            ApplError ae = readApplError(operNode);
            System.out.println("[SBOe] SELECT-WITH-VALUE rechazado: ServiceError " + e.getErrorCode()
                + (ae != null ? " | LastApplError: " + ae.raw : ""));
            return ControlResult.failWith(4, ctlModelName,
                "SELECT-WITH-VALUE rechazado: ServiceError " + e.getErrorCode(), ae, e.getErrorCode());
        }

        // 2) OPERATE: escribir Oper con el MISMO ctlNum y T nuevo
        setOperCtlVal(operNode, ctlValStr);
        fillControlStructure(operNode, testFlag, orIdent, synchroCheck, interlockCheck, ctlNum);
        try {
            association.setDataValues(operNode);
            System.out.println("[OK] OPERATE (enhanced) ejecutado: " + operNode.getReference()
                + " = " + ctlValStr + (testFlag ? " [TEST MODE]" : ""));
            return ControlResult.ok(4, ctlModelName);
        } catch (ServiceError e) {
            ApplError ae = readApplError(operNode);
            System.out.println("[ERROR] OPERATE (enhanced) falló: ServiceError " + e.getErrorCode()
                + (ae != null ? " | LastApplError: " + ae.raw : ""));
            return ControlResult.failWith(4, ctlModelName,
                "ServiceError: " + e.getErrorCode(), ae, e.getErrorCode());
        }
    }

    /** Overload sin Check — equivale a Check=0 (sin verificaciones). */
    public ControlResult operateControl(FcModelNode operNode, String ctlValStr,
                                         boolean testFlag, String orIdent) throws IOException {
        return operateControl(operNode, ctlValStr, testFlag, orIdent, false, false);
    }

    // ==================== SBO MANUAL EN DOS PASOS (SELECT / EXECUTE) ====================

    /**
     * Estado de una selección SBO pendiente iniciada desde el diálogo (SELECT manual).
     * Conserva el ctlNum reservado y todos los campos del comando para que el OPERATE
     * posterior coincida exactamente con el SBOw (exigencia del enhanced SBO).
     */
    public static class PendingSelect {
        public final FcModelNode operNode;
        public final int ctlModel;
        public final int ctlNum;          // -1 en SBO normal (beanit maneja el ctlNum del Oper)
        public final String ctlVal;
        public final boolean testFlag, synchroCheck, interlockCheck;
        public final String orIdent;
        public final int orCat;           // origin.orCat usado en el SELECT: el OPERATE debe repetirlo
        public final long deadlineMs;     // instante de expiración = ahora + sboTimeout

        PendingSelect(FcModelNode operNode, int ctlModel, int ctlNum, String ctlVal,
                      boolean testFlag, boolean synchroCheck, boolean interlockCheck,
                      String orIdent, int orCat, long deadlineMs) {
            this.operNode = operNode; this.ctlModel = ctlModel; this.ctlNum = ctlNum;
            this.ctlVal = ctlVal; this.testFlag = testFlag; this.synchroCheck = synchroCheck;
            this.interlockCheck = interlockCheck; this.orIdent = orIdent; this.orCat = orCat;
            this.deadlineMs = deadlineMs;
        }
        public boolean isExpired() { return System.currentTimeMillis() > deadlineMs; }
        public long remainingMs()  { return Math.max(0, deadlineMs - System.currentTimeMillis()); }
    }

    public PendingSelect getPendingSelect() { return pendingSelect; }
    public void clearPendingSelect() { pendingSelect = null; }

    /**
     * Lee el sboTimeout [CF] del DO de control (ms) para dimensionar la cuenta regresiva.
     * Si el modelo no lo expone o no se puede leer, retorna el default (30000 ms).
     */
    public int getSboTimeoutMs(FcModelNode operNode) {
        final int DEFAULT = 30000;
        if (serverModel == null || association == null) return DEFAULT;
        try {
            String operRef = operNode.getReference().toString();
            int lastDot = operRef.lastIndexOf('.');
            if (lastDot < 0) return DEFAULT;
            String doRef = operRef.substring(0, lastDot);
            for (Fc fc : new Fc[]{Fc.CF, Fc.SP}) {
                ModelNode doNode = serverModel.findModelNode(doRef, fc);
                ModelNode to = (doNode != null) ? doNode.getChild("sboTimeout") : null;
                if (to instanceof FcModelNode) {
                    try { association.getDataValues((FcModelNode) to); } catch (Exception ignore) {}
                    String v = formatValue(to);
                    if (v != null) {
                        try {
                            long ms = Long.parseLong(v.replaceAll("[^0-9]", ""));
                            if (ms > 0) return (int) Math.min(ms, 600000);
                        } catch (NumberFormatException ignore) {}
                    }
                }
            }
        } catch (Exception ignore) {}
        return DEFAULT;
    }

    /**
     * Paso 1 del SBO manual: SELECT (reserva el objeto de control en el IED) sin operar aún.
     *   ctlModel 4 → SELECT-WITH-VALUE: escribe SBOw con ctlNum nuevo (se guarda para el OPERATE).
     *   ctlModel 2 → association.select() (lee el atributo SBO); el valor se prepara en Oper.
     * Solo aplica a modelos SBO (2/4). Guarda el estado en pendingSelect con su deadline.
     */
    public ControlResult selectControl(FcModelNode operNode, String ctlValStr, boolean testFlag,
                                        String orIdent, boolean synchroCheck, boolean interlockCheck,
                                        int sboTimeoutMs) throws IOException {
        if (!isConnected()) throw new IOException("Not connected");
        int ctlModel = getCtlModelValue(operNode);
        String ctlModelName = CTL_MODEL_MAP.getOrDefault(ctlModel, "unknown(" + ctlModel + ")");

        if (ctlModel != 2 && ctlModel != 4) {
            return ControlResult.fail(ctlModel, ctlModelName,
                "SELECT solo aplica a ctlModel SBO (2 o 4); este nodo es: " + ctlModelName, null);
        }

        FcModelNode controlDo = (operNode.getParent() instanceof FcModelNode)
            ? (FcModelNode) operNode.getParent() : operNode;
        long deadline = System.currentTimeMillis() + Math.max(1000, sboTimeoutMs);

        if (ctlModel == 4) {
            ModelNode sbowNode = controlDo.getChild("SBOw");
            if (!(sbowNode instanceof FcModelNode)) {
                return ControlResult.fail(4, ctlModelName,
                    "Nodo SBOw no encontrado: el modelo no expone select-with-value", null);
            }
            FcModelNode sbow = (FcModelNode) sbowNode;
            ctlNumCounter = (ctlNumCounter + 1) & 0xFF;
            int ctlNum = ctlNumCounter;
            setOperCtlVal(sbow, ctlValStr);
            fillControlStructure(sbow, testFlag, orIdent, synchroCheck, interlockCheck, ctlNum);
            try {
                System.out.println("[SBOe] SELECT-WITH-VALUE (SBOw ctlNum=" + ctlNum + ") → " + sbow.getReference());
                association.setDataValues(sbow);
            } catch (ServiceError e) {
                ApplError ae = readApplError(operNode);
                return ControlResult.failWith(4, ctlModelName,
                    "SELECT-WITH-VALUE rechazado: ServiceError " + e.getErrorCode(), ae, e.getErrorCode());
            }
            pendingSelect = new PendingSelect(operNode, 4, ctlNum, ctlValStr, testFlag,
                synchroCheck, interlockCheck, orIdent, controlOrCat, deadline);
            return ControlResult.ok(4, ctlModelName);
        } else { // ctlModel 2 (SBO normal)
            setOperCtlVal(operNode, ctlValStr);
            fillControlStructure(operNode, testFlag, orIdent, synchroCheck, interlockCheck);
            try {
                System.out.println("[SBO] SELECT → " + operNode.getReference());
                boolean selected = association.select(controlDo);
                if (!selected) {
                    ApplError ae = readApplError(operNode);
                    return ControlResult.failWith(2, ctlModelName, "SELECT rechazado por el IED", ae);
                }
            } catch (ServiceError e) {
                ApplError ae = readApplError(operNode);
                return ControlResult.failWith(2, ctlModelName,
                    "SELECT ServiceError: " + e.getErrorCode(), ae, e.getErrorCode());
            }
            pendingSelect = new PendingSelect(operNode, 2, -1, ctlValStr, testFlag,
                synchroCheck, interlockCheck, orIdent, controlOrCat, deadline);
            return ControlResult.ok(2, ctlModelName);
        }
    }

    /**
     * Paso 2 del SBO manual: EXECUTE (OPERATE) usando la selección pendiente.
     * Exige un pendingSelect vigente (no expirado) para el mismo nodo; usa el ctlNum y el
     * valor guardados en el SELECT para que Oper coincida con el SBOw. Limpia el estado al terminar.
     */
    public ControlResult executeControl(FcModelNode operNode) throws IOException {
        if (!isConnected()) throw new IOException("Not connected");
        int ctlModel = getCtlModelValue(operNode);
        String ctlModelName = CTL_MODEL_MAP.getOrDefault(ctlModel, "unknown(" + ctlModel + ")");

        PendingSelect ps = pendingSelect;
        if (ps == null || ps.operNode != operNode) {
            return ControlResult.fail(ctlModel, ctlModelName,
                "No hay un SELECT activo para este nodo. Presione Seleccionar (SBOw) primero.", null);
        }
        if (ps.isExpired()) {
            pendingSelect = null;
            return ControlResult.fail(ctlModel, ctlModelName,
                "El SELECT expiró (SBO timeout). Vuelva a seleccionar.", null);
        }

        FcModelNode controlDo = (operNode.getParent() instanceof FcModelNode)
            ? (FcModelNode) operNode.getParent() : operNode;

        if (ps.ctlModel == 4) {
            setOperCtlVal(operNode, ps.ctlVal);
            fillControlStructure(operNode, ps.testFlag, ps.orIdent, ps.synchroCheck, ps.interlockCheck, ps.ctlNum, ps.orCat);
            try {
                association.setDataValues(operNode);
                System.out.println("[OK] OPERATE (enhanced, 2-pasos) ejecutado: " + operNode.getReference()
                    + " = " + ps.ctlVal + (ps.testFlag ? " [TEST]" : ""));
                pendingSelect = null;
                return ControlResult.ok(4, ctlModelName);
            } catch (ServiceError e) {
                ApplError ae = readApplError(operNode);
                pendingSelect = null;
                return ControlResult.failWith(4, ctlModelName, "ServiceError: " + e.getErrorCode(), ae, e.getErrorCode());
            }
        } else { // ctlModel 2
            setOperCtlVal(operNode, ps.ctlVal);
            fillControlStructure(operNode, ps.testFlag, ps.orIdent, ps.synchroCheck, ps.interlockCheck, -1, ps.orCat);
            try {
                association.operate(controlDo);
                System.out.println("[OK] OPERATE (SBO, 2-pasos) ejecutado: " + operNode.getReference()
                    + " = " + ps.ctlVal + (ps.testFlag ? " [TEST]" : ""));
                pendingSelect = null;
                return ControlResult.ok(2, ctlModelName);
            } catch (ServiceError e) {
                ApplError ae = readApplError(operNode);
                pendingSelect = null;
                return ControlResult.failWith(2, ctlModelName, "ServiceError: " + e.getErrorCode(), ae, e.getErrorCode());
            }
        }
    }

    /**
     * Resultado de la verificación de posición post-control (lectura de stVal).
     */
    public static class FeedbackResult {
        public final boolean verifiable;  // el DO tiene stVal [ST] y el comando es on/off
        public final boolean confirmed;   // stVal alcanzó el valor comandado dentro del timeout
        public final String observed;     // último stVal leído (normalizado: on/off/intermediate/bad)
        public final long elapsedMs;

        FeedbackResult(boolean verifiable, boolean confirmed, String observed, long elapsedMs) {
            this.verifiable = verifiable;
            this.confirmed = confirmed;
            this.observed = observed;
            this.elapsedMs = elapsedMs;
        }
    }

    /**
     * Verifica el feedback físico de un control: pollea el stVal [ST] del mismo DO
     * (p.ej. Pos.stVal tras operar Pos.Oper) hasta que coincida con el valor comandado
     * o venza el timeout. El ack MMS del OPERATE solo significa "comando aceptado";
     * el cambio real de posición lo reporta el proceso (contactos auxiliares → stVal).
     *
     * Solo verifica comandos on/off (Boolean o Dbpos). Para otros tipos (setpoints,
     * tap changer) retorna verifiable=false y el llamador decide qué mostrar.
     *
     * @param operNode  nodo Oper del DO operado
     * @param ctlValStr valor comandado ("true"/"false"/"on"/"off")
     * @param timeoutMs tiempo máximo de espera del cambio de posición
     */
    public FeedbackResult verifyControlFeedback(FcModelNode operNode, String ctlValStr, int timeoutMs) {
        long start = System.currentTimeMillis();

        String expected = normalizeOnOff(ctlValStr);
        if (expected == null || serverModel == null || association == null) {
            return new FeedbackResult(false, false, null, 0);
        }

        // stVal vive en el mismo DO pero bajo FC=ST: "LD/LN.Pos.Oper" → "LD/LN.Pos" [ST].stVal
        String operRef = operNode.getReference().toString();
        int lastDot = operRef.lastIndexOf('.');
        if (lastDot < 0) return new FeedbackResult(false, false, null, 0);
        ModelNode stDo = serverModel.findModelNode(operRef.substring(0, lastDot), Fc.ST);
        ModelNode stValNode = (stDo != null) ? stDo.getChild("stVal") : null;
        if (!(stValNode instanceof FcModelNode) || !(stValNode instanceof BasicDataAttribute)) {
            return new FeedbackResult(false, false, null, 0);
        }

        String observed = null;
        while (System.currentTimeMillis() - start < timeoutMs) {
            try {
                association.getDataValues((FcModelNode) stValNode);
                observed = normalizeStVal(stValNode);
                if (expected.equals(observed)) {
                    long elapsed = System.currentTimeMillis() - start;
                    System.out.println("[FEEDBACK] Posición confirmada: stVal=" + observed
                        + " en " + elapsed + "ms");
                    return new FeedbackResult(true, true, observed, elapsed);
                }
            } catch (Exception e) {
                System.out.println("[FEEDBACK] Error leyendo stVal (se reintenta): " + e.getMessage());
            }
            try { Thread.sleep(500); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("[FEEDBACK] SIN confirmación tras " + elapsed + "ms; último stVal=" + observed);
        return new FeedbackResult(true, false, observed, elapsed);
    }

    /** Normaliza un valor comandado a "on"/"off"; null si no es un comando binario. */
    private static String normalizeOnOff(String v) {
        if (v == null) return null;
        String s = v.trim().toLowerCase();
        if (s.equals("true") || s.equals("on") || s.equals("close") || s.equals("closed")) return "on";
        if (s.equals("false") || s.equals("off") || s.equals("open")) return "off";
        return null;
    }

    /** Normaliza el stVal leído a on/off/intermediate/bad según su tipo BDA. */
    private String normalizeStVal(ModelNode stValNode) {
        if (stValNode instanceof BdaBoolean) {
            return ((BdaBoolean) stValNode).getValue() ? "on" : "off";
        }
        if (stValNode instanceof BdaDoubleBitPos) {
            return formatDoubleBitPos((BdaDoubleBitPos) stValNode);
        }
        String s = formatValue(stValNode);
        return s != null ? s.trim().toLowerCase() : null;
    }
}
