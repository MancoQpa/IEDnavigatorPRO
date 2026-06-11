package com.iednavigator;

import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * GOOSE over UDP Bridge
 * Allows GOOSE communication over IP networks (WiFi, routed networks)
 * Based on IEC 61850-90-5 R-GOOSE concept (simplified)
 *
 * This encapsulates GOOSE frames in UDP packets for transport over IP networks
 * where Layer 2 multicast doesn't work (like WiFi hotspots)
 */
public class GooseUdpBridge {

    // Default UDP port for GOOSE over UDP
    public static final int DEFAULT_PORT = 62746;

    // Multicast group for GOOSE over UDP (optional)
    public static final String MULTICAST_GROUP = "239.255.88.184"; // Based on 0x88B8

    private DatagramSocket sendSocket;
    private DatagramSocket receiveSocket;
    private MulticastSocket multicastSocket;

    private volatile boolean receiving = false;
    private ExecutorService executor;

    private Consumer<GooseSubscriber.GooseMessage> messageListener;
    private Consumer<String> logListener;

    private int port = DEFAULT_PORT;
    private InetAddress targetAddress;
    private boolean useMulticast = false;
    private boolean useBroadcast = true;

    // Stats
    private int sentCount = 0;
    private int receivedCount = 0;

    public GooseUdpBridge() {
        executor = Executors.newSingleThreadExecutor();
    }

    public void setMessageListener(Consumer<GooseSubscriber.GooseMessage> listener) {
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
     * Initialize for sending GOOSE over UDP
     * @param targetIp Target IP address (or null for broadcast)
     */
    public boolean initSender(String targetIp) {
        try {
            sendSocket = new DatagramSocket();
            sendSocket.setBroadcast(true);

            if (targetIp != null && !targetIp.isEmpty()) {
                targetAddress = InetAddress.getByName(targetIp);
                useBroadcast = false;
                log("GOOSE-UDP Sender: unicast to " + targetIp + ":" + port);
            } else {
                // Use broadcast
                targetAddress = InetAddress.getByName("255.255.255.255");
                useBroadcast = true;
                log("GOOSE-UDP Sender: broadcast on port " + port);
            }

            return true;
        } catch (Exception e) {
            log("Error initializing GOOSE-UDP sender: " + e.getMessage());
            return false;
        }
    }

    /**
     * Initialize for receiving GOOSE over UDP
     */
    public boolean initReceiver() {
        try {
            receiveSocket = new DatagramSocket(port);
            receiveSocket.setBroadcast(true);
            receiveSocket.setSoTimeout(1000); // 1 second timeout for clean shutdown

            log("GOOSE-UDP Receiver: listening on port " + port);
            return true;
        } catch (Exception e) {
            log("Error initializing GOOSE-UDP receiver: " + e.getMessage());
            return false;
        }
    }

    /**
     * Start receiving GOOSE messages over UDP
     */
    public boolean startReceiving() {
        if (receiveSocket == null) {
            if (!initReceiver()) {
                return false;
            }
        }

        receiving = true;
        executor.submit(this::receiveLoop);
        log("GOOSE-UDP receiver started");
        return true;
    }

    /**
     * Stop receiving
     */
    public void stopReceiving() {
        receiving = false;
        if (receiveSocket != null) {
            receiveSocket.close();
            receiveSocket = null;
        }
        log("GOOSE-UDP receiver stopped. Received: " + receivedCount + " messages");
    }

    /**
     * Send a GOOSE message over UDP
     */
    public boolean send(GoosePublisher publisher) {
        if (sendSocket == null) {
            if (!initSender(null)) {
                return false;
            }
        }

        try {
            // Create GOOSE-UDP packet
            byte[] data = createGooseUdpPacket(publisher);

            DatagramPacket packet = new DatagramPacket(data, data.length, targetAddress, port);
            sendSocket.send(packet);
            sentCount++;

            return true;
        } catch (Exception e) {
            log("Error sending GOOSE-UDP: " + e.getMessage());
            return false;
        }
    }

    /**
     * Send raw GOOSE frame data over UDP
     */
    public boolean sendRaw(byte[] gooseFrame, int appId, int stNum, int sqNum, String gocbRef) {
        if (sendSocket == null) {
            if (!initSender(null)) {
                return false;
            }
        }

        try {
            // Create packet with header
            byte[] packet = new byte[gooseFrame.length + 20];
            int offset = 0;

            // Magic header "GOUD" (GOOSE Over UDP)
            packet[offset++] = 'G';
            packet[offset++] = 'O';
            packet[offset++] = 'U';
            packet[offset++] = 'D';

            // Version (1 byte)
            packet[offset++] = 1;

            // AppID (2 bytes)
            packet[offset++] = (byte) ((appId >> 8) & 0xFF);
            packet[offset++] = (byte) (appId & 0xFF);

            // stNum (4 bytes)
            packet[offset++] = (byte) ((stNum >> 24) & 0xFF);
            packet[offset++] = (byte) ((stNum >> 16) & 0xFF);
            packet[offset++] = (byte) ((stNum >> 8) & 0xFF);
            packet[offset++] = (byte) (stNum & 0xFF);

            // sqNum (4 bytes)
            packet[offset++] = (byte) ((sqNum >> 24) & 0xFF);
            packet[offset++] = (byte) ((sqNum >> 16) & 0xFF);
            packet[offset++] = (byte) ((sqNum >> 8) & 0xFF);
            packet[offset++] = (byte) (sqNum & 0xFF);

            // Payload length (2 bytes)
            packet[offset++] = (byte) ((gooseFrame.length >> 8) & 0xFF);
            packet[offset++] = (byte) (gooseFrame.length & 0xFF);

            // Reserved (2 bytes)
            packet[offset++] = 0;
            packet[offset++] = 0;

            // Copy payload
            System.arraycopy(gooseFrame, 0, packet, offset, gooseFrame.length);

            DatagramPacket udpPacket = new DatagramPacket(packet, packet.length, targetAddress, port);
            sendSocket.send(udpPacket);
            sentCount++;

            return true;
        } catch (Exception e) {
            log("Error sending GOOSE-UDP raw: " + e.getMessage());
            return false;
        }
    }

    /**
     * Create a GOOSE-UDP packet from publisher state
     */
    private byte[] createGooseUdpPacket(GoosePublisher publisher) {
        // Simple format: header + GOOSE-like data
        // Header: "GOUD" (4) + version (1) + appId (2) + stNum (4) + sqNum (4) + dataLen (2) + reserved (2) = 19 bytes
        // Data: gocbRef + values

        StringBuilder data = new StringBuilder();
        data.append(publisher.getGocbRef()).append("|");
        data.append(publisher.getGoId()).append("|");
        data.append(publisher.getDatSet()).append("|");
        // Add state value (simplified)
        data.append("stVal=").append(publisher.getStNum() % 4); // Simulated DbPos value

        byte[] dataBytes = data.toString().getBytes();
        byte[] packet = new byte[20 + dataBytes.length];

        int offset = 0;

        // Magic header "GOUD"
        packet[offset++] = 'G';
        packet[offset++] = 'O';
        packet[offset++] = 'U';
        packet[offset++] = 'D';

        // Version
        packet[offset++] = 1;

        // AppID (2 bytes)
        int appId = publisher.getAppId();
        packet[offset++] = (byte) ((appId >> 8) & 0xFF);
        packet[offset++] = (byte) (appId & 0xFF);

        // stNum (4 bytes)
        int stNum = publisher.getStNum();
        packet[offset++] = (byte) ((stNum >> 24) & 0xFF);
        packet[offset++] = (byte) ((stNum >> 16) & 0xFF);
        packet[offset++] = (byte) ((stNum >> 8) & 0xFF);
        packet[offset++] = (byte) (stNum & 0xFF);

        // sqNum (4 bytes)
        int sqNum = publisher.getSqNum();
        packet[offset++] = (byte) ((sqNum >> 24) & 0xFF);
        packet[offset++] = (byte) ((sqNum >> 16) & 0xFF);
        packet[offset++] = (byte) ((sqNum >> 8) & 0xFF);
        packet[offset++] = (byte) (sqNum & 0xFF);

        // Data length (2 bytes)
        packet[offset++] = (byte) ((dataBytes.length >> 8) & 0xFF);
        packet[offset++] = (byte) (dataBytes.length & 0xFF);

        // Reserved (2 bytes)
        packet[offset++] = 0;
        packet[offset++] = 0;

        // Copy data
        System.arraycopy(dataBytes, 0, packet, offset, dataBytes.length);

        return packet;
    }

    /**
     * Receive loop
     */
    private void receiveLoop() {
        byte[] buffer = new byte[2048];

        while (receiving) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                receiveSocket.receive(packet);

                // Process packet
                processPacket(packet);

            } catch (SocketTimeoutException e) {
                // Normal timeout, continue
            } catch (Exception e) {
                if (receiving) {
                    log("Error receiving GOOSE-UDP: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Process received UDP packet
     */
    private void processPacket(DatagramPacket packet) {
        byte[] data = packet.getData();
        int length = packet.getLength();

        if (length < 20) {
            return; // Too short
        }

        // Check magic header
        if (data[0] != 'G' || data[1] != 'O' || data[2] != 'U' || data[3] != 'D') {
            return; // Not a GOOSE-UDP packet
        }

        receivedCount++;

        int offset = 4;

        // Version
        int version = data[offset++] & 0xFF;

        // AppID
        int appId = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
        offset += 2;

        // stNum
        int stNum = ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16) |
                    ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
        offset += 4;

        // sqNum
        int sqNum = ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16) |
                    ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
        offset += 4;

        // Data length
        int dataLen = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
        offset += 2;

        // Skip reserved
        offset += 2;

        // Extract data
        String dataStr = "";
        if (dataLen > 0 && offset + dataLen <= length) {
            dataStr = new String(data, offset, dataLen);
        }

        // Parse data string
        String gocbRef = "";
        String goId = "";
        String datSet = "";
        String stVal = "0";

        String[] parts = dataStr.split("\\|");
        if (parts.length >= 1) gocbRef = parts[0];
        if (parts.length >= 2) goId = parts[1];
        if (parts.length >= 3) datSet = parts[2];
        if (parts.length >= 4) {
            String valPart = parts[3];
            if (valPart.startsWith("stVal=")) {
                stVal = valPart.substring(6);
            }
        }

        // Create GooseMessage
        GooseSubscriber.GooseMessage msg = new GooseSubscriber.GooseMessage();
        msg.timestamp = new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(new java.util.Date());
        msg.srcMac = packet.getAddress().getHostAddress();
        msg.dstMac = "UDP:" + port;
        msg.gocbRef = gocbRef;
        msg.goId = goId;
        msg.datSet = datSet;
        msg.appId = appId;
        msg.stNum = stNum;
        msg.sqNum = sqNum;
        msg.test = false;
        msg.ndsCom = false;
        msg.confRev = 1;
        msg.numDataSetEntries = 1;

        // Add data entry
        try {
            int dbPosValue = Integer.parseInt(stVal);
            msg.dataEntries.add(new GooseSubscriber.DataEntry(0, "DbPos", dbPosValue));
        } catch (NumberFormatException e) {
            msg.dataEntries.add(new GooseSubscriber.DataEntry(0, "String", stVal));
        }

        // Notify listener
        if (messageListener != null) {
            messageListener.accept(msg);
        }
    }

    /**
     * Close all sockets
     */
    public void close() {
        stopReceiving();
        if (sendSocket != null) {
            sendSocket.close();
            sendSocket = null;
        }
        executor.shutdownNow();
    }

    public boolean isReceiving() {
        return receiving;
    }

    public int getSentCount() {
        return sentCount;
    }

    public int getReceivedCount() {
        return receivedCount;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public int getPort() {
        return port;
    }

    /**
     * Set target IP for unicast sending
     */
    public void setTargetIp(String ip) {
        try {
            if (ip != null && !ip.isEmpty()) {
                targetAddress = InetAddress.getByName(ip);
                useBroadcast = false;
            } else {
                targetAddress = InetAddress.getByName("255.255.255.255");
                useBroadcast = true;
            }
        } catch (Exception e) {
            log("Error setting target IP: " + e.getMessage());
        }
    }
}
