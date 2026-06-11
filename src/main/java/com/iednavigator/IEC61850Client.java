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

    // Cache de valores leidos
    private final Map<String, CachedValue> valueCache = new ConcurrentHashMap<>();

    // Listener para cambios
    private ValueChangeListener valueChangeListener;

    // Contador de ctlNum — se incrementa en cada operación de control (IEC 61850-7-3 §20.2)
    private int ctlNumCounter = 0;

    public interface ValueChangeListener {
        void onValueChanged(String reference, String value, String type);
        void onError(String reference, String error);
        void onConnectionClosed(String reason);
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
            try {
                serverModel = association.retrieveModel();
                System.out.println("[INFO] Model retrieved successfully - " + countNodes(serverModel) + " nodes");
            } catch (ServiceError serviceEx) {
                System.err.println("[WARN] ServiceError during model retrieval:");
                System.err.println("  Error code: " + serviceEx.getErrorCode());
                System.err.println("  Message: " + serviceEx.getMessage());
                String msg = serviceEx.getMessage() != null ? serviceEx.getMessage() : "";
                // Si el error es por DataSet inexistente, intentar extraer el modelo parcial ya
                // construido por retrieveModel() antes de que updateDataSets() fallara.
                // ClientAssociation.serverModel ya tiene la estructura completa del IED.
                ServerModel partialModel = extractPartialModelFromAssociation(association);
                if (partialModel != null && partialModel.getChildren() != null
                        && !partialModel.getChildren().isEmpty()) {
                    serverModel = partialModel;
                    connected = true;
                    System.out.println("[INFO] Modelo parcial recuperado via reflexión ("
                        + countNodes(serverModel) + " nodos). DataSets omitidos.");
                    // No lanzar excepción — continuar con modelo parcial
                } else {
                    // No se pudo extraer modelo parcial → guardar asociación para fallback SCL
                    System.err.println("[INFO] No se pudo recuperar modelo parcial - conservando asociación para fallback SCL");
                    pendingAssociation = association;
                    association = null;
                    connected = false;
                    throw new IOException("SCL_FALLBACK: " + serviceEx.getErrorCode() + " - " + msg, serviceEx);
                }
            } catch (Exception modelEx) {
                System.err.println("[ERROR] Model retrieval failed: " + modelEx.getClass().getName());
                System.err.println("  Message: " + modelEx.getMessage());
                modelEx.printStackTrace();
                connected = false;
                if (association != null) {
                    try { association.close(); } catch (Exception ex) {}
                    association = null;
                }
                throw new IOException("Model retrieval failed: " + modelEx.getMessage(), modelEx);
            }

            connected = true;
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
            System.err.println("[INFO] extractPartialModel via reflexión falló: " + e.getMessage());
        }
        return null;
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
                if (bda instanceof BdaInt8 || bda instanceof BdaInt8U
                        || bda instanceof BdaInt16 || bda instanceof BdaInt16U) {
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
            // Leer valores actuales
            association.getRcbValues(rcb);

            // Setear rptEna = false localmente
            rcb.getRptEna().setValue(false);

            // Escribir SOLO rptEna al servidor (tercer param = true significa "escribir este campo")
            if (rcb instanceof Urcb) {
                association.setRcbValues((Urcb) rcb,
                    false,  // rptId
                    false,  // datSet
                    true,   // rptEna  - ESCRIBIR este campo (valor = false = deshabilitar)
                    false,  // optFlds
                    false,  // bufTm
                    false,  // sqNum
                    false,  // trgOps
                    false); // intgPd
                try { association.cancelUrcbReservation((Urcb) rcb); } catch (Exception ignore) {}
            } else if (rcb instanceof Brcb) {
                association.setRcbValues((Brcb) rcb,
                    false,  // rptId
                    false,  // datSet
                    true,   // rptEna  - ESCRIBIR este campo (valor = false = deshabilitar)
                    false,  // optFlds
                    false,  // bufTm
                    false,  // sqNum
                    false,  // trgOps
                    false); // intgPd
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
        System.out.println("[WARN] Association closed" + (e != null ? ": " + e.getMessage() : ""));
        connected = false;
        serverModel = null;
        association = null;

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
    public List<String> findSclFiles() throws IOException {
        List<String> sclFiles = new ArrayList<>();

        // Directorios comunes donde los IEDs guardan archivos de configuracion
        String[] searchDirs = {"", "/", "/config", "/CONFIG", "/scl", "/SCL", "/icd", "/ICD"};

        for (String dir : searchDirs) {
            try {
                List<FileInformation> files = listFiles(dir);
                if (files != null) {
                    for (FileInformation fi : files) {
                        String name = fi.getFilename().toLowerCase();
                        if (name.endsWith(".cid") || name.endsWith(".icd") ||
                            name.endsWith(".scd") || name.endsWith(".scl")) {
                            String fullPath = dir.isEmpty() ? fi.getFilename() : dir + "/" + fi.getFilename();
                            sclFiles.add(fullPath);
                            System.out.println("[INFO] Found SCL file: " + fullPath);
                        }
                    }
                }
            } catch (Exception e) {
                // Ignorar errores de directorios que no existen
                System.out.println("[DEBUG] Cannot list " + dir + ": " + e.getMessage());
            }
        }

        return sclFiles;
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
                    if (attr instanceof BdaInt8U) {
                        ((BdaInt8U) attr).setValue((short) groupNumber);
                    } else if (attr instanceof BdaInt8) {
                        ((BdaInt8) attr).setValue((byte) groupNumber);
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

        private ControlResult(boolean success, int ctlModel, String ctlModelName,
                               String error, String lastApplError) {
            this.success = success;
            this.ctlModel = ctlModel;
            this.ctlModelName = ctlModelName;
            this.error = error;
            this.lastApplError = lastApplError;
        }

        static ControlResult ok(int ctlModel, String ctlModelName) {
            return new ControlResult(true, ctlModel, ctlModelName, null, null);
        }

        static ControlResult fail(int ctlModel, String ctlModelName,
                                   String error, String lastApplError) {
            return new ControlResult(false, ctlModel, ctlModelName, error, lastApplError);
        }
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
        for (Fc fc : new Fc[]{Fc.CF, Fc.SP}) {
            try {
                ModelNode node = serverModel.findModelNode(doRef + ".ctlModel", fc);
                if (node instanceof BdaInt8U) {
                    try { association.getDataValues((FcModelNode) node); } catch (Exception ignore) {}
                    return ((BdaInt8U) node).getValue() & 0xFF;
                }
                if (node instanceof BdaInt8) {
                    try { association.getDataValues((FcModelNode) node); } catch (Exception ignore) {}
                    return ((BdaInt8) node).getValue() & 0xFF;
                }
            } catch (Exception ignore) {}
        }
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
        if (operNode.getChildren() == null) return;
        for (ModelNode child : operNode.getChildren()) {
            String name = child.getName();
            if ("origin".equals(name)) {
                if (child.getChildren() == null) continue;
                for (ModelNode oc : child.getChildren()) {
                    if ("orCat".equals(oc.getName()) && oc instanceof BdaInt8U) {
                        ((BdaInt8U) oc).setValue((short) 3); // remote-control
                    } else if ("orIdent".equals(oc.getName()) && oc instanceof BdaOctetString) {
                        byte[] b = (orIdent != null && !orIdent.isEmpty())
                            ? orIdent.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                            : new byte[0];
                        ((BdaOctetString) oc).setValue(b);
                    }
                }
            } else if ("ctlNum".equals(name) && child instanceof BdaInt8U) {
                ctlNumCounter = (ctlNumCounter + 1) & 0xFF;
                ((BdaInt8U) child).setValue((short) ctlNumCounter);
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
        if (serverModel == null || association == null) return null;
        try {
            String operRef = operNode.getReference().toString();
            int lastDot = operRef.lastIndexOf('.');
            if (lastDot < 0) return null;
            String doRef = operRef.substring(0, lastDot);
            ModelNode laeNode = serverModel.findModelNode(doRef + ".LastApplError", Fc.CO);
            if (laeNode instanceof FcModelNode) {
                try { association.getDataValues((FcModelNode) laeNode); } catch (Exception ignore) {}
                StringBuilder sb = new StringBuilder();
                if (laeNode.getChildren() != null) {
                    for (ModelNode child : laeNode.getChildren()) {
                        String n = child.getName();
                        if ("error".equals(n) || "addCause".equals(n)) {
                            String v = formatValue(child);
                            if (v != null && !v.isEmpty()) {
                                sb.append(n).append("=").append(v).append(" ");
                            }
                        }
                    }
                }
                return sb.length() > 0 ? sb.toString().trim() : null;
            }
        } catch (Exception ignore) {}
        return null;
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

        // Poblar Cancel: origin + ctlNum + T; sin ctlVal (Cancel no altera el proceso)
        fillControlStructure(cancelNode, false, orIdent);

        try {
            association.setDataValues(cancelNode);
            System.out.println("[SBO] CANCEL enviado: " + operNode.getReference());
            return ControlResult.ok(ctlModel, ctlModelName);
        } catch (ServiceError e) {
            String lastErr = readLastApplError(operNode);
            System.out.println("[ERROR] CANCEL rechazado: ServiceError " + e.getErrorCode()
                + (lastErr != null ? " | LastApplError: " + lastErr : ""));
            return ControlResult.fail(ctlModel, ctlModelName,
                "CANCEL ServiceError: " + e.getErrorCode(), lastErr);
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

        // Preparar estructura Oper completa
        setOperCtlVal(operNode, ctlValStr);
        fillControlStructure(operNode, testFlag, orIdent, synchroCheck, interlockCheck);

        try {
            if (ctlModel == 2 || ctlModel == 4) {
                // SBO: enviar SELECT primero
                System.out.println("[SBO] SELECT → " + operNode.getReference());
                boolean selected = association.select(operNode);
                if (!selected) {
                    String lastErr = readLastApplError(operNode);
                    System.out.println("[SBO] SELECT rechazado. LastApplError: " + lastErr);
                    return ControlResult.fail(ctlModel, ctlModelName,
                        "SELECT rechazado por el IED", lastErr);
                }
                System.out.println("[SBO] SELECT aceptado. Enviando OPERATE...");
            }

            association.operate(operNode);
            System.out.println("[OK] OPERATE ejecutado: " + operNode.getReference()
                + " = " + ctlValStr + (testFlag ? " [TEST MODE]" : ""));
            return ControlResult.ok(ctlModel, ctlModelName);

        } catch (ServiceError e) {
            String lastErr = readLastApplError(operNode);
            System.out.println("[ERROR] OPERATE falló: ServiceError " + e.getErrorCode()
                + (lastErr != null ? " | LastApplError: " + lastErr : ""));
            return ControlResult.fail(ctlModel, ctlModelName,
                "ServiceError: " + e.getErrorCode(), lastErr);
        }
    }

    /** Overload sin Check — equivale a Check=0 (sin verificaciones). */
    public ControlResult operateControl(FcModelNode operNode, String ctlValStr,
                                         boolean testFlag, String orIdent) throws IOException {
        return operateControl(operNode, ctlValStr, testFlag, orIdent, false, false);
    }
}
