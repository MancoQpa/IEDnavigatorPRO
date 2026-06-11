package com.iedexplorer;

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

    // Timeout de conexión en milisegundos
    private static final int CONNECTION_TIMEOUT_MS = 10000;  // 10 segundos

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
            clientSap.setResponseTimeout(10000);  // 10 segundos
            clientSap.setMessageFragmentTimeout(5000);  // 5 segundos

            System.out.println("[INFO] Resolving host: " + host);
            final InetAddress address = InetAddress.getByName(host);

            System.out.println("[INFO] Connecting to " + host + ":" + port + " (timeout: " + CONNECTION_TIMEOUT_MS + "ms)...");

            // Usar Future con timeout para la conexión
            Future<ClientAssociation> future = connectionExecutor.submit(() -> {
                return clientSap.associate(address, port, null, IEC61850Client.this);
            });

            try {
                association = future.get(CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new IOException("Connection timeout after " + CONNECTION_TIMEOUT_MS + "ms");
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
                System.err.println("[ERROR] ServiceError during model retrieval:");
                System.err.println("  Error code: " + serviceEx.getErrorCode());
                System.err.println("  Message: " + serviceEx.getMessage());
                System.err.println("  Description: " + serviceEx.toString());
                serviceEx.printStackTrace();
                if (serviceEx.getMessage() != null && serviceEx.getMessage().contains("DataSet")) {
                    System.err.println("[INFO] This error typically occurs when:");
                    System.err.println("  1. The server's SCL file has DataSets referencing non-existent data");
                    System.err.println("  2. The DataSet's FCDA members have invalid references");
                    System.err.println("  3. There's a mismatch between DataSet definitions and actual model");
                    System.err.println("[HINT] Try using a simpler CID file or check your SCL for errors");
                }
                connected = false;
                if (association != null) {
                    try { association.close(); } catch (Exception ex) {}
                    association = null;
                }
                throw new IOException("ServiceError: " + serviceEx.getErrorCode() + " - " + serviceEx.getMessage(), serviceEx);
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

    private static final Map<Integer, String> SI_UNIT_MAP = new LinkedHashMap<>();
    private static final Map<Integer, String> CTL_MODEL_MAP = new LinkedHashMap<>();
    private static final Map<Integer, String> HEALTH_MAP = new LinkedHashMap<>();
    private static final Map<Integer, String> MOD_BEH_MAP = new LinkedHashMap<>();
    private static final Map<Integer, String> RANGE_MAP = new LinkedHashMap<>();
    private static final Map<Integer, String> DIR_MAP = new LinkedHashMap<>();
    private static final Map<Integer, String> OR_CATEGORY_MAP = new LinkedHashMap<>();
    private static final Map<Integer, String> AUTO_REC_ST_MAP = new LinkedHashMap<>();
    private static final Map<Integer, String> FLT_LOOP_MAP = new LinkedHashMap<>();

    static {
        // IEC 61850-7-3 §20 — códigos de unidad SIUnit
        SI_UNIT_MAP.put(0,  "none");  SI_UNIT_MAP.put(1,  "m");
        SI_UNIT_MAP.put(2,  "kg");    SI_UNIT_MAP.put(3,  "s");
        SI_UNIT_MAP.put(4,  "A");     SI_UNIT_MAP.put(8,  "K");
        SI_UNIT_MAP.put(11, "deg");   SI_UNIT_MAP.put(21, "Gy");
        SI_UNIT_MAP.put(23, "°C");    SI_UNIT_MAP.put(25, "F");
        SI_UNIT_MAP.put(26, "C");     SI_UNIT_MAP.put(27, "S");
        SI_UNIT_MAP.put(28, "H");     SI_UNIT_MAP.put(29, "V");
        SI_UNIT_MAP.put(30, "Ω");     SI_UNIT_MAP.put(31, "J");
        SI_UNIT_MAP.put(32, "N");     SI_UNIT_MAP.put(33, "Hz");
        SI_UNIT_MAP.put(35, "lm");    SI_UNIT_MAP.put(36, "lx");
        SI_UNIT_MAP.put(37, "Wb");    SI_UNIT_MAP.put(38, "T");
        SI_UNIT_MAP.put(61, "VA");    SI_UNIT_MAP.put(62, "W");
        SI_UNIT_MAP.put(63, "VAr");   SI_UNIT_MAP.put(64, "φ");
        SI_UNIT_MAP.put(65, "cos(φ)");SI_UNIT_MAP.put(66, "Vs");
        SI_UNIT_MAP.put(72, "Wh");    SI_UNIT_MAP.put(73, "VAh");
        SI_UNIT_MAP.put(74, "VArh");  SI_UNIT_MAP.put(75, "V²h");
        SI_UNIT_MAP.put(76, "A²h");   SI_UNIT_MAP.put(77, "V²");
        SI_UNIT_MAP.put(78, "A²");

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

                // Configurar trigger options localmente
                if (urcb.getTrgOps() != null) {
                    urcb.getTrgOps().setDataChange(true);
                    urcb.getTrgOps().setQualityChange(true);
                    urcb.getTrgOps().setGeneralInterrogation(true);
                }

                // Paso 1: escribir trgOps (con rptEna = false para poder modificarlo)
                urcb.getRptEna().setValue(false);
                association.setRcbValues(urcb,
                    false,  // rptId
                    false,  // datSet
                    false,  // rptEna  - aun no habilitar
                    false,  // optFlds
                    false,  // bufTm
                    false,  // sqNum
                    true,   // trgOps
                    false); // intgPd

                // Paso 2: habilitar incluyendo rptId (muchos servidores NRR/NARI exigen
                // que el cliente escriba rptId junto con rptEna para reclamar ownership)
                urcb.getRptEna().setValue(true);
                association.setRcbValues(urcb,
                    true,   // rptId   - escribir para reclamar ownership del URCB
                    false,  // datSet
                    true,   // rptEna  - HABILITAR
                    false,  // optFlds
                    false,  // bufTm
                    false,  // sqNum
                    false,  // trgOps
                    false); // intgPd

                // Verificar que el servidor realmente habilitó el RCB
                association.getRcbValues(urcb);
                boolean actuallyEnabled = urcb.getRptEna() != null && urcb.getRptEna().getValue();
                System.out.println("[" + (actuallyEnabled ? "OK" : "WARN") + "] URCB " + urcb.getName()
                    + " → RptEna en servidor = " + actuallyEnabled);
                if (!actuallyEnabled) {
                    throw new IOException("El servidor rechazó el enable del URCB: " + urcb.getName()
                        + " (RptEna sigue false tras setRcbValues)");
                }

            } else if (rcb instanceof Brcb) {
                Brcb brcb = (Brcb) rcb;

                // Configurar trigger options localmente
                if (brcb.getTrgOps() != null) {
                    brcb.getTrgOps().setDataChange(true);
                    brcb.getTrgOps().setQualityChange(true);
                    brcb.getTrgOps().setGeneralInterrogation(true);
                }

                // Paso 1: escribir trgOps
                brcb.getRptEna().setValue(false);
                association.setRcbValues(brcb,
                    false,  // rptId
                    false,  // datSet
                    false,  // rptEna
                    false,  // optFlds
                    false,  // bufTm
                    false,  // sqNum
                    true,   // trgOps
                    false); // intgPd

                // Paso 2: habilitar
                brcb.getRptEna().setValue(true);
                association.setRcbValues(brcb,
                    true,   // rptId   - escribir para reclamar ownership
                    false,  // datSet
                    true,   // rptEna  - HABILITAR
                    false,  // optFlds
                    false,  // bufTm
                    false,  // sqNum
                    false,  // trgOps
                    false); // intgPd

                // Verificar que el servidor realmente habilitó el RCB
                association.getRcbValues(brcb);
                boolean actuallyEnabled = brcb.getRptEna() != null && brcb.getRptEna().getValue();
                System.out.println("[" + (actuallyEnabled ? "OK" : "WARN") + "] BRCB " + brcb.getName()
                    + " → RptEna en servidor = " + actuallyEnabled);
                if (!actuallyEnabled) {
                    throw new IOException("El servidor rechazó el enable del BRCB: " + brcb.getName()
                        + " (RptEna sigue false tras setRcbValues)");
                }
            }

        } catch (ServiceError e) {
            throw new IOException("Error enabling report: " + e.getErrorCode(), e);
        }
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
        System.out.println("[INFO] Report received: " + report.getRptId());

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
}
