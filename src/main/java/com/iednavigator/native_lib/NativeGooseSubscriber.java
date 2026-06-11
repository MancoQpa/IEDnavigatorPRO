package com.iednavigator.native_lib;

import com.sun.jna.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Native GOOSE Subscriber using libiec61850 via JNA
 * This provides full GOOSE support including data parsing
 *
 * Developer: Emilio Medina
 */
public class NativeGooseSubscriber {

    private final LibIec61850 lib;
    private Pointer receiver;
    private Pointer subscriber;
    private volatile boolean running = false;
    private String interfaceId;
    private Consumer<NativeGooseMessage> messageListener;
    private Consumer<String> logListener;
    private LibIec61850.GooseListener nativeListener;

    // Statistics
    private int messageCount = 0;

    /**
     * Represents a parsed GOOSE message
     */
    public static class NativeGooseMessage {
        public String timestamp;
        public String goCbRef;
        public String goId;
        public String datSet;
        public int appId;
        public int stNum;
        public int sqNum;
        public boolean test;
        public boolean ndsCom;
        public int confRev;
        public int timeAllowedToLive;
        public String srcMac;
        public String dstMac;
        public boolean vlanSet;
        public int vlanId;
        public int vlanPrio;
        public List<DataValue> dataValues = new ArrayList<>();

        @Override
        public String toString() {
            return String.format("GOOSE[AppID=%04X, stNum=%d, sqNum=%d, gocbRef=%s, entries=%d]",
                    appId, stNum, sqNum, goCbRef, dataValues.size());
        }
    }

    /**
     * Represents a data value from the GOOSE dataset
     */
    public static class DataValue {
        public int index;
        public String type;
        public Object value;

        public DataValue(int index, String type, Object value) {
            this.index = index;
            this.type = type;
            this.value = value;
        }

        @Override
        public String toString() {
            return String.format("[%d] %s: %s", index, type, value);
        }
    }

    public NativeGooseSubscriber() {
        this.lib = LibIec61850.INSTANCE;
    }

    public void setMessageListener(Consumer<NativeGooseMessage> listener) {
        this.messageListener = listener;
    }

    public void setLogListener(Consumer<String> listener) {
        this.logListener = listener;
    }

    private void log(String message) {
        if (logListener != null) {
            logListener.accept(message);
        }
        System.out.println("[NativeGOOSE] " + message);
    }

    /**
     * Get available network interfaces
     */
    public static List<String> getNetworkInterfaces() {
        List<String> interfaces = new ArrayList<>();
        try {
            java.util.Enumeration<java.net.NetworkInterface> nets =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (nets.hasMoreElements()) {
                java.net.NetworkInterface nif = nets.nextElement();
                if (nif.isUp() && !nif.isLoopback()) {
                    interfaces.add(nif.getName() + " - " + nif.getDisplayName());
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return interfaces;
    }

    /**
     * Start capturing GOOSE messages in observer mode (all messages)
     *
     * @param interfaceId Network interface ID (e.g., "eth0", "\\Device\\NPF_{...}")
     */
    public boolean startObserver(String interfaceId) {
        if (running) {
            log("Ya en ejecucion");
            return false;
        }

        try {
            this.interfaceId = interfaceId;

            // Create receiver
            receiver = lib.GooseReceiver_create();
            if (receiver == null) {
                log("Error: No se pudo crear GooseReceiver");
                return false;
            }

            // Set interface
            lib.GooseReceiver_setInterfaceId(receiver, interfaceId);
            log("Interface configurada: " + interfaceId);

            // Create subscriber in observer mode (null gocbRef)
            subscriber = lib.GooseSubscriber_create(null, null);
            if (subscriber == null) {
                log("Error: No se pudo crear GooseSubscriber");
                lib.GooseReceiver_destroy(receiver);
                return false;
            }

            // Set observer mode to receive all GOOSE messages
            lib.GooseSubscriber_setObserver(subscriber);

            // Create and set native callback
            nativeListener = new LibIec61850.GooseListener() {
                @Override
                public void invoke(Pointer sub, Pointer param) {
                    handleGooseMessage(sub);
                }
            };
            lib.GooseSubscriber_setListener(subscriber, nativeListener, null);

            // Add subscriber to receiver
            lib.GooseReceiver_addSubscriber(receiver, subscriber);

            // Start receiver
            lib.GooseReceiver_start(receiver);

            // Verify it's running
            Thread.sleep(100);
            running = lib.GooseReceiver_isRunning(receiver);

            if (running) {
                log("GOOSE Receiver iniciado exitosamente");
            } else {
                log("Error: GooseReceiver no inicio correctamente");
                cleanup();
                return false;
            }

            return true;

        } catch (Exception e) {
            log("Error iniciando GOOSE: " + e.getMessage());
            e.printStackTrace();
            cleanup();
            return false;
        }
    }

    /**
     * Start capturing GOOSE messages for a specific GoCB
     *
     * @param interfaceId Network interface ID
     * @param goCbRef GOOSE Control Block reference (e.g., "simpleIOGenericIO/LLN0$GO$gcbAnalogValues")
     */
    public boolean start(String interfaceId, String goCbRef) {
        if (running) {
            log("Ya en ejecucion");
            return false;
        }

        try {
            this.interfaceId = interfaceId;

            // Create receiver
            receiver = lib.GooseReceiver_create();
            if (receiver == null) {
                log("Error: No se pudo crear GooseReceiver");
                return false;
            }

            // Set interface
            lib.GooseReceiver_setInterfaceId(receiver, interfaceId);
            log("Interface: " + interfaceId);

            // Create subscriber for specific GoCB
            subscriber = lib.GooseSubscriber_create(goCbRef, null);
            if (subscriber == null) {
                log("Error: No se pudo crear GooseSubscriber para " + goCbRef);
                lib.GooseReceiver_destroy(receiver);
                return false;
            }

            log("Suscrito a GoCB: " + goCbRef);

            // Create and set native callback
            nativeListener = new LibIec61850.GooseListener() {
                @Override
                public void invoke(Pointer sub, Pointer param) {
                    handleGooseMessage(sub);
                }
            };
            lib.GooseSubscriber_setListener(subscriber, nativeListener, null);

            // Add subscriber to receiver
            lib.GooseReceiver_addSubscriber(receiver, subscriber);

            // Start receiver
            lib.GooseReceiver_start(receiver);

            Thread.sleep(100);
            running = lib.GooseReceiver_isRunning(receiver);

            if (running) {
                log("GOOSE Receiver iniciado para " + goCbRef);
            } else {
                log("Error: GooseReceiver no inicio");
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
     * Handle incoming GOOSE message
     */
    private void handleGooseMessage(Pointer sub) {
        try {
            messageCount++;

            NativeGooseMessage msg = new NativeGooseMessage();
            msg.timestamp = new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(new Date());

            // Get message properties
            msg.goCbRef = lib.GooseSubscriber_getGoCbRef(sub);
            msg.goId = lib.GooseSubscriber_getGoId(sub);
            msg.datSet = lib.GooseSubscriber_getDataSet(sub);
            msg.appId = lib.GooseSubscriber_getAppId(sub);
            msg.stNum = lib.GooseSubscriber_getStNum(sub);
            msg.sqNum = lib.GooseSubscriber_getSqNum(sub);
            msg.test = lib.GooseSubscriber_isTest(sub);
            msg.ndsCom = lib.GooseSubscriber_needsCommission(sub);
            msg.confRev = lib.GooseSubscriber_getConfRev(sub);
            msg.timeAllowedToLive = lib.GooseSubscriber_getTimeAllowedToLive(sub);

            // Get MAC addresses
            byte[] srcMac = new byte[6];
            byte[] dstMac = new byte[6];
            lib.GooseSubscriber_getSrcMac(sub, srcMac);
            lib.GooseSubscriber_getDstMac(sub, dstMac);
            msg.srcMac = formatMac(srcMac);
            msg.dstMac = formatMac(dstMac);

            // Get VLAN info
            msg.vlanSet = lib.GooseSubscriber_isVlanSet(sub);
            if (msg.vlanSet) {
                msg.vlanId = lib.GooseSubscriber_getVlanId(sub) & 0xFFFF;
                msg.vlanPrio = lib.GooseSubscriber_getVlanPrio(sub) & 0xFF;
            }

            // Parse data set values
            Pointer dataSetValues = lib.GooseSubscriber_getDataSetValues(sub);
            if (dataSetValues != null) {
                parseDataSetValues(dataSetValues, msg.dataValues);
            }

            // Notify listener
            if (messageListener != null) {
                messageListener.accept(msg);
            }

        } catch (Exception e) {
            log("Error procesando mensaje GOOSE: " + e.getMessage());
        }
    }

    /**
     * Parse MmsValue data set into list of DataValue
     */
    private void parseDataSetValues(Pointer dataSet, List<DataValue> values) {
        try {
            int type = lib.MmsValue_getType(dataSet);

            // Should be an array or structure
            if (type == LibIec61850.MMS_ARRAY || type == LibIec61850.MMS_STRUCTURE) {
                int count = lib.MmsValue_getArraySize(dataSet);

                for (int i = 0; i < count; i++) {
                    Pointer element = lib.MmsValue_getElement(dataSet, i);
                    if (element != null) {
                        DataValue dv = parseMmsValue(i, element);
                        values.add(dv);
                    }
                }
            } else {
                // Single value
                DataValue dv = parseMmsValue(0, dataSet);
                values.add(dv);
            }
        } catch (Exception e) {
            log("Error parseando dataset: " + e.getMessage());
        }
    }

    /**
     * Parse a single MmsValue
     */
    private DataValue parseMmsValue(int index, Pointer value) {
        int type = lib.MmsValue_getType(value);
        String typeName;
        Object val;

        switch (type) {
            case LibIec61850.MMS_BOOLEAN:
                typeName = "Boolean";
                val = lib.MmsValue_getBoolean(value);
                break;

            case LibIec61850.MMS_INTEGER:
                typeName = "Integer";
                val = lib.MmsValue_toInt32(value);
                break;

            case LibIec61850.MMS_UNSIGNED:
                typeName = "Unsigned";
                val = lib.MmsValue_toUint32(value);
                break;

            case LibIec61850.MMS_FLOAT:
                typeName = "Float";
                val = lib.MmsValue_toFloat(value);
                break;

            case LibIec61850.MMS_BIT_STRING:
                typeName = "BitString";
                val = String.format("0x%08X", lib.MmsValue_getBitStringAsInteger(value));
                break;

            case LibIec61850.MMS_VISIBLE_STRING:
            case LibIec61850.MMS_STRING:
                typeName = "String";
                val = lib.MmsValue_toString(value);
                break;

            case LibIec61850.MMS_UTC_TIME:
                typeName = "UtcTime";
                val = "UTC";
                break;

            case LibIec61850.MMS_BINARY_TIME:
                typeName = "BinaryTime";
                val = "BinTime";
                break;

            case LibIec61850.MMS_OCTET_STRING:
                typeName = "OctetString";
                val = "Octets";
                break;

            case LibIec61850.MMS_STRUCTURE:
                typeName = "Structure";
                val = "[" + lib.MmsValue_getArraySize(value) + " elements]";
                break;

            case LibIec61850.MMS_ARRAY:
                typeName = "Array";
                val = "[" + lib.MmsValue_getArraySize(value) + " elements]";
                break;

            default:
                typeName = "Unknown(" + type + ")";
                val = "?";
        }

        return new DataValue(index, typeName, val);
    }

    /**
     * Format MAC address bytes to string
     */
    private String formatMac(byte[] mac) {
        return String.format("%02X:%02X:%02X:%02X:%02X:%02X",
                mac[0] & 0xFF, mac[1] & 0xFF, mac[2] & 0xFF,
                mac[3] & 0xFF, mac[4] & 0xFF, mac[5] & 0xFF);
    }

    /**
     * Stop the GOOSE receiver
     */
    public void stop() {
        if (!running) return;

        running = false;
        cleanup();
        log("GOOSE Receiver detenido. Total mensajes: " + messageCount);
    }

    private void cleanup() {
        try {
            if (receiver != null) {
                lib.GooseReceiver_stop(receiver);
                lib.GooseReceiver_destroy(receiver);
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

    public int getMessageCount() {
        return messageCount;
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
}
