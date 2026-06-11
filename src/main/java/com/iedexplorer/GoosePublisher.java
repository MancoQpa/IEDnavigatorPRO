package com.iedexplorer;

import org.pcap4j.core.*;
import org.pcap4j.packet.*;
import org.pcap4j.packet.namednumber.EtherType;
import org.pcap4j.util.MacAddress;

import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * GOOSE Publisher using pcap4j
 * Publishes IEC 61850 GOOSE messages to network
 */
public class GoosePublisher {

    // GOOSE Ethertype
    private static final EtherType GOOSE_ETHERTYPE = EtherType.getInstance((short) 0x88B8);

    // Default GOOSE multicast MAC (01-0C-CD-01-00-00 to 01-0C-CD-01-01-FF)
    private static final MacAddress DEFAULT_DST_MAC = MacAddress.getByName("01-0C-CD-01-00-01");

    private PcapHandle sendHandle;
    private PcapNetworkInterface selectedInterface;
    private MacAddress srcMac;
    private MacAddress dstMac = DEFAULT_DST_MAC;

    private volatile boolean publishing = false;
    private ScheduledExecutorService scheduler;
    private Consumer<String> logListener;
    private Consumer<PublishedMessage> publishListener;

    /**
     * Represents a published GOOSE message for GUI feedback
     */
    public static class PublishedMessage {
        public String timestamp;
        public int appId;
        public int stNum;
        public int sqNum;
        public String gocbRef;
        public String goId;
        public String datSet;
        public List<DataValue> dataValues;
    }

    // GOOSE parameters
    private String gocbRef = "simulated/LLN0$GO$gcb01";
    private String goId = "GOOSE_SIM";
    private String datSet = "simulated/LLN0$DataSet1";
    private int appId = 0x0001;
    private int confRev = 1;
    private int stNum = 1;
    private int sqNum = 0;
    private boolean testMode = false;
    private boolean needsCommissioning = false;

    // Data values to publish
    private List<DataValue> dataValues = new ArrayList<>();

    // Heartbeat interval in ms
    private int heartbeatInterval = 1000;

    public static class DataValue {
        public enum Type {
            BOOLEAN, INTEGER, UNSIGNED, FLOAT, BITSTRING, VISIBLE_STRING, DBPOS
        }

        public Type type;
        public Object value;
        public String name;

        public DataValue(String name, Type type, Object value) {
            this.name = name;
            this.type = type;
            this.value = value;
        }
    }

    public GoosePublisher() {
        scheduler = Executors.newSingleThreadScheduledExecutor();

        // Add some default values
        dataValues.add(new DataValue("Pos.stVal", DataValue.Type.DBPOS, 1)); // OFF
        dataValues.add(new DataValue("Pos.q", DataValue.Type.BITSTRING, 0));
        dataValues.add(new DataValue("Pos.t", DataValue.Type.UNSIGNED, System.currentTimeMillis() / 1000));
    }

    public void setLogListener(Consumer<String> listener) {
        this.logListener = listener;
    }

    public void setPublishListener(Consumer<PublishedMessage> listener) {
        this.publishListener = listener;
    }

    private void log(String message) {
        if (logListener != null) {
            logListener.accept(message);
        }
    }

    /**
     * Initialize publisher with network interface
     */
    public boolean init(PcapNetworkInterface nif) {
        try {
            selectedInterface = nif;

            // Get source MAC address
            srcMac = MacAddress.getByAddress(nif.getLinkLayerAddresses().get(0).getAddress());

            // Open handle for sending
            sendHandle = nif.openLive(65536, PcapNetworkInterface.PromiscuousMode.PROMISCUOUS, 10);

            log("GOOSE Publisher initialized on: " + nif.getDescription());
            log("Source MAC: " + srcMac);
            return true;
        } catch (Exception e) {
            log("Error initializing GOOSE Publisher: " + e.getMessage());
            return false;
        }
    }

    /**
     * Start periodic GOOSE publishing (heartbeat)
     */
    public void startPublishing() {
        // Recreate scheduler if it was shut down
        if (scheduler.isShutdown()) {
            scheduler = Executors.newSingleThreadScheduledExecutor();
        }

        publishing = true;
        sqNum = 0;

        // Schedule periodic publishing
        scheduler.scheduleAtFixedRate(() -> {
            if (publishing) {
                try {
                    publishGoose();
                    sqNum++;
                    if (sqNum > 65535) sqNum = 0;
                } catch (Exception e) {
                    log("Error publishing GOOSE: " + e.getMessage());
                }
            }
        }, 0, heartbeatInterval, TimeUnit.MILLISECONDS);

        log("GOOSE publishing started (interval: " + heartbeatInterval + "ms)");
    }

    /**
     * Stop periodic publishing
     */
    public void stopPublishing() {
        publishing = false;
        scheduler.shutdownNow();
        log("GOOSE publishing stopped");
    }

    /**
     * Publish a single GOOSE message (for state changes)
     */
    public void publishStateChange() {

        // Increment state number and reset sequence
        stNum++;
        sqNum = 0;

        try {
            publishGoose();
            log("GOOSE state change published (stNum=" + stNum + ")");

            // Rapid retransmission for state changes (T0, T1, T2, T3)
            // T0=immediate (done), T1=2ms, T2=4ms, T3=8ms, then heartbeat
            scheduler.schedule(() -> { sqNum++; try { publishGoose(); } catch (Exception e) {} }, 2, TimeUnit.MILLISECONDS);
            scheduler.schedule(() -> { sqNum++; try { publishGoose(); } catch (Exception e) {} }, 4, TimeUnit.MILLISECONDS);
            scheduler.schedule(() -> { sqNum++; try { publishGoose(); } catch (Exception e) {} }, 8, TimeUnit.MILLISECONDS);
            scheduler.schedule(() -> { sqNum++; try { publishGoose(); } catch (Exception e) {} }, 16, TimeUnit.MILLISECONDS);

        } catch (Exception e) {
            log("Error publishing state change: " + e.getMessage());
        }
    }

    /**
     * Build and send GOOSE packet
     */
    private void publishGoose() throws Exception {
        // Only send to network if handle is available (not in loopback-only mode)
        if (sendHandle != null) {
            byte[] goosePdu = buildGoosePdu();
            byte[] payload = buildGoosePayload(goosePdu);

            // Build Ethernet packet with padding enabled
            EthernetPacket.Builder ethBuilder = new EthernetPacket.Builder();
            ethBuilder.dstAddr(dstMac)
                      .srcAddr(srcMac)
                      .type(GOOSE_ETHERTYPE)
                      .payloadBuilder(new UnknownPacket.Builder().rawData(payload))
                      .paddingAtBuild(true);  // Enable automatic Ethernet padding

            EthernetPacket packet = ethBuilder.build();

            // Send packet
            sendHandle.sendPacket(packet);
        }

        // Notify publish listener (for GUI table feedback)
        notifyPublishListener();
    }

    /**
     * Notify the publish listener with current message state
     */
    private void notifyPublishListener() {
        if (publishListener != null) {
            try {
                PublishedMessage msg = new PublishedMessage();
                msg.timestamp = new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(new java.util.Date());
                msg.appId = appId;
                msg.stNum = stNum;
                msg.sqNum = sqNum;
                msg.gocbRef = gocbRef;
                msg.goId = goId;
                msg.datSet = datSet;
                msg.dataValues = new ArrayList<>(dataValues);
                publishListener.accept(msg);
            } catch (Exception e) {
                // Don't let listener errors break publishing
            }
        }
    }

    /**
     * Build GOOSE payload (APPID + Length + Reserved + PDU)
     */
    private byte[] buildGoosePayload(byte[] pdu) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        // APPID (2 bytes)
        baos.write((appId >> 8) & 0xFF);
        baos.write(appId & 0xFF);

        // Length (2 bytes) - 8 header bytes + PDU
        int length = 8 + pdu.length;
        baos.write((length >> 8) & 0xFF);
        baos.write(length & 0xFF);

        // Reserved1 (2 bytes)
        baos.write(0);
        baos.write(0);

        // Reserved2 (2 bytes)
        baos.write(0);
        baos.write(0);

        // GOOSE PDU
        try {
            baos.write(pdu);
        } catch (Exception e) {}

        return baos.toByteArray();
    }

    /**
     * Build GOOSE PDU (ASN.1 BER encoded)
     */
    private byte[] buildGoosePdu() {
        ByteArrayOutputStream pdu = new ByteArrayOutputStream();

        // Build inner content first
        ByteArrayOutputStream content = new ByteArrayOutputStream();

        // gocbRef [0] VISIBLE-STRING
        writeTagLengthValue(content, 0x80, gocbRef.getBytes());

        // timeAllowedToLive [1] INTEGER (ms)
        writeTagLengthValue(content, 0x81, encodeInteger(heartbeatInterval * 2));

        // datSet [2] VISIBLE-STRING
        writeTagLengthValue(content, 0x82, datSet.getBytes());

        // goID [3] VISIBLE-STRING (optional)
        if (goId != null && !goId.isEmpty()) {
            writeTagLengthValue(content, 0x83, goId.getBytes());
        }

        // t [4] UtcTime (8 bytes)
        writeTagLengthValue(content, 0x84, encodeUtcTime());

        // stNum [5] INTEGER
        writeTagLengthValue(content, 0x85, encodeInteger(stNum));

        // sqNum [6] INTEGER
        writeTagLengthValue(content, 0x86, encodeInteger(sqNum));

        // test [7] BOOLEAN
        writeTagLengthValue(content, 0x87, new byte[]{(byte) (testMode ? 0xFF : 0x00)});

        // confRev [8] INTEGER
        writeTagLengthValue(content, 0x88, encodeInteger(confRev));

        // ndsCom [9] BOOLEAN
        writeTagLengthValue(content, 0x89, new byte[]{(byte) (needsCommissioning ? 0xFF : 0x00)});

        // numDatSetEntries [10] INTEGER
        writeTagLengthValue(content, 0x8A, encodeInteger(dataValues.size()));

        // allData [11] SEQUENCE
        byte[] allData = encodeAllData();
        writeTagLengthValue(content, 0xAB, allData);

        // Wrap in goosePdu [APPLICATION 1]
        byte[] contentBytes = content.toByteArray();
        pdu.write(0x61);  // Tag for goosePdu
        writeLength(pdu, contentBytes.length);
        try {
            pdu.write(contentBytes);
        } catch (Exception e) {}

        return pdu.toByteArray();
    }

    /**
     * Encode all data values
     */
    private byte[] encodeAllData() {
        ByteArrayOutputStream data = new ByteArrayOutputStream();

        for (DataValue dv : dataValues) {
            switch (dv.type) {
                case BOOLEAN:
                    writeTagLengthValue(data, 0x83, new byte[]{(byte) ((Boolean) dv.value ? 0xFF : 0x00)});
                    break;
                case INTEGER:
                    writeTagLengthValue(data, 0x85, encodeInteger((Integer) dv.value));
                    break;
                case UNSIGNED:
                    writeTagLengthValue(data, 0x86, encodeInteger(((Number) dv.value).intValue()));
                    break;
                case FLOAT:
                    writeTagLengthValue(data, 0x87, encodeFloat((Float) dv.value));
                    break;
                case BITSTRING:
                    // Bitstring: first byte is padding bits count
                    int bits = ((Number) dv.value).intValue();
                    writeTagLengthValue(data, 0x84, new byte[]{0x00, (byte) bits});
                    break;
                case DBPOS:
                    // DoubleBitPos as bitstring (2 bits): 00=intermediate, 01=off, 10=on, 11=bad
                    int dbpos = ((Number) dv.value).intValue();
                    writeTagLengthValue(data, 0x84, new byte[]{0x06, (byte) (dbpos << 6)});
                    break;
                case VISIBLE_STRING:
                    writeTagLengthValue(data, 0x8A, ((String) dv.value).getBytes());
                    break;
            }
        }

        return data.toByteArray();
    }

    private void writeTagLengthValue(ByteArrayOutputStream baos, int tag, byte[] value) {
        baos.write(tag);
        writeLength(baos, value.length);
        try {
            baos.write(value);
        } catch (Exception e) {}
    }

    private void writeLength(ByteArrayOutputStream baos, int length) {
        if (length < 128) {
            baos.write(length);
        } else if (length < 256) {
            baos.write(0x81);
            baos.write(length);
        } else {
            baos.write(0x82);
            baos.write((length >> 8) & 0xFF);
            baos.write(length & 0xFF);
        }
    }

    private byte[] encodeInteger(int value) {
        if (value == 0) {
            return new byte[]{0};
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        boolean started = false;
        for (int i = 3; i >= 0; i--) {
            int b = (value >> (i * 8)) & 0xFF;
            if (b != 0 || started || i == 0) {
                // Handle sign extension
                if (!started && (b & 0x80) != 0 && value > 0) {
                    baos.write(0);
                }
                baos.write(b);
                started = true;
            }
        }
        return baos.toByteArray();
    }

    private byte[] encodeFloat(float value) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(0x08);  // Single precision indicator
        int bits = Float.floatToIntBits(value);
        baos.write((bits >> 24) & 0xFF);
        baos.write((bits >> 16) & 0xFF);
        baos.write((bits >> 8) & 0xFF);
        baos.write(bits & 0xFF);
        return baos.toByteArray();
    }

    private byte[] encodeUtcTime() {
        // UTC Time: 4 bytes seconds since 1970, 3 bytes fraction, 1 byte quality
        long now = System.currentTimeMillis();
        int seconds = (int) (now / 1000);
        int fraction = (int) ((now % 1000) * 16777); // Scale to 24-bit fraction

        return new byte[]{
            (byte) ((seconds >> 24) & 0xFF),
            (byte) ((seconds >> 16) & 0xFF),
            (byte) ((seconds >> 8) & 0xFF),
            (byte) (seconds & 0xFF),
            (byte) ((fraction >> 16) & 0xFF),
            (byte) ((fraction >> 8) & 0xFF),
            (byte) (fraction & 0xFF),
            0x18  // Quality: clockNotSynchronized=false, leapSecondsKnown=true
        };
    }

    /**
     * Close and cleanup
     */
    public void close() {
        stopPublishing();
        scheduler.shutdown();
        if (sendHandle != null && sendHandle.isOpen()) {
            sendHandle.close();
        }
    }

    // Getters and setters
    public void setGocbRef(String gocbRef) { this.gocbRef = gocbRef; }
    public void setGoId(String goId) { this.goId = goId; }
    public void setDatSet(String datSet) { this.datSet = datSet; }
    public void setAppId(int appId) { this.appId = appId; }
    public void setConfRev(int confRev) { this.confRev = confRev; }
    public void setTestMode(boolean testMode) { this.testMode = testMode; }
    public void setHeartbeatInterval(int ms) { this.heartbeatInterval = ms; }
    public void setDstMac(String mac) { this.dstMac = MacAddress.getByName(mac); }

    public List<DataValue> getDataValues() { return dataValues; }
    public void setDataValues(List<DataValue> values) { this.dataValues = values; }

    public void setDataValue(int index, Object value) {
        if (index >= 0 && index < dataValues.size()) {
            dataValues.get(index).value = value;
        }
    }

    public int getStNum() { return stNum; }
    public int getSqNum() { return sqNum; }
    public boolean isPublishing() { return publishing; }
    public String getGocbRef() { return gocbRef; }
    public String getGoId() { return goId; }
    public int getAppId() { return appId; }
    public String getDatSet() { return datSet; }

    /**
     * Reset counters (stNum and sqNum) to initial values
     */
    public void resetCounters() {
        stNum = 1;
        sqNum = 0;
        log("Contadores reseteados: stNum=1, sqNum=0");
    }

    /**
     * Set stNum directly (for synchronization)
     */
    public void setStNum(int value) {
        this.stNum = value;
    }
}
