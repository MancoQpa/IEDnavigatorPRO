package com.iedexplorer;

import com.beanit.iec61850bean.*;
import com.iedexplorer.native_lib.*;
import org.pcap4j.core.*;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.text.SimpleDateFormat;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

/**
 * GoosePanel - Manages the GOOSE tab of IEDNavigator.
 * Extracted from IEDExplorerApp as part of Phase 5 refactoring.
 */
class GoosePanel {

    // ─── Context interface ────────────────────────────────────────────────────
    interface Context {
        void log(String msg);
        IEC61850Server getServer();
        IEC61850Client getClient();
        boolean isConnected();
        boolean isServerRunning();
        boolean isServerMode();
        ExecutorService backgroundExecutor();
        void updateSingleNodeInTree(String modelRef);
        void updateServerMonitorValues();
        String formatReference(String ref);
        Component parentWindow();
        int showIEDSelectionDialog(List<String> iedNames, String fileName);
        String getLoadedIedName();
        void setLoadedIedName(String name);
        String[] getLoadedIedNameplate();
        void setLoadedIedNameplate(String[] np);
        File getLoadedSclFile();
        void setLoadedSclFile(File f);
        List<SclGoCB> getSclGoCBs();
        void setSclGoCBs(List<SclGoCB> gcbs);
        List<SclDataSet> getSclDataSets();
        void setSclDataSets(List<SclDataSet> datasets);
        void setSclEnumMaps(
            Map<String, LinkedHashMap<Integer, String>> enumTypes,
            Map<String, String> daEnumType,
            Map<String, Map<String, String>> lnTypeDoTypes,
            Map<String, String> lnClassToLnType);
        Map<String, LinkedHashMap<Integer, String>> getSclEnumTypes();
        Map<String, String> getSclDaEnumType();
        Map<String, Map<String, String>> getSclLnTypeDoTypes();
        Map<String, String> getSclLnClassToLnType();
        void onSclLoaded();
    }

    private final Context ctx;

    // GOOSE-specific GUI fields
    private JTable gooseTable;
    private DefaultTableModel gooseTableModel;
    private JTable gooseDataTable;
    private DefaultTableModel gooseDataTableModel;
    private JTextArea gooseLogArea;
    private GooseSubscriber gooseSubscriber;
    private GoosePublisher goosePublisher;
    private Map<Integer, GoosePublisher> activePublishers = new LinkedHashMap<>();
    private GooseUdpBridge gooseUdpBridge;
    private JComboBox<String> gooseInterfaceCombo;
    private JButton btnGooseStartStop;
    private JButton btnGoosePublish;
    private JButton btnGooseStateChange;
    private JButton btnPublicarTodos;
    private JLabel lblGooseStatus;
    private JLabel lblPublishStatus;
    private Map<String, PcapNetworkInterface> interfaceMap = new HashMap<>();
    private Map<Integer, GooseSubscriber.GooseMessage> gooseMessages = new LinkedHashMap<>();
    private JComboBox<String> cbGooseState;

    private NativeGooseSubscriber nativeGooseSubscriber;

    private volatile boolean internalLoopbackEnabled = false;
    private volatile boolean udpBridgeEnabled = false;

    GoosePanel(Context ctx) {
        this.ctx = ctx;
    }

    // ─── Public accessors for GOOSE-MODEL SYNC section in IEDExplorerApp ─────

    GoosePublisher getGoosePublisher() {
        return goosePublisher;
    }

    Map<Integer, GoosePublisher> getActivePublishers() {
        return activePublishers;
    }

    void logGoose(String message) {
        SwingUtilities.invokeLater(() -> {
            String timestamp = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
            if (gooseLogArea != null) {
                gooseLogArea.append("[" + timestamp + "] " + message + "\n");
                gooseLogArea.setCaretPosition(gooseLogArea.getDocument().getLength());
            }
        });
    }

    /** Infer IEC 61850 data type from FCDA member name (public so TREE POPUP can call it). */
    GoosePublisher.DataValue.Type inferDataType(String memberName) {
        String lower = memberName.toLowerCase();

        if (lower.contains(".q ") || lower.contains(".q[") || lower.endsWith(".q")) {
            return GoosePublisher.DataValue.Type.BITSTRING;
        }
        if (lower.contains(".t ") || lower.contains(".t[") || lower.endsWith(".t")) {
            return GoosePublisher.DataValue.Type.UNSIGNED;
        }
        if (lower.contains(".general") || lower.contains(".stval") ||
            lower.contains(".op ") || lower.contains(".op[")) {
            return GoosePublisher.DataValue.Type.BOOLEAN;
        }
        if (lower.contains("pos.") && (lower.contains(".stval") || lower.contains(".ctlval"))) {
            return GoosePublisher.DataValue.Type.DBPOS;
        }
        if (lower.contains(".mag") || lower.contains(".instmag") || lower.contains(".cval")) {
            return GoosePublisher.DataValue.Type.FLOAT;
        }
        if (lower.contains(".ctlval") || lower.contains(".setval") || lower.contains(".actval")) {
            return GoosePublisher.DataValue.Type.INTEGER;
        }
        return GoosePublisher.DataValue.Type.BOOLEAN;
    }

    // ─── Panel creation ───────────────────────────────────────────────────────

    JPanel createPanel() {
        JPanel panel = new JPanel(new BorderLayout(3, 3));
        panel.setBorder(BorderFactory.createEmptyBorder(3, 3, 3, 3));

        // Initialize Native GOOSE subscriber (libiec61850) - preferred
        nativeGooseSubscriber = new NativeGooseSubscriber();
        nativeGooseSubscriber.setLogListener(msg -> logGoose("[NATIVE] " + msg));
        nativeGooseSubscriber.setMessageListener(msg -> handleNativeGooseMessage(msg));

        // Initialize pcap4j GOOSE subscriber as fallback
        gooseSubscriber = new GooseSubscriber();
        gooseSubscriber.setLogListener(msg -> logGoose("[PCAP] " + msg));
        gooseSubscriber.setMessageListener(msg -> handleGooseMessage(msg));

        // Publisher (still uses pcap4j)
        goosePublisher = new GoosePublisher();
        goosePublisher.setLogListener(msg -> logGoose("[PUB] " + msg));
        goosePublisher.setPublishListener(pubMsg -> {
            GooseSubscriber.GooseMessage tableMsg = new GooseSubscriber.GooseMessage();
            tableMsg.timestamp = pubMsg.timestamp;
            tableMsg.srcMac = "LOCAL";
            tableMsg.dstMac = "01:0C:CD:01:00:01";
            tableMsg.gocbRef = pubMsg.gocbRef;
            tableMsg.goId = pubMsg.goId;
            tableMsg.datSet = pubMsg.datSet;
            tableMsg.appId = pubMsg.appId;
            tableMsg.stNum = pubMsg.stNum;
            tableMsg.sqNum = pubMsg.sqNum;
            tableMsg.confRev = 1;
            tableMsg.numDataSetEntries = pubMsg.dataValues != null ? pubMsg.dataValues.size() : 0;
            if (pubMsg.dataValues != null) {
                int idx = 0;
                for (GoosePublisher.DataValue dv : pubMsg.dataValues) {
                    String typeName = dv.type.name();
                    tableMsg.dataEntries.add(new GooseSubscriber.DataEntry(idx++, typeName, dv.value));
                }
            }
            handleGooseMessage(tableMsg);
        });

        // UDP Bridge for WiFi/routed networks
        gooseUdpBridge = new GooseUdpBridge();
        gooseUdpBridge.setLogListener(msg -> logGoose("[UDP] " + msg));
        gooseUdpBridge.setMessageListener(msg -> handleGooseMessage(msg));

        // ===== TOP PANEL: Two rows for better visibility =====
        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.setBackground(new Color(240, 240, 245));
        topPanel.setBorder(BorderFactory.createEmptyBorder(3, 5, 3, 5));

        // Row 1: Interface + Capture Button
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 3));
        row1.setOpaque(false);

        row1.add(new JLabel("Interface:"));
        gooseInterfaceCombo = new JComboBox<>();
        gooseInterfaceCombo.setPreferredSize(new Dimension(300, 26));
        loadNetworkInterfaces();
        row1.add(gooseInterfaceCombo);

        JButton btnRefresh = new JButton("↻");
        btnRefresh.setToolTipText("Refrescar interfaces");
        btnRefresh.setMargin(new Insets(2, 6, 2, 6));
        btnRefresh.addActionListener(e -> loadNetworkInterfaces());
        row1.add(btnRefresh);

        row1.add(Box.createHorizontalStrut(15));

        btnGooseStartStop = new JButton("▶ CAPTURAR GOOSE");
        btnGooseStartStop.setBackground(new Color(46, 125, 50));
        btnGooseStartStop.setForeground(Color.WHITE);
        btnGooseStartStop.setFocusPainted(false);
        btnGooseStartStop.setFont(btnGooseStartStop.getFont().deriveFont(Font.BOLD));
        btnGooseStartStop.setPreferredSize(new Dimension(160, 28));
        btnGooseStartStop.addActionListener(e -> toggleGooseCapture());
        row1.add(btnGooseStartStop);

        lblGooseStatus = new JLabel("Detenido");
        lblGooseStatus.setForeground(Color.GRAY);
        row1.add(lblGooseStatus);

        topPanel.add(row1, BorderLayout.NORTH);

        // Row 2: Publisher controls
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 3));
        row2.setOpaque(false);

        row2.add(new JLabel("Publicar:"));
        btnGoosePublish = new JButton("▶ Publicar");
        btnGoosePublish.setBackground(new Color(21, 101, 192));
        btnGoosePublish.setForeground(Color.WHITE);
        btnGoosePublish.setFocusPainted(false);
        btnGoosePublish.addActionListener(e -> toggleGoosePublishing());
        row2.add(btnGoosePublish);

        lblPublishStatus = new JLabel("Detenido");
        lblPublishStatus.setForeground(Color.GRAY);
        row2.add(lblPublishStatus);

        row2.add(Box.createHorizontalStrut(20));
        row2.add(new JLabel("Estado:"));
        cbGooseState = new JComboBox<>(new String[]{"OFF", "ON", "INTERMEDIATE", "BAD"});
        cbGooseState.setPreferredSize(new Dimension(110, 24));
        row2.add(cbGooseState);

        btnGooseStateChange = new JButton("⚡ Cambio");
        btnGooseStateChange.setToolTipText("Enviar cambio de estado GOOSE");
        btnGooseStateChange.setBackground(new Color(230, 81, 0));
        btnGooseStateChange.setForeground(Color.WHITE);
        btnGooseStateChange.setFocusPainted(false);
        btnGooseStateChange.addActionListener(e -> publishGooseStateChange());
        btnGooseStateChange.setEnabled(false);
        row2.add(btnGooseStateChange);

        topPanel.add(row2, BorderLayout.SOUTH);

        panel.add(topPanel, BorderLayout.NORTH);

        // ===== CENTER: Messages Table =====
        String[] captureColumns = {"Tiempo", "AppID", "st#", "sq#", "gocbRef", "Datos"};
        gooseDataTableModel = new DefaultTableModel(captureColumns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        gooseDataTable = new JTable(gooseDataTableModel);
        gooseDataTable.setRowHeight(20);
        gooseDataTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        gooseDataTable.getColumnModel().getColumn(0).setPreferredWidth(80);
        gooseDataTable.getColumnModel().getColumn(1).setPreferredWidth(55);
        gooseDataTable.getColumnModel().getColumn(2).setPreferredWidth(40);
        gooseDataTable.getColumnModel().getColumn(3).setPreferredWidth(40);
        gooseDataTable.getColumnModel().getColumn(4).setPreferredWidth(180);
        gooseDataTable.getColumnModel().getColumn(5).setPreferredWidth(250);

        gooseDataTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (!isSelected) {
                    try {
                        Object stNumObj = table.getValueAt(row, 2);
                        if (stNumObj != null) {
                            int stNum = Integer.parseInt(stNumObj.toString());
                            c.setBackground(stNum > 1 ? new Color(255, 253, 208) : Color.WHITE);
                        }
                    } catch (Exception e) { c.setBackground(Color.WHITE); }
                }
                return c;
            }
        });

        JScrollPane tableScroll = new JScrollPane(gooseDataTable);
        tableScroll.setBorder(BorderFactory.createTitledBorder("Mensajes GOOSE"));
        panel.add(tableScroll, BorderLayout.CENTER);

        // ===== BOTTOM: GoCBs + Log =====
        JPanel bottomPanel = new JPanel(new GridLayout(1, 2, 5, 0));
        bottomPanel.setPreferredSize(new Dimension(100, 140));

        String[] gooseColumns = {"GoCB Reference", "GoID", "DataSet", "AppID", "MAC", "ConfRev", "Estado"};
        gooseTableModel = new DefaultTableModel(gooseColumns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        gooseTable = new JTable(gooseTableModel);
        gooseTable.setRowHeight(18);
        gooseTable.getColumnModel().getColumn(0).setPreferredWidth(160);
        gooseTable.getColumnModel().getColumn(1).setPreferredWidth(200);
        gooseTable.getColumnModel().getColumn(2).setPreferredWidth(80);
        gooseTable.getColumnModel().getColumn(3).setPreferredWidth(45);
        gooseTable.getColumnModel().getColumn(4).setPreferredWidth(120);
        gooseTable.getColumnModel().getColumn(5).setPreferredWidth(50);
        gooseTable.getColumnModel().getColumn(6).setPreferredWidth(70);

        JPopupMenu gcbPopupMenu = new JPopupMenu();
        JMenuItem menuPublicar = new JMenuItem("Publicar este GoCB");
        menuPublicar.addActionListener(e -> {
            int row = gooseTable.getSelectedRow();
            if (row >= 0 && row < ctx.getSclGoCBs().size()) {
                publishSelectedGoCB(row);
            }
        });
        gcbPopupMenu.add(menuPublicar);

        JMenuItem menuDetenerUno = new JMenuItem("Detener este GoCB");
        menuDetenerUno.addActionListener(e -> {
            int row = gooseTable.getSelectedRow();
            if (row >= 0 && activePublishers.containsKey(row)) {
                GoosePublisher pub = activePublishers.get(row);
                pub.stopPublishing();
                pub.close();
                activePublishers.remove(row);
                if (row < gooseTableModel.getRowCount()) {
                    gooseTableModel.setValueAt("Detenido", row, 6);
                }
                logGoose("GoCB #" + row + " detenido");
                if (activePublishers.isEmpty()) {
                    btnPublicarTodos.setText("Publicar Todos");
                    btnPublicarTodos.setBackground(new Color(0, 130, 60));
                    lblPublishStatus.setText("  Detenido");
                    lblPublishStatus.setForeground(Color.GRAY);
                }
            }
        });
        gcbPopupMenu.add(menuDetenerUno);

        gcbPopupMenu.addSeparator();

        JMenu menuCambiarEstado = new JMenu("Cambiar Estado de este GoCB");
        gcbPopupMenu.add(menuCambiarEstado);

        gcbPopupMenu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                menuCambiarEstado.removeAll();
                int row = gooseTable.getSelectedRow();
                if (row < 0 || row >= ctx.getSclGoCBs().size()) return;

                GoosePublisher pub = activePublishers.get(row);
                GoosePublisher.DataValue.Type firstType = null;

                if (pub != null && !pub.getDataValues().isEmpty()) {
                    firstType = pub.getDataValues().get(0).type;
                } else {
                    SclGoCB gcb = ctx.getSclGoCBs().get(row);
                    List<GoosePublisher.DataValue> inferred = buildDataValuesFromDataSet(gcb);
                    if (!inferred.isEmpty()) firstType = inferred.get(0).type;
                }

                if (firstType == GoosePublisher.DataValue.Type.BOOLEAN) {
                    for (String[] opt : new String[][]{{"TRUE", "TRUE"}, {"FALSE", "FALSE"}}) {
                        JMenuItem mi = new JMenuItem(opt[0]);
                        String val = opt[1];
                        mi.addActionListener(ev -> { int r = gooseTable.getSelectedRow(); if (r >= 0) changeGoCBState(r, val); });
                        menuCambiarEstado.add(mi);
                    }
                } else if (firstType == GoosePublisher.DataValue.Type.DBPOS) {
                    for (String[] opt : new String[][]{
                            {"ON (Cerrado)", "ON"}, {"OFF (Abierto)", "OFF"},
                            {"INTERMEDIATE", "INTERMEDIATE"}, {"BAD", "BAD"}}) {
                        JMenuItem mi = new JMenuItem(opt[0]);
                        String val = opt[1];
                        mi.addActionListener(ev -> { int r = gooseTable.getSelectedRow(); if (r >= 0) changeGoCBState(r, val); });
                        menuCambiarEstado.add(mi);
                    }
                } else {
                    for (String[] opt : new String[][]{{"TRUE", "TRUE"}, {"FALSE", "FALSE"}}) {
                        JMenuItem mi = new JMenuItem(opt[0]);
                        String val = opt[1];
                        mi.addActionListener(ev -> { int r = gooseTable.getSelectedRow(); if (r >= 0) changeGoCBState(r, val); });
                        menuCambiarEstado.add(mi);
                    }
                }

                if (pub != null && pub.getDataValues().size() > 1) {
                    menuCambiarEstado.addSeparator();
                    for (int i = 0; i < pub.getDataValues().size(); i++) {
                        GoosePublisher.DataValue dv = pub.getDataValues().get(i);
                        JMenu memberMenu = new JMenu("[" + i + "] " + dv.name + " (" + dv.type + ")");
                        final int idx = i;
                        final int pubRow = row;
                        if (dv.type == GoosePublisher.DataValue.Type.BOOLEAN) {
                            JMenuItem miT = new JMenuItem("TRUE");
                            miT.addActionListener(ev -> setPublisherDataValue(pubRow, idx, true));
                            memberMenu.add(miT);
                            JMenuItem miF = new JMenuItem("FALSE");
                            miF.addActionListener(ev -> setPublisherDataValue(pubRow, idx, false));
                            memberMenu.add(miF);
                        } else if (dv.type == GoosePublisher.DataValue.Type.DBPOS) {
                            for (String[] opt : new String[][]{{"ON", "2"}, {"OFF", "1"}, {"INTERMEDIATE", "0"}, {"BAD", "3"}}) {
                                JMenuItem mi = new JMenuItem(opt[0]);
                                String val = opt[1];
                                mi.addActionListener(ev -> setPublisherDataValue(pubRow, idx, Integer.parseInt(val)));
                                memberMenu.add(mi);
                            }
                        } else if (dv.type == GoosePublisher.DataValue.Type.BITSTRING) {
                            JMenuItem miGood = new JMenuItem("GOOD (0x0000)");
                            miGood.addActionListener(ev -> setPublisherDataValue(pubRow, idx, 0));
                            memberMenu.add(miGood);
                            JMenuItem miBad = new JMenuItem("INVALID (0x0004)");
                            miBad.addActionListener(ev -> setPublisherDataValue(pubRow, idx, 4));
                            memberMenu.add(miBad);
                        } else {
                            JMenuItem miCustom = new JMenuItem("Valor personalizado...");
                            miCustom.addActionListener(ev -> {
                                String val = JOptionPane.showInputDialog(null, "Nuevo valor para " + dv.name + ":", String.valueOf(dv.value));
                                if (val != null) {
                                    try { setPublisherDataValue(pubRow, idx, Integer.parseInt(val)); }
                                    catch (NumberFormatException ex) { setPublisherDataValue(pubRow, idx, val); }
                                }
                            });
                            memberMenu.add(miCustom);
                        }
                        menuCambiarEstado.add(memberMenu);
                    }
                }
            }
            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {}
            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {}
        });

        gcbPopupMenu.addSeparator();

        JMenuItem menuPublicarTodos = new JMenuItem("Publicar TODOS los GoCBs");
        menuPublicarTodos.addActionListener(e -> publishAllGoCBs());
        gcbPopupMenu.add(menuPublicarTodos);

        JMenuItem menuDetener = new JMenuItem("Detener TODOS");
        menuDetener.addActionListener(e -> stopAllPublishers());
        gcbPopupMenu.add(menuDetener);

        gooseTable.setComponentPopupMenu(gcbPopupMenu);
        gooseTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                if (e.isPopupTrigger() || javax.swing.SwingUtilities.isRightMouseButton(e)) {
                    int row = gooseTable.rowAtPoint(e.getPoint());
                    if (row >= 0) gooseTable.setRowSelectionInterval(row, row);
                }
            }
            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
                if (e.isPopupTrigger() || javax.swing.SwingUtilities.isRightMouseButton(e)) {
                    int row = gooseTable.rowAtPoint(e.getPoint());
                    if (row >= 0) gooseTable.setRowSelectionInterval(row, row);
                }
            }
        });

        JPanel gcbPanel = new JPanel(new BorderLayout());
        JScrollPane gcbScroll = new JScrollPane(gooseTable);
        JButton btnLoadGcb = new JButton("Cargar GoCBs");
        btnLoadGcb.addActionListener(e -> refreshGooseControlBlocks());
        JButton btnClearGcb = new JButton("Limpiar");
        btnClearGcb.addActionListener(e -> {
            gooseTableModel.setRowCount(0);
            ctx.getSclGoCBs().clear();
            logGoose("GoCBs limpiados");
        });

        JButton btnLoadScl = new JButton("Cargar SCL...");
        btnLoadScl.setToolTipText("Cargar archivo SCL/CID/ICD/SCD para obtener GoCBs");
        btnLoadScl.addActionListener(e -> cargarSclParaGoose());

        btnPublicarTodos = new JButton("Publicar Todos");
        btnPublicarTodos.setToolTipText("Publicar TODOS los GoCBs simultaneamente (modo IED)");
        btnPublicarTodos.setBackground(new Color(0, 130, 60));
        btnPublicarTodos.setForeground(Color.WHITE);
        btnPublicarTodos.addActionListener(e -> {
            if (!activePublishers.isEmpty()) {
                stopAllPublishers();
            } else {
                publishAllGoCBs();
            }
        });

        JPanel gcbBtnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
        gcbBtnPanel.add(btnLoadScl);
        gcbBtnPanel.add(btnLoadGcb);
        gcbBtnPanel.add(btnPublicarTodos);
        gcbBtnPanel.add(btnClearGcb);
        gcbPanel.add(gcbScroll, BorderLayout.CENTER);
        gcbPanel.add(gcbBtnPanel, BorderLayout.SOUTH);
        gcbPanel.setBorder(BorderFactory.createTitledBorder("GoCBs del Modelo"));
        bottomPanel.add(gcbPanel);

        gooseLogArea = new JTextArea();
        gooseLogArea.setEditable(false);
        gooseLogArea.setFont(new Font("Monospaced", Font.PLAIN, 10));
        JScrollPane logScroll = new JScrollPane(gooseLogArea);
        JPanel logPanel = new JPanel(new BorderLayout());
        logPanel.add(logScroll, BorderLayout.CENTER);
        JPanel logBtnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
        JButton btnClear = new JButton("Limpiar");
        btnClear.addActionListener(e -> {
            gooseLogArea.setText("");
            gooseDataTableModel.setRowCount(0);
            if (goosePublisher != null) {
                goosePublisher.resetCounters();
            }
            logGoose("Log y contadores limpiados");
        });
        JLabel lblCount = new JLabel("Msgs: 0");
        javax.swing.Timer countTimer = new javax.swing.Timer(500, e -> lblCount.setText("Msgs: " + gooseDataTableModel.getRowCount()));
        countTimer.start();
        logBtnPanel.add(btnClear);
        logBtnPanel.add(lblCount);
        logPanel.add(logBtnPanel, BorderLayout.SOUTH);
        logPanel.setBorder(BorderFactory.createTitledBorder("Log"));
        bottomPanel.add(logPanel);

        panel.add(bottomPanel, BorderLayout.SOUTH);

        return panel;
    }

    // ─── Network interface loading ─────────────────────────────────────────────

    void autoSelectGooseInterface(String localIp) {
        if (localIp == null || localIp.isEmpty() || gooseInterfaceCombo == null) {
            ctx.log("autoSelectGooseInterface: parametros invalidos - localIp=" + localIp);
            return;
        }

        ctx.log("=== AUTO-SELECCION DE INTERFAZ ===");
        ctx.log("Buscando interfaz para IP: " + localIp);
        ctx.log("Interfaces disponibles (" + gooseInterfaceCombo.getItemCount() + "):");
        for (int i = 0; i < gooseInterfaceCombo.getItemCount(); i++) {
            String item = gooseInterfaceCombo.getItemAt(i);
            boolean matches = item != null && item.contains(localIp);
            ctx.log("  [" + i + "] " + item + (matches ? " <-- MATCH" : ""));
        }

        for (int i = 0; i < gooseInterfaceCombo.getItemCount(); i++) {
            String item = gooseInterfaceCombo.getItemAt(i);
            if (item != null && item.contains(localIp)) {
                gooseInterfaceCombo.setSelectedIndex(i);
                logGoose("*** INTERFAZ AUTO-SELECCIONADA: " + item);
                ctx.log(">>> SELECCIONADA: " + item);
                return;
            }
        }

        String[] parts = localIp.split("\\.");
        if (parts.length >= 3) {
            String subnet = parts[0] + "." + parts[1] + "." + parts[2] + ".";
            ctx.log("Buscando por subred: " + subnet);
            for (int i = 0; i < gooseInterfaceCombo.getItemCount(); i++) {
                String item = gooseInterfaceCombo.getItemAt(i);
                if (item != null && item.contains(subnet)) {
                    gooseInterfaceCombo.setSelectedIndex(i);
                    logGoose("*** INTERFAZ AUTO-SELECCIONADA (subred): " + item);
                    ctx.log("Interfaz GOOSE seleccionada por subred: " + subnet);
                    return;
                }
            }
        }

        ctx.log("ADVERTENCIA: No se encontro interfaz para " + localIp + " - Seleccione manualmente");
        logGoose("ADVERTENCIA: Seleccione la interfaz correcta manualmente!");
    }

    private void loadNetworkInterfaces() {
        gooseInterfaceCombo.removeAllItems();
        interfaceMap.clear();

        gooseInterfaceCombo.addItem("★ Loopback Interno (pruebas misma maquina)");
        gooseInterfaceCombo.addItem("★ GOOSE sobre UDP (WiFi/Hotspot)");

        try {
            List<PcapNetworkInterface> interfaces = GooseSubscriber.getNetworkInterfaces();
            for (PcapNetworkInterface nif : interfaces) {
                String desc = nif.getDescription() != null ? nif.getDescription() : nif.getName();

                StringBuilder ips = new StringBuilder();
                for (PcapAddress addr : nif.getAddresses()) {
                    if (addr.getAddress() != null) {
                        String ip = addr.getAddress().getHostAddress();
                        if (!ip.contains(":") && !ip.startsWith("127.")) {
                            if (ips.length() > 0) ips.append(", ");
                            ips.append(ip);
                        }
                    }
                }

                String ipInfo = ips.length() > 0 ? " - " + ips.toString() : "";
                String name = desc + ipInfo;
                gooseInterfaceCombo.addItem(name);
                interfaceMap.put(name, nif);

                if (ips.length() > 0) {
                    logGoose("Interface: " + desc + " -> " + ips.toString());
                }
            }
            if (interfaces.isEmpty()) {
                gooseInterfaceCombo.addItem("No se encontraron interfaces (ejecutar como Admin?)");
            }
        } catch (Exception e) {
            gooseInterfaceCombo.addItem("Error: " + e.getMessage());
            ctx.log("Error cargando interfaces: " + e.getMessage());
        }
    }

    // ─── Capture toggle ────────────────────────────────────────────────────────

    private void toggleGooseCapture() {
        boolean nativeRunning = nativeGooseSubscriber != null && nativeGooseSubscriber.isRunning();
        boolean pcapRunning = gooseSubscriber != null && gooseSubscriber.isRunning();
        boolean loopbackRunning = internalLoopbackEnabled;
        boolean udpRunning = gooseUdpBridge != null && gooseUdpBridge.isReceiving();

        if (nativeRunning || pcapRunning || loopbackRunning || udpRunning) {
            if (nativeRunning) nativeGooseSubscriber.stop();
            if (pcapRunning) gooseSubscriber.stop();
            if (udpRunning) gooseUdpBridge.stopReceiving();
            internalLoopbackEnabled = false;
            udpBridgeEnabled = false;
            btnGooseStartStop.setText("▶ Capturar");
            btnGooseStartStop.setBackground(new Color(46, 125, 50));
            lblGooseStatus.setText("Detenido");
            lblGooseStatus.setForeground(Color.GRAY);
        } else {
            String selected = (String) gooseInterfaceCombo.getSelectedItem();
            if (selected == null) {
                JOptionPane.showMessageDialog(ctx.parentWindow(),
                    "Seleccione una interface de red valida.\n" +
                    "Si no aparecen interfaces, ejecute como Administrador.",
                    "GOOSE Capture", JOptionPane.WARNING_MESSAGE);
                return;
            }

            boolean started = false;

            if (selected.contains("Loopback Interno") || selected.contains("Internal Loopback")) {
                internalLoopbackEnabled = true;
                started = true;
                logGoose("=== MODO LOOPBACK INTERNO ACTIVADO ===");
                logGoose("Los mensajes GOOSE publicados se mostraran directamente");
                logGoose("(Sin pasar por la red - para pruebas en misma maquina)");
                lblGooseStatus.setText("Loopback Interno");

                javax.swing.Timer loopbackTimer = new javax.swing.Timer(2000, ev -> {
                    if (internalLoopbackEnabled) {
                        int gooseRows = gooseDataTableModel.getRowCount();
                        lblGooseStatus.setText("Loopback: " + gooseRows + " msgs");
                    }
                });
                loopbackTimer.start();
            } else if (selected.contains("GOOSE sobre UDP") || selected.contains("UDP")) {
                udpBridgeEnabled = true;
                logGoose("=== MODO GOOSE SOBRE UDP (WiFi/Hotspot) ===");
                logGoose("Puerto UDP: " + GooseUdpBridge.DEFAULT_PORT);
                logGoose("Este modo permite GOOSE sobre redes WiFi/IP");

                if (gooseUdpBridge.startReceiving()) {
                    started = true;
                    lblGooseStatus.setText("UDP Listener activo");
                    logGoose("*** ESCUCHANDO GOOSE-UDP en puerto " + GooseUdpBridge.DEFAULT_PORT + " ***");
                    logGoose("La otra maquina debe publicar en modo 'GOOSE sobre UDP'");

                    javax.swing.Timer udpTimer = new javax.swing.Timer(2000, ev -> {
                        if (udpBridgeEnabled && gooseUdpBridge.isReceiving()) {
                            int received = gooseUdpBridge.getReceivedCount();
                            lblGooseStatus.setText("UDP: " + received + " msgs");
                        }
                    });
                    udpTimer.start();
                } else {
                    logGoose("ERROR: No se pudo iniciar receptor UDP");
                    logGoose("Verifique que el puerto " + GooseUdpBridge.DEFAULT_PORT + " no este en uso");
                    udpBridgeEnabled = false;
                }
            } else {
                PcapNetworkInterface nif = interfaceMap.get(selected);

                if (nif == null) {
                    logGoose("ERROR: Interface no encontrada en el mapa");
                    JOptionPane.showMessageDialog(ctx.parentWindow(),
                        "Interface no valida. Recargue las interfaces.",
                        "GOOSE Capture", JOptionPane.ERROR_MESSAGE);
                    return;
                }

                String ifDesc = nif.getDescription() != null ? nif.getDescription().toLowerCase() : "";
                boolean isWifi = ifDesc.contains("wireless") || ifDesc.contains("wi-fi") ||
                                ifDesc.contains("wifi") || ifDesc.contains("802.11") ||
                                ifDesc.contains("wlan");
                if (isWifi) {
                    logGoose("*** ADVERTENCIA: Interface WiFi detectada ***");
                    logGoose("WiFi tiene limitaciones para captura GOOSE (multicast L2)");
                    logGoose("Se recomienda usar 'GOOSE sobre UDP' para WiFi");
                }

                String deviceName = nif.getName();
                logGoose("Interface seleccionada: " + nif.getDescription());
                logGoose("Device: " + deviceName);
                logGoose("=== INICIANDO CAPTURA pcap4j ===");
                logGoose("MACs: " + nif.getLinkLayerAddresses());
                logGoose("IPs: " + nif.getAddresses());

                try {
                    if (gooseSubscriber.start(nif)) {
                        started = true;
                        lblGooseStatus.setText("Capturando (pcap4j)");
                        logGoose("*** CAPTURA INICIADA - Esperando tramas GOOSE ***");
                        logGoose("Filtro: multicast/EtherType 0x88B8");
                        if (isWifi) {
                            logGoose("NOTA: En WiFi, algunos paquetes multicast pueden no capturarse");
                        }

                        javax.swing.Timer captureTimer = new javax.swing.Timer(2000, ev -> {
                            if (gooseSubscriber != null && gooseSubscriber.isRunning()) {
                                int pkts = gooseSubscriber.getPacketCount();
                                int goose = gooseSubscriber.getGooseCount();
                                lblGooseStatus.setText("Capturando: " + pkts + " pkts, " + goose + " GOOSE");
                            }
                        });
                        captureTimer.start();
                    } else {
                        logGoose("ERROR: No se pudo iniciar captura pcap4j");
                        logGoose("Posibles causas:");
                        logGoose("  - Ejecutar como Administrador");
                        logGoose("  - Verificar que Npcap este instalado con WinPcap compatibility");
                        logGoose("  - La interfaz WiFi puede no soportar modo promiscuo");
                        if (isWifi) {
                            logGoose("  - WiFi NO soporta captura GOOSE - use Ethernet");
                        }
                    }
                } catch (Exception e) {
                    logGoose("EXCEPCION iniciando captura: " + e.getMessage());
                    e.printStackTrace();
                }
            }

            if (started) {
                btnGooseStartStop.setText("⬛ Detener");
                btnGooseStartStop.setBackground(new Color(200, 50, 50));
                lblGooseStatus.setForeground(new Color(0, 150, 0));
            } else if (!internalLoopbackEnabled && !udpBridgeEnabled) {
                JOptionPane.showMessageDialog(ctx.parentWindow(),
                    "Error iniciando captura GOOSE.\n" +
                    "Verifique:\n" +
                    "- Ejecutar como Administrador\n" +
                    "- Npcap instalado con 'WinPcap API-compatible Mode'\n" +
                    "- Para WiFi, use 'GOOSE sobre UDP'",
                    "GOOSE Capture", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    // ─── Publishing ────────────────────────────────────────────────────────────

    private void publishSelectedGoCB(int gcbIndex) {
        List<SclGoCB> sclGoCBs = ctx.getSclGoCBs();
        if (gcbIndex < 0 || gcbIndex >= sclGoCBs.size()) {
            logGoose("Indice de GoCB invalido: " + gcbIndex);
            return;
        }

        if (activePublishers.containsKey(gcbIndex)) {
            GoosePublisher existing = activePublishers.get(gcbIndex);
            existing.stopPublishing();
            existing.close();
            activePublishers.remove(gcbIndex);
            logGoose("Publisher #" + gcbIndex + " detenido para reconfigurar");
        }

        String selected = (String) gooseInterfaceCombo.getSelectedItem();
        if (selected == null) {
            JOptionPane.showMessageDialog(ctx.parentWindow(),
                "Seleccione una interface de red primero.",
                "Publicar GoCB", JOptionPane.WARNING_MESSAGE);
            return;
        }

        boolean isLoopback = selected.contains("Loopback Interno");
        boolean isUdp = selected.contains("GOOSE sobre UDP") || selected.contains("UDP");
        PcapNetworkInterface nif = null;
        if (!isLoopback && !isUdp) {
            if (!interfaceMap.containsKey(selected)) {
                JOptionPane.showMessageDialog(ctx.parentWindow(),
                    "Seleccione una interface de red valida.",
                    "Publicar GoCB", JOptionPane.WARNING_MESSAGE);
                return;
            }
            nif = interfaceMap.get(selected);
        }

        SclGoCB gcb = sclGoCBs.get(gcbIndex);
        GoosePublisher pub = createPublisherForGoCB(gcb, gcbIndex);

        boolean initOk = isLoopback || isUdp || pub.init(nif);
        if (initOk) {
            pub.startPublishing();
            activePublishers.put(gcbIndex, pub);

            logGoose("=== GoCB #" + gcbIndex + " publicando: " + gcb.toString() + " ===");
            logGoose("  goID: " + pub.getGoId() + " | AppID: " + String.format("0x%04X", pub.getAppId()));

            if (gcbIndex < gooseTableModel.getRowCount()) {
                gooseTableModel.setValueAt("Publicando", gcbIndex, 6);
            }

            btnGoosePublish.setText("Detener Publicacion");
            btnGoosePublish.setBackground(new Color(200, 50, 50));
            String modeStr = isLoopback ? "Loopback" : (isUdp ? "UDP" : "L2");
            lblPublishStatus.setText("  " + activePublishers.size() + " GoCB(s) publicando (" + modeStr + ")");
            lblPublishStatus.setForeground(new Color(0, 150, 0));
        } else {
            logGoose("ERROR: No se pudo inicializar publisher para " + gcb.cbName);
            pub.close();
        }
    }

    private GoosePublisher createPublisherForGoCB(SclGoCB gcb, int gcbIndex) {
        GoosePublisher pub = new GoosePublisher();

        String gocbRef = gcb.ldInst + "/LLN0$GO$" + gcb.cbName;
        pub.setGocbRef(gocbRef);
        pub.setGoId(gcb.goID != null && !gcb.goID.isEmpty() ? gcb.goID : gcb.cbName);
        pub.setDatSet(gcb.ldInst + "/LLN0$" + (gcb.datSet != null ? gcb.datSet : "DataSet1"));

        if (gcb.appID != null && !gcb.appID.isEmpty()) {
            try {
                pub.setAppId(Integer.parseInt(gcb.appID, 16));
            } catch (NumberFormatException e) {
                try { pub.setAppId(Integer.parseInt(gcb.appID)); }
                catch (NumberFormatException e2) { pub.setAppId(0x0001 + gcbIndex); }
            }
        } else {
            pub.setAppId(0x0001 + gcbIndex);
        }

        if (gcb.confRev > 0) pub.setConfRev(gcb.confRev);

        if (gcb.macAddress != null && !gcb.macAddress.isEmpty()) {
            try {
                pub.setDstMac(gcb.macAddress.replace(":", "-").replace(".", "-"));
            } catch (Exception e) {
                logGoose("  MAC invalido para " + gcb.cbName + ": " + gcb.macAddress);
            }
        }

        List<GoosePublisher.DataValue> dataValues = buildDataValuesFromDataSet(gcb);
        if (!dataValues.isEmpty()) {
            pub.setDataValues(dataValues);
        }

        pub.setLogListener(msg -> logGoose("[GoCB#" + gcbIndex + "] " + msg));
        pub.setPublishListener(pubMsg -> {
            GooseSubscriber.GooseMessage tableMsg = new GooseSubscriber.GooseMessage();
            tableMsg.timestamp = pubMsg.timestamp;
            tableMsg.srcMac = "LOCAL";
            tableMsg.dstMac = "01:0C:CD:01:00:01";
            tableMsg.gocbRef = pubMsg.gocbRef;
            tableMsg.goId = pubMsg.goId;
            tableMsg.datSet = pubMsg.datSet;
            tableMsg.appId = pubMsg.appId;
            tableMsg.stNum = pubMsg.stNum;
            tableMsg.sqNum = pubMsg.sqNum;
            tableMsg.confRev = gcb.confRev;
            tableMsg.numDataSetEntries = pubMsg.dataValues != null ? pubMsg.dataValues.size() : 0;
            if (pubMsg.dataValues != null) {
                int idx = 0;
                for (GoosePublisher.DataValue dv : pubMsg.dataValues) {
                    tableMsg.dataEntries.add(new GooseSubscriber.DataEntry(idx++, dv.type.name(), dv.value));
                }
            }
            handleGooseMessage(tableMsg);
        });

        return pub;
    }

    private void changeGoCBState(int gcbIndex, String state) {
        GoosePublisher pub = activePublishers.get(gcbIndex);
        if (pub == null || !pub.isPublishing()) {
            logGoose("GoCB #" + gcbIndex + " no esta publicando");
            return;
        }

        List<GoosePublisher.DataValue> values = pub.getDataValues();
        if (values.isEmpty()) return;

        GoosePublisher.DataValue firstVal = values.get(0);
        switch (firstVal.type) {
            case BOOLEAN:
                pub.setDataValue(0, "ON".equalsIgnoreCase(state) || "TRUE".equalsIgnoreCase(state));
                break;
            case DBPOS:
                int dbpos;
                switch (state.toUpperCase()) {
                    case "ON": dbpos = 2; break;
                    case "OFF": dbpos = 1; break;
                    case "INTERMEDIATE": dbpos = 0; break;
                    case "BAD": dbpos = 3; break;
                    default: dbpos = 1;
                }
                pub.setDataValue(0, dbpos);
                break;
            case INTEGER: case UNSIGNED:
                try { pub.setDataValue(0, Integer.parseInt(state)); }
                catch (NumberFormatException e) { pub.setDataValue(0, "ON".equalsIgnoreCase(state) ? 1 : 0); }
                break;
            default:
                pub.setDataValue(0, "ON".equalsIgnoreCase(state));
        }

        pub.publishStateChange();
        List<SclGoCB> sclGoCBs = ctx.getSclGoCBs();
        SclGoCB gcb = gcbIndex < sclGoCBs.size() ? sclGoCBs.get(gcbIndex) : null;
        String gcbName = gcb != null ? gcb.toString() : "#" + gcbIndex;
        logGoose("GoCB " + gcbName + " -> " + state.toUpperCase() + " (stNum=" + pub.getStNum() + ")");

        syncPublisherToServerModel(gcbIndex, 0);
    }

    private void setPublisherDataValue(int gcbIndex, int dataIndex, Object value) {
        GoosePublisher pub = activePublishers.get(gcbIndex);
        if (pub == null || !pub.isPublishing()) {
            logGoose("GoCB #" + gcbIndex + " no esta publicando");
            return;
        }
        pub.setDataValue(dataIndex, value);
        pub.publishStateChange();

        GoosePublisher.DataValue dv = pub.getDataValues().get(dataIndex);
        logGoose("GoCB#" + gcbIndex + " [" + dataIndex + "] " + dv.name + " = " + value + " (stNum=" + pub.getStNum() + ")");

        syncPublisherToServerModel(gcbIndex, dataIndex);
    }

    private void syncPublisherToServerModel(int gcbIndex, int dataIndex) {
        IEC61850Server server = ctx.getServer();
        if (server == null || !ctx.isServerRunning()) return;
        List<SclGoCB> sclGoCBs = ctx.getSclGoCBs();
        if (gcbIndex >= sclGoCBs.size()) return;

        SclGoCB gcb = sclGoCBs.get(gcbIndex);
        SclDataSet ds = findDataSetForGoCB(gcb);
        if (ds == null || dataIndex >= ds.members.size()) return;

        GoosePublisher pub = activePublishers.get(gcbIndex);
        if (pub == null) return;
        List<GoosePublisher.DataValue> pubValues = pub.getDataValues();
        if (dataIndex >= pubValues.size()) return;

        String member = ds.members.get(dataIndex);
        String modelRef = buildModelRefFromFCDA(member);
        if (modelRef == null) return;

        GoosePublisher.DataValue dv = pubValues.get(dataIndex);
        String strValue = convertPublisherValueToString(dv);

        boolean success = server.setDataValue(modelRef, strValue);
        if (success) {
            ctx.log("GOOSE -> Modelo: " + ctx.formatReference(modelRef) + " = " + strValue);
            ctx.updateSingleNodeInTree(modelRef);
            ctx.updateServerMonitorValues();
        }
    }

    String convertPublisherValueToString(GoosePublisher.DataValue dv) {
        if (dv.value == null) return "0";

        switch (dv.type) {
            case BOOLEAN:
                return Boolean.TRUE.equals(dv.value) ? "true" : "false";
            case DBPOS:
                int dbpos = ((Number) dv.value).intValue();
                switch (dbpos) {
                    case 0: return "INTERMEDIATE_STATE";
                    case 1: return "OFF";
                    case 2: return "ON";
                    case 3: return "BAD_STATE";
                    default: return String.valueOf(dbpos);
                }
            case INTEGER: case UNSIGNED: case BITSTRING:
                return String.valueOf(dv.value);
            case FLOAT:
                return String.valueOf(dv.value);
            default:
                return String.valueOf(dv.value);
        }
    }

    private void publishAllGoCBs() {
        List<SclGoCB> sclGoCBs = ctx.getSclGoCBs();
        if (sclGoCBs.isEmpty()) {
            JOptionPane.showMessageDialog(ctx.parentWindow(),
                "No hay GoCBs cargados.\nCargue un archivo SCL primero.",
                "Publicar Todos", JOptionPane.WARNING_MESSAGE);
            return;
        }

        stopAllPublishers();

        String selected = (String) gooseInterfaceCombo.getSelectedItem();
        if (selected == null) {
            JOptionPane.showMessageDialog(ctx.parentWindow(),
                "Seleccione una interface de red primero.",
                "Publicar Todos", JOptionPane.WARNING_MESSAGE);
            return;
        }

        boolean isLoopback = selected.contains("Loopback Interno");
        boolean isUdp = selected.contains("GOOSE sobre UDP") || selected.contains("UDP");
        PcapNetworkInterface nif = null;

        if (!isLoopback && !isUdp) {
            if (!interfaceMap.containsKey(selected)) {
                JOptionPane.showMessageDialog(ctx.parentWindow(),
                    "Seleccione una interface de red valida.",
                    "Publicar Todos", JOptionPane.WARNING_MESSAGE);
                return;
            }
            nif = interfaceMap.get(selected);
        }

        logGoose("=== PUBLICANDO TODOS LOS GoCBs (" + sclGoCBs.size() + ") ===");
        int successCount = 0;

        for (int i = 0; i < sclGoCBs.size(); i++) {
            SclGoCB gcb = sclGoCBs.get(i);
            GoosePublisher pub = createPublisherForGoCB(gcb, i);

            boolean initOk = isLoopback || isUdp || pub.init(nif);

            if (initOk) {
                pub.startPublishing();
                activePublishers.put(i, pub);
                successCount++;
                logGoose("  [" + i + "] " + pub.getGocbRef() + " -> AppID=" + String.format("0x%04X", pub.getAppId())
                    + ", DataValues=" + pub.getDataValues().size());
            } else {
                logGoose("  ERROR: No se pudo inicializar publisher para " + gcb.cbName);
                pub.close();
            }
        }

        if (successCount > 0) {
            logGoose("=== " + successCount + "/" + sclGoCBs.size() + " GoCBs publicando ===");
            btnGoosePublish.setText("Detener Publicacion");
            btnGoosePublish.setBackground(new Color(200, 50, 50));
            btnPublicarTodos.setText("Detener Todos");
            btnPublicarTodos.setBackground(new Color(200, 50, 50));
            String modeStr = isLoopback ? "Loopback" : (isUdp ? "UDP" : "L2");
            lblPublishStatus.setText("  " + successCount + " GoCBs publicando (" + modeStr + ")");
            lblPublishStatus.setForeground(new Color(0, 150, 0));

            for (int i = 0; i < gooseTableModel.getRowCount() && i < sclGoCBs.size(); i++) {
                if (activePublishers.containsKey(i)) {
                    gooseTableModel.setValueAt("Publicando", i, 6);
                }
            }
        } else {
            logGoose("ERROR: No se pudo iniciar ninguna publicacion");
        }
    }

    private void stopAllPublishers() {
        for (Map.Entry<Integer, GoosePublisher> entry : activePublishers.entrySet()) {
            try {
                entry.getValue().stopPublishing();
                entry.getValue().close();
            } catch (Exception e) {
                logGoose("Error deteniendo publisher #" + entry.getKey() + ": " + e.getMessage());
            }
        }
        int count = activePublishers.size();
        activePublishers.clear();

        if (goosePublisher.isPublishing()) {
            goosePublisher.stopPublishing();
            goosePublisher.resetCounters();
            count++;
        }

        if (count > 0) {
            btnGoosePublish.setText("Iniciar Publicacion");
            btnGoosePublish.setBackground(new Color(0, 100, 180));
            btnGooseStateChange.setEnabled(false);
            btnPublicarTodos.setText("Publicar Todos");
            btnPublicarTodos.setBackground(new Color(0, 130, 60));
            lblPublishStatus.setText("  Detenido");
            lblPublishStatus.setForeground(Color.GRAY);
            logGoose(count + " publisher(s) detenidos");

            for (int i = 0; i < gooseTableModel.getRowCount(); i++) {
                gooseTableModel.setValueAt("Detenido", i, 6);
            }
        }
    }

    // ─── DataSet helpers ───────────────────────────────────────────────────────

    List<GoosePublisher.DataValue> buildDataValuesFromDataSet(SclGoCB gcb) {
        List<GoosePublisher.DataValue> values = new ArrayList<>();
        if (gcb.datSet == null) return values;

        SclDataSet foundDs = null;
        for (SclDataSet ds : ctx.getSclDataSets()) {
            if (ds.name != null && ds.name.equals(gcb.datSet)) {
                if (ds.ldInst == null || ds.ldInst.equals(gcb.ldInst)) {
                    foundDs = ds;
                    break;
                }
            }
        }

        if (foundDs == null || foundDs.members.isEmpty()) {
            return values;
        }

        for (String member : foundDs.members) {
            String name = member;
            int bracketIdx = member.lastIndexOf('[');
            if (bracketIdx > 0) {
                name = member.substring(0, bracketIdx).trim();
            }

            GoosePublisher.DataValue.Type type = inferDataType(member);
            Object defaultValue = getDefaultValueForType(type);
            values.add(new GoosePublisher.DataValue(name, type, defaultValue));
        }

        return values;
    }

    private Object getDefaultValueForType(GoosePublisher.DataValue.Type type) {
        switch (type) {
            case BOOLEAN: return false;
            case INTEGER: return 0;
            case UNSIGNED: return (long)(System.currentTimeMillis() / 1000);
            case FLOAT: return 0.0f;
            case BITSTRING: return 0;
            case DBPOS: return 1;
            case VISIBLE_STRING: return "";
            default: return false;
        }
    }

    SclDataSet findDataSetForGoCB(SclGoCB gcb) {
        for (SclDataSet ds : ctx.getSclDataSets()) {
            if (ds.name != null && ds.name.equals(gcb.datSet)
                && (ds.ldInst == null || ds.ldInst.equals(gcb.ldInst))) {
                return ds;
            }
        }
        return null;
    }

    // ─── Publishing toggle ─────────────────────────────────────────────────────

    private void toggleGoosePublishing() {
        if (goosePublisher.isPublishing() || !activePublishers.isEmpty()) {
            stopAllPublishers();
        } else {
            String selected = (String) gooseInterfaceCombo.getSelectedItem();
            if (selected == null) {
                JOptionPane.showMessageDialog(ctx.parentWindow(),
                    "Seleccione una opcion primero.",
                    "GOOSE Publisher", JOptionPane.WARNING_MESSAGE);
                return;
            }

            boolean publishStarted = false;

            if (selected.contains("Loopback Interno")) {
                internalLoopbackEnabled = true;
                publishStarted = true;
                logGoose("Publisher iniciado en modo LOOPBACK INTERNO");
                logGoose("Los mensajes se mostraran localmente sin red");
            } else if (selected.contains("GOOSE sobre UDP") || selected.contains("UDP")) {
                udpBridgeEnabled = true;
                if (gooseUdpBridge.initSender(null)) {
                    publishStarted = true;
                    logGoose("Publisher iniciado en modo UDP BROADCAST");
                    logGoose("Puerto: " + GooseUdpBridge.DEFAULT_PORT);
                } else {
                    logGoose("ERROR: No se pudo inicializar UDP sender");
                    udpBridgeEnabled = false;
                }
            } else {
                if (!interfaceMap.containsKey(selected)) {
                    JOptionPane.showMessageDialog(ctx.parentWindow(),
                        "Seleccione una interface de red valida.",
                        "GOOSE Publisher", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                PcapNetworkInterface nif = interfaceMap.get(selected);
                if (goosePublisher.init(nif)) {
                    publishStarted = true;
                } else {
                    JOptionPane.showMessageDialog(ctx.parentWindow(),
                        "Error inicializando GOOSE Publisher.\nVerifique que la interface es correcta y ejecute como Admin.",
                        "GOOSE Publisher", JOptionPane.ERROR_MESSAGE);
                }
            }

            if (publishStarted) {
                updatePublisherState();
                goosePublisher.startPublishing();
                btnGoosePublish.setText("Detener Publicacion");
                btnGoosePublish.setBackground(new Color(200, 50, 50));
                btnGooseStateChange.setEnabled(true);

                String modeStr = internalLoopbackEnabled ? "Loopback" : (udpBridgeEnabled ? "UDP" : "L2");
                lblPublishStatus.setText("  Publicando " + modeStr + " (stNum=" + goosePublisher.getStNum() + ")");
                lblPublishStatus.setForeground(new Color(0, 150, 0));

                javax.swing.Timer pubTimer = new javax.swing.Timer(500, e -> {
                    if (goosePublisher.isPublishing()) {
                        lblPublishStatus.setText("  " + modeStr + " stNum=" + goosePublisher.getStNum() + ", sqNum=" + goosePublisher.getSqNum());
                    }
                });
                pubTimer.start();
            }
        }
    }

    private void updatePublisherState() {
        int selectedIndex = cbGooseState.getSelectedIndex();
        int dbposValue;
        switch (selectedIndex) {
            case 0: dbposValue = 1; break;
            case 1: dbposValue = 2; break;
            case 2: dbposValue = 0; break;
            case 3: dbposValue = 3; break;
            default: dbposValue = 1;
        }
        goosePublisher.setDataValue(0, dbposValue);
    }

    private void publishGooseStateChange() {
        boolean anyActive = goosePublisher.isPublishing() || !activePublishers.isEmpty();
        if (!anyActive) {
            logGoose("Publisher no esta activo");
            return;
        }

        updatePublisherState();

        String stateName = (String) cbGooseState.getSelectedItem();

        if (goosePublisher.isPublishing()) {
            goosePublisher.publishStateChange();
            logGoose("Cambio de estado publicado: " + stateName + " (stNum=" + goosePublisher.getStNum() + ")");
        }

        for (Map.Entry<Integer, GoosePublisher> entry : activePublishers.entrySet()) {
            GoosePublisher pub = entry.getValue();
            if (pub.isPublishing()) {
                int selectedIndex = cbGooseState.getSelectedIndex();
                boolean boolValue = (selectedIndex == 1);
                pub.setDataValue(0, boolValue);
                pub.publishStateChange();
            }
        }

        if (!activePublishers.isEmpty()) {
            logGoose("Cambio de estado en " + activePublishers.size() + " GoCBs: " + stateName);
        }

        if (udpBridgeEnabled) {
            if (gooseUdpBridge.send(goosePublisher)) {
                logGoose("[UDP] Mensaje enviado por broadcast UDP");
            }
        }

        String stInfo = goosePublisher.isPublishing() ?
            "stNum=" + goosePublisher.getStNum() :
            activePublishers.size() + " GoCBs";
        lblPublishStatus.setText("  CAMBIO! " + stInfo);
        lblPublishStatus.setForeground(new Color(200, 100, 0));

        javax.swing.Timer resetTimer = new javax.swing.Timer(2000, e -> {
            if (goosePublisher.isPublishing() || !activePublishers.isEmpty()) {
                lblPublishStatus.setForeground(new Color(0, 150, 0));
            }
        });
        resetTimer.setRepeats(false);
        resetTimer.start();
    }

    // ─── Message handlers ──────────────────────────────────────────────────────

    private void handleGooseMessage(GooseSubscriber.GooseMessage msg) {
        SwingUtilities.invokeLater(() -> {
            StringBuilder dataStr = new StringBuilder();
            for (GooseSubscriber.DataEntry entry : msg.dataEntries) {
                if (dataStr.length() > 0) dataStr.append(", ");
                dataStr.append(entry.value);
            }

            if (gooseDataTableModel.getRowCount() > 1000) {
                gooseDataTableModel.removeRow(gooseDataTableModel.getRowCount() - 1);
            }

            gooseDataTableModel.insertRow(0, new Object[]{
                msg.timestamp,
                String.format("%04X", msg.appId),
                msg.stNum,
                msg.sqNum,
                msg.gocbRef != null ? msg.gocbRef : (msg.goId != null ? msg.goId : ""),
                dataStr.toString()
            });

            gooseMessages.put(msg.appId, msg);
        });
    }

    private void handleNativeGooseMessage(NativeGooseSubscriber.NativeGooseMessage msg) {
        SwingUtilities.invokeLater(() -> {
            StringBuilder dataStr = new StringBuilder();
            for (NativeGooseSubscriber.DataValue dv : msg.dataValues) {
                if (dataStr.length() > 0) dataStr.append(", ");
                dataStr.append(dv.value);
            }

            if (gooseDataTableModel.getRowCount() > 1000) {
                gooseDataTableModel.removeRow(gooseDataTableModel.getRowCount() - 1);
            }

            gooseDataTableModel.insertRow(0, new Object[]{
                msg.timestamp,
                String.format("%04X", msg.appId),
                msg.stNum,
                msg.sqNum,
                msg.goCbRef != null ? msg.goCbRef : (msg.goId != null ? msg.goId : ""),
                dataStr.toString()
            });
        });
    }

    // ─── GoCB refresh ──────────────────────────────────────────────────────────

    private void refreshGooseControlBlocks() {
        gooseTableModel.setRowCount(0);
        List<SclGoCB> sclGoCBs = ctx.getSclGoCBs();

        if (!sclGoCBs.isEmpty()) {
            for (SclGoCB gcb : sclGoCBs) {
                String ref = gcb.ldInst + "/" + gcb.lnClass + "." + gcb.cbName;
                String goId = gcb.goID != null ? gcb.goID : gcb.cbName;
                String datSet = gcb.datSet != null ? gcb.datSet : "";
                String appId = gcb.appID != null ? gcb.appID : "";
                String mac = gcb.macAddress != null ? gcb.macAddress : "";
                String confRev = String.valueOf(gcb.confRev);
                String estado = "Disponible";
                gooseTableModel.addRow(new Object[]{ref, goId, datSet, appId, mac, confRev, estado});
            }
            ctx.log("GoCBs del SCL: " + gooseTableModel.getRowCount());
            logGoose("Cargados " + gooseTableModel.getRowCount() + " GoCBs del SCL");
            return;
        }

        ServerModel model = null;
        IEC61850Server server = ctx.getServer();
        IEC61850Client client = ctx.getClient();
        if (ctx.isServerMode() && server != null) {
            model = server.getServerModel();
        } else if (!ctx.isServerMode() && client != null && ctx.isConnected()) {
            model = client.getServerModel();
        }

        if (model == null) {
            ctx.log("No hay modelo cargado. Cargue un archivo SCL para ver los GoCBs.");
            return;
        }

        try {
            for (ModelNode ld : model.getChildren()) {
                String ldName = ld.getName();

                for (ModelNode ln : ld.getChildren()) {
                    if (ln.getChildren() == null) continue;
                    String lnName = ln.getName();

                    if (lnName.equals("LLN0")) {
                        ctx.log("Buscando GoCBs en " + ldName + "/" + lnName + " (" +
                            (ln.getChildren() != null ? ln.getChildren().size() : 0) + " nodos)");
                    }

                    for (ModelNode node : ln.getChildren()) {
                        String nodeName = node.getName();
                        String nodeNameUpper = nodeName.toUpperCase();

                        if (nodeNameUpper.contains("SGCB") ||
                            node instanceof Urcb ||
                            node instanceof Brcb ||
                            nodeNameUpper.equals("MOD") ||
                            nodeNameUpper.equals("BEH") ||
                            nodeNameUpper.equals("HEALTH") ||
                            nodeNameUpper.equals("NAMPLT")) {
                            continue;
                        }

                        boolean hasGoEna = false;
                        boolean hasGoID = false;
                        boolean hasDatSet = false;
                        String datSet = "";
                        String goID = "";

                        if (node.getChildren() != null) {
                            for (ModelNode attr : node.getChildren()) {
                                String attrName = attr.getName().toLowerCase();
                                if (attrName.equals("goena")) hasGoEna = true;
                                if (attrName.equals("goid")) {
                                    hasGoID = true;
                                    if (attr instanceof BasicDataAttribute) {
                                        goID = ((BasicDataAttribute) attr).getValueString();
                                        if (goID == null) goID = "";
                                    }
                                }
                                if (attrName.equals("datset")) {
                                    hasDatSet = true;
                                    if (attr instanceof BasicDataAttribute) {
                                        datSet = ((BasicDataAttribute) attr).getValueString();
                                        if (datSet == null) datSet = "";
                                    }
                                }
                            }
                        }

                        boolean isGoCB = hasGoEna || (hasGoID && hasDatSet);

                        if (!isGoCB) {
                            isGoCB = nodeNameUpper.startsWith("GOCB") ||
                                    nodeNameUpper.startsWith("GCB") ||
                                    (nodeNameUpper.contains("CONTROL") && nodeNameUpper.contains("DATASET"));
                        }

                        if (isGoCB) {
                            String ref = lnName + "." + nodeName;
                            String displayDatSet = !datSet.isEmpty() ? datSet : (!goID.isEmpty() ? goID : nodeName);
                            gooseTableModel.addRow(new Object[]{ldName + "/" + ref, goID, displayDatSet, "", "", "", "MMS"});
                            ctx.log("  GoCB encontrado: " + ldName + "/" + ref);
                        }
                    }
                }
            }

            if (gooseTableModel.getRowCount() == 0) {
                ctx.log("No se encontraron GoCBs. Listando nodos de LLN0:");
                for (ModelNode ld : model.getChildren()) {
                    for (ModelNode ln : ld.getChildren()) {
                        if (ln.getName().equals("LLN0") && ln.getChildren() != null) {
                            for (ModelNode node : ln.getChildren()) {
                                String type = node.getClass().getSimpleName();
                                ctx.log("  - " + node.getName() + " [" + type + "]");
                            }
                            break;
                        }
                    }
                    break;
                }
            } else {
                ctx.log("GoCBs encontrados (MMS): " + gooseTableModel.getRowCount());
            }
        } catch (Exception e) {
            ctx.log("Error obteniendo GoCBs: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ─── SCL loading ───────────────────────────────────────────────────────────

    /** Public entry point for loading an SCL file (called from IEDExplorerApp.loadSclForGoCBs). */
    void loadSclFile(File file) {
        ctx.log("Cargando SCL para GoCBs: " + file.getName());
        logGoose("Cargando: " + file.getName());
        try {
            int iedIndex = detectAndSelectIED(file);
            if (iedIndex == -2) return;

            if (iedIndex >= 0) {
                parseGoCBsFromScl(file, iedIndex);
            } else {
                parseGoCBsFromScl(file);
            }
            ctx.setLoadedSclFile(file);
            refreshGooseControlBlocks();
            ctx.log("GoCBs cargados: " + ctx.getSclGoCBs().size());
            logGoose("GoCBs encontrados: " + ctx.getSclGoCBs().size());
            ctx.onSclLoaded();
        } catch (Exception e) {
            ctx.log("Error cargando SCL: " + e.getMessage());
            logGoose("ERROR: " + e.getMessage());
        }
    }

    private void cargarSclParaGoose() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Cargar archivo SCL para obtener GoCBs");
        fc.setFileFilter(new javax.swing.filechooser.FileFilter() {
            public boolean accept(File f) {
                if (f.isDirectory()) return true;
                String name = f.getName().toLowerCase();
                return name.endsWith(".cid") || name.endsWith(".icd") ||
                       name.endsWith(".scd") || name.endsWith(".scl");
            }
            public String getDescription() {
                return "Archivos SCL (*.cid, *.icd, *.scd, *.scl)";
            }
        });

        File loadedSclFile = ctx.getLoadedSclFile();
        if (loadedSclFile != null && loadedSclFile.getParentFile() != null) {
            fc.setCurrentDirectory(loadedSclFile.getParentFile());
        }

        if (fc.showOpenDialog(ctx.parentWindow()) == JFileChooser.APPROVE_OPTION) {
            File file = fc.getSelectedFile();
            ctx.log("Cargando SCL para GoCBs: " + file.getName());
            logGoose("Cargando: " + file.getName());

            try {
                int iedIndex = detectAndSelectIED(file);
                if (iedIndex == -2) return;

                if (iedIndex >= 0) {
                    parseGoCBsFromScl(file, iedIndex);
                } else {
                    parseGoCBsFromScl(file);
                }
                ctx.setLoadedSclFile(file);
                refreshGooseControlBlocks();
                ctx.log("GoCBs cargados: " + ctx.getSclGoCBs().size());
                logGoose("GoCBs encontrados: " + ctx.getSclGoCBs().size());
                ctx.onSclLoaded();
            } catch (Exception e) {
                ctx.log("Error cargando SCL: " + e.getMessage());
                logGoose("ERROR: " + e.getMessage());
                JOptionPane.showMessageDialog(ctx.parentWindow(),
                    "Error cargando archivo SCL:\n" + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    /** Package-visible: refresh the GoCBs table (called from IEDExplorerApp after SCL load). */
    void refreshGooseControlBlocksPublic() {
        refreshGooseControlBlocks();
    }

    /** Package-visible wrapper for IEDExplorerApp's SCL PARSING delegation. */
    void parseSclDataTypeTemplatesDelegated(Document doc) {
        parseSclDataTypeTemplates(doc);
    }

    /** Package-visible wrapper so IEDExplorerApp can use IED detection logic. */
    int detectAndSelectIEDPublic(File sclFile) {
        return detectAndSelectIED(sclFile);
    }

    private int detectAndSelectIED(File sclFile) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(sclFile);

            NodeList ieds = doc.getElementsByTagName("IED");
            if (ieds.getLength() <= 1) {
                return -1;
            }

            List<String> iedNames = new ArrayList<>();
            for (int i = 0; i < ieds.getLength(); i++) {
                Element ied = (Element) ieds.item(i);
                String name = ied.getAttribute("name");
                if (name == null || name.isEmpty()) name = "IED_" + i;
                iedNames.add(name);
            }

            ctx.log("Archivo SCD contiene " + iedNames.size() + " IEDs: " + iedNames);

            int selected = ctx.showIEDSelectionDialog(iedNames, sclFile.getName());
            if (selected < 0) return -2;
            return selected;

        } catch (Exception e) {
            ctx.log("Error detectando IEDs: " + e.getMessage());
            return -1;
        }
    }

    private void parseSclDataTypeTemplates(Document doc) {
        Map<String, LinkedHashMap<Integer, String>> sclEnumTypes = new HashMap<>();
        Map<String, String> sclDaEnumType = new HashMap<>();
        Map<String, Map<String, String>> sclLnTypeDoTypes = new HashMap<>();
        Map<String, String> sclLnClassToLnType = new HashMap<>();

        NodeList enumTypes = doc.getElementsByTagName("EnumType");
        for (int i = 0; i < enumTypes.getLength(); i++) {
            Element et = (Element) enumTypes.item(i);
            String id = et.getAttribute("id");
            LinkedHashMap<Integer, String> vals = new LinkedHashMap<>();
            NodeList enumVals = et.getElementsByTagName("EnumVal");
            for (int j = 0; j < enumVals.getLength(); j++) {
                Element ev = (Element) enumVals.item(j);
                try {
                    int ord = Integer.parseInt(ev.getAttribute("ord").trim());
                    vals.put(ord, ev.getTextContent().trim());
                } catch (NumberFormatException ignore) {}
            }
            if (!vals.isEmpty()) sclEnumTypes.put(id, vals);
        }

        NodeList doTypes = doc.getElementsByTagName("DOType");
        for (int i = 0; i < doTypes.getLength(); i++) {
            Element dot = (Element) doTypes.item(i);
            String doTypeId = dot.getAttribute("id");
            NodeList das = dot.getElementsByTagName("DA");
            for (int j = 0; j < das.getLength(); j++) {
                Element da = (Element) das.item(j);
                if ("Enum".equals(da.getAttribute("bType"))) {
                    String daName = da.getAttribute("name");
                    String enumType = da.getAttribute("type");
                    if (!daName.isEmpty() && !enumType.isEmpty()) {
                        sclDaEnumType.put(doTypeId + "." + daName, enumType);
                    }
                }
            }
        }

        NodeList lnTypes = doc.getElementsByTagName("LNodeType");
        for (int i = 0; i < lnTypes.getLength(); i++) {
            Element lnt = (Element) lnTypes.item(i);
            String lnTypeId = lnt.getAttribute("id");
            String lnClass = lnt.getAttribute("lnClass");
            sclLnClassToLnType.putIfAbsent(lnClass, lnTypeId);
            Map<String, String> doMap = new HashMap<>();
            NodeList doEls = lnt.getElementsByTagName("DO");
            for (int j = 0; j < doEls.getLength(); j++) {
                Element doEl = (Element) doEls.item(j);
                String doName = doEl.getAttribute("name");
                String doType = doEl.getAttribute("type");
                if (!doName.isEmpty() && !doType.isEmpty()) doMap.put(doName, doType);
            }
            sclLnTypeDoTypes.put(lnTypeId, doMap);
        }
        ctx.log("DataTypeTemplates: " + sclEnumTypes.size() + " EnumTypes, " + sclDaEnumType.size() + " DA enumeradas");

        ctx.setSclEnumMaps(sclEnumTypes, sclDaEnumType, sclLnTypeDoTypes, sclLnClassToLnType);
    }

    private String extractLnClass(String lnFull) {
        if (lnFull == null || lnFull.isEmpty()) return "";
        if ("LLN0".equals(lnFull)) return "LLN0";
        String noInst = lnFull.replaceAll("\\d+$", "");
        if (noInst.length() >= 4) return noInst.substring(noInst.length() - 4);
        return noInst.isEmpty() ? lnFull : noInst;
    }

    LinkedHashMap<Integer, String> getEnumOptionsForNode(ModelNode node) {
        if (!(node instanceof BdaInt8) && !(node instanceof BdaInt8U)) return null;
        String ref = node.getReference().toString();
        int slashIdx = ref.indexOf('/');
        if (slashIdx < 0) return null;
        String[] parts = ref.substring(slashIdx + 1).split("\\.");
        if (parts.length < 3) return null;

        String lnClass = extractLnClass(parts[0]);
        String doName = parts[1];
        String daName = parts[2];

        Map<String, String> lnClassToLnType = ctx.getSclLnClassToLnType();
        String lnTypeId = lnClassToLnType.get(lnClass);
        if (lnTypeId == null) lnTypeId = lnClassToLnType.get(parts[0]);
        if (lnTypeId == null) return null;

        Map<String, Map<String, String>> lnTypeDoTypes = ctx.getSclLnTypeDoTypes();
        Map<String, String> doMap = lnTypeDoTypes.get(lnTypeId);
        if (doMap == null) return null;
        String doTypeId = doMap.get(doName);
        if (doTypeId == null) return null;

        String enumTypeId = ctx.getSclDaEnumType().get(doTypeId + "." + daName);
        if (enumTypeId == null) return null;

        return ctx.getSclEnumTypes().get(enumTypeId);
    }

    String formatEnumValue(ModelNode node, String rawValue) {
        LinkedHashMap<Integer, String> enumVals = getEnumOptionsForNode(node);
        if (enumVals == null || enumVals.isEmpty()) return rawValue;
        try {
            int ord = Integer.parseInt(rawValue.trim());
            String label = enumVals.get(ord);
            return label != null ? ord + " [" + label + "]" : rawValue;
        } catch (NumberFormatException ignore) {
            return rawValue;
        }
    }

    String showEnumDialog(String daName, int currentOrd, LinkedHashMap<Integer, String> enumVals) {
        List<String> display = new ArrayList<>();
        List<Integer> ords = new ArrayList<>();
        int selIdx = 0, idx = 0;
        for (Map.Entry<Integer, String> e : enumVals.entrySet()) {
            display.add(e.getKey() + " [" + e.getValue() + "]");
            ords.add(e.getKey());
            if (e.getKey() == currentOrd) selIdx = idx;
            idx++;
        }
        JComboBox<String> combo = new JComboBox<>(display.toArray(new String[0]));
        combo.setSelectedIndex(selIdx);
        combo.setPreferredSize(new Dimension(260, 26));

        JPanel panel = new JPanel(new BorderLayout(10, 8));
        panel.add(new JLabel("Seleccionar valor para " + daName + ":"), BorderLayout.NORTH);
        panel.add(combo, BorderLayout.CENTER);

        int result = JOptionPane.showConfirmDialog(ctx.parentWindow(), panel,
            "Establecer Valor", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result == JOptionPane.OK_OPTION && combo.getSelectedIndex() >= 0) {
            return String.valueOf(ords.get(combo.getSelectedIndex()));
        }
        return null;
    }

    // ─── publishGooseFromSelection (called from TREE POPUP) ───────────────────

    void publishGooseFromSelection() {
        if (!ctx.isServerRunning()) {
            JOptionPane.showMessageDialog(ctx.parentWindow(), "El servidor debe estar activo", "GOOSE", JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (!activePublishers.isEmpty()) {
            for (Map.Entry<Integer, GoosePublisher> entry : activePublishers.entrySet()) {
                entry.getValue().publishStateChange();
            }
            ctx.log("GOOSE: Cambio de estado en " + activePublishers.size() + " GoCBs");
            logGoose("Estado publicado en " + activePublishers.size() + " GoCBs");
        } else if (goosePublisher != null && goosePublisher.isPublishing()) {
            goosePublisher.publishStateChange();
            ctx.log("GOOSE: Cambio de estado publicado (stNum=" + goosePublisher.getStNum() + ")");
            logGoose("Estado publicado: stNum=" + goosePublisher.getStNum());
        } else {
            String selected = (String) gooseInterfaceCombo.getSelectedItem();
            if (selected != null && interfaceMap.containsKey(selected)) {
                PcapNetworkInterface nif = interfaceMap.get(selected);
                if (goosePublisher.init(nif)) {
                    goosePublisher.publishStateChange();
                    ctx.log("GOOSE: Mensaje unico publicado");
                    logGoose("GOOSE publicado (una vez)");
                } else {
                    JOptionPane.showMessageDialog(ctx.parentWindow(),
                        "No se pudo publicar GOOSE.\nVerifique la interfaz seleccionada.",
                        "GOOSE Error", JOptionPane.ERROR_MESSAGE);
                }
            } else {
                JOptionPane.showMessageDialog(ctx.parentWindow(),
                    "Seleccione una interfaz de red en la pestana GOOSE primero.",
                    "GOOSE", JOptionPane.WARNING_MESSAGE);
            }
        }
    }

    // ─── SCL parsing (formerly in IEDExplorerApp) ─────────────────────────────

    private void parseGoCBsFromScl(File sclFile) {
        List<SclGoCB> sclGoCBs = new ArrayList<>();

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(sclFile);

            NodeList iedNodes = doc.getElementsByTagName("IED");
            if (iedNodes.getLength() > 0) {
                Element iedEl = (Element) iedNodes.item(0);
                ctx.setLoadedIedName(iedEl.getAttribute("name"));
                ctx.setLoadedIedNameplate(new String[] {
                    iedEl.getAttribute("manufacturer"),
                    iedEl.getAttribute("type"),
                    iedEl.getAttribute("desc"),
                    iedEl.getAttribute("configVersion")
                });
            }

            parseSclDataTypeTemplates(doc);

            NodeList gseControls = doc.getElementsByTagName("GSEControl");
            ctx.log("Encontrados " + gseControls.getLength() + " elementos GSEControl");

            Map<String, Map<String, String>> gseInfo = new HashMap<>();

            NodeList gseNodes = doc.getElementsByTagName("GSE");
            for (int i = 0; i < gseNodes.getLength(); i++) {
                Element gse = (Element) gseNodes.item(i);
                String ldInst = gse.getAttribute("ldInst");
                String cbName = gse.getAttribute("cbName");
                String key = ldInst + "/" + cbName;

                Map<String, String> info = new HashMap<>();

                NodeList pNodes = gse.getElementsByTagName("P");
                for (int j = 0; j < pNodes.getLength(); j++) {
                    Element p = (Element) pNodes.item(j);
                    String type = p.getAttribute("type");
                    String value = p.getTextContent();
                    if ("MAC-Address".equals(type)) {
                        info.put("mac", value);
                    } else if ("APPID".equals(type)) {
                        info.put("appid", value);
                    }
                }
                gseInfo.put(key, info);
            }

            for (int i = 0; i < gseControls.getLength(); i++) {
                Element gseCtrl = (Element) gseControls.item(i);

                SclGoCB gcb = new SclGoCB();
                gcb.cbName = gseCtrl.getAttribute("name");
                gcb.datSet = gseCtrl.getAttribute("datSet");

                String sclAppId = gseCtrl.getAttribute("appID");
                gcb.goID = sclAppId;

                String confRevStr = gseCtrl.getAttribute("confRev");
                if (!confRevStr.isEmpty()) {
                    try {
                        gcb.confRev = Integer.parseInt(confRevStr);
                    } catch (NumberFormatException e) {
                        gcb.confRev = 1;
                    }
                }

                org.w3c.dom.Node parent = gseCtrl.getParentNode();
                while (parent != null) {
                    if (parent instanceof Element) {
                        Element parentEl = (Element) parent;
                        if ("LN0".equals(parentEl.getTagName())) {
                            gcb.lnClass = "LLN0";
                        } else if ("LDevice".equals(parentEl.getTagName())) {
                            gcb.ldInst = parentEl.getAttribute("inst");
                            break;
                        }
                    }
                    parent = parent.getParentNode();
                }

                if (gcb.lnClass == null) {
                    gcb.lnClass = "LLN0";
                }

                String key = gcb.ldInst + "/" + gcb.cbName;
                if (gseInfo.containsKey(key)) {
                    Map<String, String> info = gseInfo.get(key);
                    gcb.macAddress = info.get("mac");
                    if (info.containsKey("appid")) {
                        gcb.appID = info.get("appid");
                    }
                }

                sclGoCBs.add(gcb);
                ctx.log("  GoCB: " + gcb.toString() + " appID=" + gcb.appID + " goID=" + gcb.goID);
            }

        } catch (Exception e) {
            ctx.log("Error parseando GoCBs del SCL: " + e.getMessage());
            e.printStackTrace();
        }

        ctx.setSclGoCBs(sclGoCBs);
    }

    private void parseGoCBsFromScl(File sclFile, int iedIndex) {
        List<SclGoCB> sclGoCBs = new ArrayList<>();
        List<SclDataSet> sclDataSets = new ArrayList<>();
        List<SclReport> sclReports = new ArrayList<>();

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(sclFile);

            NodeList ieds = doc.getElementsByTagName("IED");
            if (iedIndex >= ieds.getLength()) {
                ctx.log("Índice de IED inválido: " + iedIndex);
                parseGoCBsFromScl(sclFile);
                return;
            }

            Element selectedIED = (Element) ieds.item(iedIndex);
            String iedName = selectedIED.getAttribute("name");
            ctx.setLoadedIedName(iedName);
            ctx.setLoadedIedNameplate(new String[] {
                selectedIED.getAttribute("manufacturer"),
                selectedIED.getAttribute("type"),
                selectedIED.getAttribute("desc"),
                selectedIED.getAttribute("configVersion")
            });
            ctx.log("Parseando SCL para IED: " + iedName);

            parseSclDataTypeTemplates(doc);

            parseDataSetsFromIED(selectedIED, sclDataSets);
            ctx.log("DataSets encontrados: " + sclDataSets.size());

            parseReportsFromIED(selectedIED, sclReports);
            ctx.log("Reports encontrados: " + sclReports.size());

            Map<String, Map<String, String>> gseInfo = new HashMap<>();

            NodeList connectedAPs = doc.getElementsByTagName("ConnectedAP");
            for (int c = 0; c < connectedAPs.getLength(); c++) {
                Element connAP = (Element) connectedAPs.item(c);
                if (!iedName.equals(connAP.getAttribute("iedName"))) continue;

                NodeList gseNodes = connAP.getElementsByTagName("GSE");
                for (int i = 0; i < gseNodes.getLength(); i++) {
                    Element gse = (Element) gseNodes.item(i);
                    String ldInst = gse.getAttribute("ldInst");
                    String cbName = gse.getAttribute("cbName");
                    String key = ldInst + "/" + cbName;

                    Map<String, String> info = new HashMap<>();
                    NodeList pNodes = gse.getElementsByTagName("P");
                    for (int j = 0; j < pNodes.getLength(); j++) {
                        Element p = (Element) pNodes.item(j);
                        String type = p.getAttribute("type");
                        String value = p.getTextContent();
                        if ("MAC-Address".equals(type)) {
                            info.put("mac", value);
                        } else if ("APPID".equals(type)) {
                            info.put("appid", value);
                        }
                    }
                    gseInfo.put(key, info);
                }
            }

            NodeList gseControls = selectedIED.getElementsByTagName("GSEControl");
            ctx.log("Encontrados " + gseControls.getLength() + " GSEControl para " + iedName);

            for (int i = 0; i < gseControls.getLength(); i++) {
                Element gseCtrl = (Element) gseControls.item(i);

                SclGoCB gcb = new SclGoCB();
                gcb.cbName = gseCtrl.getAttribute("name");
                gcb.datSet = gseCtrl.getAttribute("datSet");

                String sclAppId = gseCtrl.getAttribute("appID");
                gcb.goID = sclAppId;

                String confRevStr = gseCtrl.getAttribute("confRev");
                if (!confRevStr.isEmpty()) {
                    try { gcb.confRev = Integer.parseInt(confRevStr); }
                    catch (NumberFormatException e) { gcb.confRev = 1; }
                }

                org.w3c.dom.Node parent = gseCtrl.getParentNode();
                while (parent != null) {
                    if (parent instanceof Element) {
                        Element parentEl = (Element) parent;
                        if ("LN0".equals(parentEl.getTagName())) {
                            gcb.lnClass = "LLN0";
                        } else if ("LDevice".equals(parentEl.getTagName())) {
                            gcb.ldInst = parentEl.getAttribute("inst");
                            break;
                        }
                    }
                    parent = parent.getParentNode();
                }

                if (gcb.lnClass == null) gcb.lnClass = "LLN0";

                String key = gcb.ldInst + "/" + gcb.cbName;
                if (gseInfo.containsKey(key)) {
                    Map<String, String> info = gseInfo.get(key);
                    gcb.macAddress = info.get("mac");
                    if (info.containsKey("appid")) {
                        gcb.appID = info.get("appid");
                    }
                }

                sclGoCBs.add(gcb);
                ctx.log("  GoCB: " + gcb.toString() + " appID=" + gcb.appID + " goID=" + gcb.goID);
            }

        } catch (Exception e) {
            ctx.log("Error parseando GoCBs: " + e.getMessage());
            e.printStackTrace();
        }

        ctx.setSclGoCBs(sclGoCBs);
        ctx.setSclDataSets(sclDataSets);
        // sclReports is managed by context separately - push via existing field
        // (IEDExplorerApp keeps sclReports; we pass datasets back)
    }

    private void parseDataSetsFromIED(Element iedElement, List<SclDataSet> sclDataSets) {
        NodeList dataSets = iedElement.getElementsByTagName("DataSet");

        for (int i = 0; i < dataSets.getLength(); i++) {
            Element dsElement = (Element) dataSets.item(i);

            SclDataSet ds = new SclDataSet();
            ds.name = dsElement.getAttribute("name");
            ds.desc = dsElement.getAttribute("desc");

            org.w3c.dom.Node parent = dsElement.getParentNode();
            while (parent != null) {
                if (parent instanceof Element) {
                    Element parentEl = (Element) parent;
                    if ("LN0".equals(parentEl.getTagName()) || "LN".equals(parentEl.getTagName())) {
                        ds.lnClass = parentEl.getAttribute("lnClass");
                        if (ds.lnClass == null || ds.lnClass.isEmpty()) {
                            ds.lnClass = "LLN0";
                        }
                    } else if ("LDevice".equals(parentEl.getTagName())) {
                        ds.ldInst = parentEl.getAttribute("inst");
                        break;
                    }
                }
                parent = parent.getParentNode();
            }

            NodeList fcdas = dsElement.getElementsByTagName("FCDA");
            for (int j = 0; j < fcdas.getLength(); j++) {
                Element fcda = (Element) fcdas.item(j);
                StringBuilder member = new StringBuilder();

                String ldInst = fcda.getAttribute("ldInst");
                String prefix = fcda.getAttribute("prefix");
                String lnClass = fcda.getAttribute("lnClass");
                String lnInst = fcda.getAttribute("lnInst");
                String doName = fcda.getAttribute("doName");
                String daName = fcda.getAttribute("daName");
                String fc = fcda.getAttribute("fc");

                if (ldInst != null && !ldInst.isEmpty()) member.append(ldInst).append("/");
                if (prefix != null && !prefix.isEmpty()) member.append(prefix);
                member.append(lnClass);
                if (lnInst != null && !lnInst.isEmpty()) member.append(lnInst);
                member.append(".").append(doName);
                if (daName != null && !daName.isEmpty()) member.append(".").append(daName);
                member.append(" [").append(fc).append("]");

                ds.members.add(member.toString());
            }

            sclDataSets.add(ds);
        }
    }

    private void parseReportsFromIED(Element iedElement, List<SclReport> sclReports) {
        NodeList reports = iedElement.getElementsByTagName("ReportControl");

        for (int i = 0; i < reports.getLength(); i++) {
            Element rptElement = (Element) reports.item(i);

            SclReport rpt = new SclReport();
            rpt.name = rptElement.getAttribute("name");
            rpt.rptID = rptElement.getAttribute("rptID");
            rpt.datSet = rptElement.getAttribute("datSet");
            rpt.buffered = "true".equals(rptElement.getAttribute("buffered"));

            String confRevStr = rptElement.getAttribute("confRev");
            if (confRevStr != null && !confRevStr.isEmpty()) {
                try { rpt.confRev = Integer.parseInt(confRevStr); }
                catch (NumberFormatException e) { rpt.confRev = 1; }
            }

            org.w3c.dom.Node parent = rptElement.getParentNode();
            while (parent != null) {
                if (parent instanceof Element) {
                    Element parentEl = (Element) parent;
                    if ("LN0".equals(parentEl.getTagName()) || "LN".equals(parentEl.getTagName())) {
                        rpt.lnClass = parentEl.getAttribute("lnClass");
                        if (rpt.lnClass == null || rpt.lnClass.isEmpty()) {
                            rpt.lnClass = "LLN0";
                        }
                    } else if ("LDevice".equals(parentEl.getTagName())) {
                        rpt.ldInst = parentEl.getAttribute("inst");
                        break;
                    }
                }
                parent = parent.getParentNode();
            }

            sclReports.add(rpt);
        }
    }

    // ─── Publisher sync (F21: moved from IEDExplorerApp.java) ────────────────

    boolean updateGoosePublisherValues(ServerModel model) {
        if (goosePublisher == null || model == null) return false;
        List<SclGoCB> goCBs = ctx.getSclGoCBs();
        if (goCBs.isEmpty()) return false;
        SclGoCB gcb = goCBs.get(0);
        SclDataSet ds = findDataSetForGoCB(gcb);
        if (ds == null) return false;
        try {
            boolean changed = false;
            List<GoosePublisher.DataValue> pubValues = goosePublisher.getDataValues();
            for (int i = 0; i < ds.members.size() && i < pubValues.size(); i++) {
                String member = ds.members.get(i);
                String modelRef = buildModelRefFromFCDA(member);
                Fc fc = SclReferenceUtils.extractFcFromMember(member);
                if (modelRef == null || fc == null) continue;
                ModelNode node = model.findModelNode(modelRef, fc);
                if (node instanceof BasicDataAttribute) {
                    BasicDataAttribute bda = (BasicDataAttribute) node;
                    Object newVal = SclReferenceUtils.convertBdaToPublisherValue(bda, pubValues.get(i).type);
                    if (newVal != null && !newVal.equals(pubValues.get(i).value)) {
                        goosePublisher.setDataValue(i, newVal);
                        changed = true;
                    }
                }
            }
            return changed;
        } catch (Exception e) {
            ctx.log("Error actualizando valores GOOSE: " + e.getMessage());
            return false;
        }
    }

    void propagateValueToPublishers(String ref, ServerModel model) {
        if (model == null || activePublishers.isEmpty()) return;
        List<SclGoCB> goCBs = ctx.getSclGoCBs();
        for (Map.Entry<Integer, GoosePublisher> entry : activePublishers.entrySet()) {
            int gcbIdx = entry.getKey();
            GoosePublisher pub = entry.getValue();
            if (!pub.isPublishing() || gcbIdx >= goCBs.size()) continue;
            SclGoCB gcb = goCBs.get(gcbIdx);
            SclDataSet ds = findDataSetForGoCB(gcb);
            if (ds == null) continue;
            boolean changed = false;
            List<GoosePublisher.DataValue> pubValues = pub.getDataValues();
            for (int i = 0; i < ds.members.size() && i < pubValues.size(); i++) {
                String member = ds.members.get(i);
                String modelRef = buildModelRefFromFCDA(member);
                Fc fc = SclReferenceUtils.extractFcFromMember(member);
                if (modelRef == null || fc == null) continue;
                ModelNode node = model.findModelNode(modelRef, fc);
                if (node instanceof BasicDataAttribute) {
                    BasicDataAttribute bda = (BasicDataAttribute) node;
                    Object newVal = SclReferenceUtils.convertBdaToPublisherValue(bda, pubValues.get(i).type);
                    if (newVal != null && !newVal.equals(pubValues.get(i).value)) {
                        pub.setDataValue(i, newVal);
                        changed = true;
                    }
                }
            }
            if (changed) {
                pub.publishStateChange();
                logGoose("Modelo -> GoCB#" + gcbIdx + " sincronizado (stNum=" + pub.getStNum() + ")");
            }
        }
    }

    // ─── FCDA helpers (used by GOOSE-MODEL SYNC in IEDExplorerApp) ───────────

    String buildModelRefFromFCDA(String member) {
        int bracket = member.lastIndexOf('[');
        String clean = bracket > 0 ? member.substring(0, bracket).trim() : member.trim();
        if (clean.isEmpty()) return null;

        String loadedIedName = ctx.getLoadedIedName();
        if (loadedIedName != null) {
            int slash = clean.indexOf('/');
            if (slash > 0) {
                String ldInst = clean.substring(0, slash);
                String rest = clean.substring(slash + 1);
                return loadedIedName + ldInst + "/" + rest;
            }
        }
        return clean;
    }
}
