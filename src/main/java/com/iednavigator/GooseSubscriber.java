package com.iednavigator;

import org.pcap4j.core.*;
import org.pcap4j.packet.*;
import org.pcap4j.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * GOOSE Subscriber using pcap4j
 * Captures and parses IEC 61850 GOOSE messages from network
 */
public class GooseSubscriber {

    // GOOSE Ethertype
    private static final int GOOSE_ETHERTYPE = 0x88B8;

    private PcapHandle handle;
    private PcapNetworkInterface selectedInterface;
    private volatile boolean running = false;
    private ExecutorService executor;
    private Consumer<GooseMessage> messageListener;
    private Consumer<String> logListener;

    // Parsed GOOSE message
    public static class GooseMessage {
        public String timestamp;
        public String srcMac;
        public String dstMac;
        public String gocbRef;
        public String goId;
        public String datSet;
        public int appId;
        public int stNum;
        public int sqNum;
        public boolean test;
        public boolean ndsCom;
        public int confRev;
        public int numDataSetEntries;
        public byte[] rawData;
        public List<DataEntry> dataEntries = new ArrayList<>();

        @Override
        public String toString() {
            return String.format("GOOSE[AppID=%04X, stNum=%d, sqNum=%d, gocbRef=%s]",
                    appId, stNum, sqNum, gocbRef);
        }
    }

    public static class DataEntry {
        public String type;
        public Object value;
        public int index;

        public DataEntry(int index, String type, Object value) {
            this.index = index;
            this.type = type;
            this.value = value;
        }

        @Override
        public String toString() {
            return String.format("[%d] %s: %s", index, type, value);
        }
    }

    public GooseSubscriber() {
        executor = Executors.newSingleThreadExecutor();
    }

    public void setMessageListener(Consumer<GooseMessage> listener) {
        this.messageListener = listener;
    }

    public void setLogListener(Consumer<String> listener) {
        this.logListener = listener;
    }

    private void log(String message) {
        if (logListener != null) {
            logListener.accept(message);
        }
    }

    /**
     * Get available network interfaces
     */
    public static List<PcapNetworkInterface> getNetworkInterfaces() {
        try {
            return Pcaps.findAllDevs();
        } catch (PcapNativeException e) {
            return Collections.emptyList();
        }
    }

    /**
     * Start capturing GOOSE on specified interface
     */
    public boolean start(PcapNetworkInterface nif) {
        if (running) {
            log("Already running");
            return false;
        }

        try {
            selectedInterface = nif;

            // Open handle for capturing
            handle = nif.openLive(
                65536,  // Snapshot length
                PcapNetworkInterface.PromiscuousMode.PROMISCUOUS,
                100     // Timeout ms
            );

            // Set filter for multicast (GOOSE uses multicast MACs 01-0C-CD-xx-xx-xx)
            // This is similar to IEDExplorer approach
            try {
                handle.setFilter("multicast", BpfProgram.BpfCompileMode.OPTIMIZE);
                log("Filtro multicast aplicado (captura GOOSE)");
            } catch (Exception filterEx) {
                log("AVISO: No se pudo aplicar filtro multicast: " + filterEx.getMessage());
                // Try alternative filter
                try {
                    handle.setFilter("ether proto 0x88b8", BpfProgram.BpfCompileMode.OPTIMIZE);
                    log("Filtro alternativo aplicado (ethertype 0x88B8)");
                } catch (Exception e2) {
                    log("AVISO: Sin filtro, capturando todo el trafico");
                }
            }

            running = true;
            log("Captura GOOSE iniciada en: " + nif.getDescription());
            log("MAC local: " + (nif.getLinkLayerAddresses().isEmpty() ? "N/A" : nif.getLinkLayerAddresses().get(0)));

            // Start capture loop in background
            executor.submit(this::captureLoop);

            return true;
        } catch (Exception e) {
            log("Error starting GOOSE capture: " + e.getMessage());
            return false;
        }
    }

    /**
     * Stop capturing
     */
    public void stop() {
        running = false;
        if (handle != null && handle.isOpen()) {
            try {
                handle.breakLoop();
            } catch (Exception e) {
                // Ignore
            }
            handle.close();
        }
        log("GOOSE capture stopped");
    }

    private int packetCount = 0;
    private int gooseCount = 0;

    /**
     * Main capture loop
     */
    private void captureLoop() {
        packetCount = 0;
        gooseCount = 0;
        log("Loop de captura iniciado...");
        log("Esperando paquetes multicast/GOOSE...");

        long lastLogTime = System.currentTimeMillis();

        try {
            while (running && handle != null && handle.isOpen()) {
                try {
                    Packet packet = handle.getNextPacket();
                    if (packet != null) {
                        packetCount++;

                        // Log first packets for debugging
                        if (packetCount <= 5) {
                            EthernetPacket eth = packet.get(EthernetPacket.class);
                            if (eth != null) {
                                int etherType = eth.getHeader().getType().value() & 0xFFFF;
                                log(String.format("Paquete #%d: EtherType=0x%04X, Src=%s, Dst=%s",
                                    packetCount, etherType,
                                    eth.getHeader().getSrcAddr(),
                                    eth.getHeader().getDstAddr()));
                            }
                        }

                        processPacket(packet);

                        // Log periodic stats every 10 seconds
                        long now = System.currentTimeMillis();
                        if (now - lastLogTime > 10000) {
                            log("Stats: " + packetCount + " paquetes, " + gooseCount + " GOOSE");
                            lastLogTime = now;
                        }
                    }
                } catch (NotOpenException e) {
                    break;
                }
            }
        } catch (Exception e) {
            if (running) {
                log("Error en captura: " + e.getMessage());
                e.printStackTrace();
            }
        }
        log("Loop de captura terminado. Total: " + packetCount + " paquetes, " + gooseCount + " GOOSE");
    }

    public int getPacketCount() { return packetCount; }
    public int getGooseCount() { return gooseCount; }

    /**
     * Process captured packet
     */
    private void processPacket(Packet packet) {
        try {
            EthernetPacket ethPacket = packet.get(EthernetPacket.class);
            if (ethPacket == null) return;

            // Check if it's GOOSE
            int etherType = ethPacket.getHeader().getType().value() & 0xFFFF;
            if (etherType != GOOSE_ETHERTYPE) return;

            gooseCount++;

            // Parse GOOSE PDU
            Packet payload = ethPacket.getPayload();
            if (payload == null) {
                log("GOOSE sin payload");
                return;
            }

            byte[] rawData = payload.getRawData();
            GooseMessage msg = parseGoosePdu(rawData);

            if (msg != null) {
                msg.srcMac = ethPacket.getHeader().getSrcAddr().toString();
                msg.dstMac = ethPacket.getHeader().getDstAddr().toString();
                msg.timestamp = new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(new Date());

                if (messageListener != null) {
                    messageListener.accept(msg);
                }
            } else {
                log("GOOSE parse failed, AppID=" + String.format("%04X", ((rawData[0] & 0xFF) << 8) | (rawData[1] & 0xFF)));
            }
        } catch (Exception e) {
            log("Error procesando paquete: " + e.getMessage());
        }
    }

    /**
     * Parse GOOSE PDU from raw bytes
     * Based on IEC 61850-8-1 GOOSE encoding (ASN.1 BER)
     */
    private GooseMessage parseGoosePdu(byte[] data) {
        if (data == null || data.length < 8) return null;

        GooseMessage msg = new GooseMessage();
        msg.rawData = data;

        int offset = 0;

        try {
            // APPID (2 bytes)
            msg.appId = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
            offset += 2;

            // Length (2 bytes)
            int length = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
            offset += 2;

            // Reserved1 (2 bytes)
            offset += 2;

            // Reserved2 (2 bytes)
            offset += 2;

            // Now parse the GOOSE PDU (ASN.1 BER encoded)
            if (offset < data.length && data[offset] == 0x61) {
                offset++; // Skip tag 0x61 (goosePdu)
                int pduLen = parseLength(data, offset);
                offset += getLengthBytes(data, offset);

                // Parse GOOSE fields
                while (offset < data.length - 1) {
                    int tag = data[offset] & 0xFF;
                    offset++;
                    int fieldLen = parseLength(data, offset);
                    offset += getLengthBytes(data, offset);

                    switch (tag) {
                        case 0x80: // gocbRef
                            msg.gocbRef = new String(data, offset, fieldLen);
                            break;
                        case 0x81: // timeAllowedToLive
                            break;
                        case 0x82: // datSet
                            msg.datSet = new String(data, offset, fieldLen);
                            break;
                        case 0x83: // goID
                            msg.goId = new String(data, offset, fieldLen);
                            break;
                        case 0x84: // t (timestamp)
                            break;
                        case 0x85: // stNum
                            msg.stNum = parseInteger(data, offset, fieldLen);
                            break;
                        case 0x86: // sqNum
                            msg.sqNum = parseInteger(data, offset, fieldLen);
                            break;
                        case 0x87: // test
                            msg.test = data[offset] != 0;
                            break;
                        case 0x88: // confRev
                            msg.confRev = parseInteger(data, offset, fieldLen);
                            break;
                        case 0x89: // ndsCom
                            msg.ndsCom = data[offset] != 0;
                            break;
                        case 0x8A: // numDatSetEntries
                            msg.numDataSetEntries = parseInteger(data, offset, fieldLen);
                            break;
                        case 0xAB: // allData
                            parseAllData(data, offset, fieldLen, msg.dataEntries);
                            break;
                    }
                    offset += fieldLen;
                }
            }

            return msg;
        } catch (Exception e) {
            return msg; // Return partial message
        }
    }

    /**
     * Parse ASN.1 BER length
     */
    private int parseLength(byte[] data, int offset) {
        if (offset >= data.length) return 0;
        int firstByte = data[offset] & 0xFF;
        if (firstByte < 0x80) {
            return firstByte;
        } else {
            int numBytes = firstByte & 0x7F;
            int length = 0;
            for (int i = 0; i < numBytes && (offset + 1 + i) < data.length; i++) {
                length = (length << 8) | (data[offset + 1 + i] & 0xFF);
            }
            return length;
        }
    }

    private int getLengthBytes(byte[] data, int offset) {
        if (offset >= data.length) return 1;
        int firstByte = data[offset] & 0xFF;
        if (firstByte < 0x80) {
            return 1;
        } else {
            return 1 + (firstByte & 0x7F);
        }
    }

    private int parseInteger(byte[] data, int offset, int length) {
        int value = 0;
        for (int i = 0; i < length && (offset + i) < data.length; i++) {
            value = (value << 8) | (data[offset + i] & 0xFF);
        }
        return value;
    }

    /**
     * Parse allData sequence
     */
    private void parseAllData(byte[] data, int offset, int length, List<DataEntry> entries) {
        int end = offset + length;
        int index = 0;

        while (offset < end && offset < data.length) {
            int tag = data[offset] & 0xFF;
            offset++;
            if (offset >= data.length) break;

            int fieldLen = parseLength(data, offset);
            offset += getLengthBytes(data, offset);

            String type;
            Object value;

            switch (tag) {
                case 0x83: // boolean
                    type = "Boolean";
                    value = (offset < data.length && data[offset] != 0);
                    break;
                case 0x84: // bit-string
                    type = "BitString";
                    value = fieldLen > 0 ? String.format("0x%02X", data[offset] & 0xFF) : "0x00";
                    break;
                case 0x85: // integer
                    type = "Integer";
                    value = parseInteger(data, offset, fieldLen);
                    break;
                case 0x86: // unsigned
                    type = "Unsigned";
                    value = parseInteger(data, offset, fieldLen);
                    break;
                case 0x87: // float
                    type = "Float";
                    if (fieldLen >= 5 && offset + 4 < data.length) {
                        int bits = parseInteger(data, offset + 1, 4);
                        value = Float.intBitsToFloat(bits);
                    } else {
                        value = 0.0f;
                    }
                    break;
                case 0x89: // octet-string
                    type = "OctetString";
                    value = bytesToHex(data, offset, Math.min(fieldLen, 8));
                    break;
                case 0x8A: // visible-string
                    type = "VisibleString";
                    value = new String(data, offset, Math.min(fieldLen, data.length - offset));
                    break;
                case 0x8C: // binary-time
                    type = "BinaryTime";
                    value = "T+" + parseInteger(data, offset, Math.min(fieldLen, 4));
                    break;
                case 0x91: // utc-time
                    type = "UtcTime";
                    value = parseInteger(data, offset, Math.min(fieldLen, 4));
                    break;
                default:
                    type = String.format("Tag_%02X", tag);
                    value = bytesToHex(data, offset, Math.min(fieldLen, 8));
            }

            entries.add(new DataEntry(index++, type, value));
            offset += fieldLen;
        }
    }

    private String bytesToHex(byte[] data, int offset, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length && (offset + i) < data.length; i++) {
            sb.append(String.format("%02X", data[offset + i] & 0xFF));
        }
        return sb.toString();
    }

    public boolean isRunning() {
        return running;
    }

    public PcapNetworkInterface getSelectedInterface() {
        return selectedInterface;
    }
}
