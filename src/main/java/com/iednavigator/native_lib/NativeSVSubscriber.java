package com.iednavigator.native_lib;

import com.sun.jna.*;

import java.util.*;
import java.util.function.Consumer;

/**
 * Native Sampled Values Subscriber using libiec61850 via JNA
 * Provides full SV (Sampled Values / SMV) support
 *
 * Developer: Emilio Medina
 */
public class NativeSVSubscriber {

    private final LibIec61850 lib;
    private Pointer receiver;
    private Pointer subscriber;
    private volatile boolean running = false;
    private String interfaceId;
    private Consumer<SVMessage> messageListener;
    private Consumer<String> logListener;
    private LibIec61850.SVUpdateListener nativeListener;

    // Statistics
    private int asduCount = 0;
    private int sampleCount = 0;

    /**
     * Represents a Sampled Values ASDU (Application Service Data Unit)
     */
    public static class SVMessage {
        public String timestamp;
        public String svId;
        public String datSet;
        public int appId;
        public int smpCnt;
        public int confRev;
        public int smpMod;
        public int smpRate;
        public int smpSynch;
        public boolean hasDatSet;
        public boolean hasRefrTm;
        public boolean hasSmpMod;
        public boolean hasSmpRate;
        public long refrTmMs;
        public int dataSize;
        public List<DataSample> samples = new ArrayList<>();

        @Override
        public String toString() {
            return String.format("SV[AppID=%04X, svId=%s, smpCnt=%d, samples=%d]",
                    appId, svId, smpCnt, samples.size());
        }
    }

    /**
     * Represents a single data sample
     */
    public static class DataSample {
        public int index;
        public String name;
        public double value;
        public int quality;

        public DataSample(int index, String name, double value, int quality) {
            this.index = index;
            this.name = name;
            this.value = value;
            this.quality = quality;
        }

        @Override
        public String toString() {
            return String.format("[%d] %s: %.4f (Q=%d)", index, name, value, quality);
        }
    }

    /**
     * SV Dataset configuration
     */
    public static class SVDatasetConfig {
        public List<DataField> fields = new ArrayList<>();

        public static class DataField {
            public String name;
            public String type; // FLOAT32, INT32, Quality, etc.
            public int offset;
            public int size;

            public DataField(String name, String type, int offset, int size) {
                this.name = name;
                this.type = type;
                this.offset = offset;
                this.size = size;
            }
        }

        /**
         * Create standard 9-2LE dataset configuration
         * (4 currents + 4 voltages with quality)
         */
        public static SVDatasetConfig create9_2LE() {
            SVDatasetConfig config = new SVDatasetConfig();

            int offset = 0;

            // 4 Current samples (FLOAT32)
            for (int i = 1; i <= 4; i++) {
                config.fields.add(new DataField("Ia" + i, "FLOAT32", offset, 4));
                offset += 4;
            }

            // 4 Current qualities
            for (int i = 1; i <= 4; i++) {
                config.fields.add(new DataField("qIa" + i, "Quality", offset, 4));
                offset += 4;
            }

            // 4 Voltage samples (FLOAT32)
            for (int i = 1; i <= 4; i++) {
                config.fields.add(new DataField("Va" + i, "FLOAT32", offset, 4));
                offset += 4;
            }

            // 4 Voltage qualities
            for (int i = 1; i <= 4; i++) {
                config.fields.add(new DataField("qVa" + i, "Quality", offset, 4));
                offset += 4;
            }

            return config;
        }
    }

    private SVDatasetConfig datasetConfig;
    private int configuredAppId = -1;

    public NativeSVSubscriber() {
        this.lib = LibIec61850.INSTANCE;
    }

    public void setMessageListener(Consumer<SVMessage> listener) {
        this.messageListener = listener;
    }

    public void setLogListener(Consumer<String> listener) {
        this.logListener = listener;
    }

    public void setDatasetConfig(SVDatasetConfig config) {
        this.datasetConfig = config;
    }

    private void log(String message) {
        if (logListener != null) {
            logListener.accept(message);
        }
        System.out.println("[NativeSV] " + message);
    }

    /**
     * Start capturing SV messages for a specific APPID
     *
     * @param interfaceId Network interface ID
     * @param appId Application ID to filter (0x4000-0x7FFF typical range)
     */
    public boolean start(String interfaceId, int appId) {
        if (running) {
            log("Ya en ejecucion");
            return false;
        }

        try {
            this.interfaceId = interfaceId;
            this.configuredAppId = appId;

            // Create receiver
            receiver = lib.SVReceiver_create();
            if (receiver == null) {
                log("Error: No se pudo crear SVReceiver");
                return false;
            }

            // Set interface
            lib.SVReceiver_setInterfaceId(receiver, interfaceId);
            log("Interface: " + interfaceId);

            // Disable destination address check for broader compatibility
            lib.SVReceiver_disableDestAddrCheck(receiver);

            // Create subscriber for specific APPID
            subscriber = lib.SVSubscriber_create(null, (short) appId);
            if (subscriber == null) {
                log("Error: No se pudo crear SVSubscriber");
                lib.SVReceiver_destroy(receiver);
                return false;
            }

            log("Suscrito a APPID: 0x" + String.format("%04X", appId));

            // Create and set native callback
            nativeListener = new LibIec61850.SVUpdateListener() {
                @Override
                public void invoke(Pointer sub, Pointer param, Pointer asdu) {
                    handleSVMessage(sub, asdu);
                }
            };
            lib.SVSubscriber_setListener(subscriber, nativeListener, null);

            // Add subscriber to receiver
            lib.SVReceiver_addSubscriber(receiver, subscriber);

            // Start receiver
            lib.SVReceiver_start(receiver);

            Thread.sleep(100);
            running = lib.SVReceiver_isRunning(receiver);

            if (running) {
                log("SV Receiver iniciado para APPID 0x" + String.format("%04X", appId));
            } else {
                log("Error: SVReceiver no inicio");
                cleanup();
                return false;
            }

            return true;

        } catch (Exception e) {
            log("Error: " + e.getMessage());
            e.printStackTrace();
            cleanup();
            return false;
        }
    }

    /**
     * Handle incoming SV ASDU
     */
    private void handleSVMessage(Pointer sub, Pointer asdu) {
        try {
            asduCount++;

            SVMessage msg = new SVMessage();
            msg.timestamp = new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(new Date());

            // Get ASDU properties
            msg.svId = lib.SVSubscriber_ASDU_getSvId(asdu);
            msg.smpCnt = lib.SVSubscriber_ASDU_getSmpCnt(asdu) & 0xFFFF;
            msg.confRev = lib.SVSubscriber_ASDU_getConfRev(asdu);
            msg.appId = configuredAppId;

            // Check optional fields
            msg.hasDatSet = lib.SVSubscriber_ASDU_hasDatSet(asdu);
            msg.hasRefrTm = lib.SVSubscriber_ASDU_hasRefrTm(asdu);
            msg.hasSmpMod = lib.SVSubscriber_ASDU_hasSmpMod(asdu);
            msg.hasSmpRate = lib.SVSubscriber_ASDU_hasSmpRate(asdu);

            if (msg.hasDatSet) {
                msg.datSet = lib.SVSubscriber_ASDU_getDatSet(asdu);
            }

            if (msg.hasSmpMod) {
                msg.smpMod = lib.SVSubscriber_ASDU_getSmpMod(asdu) & 0xFF;
            }

            if (msg.hasSmpRate) {
                msg.smpRate = lib.SVSubscriber_ASDU_getSmpRate(asdu) & 0xFFFF;
            }

            if (msg.hasRefrTm) {
                msg.refrTmMs = lib.SVSubscriber_ASDU_getRefrTmAsMs(asdu);
            }

            msg.smpSynch = lib.SVSubscriber_ASDU_getSmpSynch(asdu) & 0xFF;
            msg.dataSize = lib.SVSubscriber_ASDU_getDataSize(asdu);

            // Parse data values based on configuration
            if (datasetConfig != null) {
                parseDataWithConfig(asdu, msg);
            } else {
                // Default: try to read as FLOAT32 values
                parseDataAsFloats(asdu, msg);
            }

            sampleCount++;

            // Notify listener
            if (messageListener != null) {
                messageListener.accept(msg);
            }

        } catch (Exception e) {
            log("Error procesando ASDU SV: " + e.getMessage());
        }
    }

    /**
     * Parse SV data using configured dataset
     */
    private void parseDataWithConfig(Pointer asdu, SVMessage msg) {
        for (SVDatasetConfig.DataField field : datasetConfig.fields) {
            try {
                double value;
                int quality = 0;

                switch (field.type) {
                    case "FLOAT32":
                        value = lib.SVSubscriber_ASDU_getFLOAT32(asdu, field.offset);
                        break;
                    case "FLOAT64":
                        value = lib.SVSubscriber_ASDU_getFLOAT64(asdu, field.offset);
                        break;
                    case "INT32":
                        value = lib.SVSubscriber_ASDU_getINT32(asdu, field.offset);
                        break;
                    case "INT16":
                        value = lib.SVSubscriber_ASDU_getINT16(asdu, field.offset);
                        break;
                    case "Quality":
                        quality = lib.SVSubscriber_ASDU_getINT32U(asdu, field.offset);
                        value = quality;
                        break;
                    default:
                        value = 0;
                }

                msg.samples.add(new DataSample(
                        msg.samples.size(),
                        field.name,
                        value,
                        quality
                ));
            } catch (Exception e) {
                // Skip this field
            }
        }
    }

    /**
     * Parse SV data as FLOAT32 values (default)
     */
    private void parseDataAsFloats(Pointer asdu, SVMessage msg) {
        int dataSize = msg.dataSize;
        int numFloats = dataSize / 4;

        for (int i = 0; i < numFloats && i < 32; i++) {
            try {
                float value = lib.SVSubscriber_ASDU_getFLOAT32(asdu, i * 4);
                msg.samples.add(new DataSample(i, "V" + (i + 1), value, 0));
            } catch (Exception e) {
                break;
            }
        }
    }

    /**
     * Stop the SV receiver
     */
    public void stop() {
        if (!running) return;

        running = false;
        cleanup();
        log("SV Receiver detenido. Total ASDUs: " + asduCount + ", Samples: " + sampleCount);
    }

    private void cleanup() {
        try {
            if (receiver != null) {
                lib.SVReceiver_stop(receiver);
                lib.SVReceiver_destroy(receiver);
                receiver = null;
            }
            subscriber = null;
            nativeListener = null;
        } catch (Exception e) {
            log("Error en cleanup: " + e.getMessage());
        }
    }

    public boolean isRunning() {
        return running;
    }

    public int getAsduCount() {
        return asduCount;
    }

    public int getSampleCount() {
        return sampleCount;
    }

    public String getInterfaceId() {
        return interfaceId;
    }

    /**
     * Check if native library is available
     */
    public static boolean isNativeLibraryAvailable() {
        try {
            LibIec61850 lib = LibIec61850.INSTANCE;
            return lib != null;
        } catch (UnsatisfiedLinkError e) {
            System.err.println("libiec61850 no disponible: " + e.getMessage());
            return false;
        } catch (Exception e) {
            System.err.println("Error verificando libiec61850: " + e.getMessage());
            return false;
        }
    }

    /**
     * Get sample synchronization mode description
     */
    public static String getSmpSynchDescription(int smpSynch) {
        switch (smpSynch) {
            case 0:
                return "Not synchronized";
            case 1:
                return "Local area clock";
            case 2:
                return "Global area clock";
            default:
                return "Unknown (" + smpSynch + ")";
        }
    }

    /**
     * Get sample mode description
     */
    public static String getSmpModDescription(int smpMod) {
        switch (smpMod) {
            case 0:
                return "Samples per nominal period";
            case 1:
                return "Samples per second";
            case 2:
                return "Seconds per sample";
            default:
                return "Unknown (" + smpMod + ")";
        }
    }
}
