package com.iedexplorer;

import com.beanit.iec61850bean.*;
import com.iedexplorer.native_lib.*;

import javax.swing.*;
import javax.swing.table.*;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.awt.datatransfer.*;
import java.awt.dnd.*;
import javax.swing.border.*;

/**
 * IEC 61850 Explorer - Similar a OMICRON IEDScout
 * Modos: SERVER (simular IED) y CLIENT (conectar a IED real)
 * Caracteristicas: Activity Monitor, Drag-and-Drop, SCL Loading
 */
public class IEDExplorerApp extends JFrame {

    // ─── SECTION: ENUMS & INNER DATA CLASSES ────────────────────────────────────────
    // TODO-REFACTOR F1: Clases internas extraídas a archivos propios (ver BITACORA)
    // Modo actual
    private enum AppMode { SERVER, CLIENT }
    private AppMode currentMode = AppMode.CLIENT;

    // Cliente y servidor
    private IEC61850Client client;
    private IEC61850Server server;

    // Estado
    private boolean isConnected = false;
    private boolean isServerRunning = false;
    private ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();

    // Archivo SCL cargado (para extraer GoCBs)
    private File loadedSclFile = null;
    private String loadedIedName = null;
    private String[] loadedIedNameplate = null; // [manufacturer, type, desc, configVersion]

    // DataTypeTemplates parsed from SCL for BdaEnum dropdown support
    private Map<String, LinkedHashMap<Integer, String>> sclEnumTypes = new HashMap<>();
    private Map<String, String> sclDaEnumType = new HashMap<>();    // "doTypeId.daName" → enumTypeId
    private Map<String, Map<String, String>> sclLnTypeDoTypes = new HashMap<>(); // lnTypeId → {doName → doTypeId}
    private Map<String, String> sclLnClassToLnType = new HashMap<>();  // lnClass → lnTypeId
    private List<SclGoCB> sclGoCBs = new ArrayList<>();

    // Componentes GUI principales
    private JRadioButton rbServer, rbClient;
    private JPanel cardPanel;
    private CardLayout cardLayout;

    // Panel Server
    private JButton btnSelectFile;
    private JLabel lblFileName;
    private JTextField tfServerPort;
    private JButton btnStartStop;

    // Panel Client
    private JTextField tfHost;
    private JTextField tfClientPort;
    private JButton btnConnect;
    private JCheckBox cbPolling;
    private JSpinner spinnerInterval;

    // Comunes
    private JLabel lblStatus;
    private JLabel lblIedInfo;   // placa de identificación del IED (FC=DC)
    private JPanel statusIndicator;
    private JTree modelTree;
    private DefaultMutableTreeNode rootNode;
    private DefaultTreeModel treeModel;
    private Map<String, DefaultMutableTreeNode> nodeMap = new HashMap<>();
    private JTextArea logArea;

    // Watchlist - nodos seleccionados para monitorear
    private Set<String> watchlist = new HashSet<>();
    private JPopupMenu treePopupMenu;
    private JLabel lblWatchlistCount;

    // Activity Monitor Panel
    private JTable monitorTable;
    private DefaultTableModel monitorTableModel;
    private Map<String, MonitorItem> monitorItems = new LinkedHashMap<>();


    // GOOSE Panel (extracted to GoosePanel.java - Phase 5 refactor)
    private GoosePanel goosePanel;

    // Connection management (extracted to ConnectionManager.java - Phase 7 refactor)
    private ConnectionManager connectionManager;

    // Polling management (extracted to PollingManager.java - Phase 8 refactor)
    private PollingManager pollingManager;

    private boolean nativeLibAvailable = false;




    // ===== ICONOS PERSONALIZADOS =====
    private Map<String, Icon> iconCache = new HashMap<>();

    // Iconos inicializados en constructor: IconFactory.fillCache(iconCache) (F11)


    // ===== DRAG AND DROP SIMPLIFICADO =====
    private Point dragStart;
    private boolean isDragging = false;

    private void setupDragAndDrop() {
        // Mouse listener en el arbol para detectar drag
        modelTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                dragStart = e.getPoint();
                isDragging = false;
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                isDragging = false;
                dragStart = null;
            }
        });

        modelTree.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragStart != null && !isDragging) {
                    int dx = Math.abs(e.getX() - dragStart.x);
                    int dy = Math.abs(e.getY() - dragStart.y);
                    if (dx > 5 || dy > 5) {
                        isDragging = true;
                        // Iniciar drag
                        TreePath[] paths = modelTree.getSelectionPaths();
                        if (paths != null && paths.length > 0) {
                            modelTree.getTransferHandler().exportAsDrag(modelTree, e, TransferHandler.COPY);
                        }
                    }
                }
            }
        });

        // TransferHandler simple para el arbol
        modelTree.setTransferHandler(new TransferHandler() {
            @Override
            public int getSourceActions(JComponent c) {
                return COPY;
            }

            @Override
            protected Transferable createTransferable(JComponent c) {
                TreePath[] paths = modelTree.getSelectionPaths();
                if (paths == null) return null;

                StringBuilder sb = new StringBuilder();
                for (TreePath path : paths) {
                    DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                    if (node.getUserObject() instanceof NodeInfo) {
                        NodeInfo info = (NodeInfo) node.getUserObject();
                        if (info.node instanceof FcModelNode) {
                            String ref = info.node.getReference().toString();
                            Fc fc = ((FcModelNode) info.node).getFc();
                            sb.append(ref).append("$").append(fc.toString()).append("\n");
                        }
                    }
                }
                return new StringSelection(sb.toString());
            }
        });

        // Drop target en el panel del monitor
        monitorTable.setDropTarget(new DropTarget() {
            @Override
            public synchronized void dragEnter(DropTargetDragEvent dtde) {
                if (dtde.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                    dtde.acceptDrag(DnDConstants.ACTION_COPY);
                    monitorTable.setBorder(BorderFactory.createLineBorder(new Color(0, 150, 255), 2));
                } else {
                    dtde.rejectDrag();
                }
            }

            @Override
            public synchronized void dragExit(DropTargetEvent dte) {
                monitorTable.setBorder(null);
            }

            @Override
            public synchronized void drop(DropTargetDropEvent dtde) {
                monitorTable.setBorder(null);
                try {
                    dtde.acceptDrop(DnDConstants.ACTION_COPY);
                    String data = (String) dtde.getTransferable().getTransferData(DataFlavor.stringFlavor);

                    // Agregar nodos desde la seleccion actual del arbol
                    TreePath[] paths = modelTree.getSelectionPaths();
                    if (paths != null) {
                        for (TreePath path : paths) {
                            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                            addNodeToMonitor(node);
                        }
                    }
                    dtde.dropComplete(true);
                } catch (Exception e) {
                    log("Drop error: " + e.getMessage());
                    dtde.dropComplete(false);
                }
            }
        });
    }

    // Colores
    private static final Color COLOR_RUNNING = new Color(0, 170, 0);
    private static final Color COLOR_STOPPED = new Color(200, 0, 0);
    private static final Color COLOR_CONNECTING = new Color(255, 165, 0);
    private static final Color COLOR_BREAKER_ON = new Color(0, 180, 0);
    private static final Color COLOR_BREAKER_OFF = new Color(220, 50, 50);
    private static final Color COLOR_BREAKER_INTERMEDIATE = new Color(255, 165, 0);

    // Info de conexion
    private JLabel lblConnectionInfo;
    public IEDExplorerApp() {
        client = new IEC61850Client();
        server = new IEC61850Server();

        IconFactory.fillCache(iconCache);  // Inicializar iconos (F11)
        initUI();
        setupListeners();
        setupDragAndDrop();  // Configurar drag and drop

        connectionManager = new ConnectionManager(createConnectionContext());
        monitorManager    = new MonitorManager(createMonitorContext());
        pollingManager    = new PollingManager(createPollingContext());
        // Default: modo cliente
        switchToClientMode();
    }

    private void initUI() {
        setTitle("IED Navigator - IEC 61850 Explorer");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1200, 800);
        setLocationRelativeTo(null);

        // Barra de menu
        setJMenuBar(createMenuBar());

        // Panel principal
        JPanel mainPanel = new JPanel(new BorderLayout(0, 0));

        // === TOOLBAR PROFESIONAL ===
        JToolBar toolbar = createToolbar();
        mainPanel.add(toolbar, BorderLayout.NORTH);

        // === Panel de contenido ===
        JPanel contentPanel = new JPanel(new BorderLayout(5, 5));
        contentPanel.setBorder(BorderFactory.createEmptyBorder(5, 10, 10, 10));

        // === Panel Superior: Modo + Status + Connection Info ===
        JPanel topPanel = new JPanel(new BorderLayout(10, 0));

        // Selector de modo
        JPanel modePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        modePanel.setBorder(BorderFactory.createTitledBorder("Modo"));
        ButtonGroup modeGroup = new ButtonGroup();
        rbServer = new JRadioButton("Servidor");
        rbClient = new JRadioButton("Cliente", true);
        modeGroup.add(rbServer);
        modeGroup.add(rbClient);
        modePanel.add(rbServer);
        modePanel.add(rbClient);
        topPanel.add(modePanel, BorderLayout.WEST);

        // Status y Connection Info
        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 5));
        statusIndicator = new JPanel();
        statusIndicator.setPreferredSize(new Dimension(16, 16));
        statusIndicator.setBackground(COLOR_STOPPED);
        statusIndicator.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
        lblStatus = new JLabel("Desconectado");
        lblStatus.setFont(lblStatus.getFont().deriveFont(Font.BOLD));
        statusPanel.add(statusIndicator);
        statusPanel.add(lblStatus);

        // Separador
        JSeparator sep = new JSeparator(SwingConstants.VERTICAL);
        sep.setPreferredSize(new Dimension(2, 20));
        statusPanel.add(sep);

        // Info de conexion (IP:Puerto)
        lblConnectionInfo = new JLabel("Sin conexion");
        lblConnectionInfo.setFont(new Font("Monospaced", Font.BOLD, 12));
        lblConnectionInfo.setForeground(new Color(0, 80, 160));
        statusPanel.add(new JLabel("Conexion:"));
        statusPanel.add(lblConnectionInfo);

        topPanel.add(statusPanel, BorderLayout.CENTER);

        contentPanel.add(topPanel, BorderLayout.NORTH);

        // === Panel Central: Cards (Server/Client) + Tree ===
        JPanel centerPanel = new JPanel(new BorderLayout(5, 5));

        // Cards para Server/Client
        cardLayout = new CardLayout();
        cardPanel = new JPanel(cardLayout);
        cardPanel.add(createServerPanel(), "SERVER");
        cardPanel.add(createClientPanel(), "CLIENT");
        cardPanel.setPreferredSize(new Dimension(300, 150));

        // Panel izquierdo (cards + log)
        JPanel leftPanel = new JPanel(new BorderLayout(5, 5));
        leftPanel.add(cardPanel, BorderLayout.NORTH);

        // Log
        logArea = new JTextArea(8, 30);
        logArea.setEditable(false);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("Log"));
        leftPanel.add(logScroll, BorderLayout.CENTER);

        // Tree del modelo con soporte Drag
        rootNode = new DefaultMutableTreeNode("Modelo");
        treeModel = new DefaultTreeModel(rootNode);
        modelTree = new JTree(treeModel);
        modelTree.setRootVisible(true);
        modelTree.setCellRenderer(new ModelTreeCellRenderer(iconCache, watchlist)); // F18: usa clase externa
        modelTree.setDragEnabled(true);
        modelTree.setRowHeight(20);  // Altura consistente para iconos
        modelTree.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        modelTree.setLargeModel(true);
        JScrollPane treeScroll = new JScrollPane(modelTree);
        treeScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        treeScroll.setBorder(BorderFactory.createTitledBorder("Modelo de Datos"));

        // Panel derecho con tabs: Activity Monitor, Reports, GOOSE, Setting Groups, Dataset, Data Model
        JTabbedPane rightTabbedPane = new JTabbedPane();
        rightTabbedPane.addTab("Monitor", createMonitorPanel());
        Supplier<ServerModel> panelModelSupplier = () -> {
            if (currentMode == AppMode.SERVER && server != null) return server.getServerModel();
            if (currentMode == AppMode.CLIENT && client != null && isConnected) return client.getServerModel();
            return null;
        };
        Supplier<IEC61850Client> panelClientSupplier = () ->
            (currentMode == AppMode.CLIENT && isConnected && client != null) ? client : null;
        rightTabbedPane.addTab("Reports",
            new ReportsPanel(this, this::log, panelModelSupplier, panelClientSupplier,
                backgroundExecutor, this::updateSingleNodeInTree).createPanel());
        goosePanel = new GoosePanel(createGooseContext());
        rightTabbedPane.addTab("GOOSE", goosePanel.createPanel());
        // rightTabbedPane.addTab("SV (SMV)", createSampledValuesPanel()); // SIN SMV
        rightTabbedPane.addTab("Setting Groups",
            new SettingGroupsPanel(this, this::log, panelModelSupplier, panelClientSupplier, backgroundExecutor).createPanel());
        rightTabbedPane.addTab("Dataset",
            new DatasetPanel(this, this::log, panelModelSupplier).createPanel());
        rightTabbedPane.addTab("Data Model",
            new DataModelPanel(this, this::log, panelModelSupplier, iconCache).createPanel());
        rightTabbedPane.setTabPlacement(JTabbedPane.TOP);

        // Check for native library availability
        checkNativeLibrary();

        // SplitPane principal (izquierda: controles+log, centro: tree, derecha: tabs)
        // Establecer tamaños minimos para evitar que se colapsen
        treeScroll.setMinimumSize(new Dimension(300, 200));
        rightTabbedPane.setMinimumSize(new Dimension(400, 200));
        leftPanel.setMinimumSize(new Dimension(250, 200));

        JSplitPane rightSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeScroll, rightTabbedPane);
        rightSplit.setDividerLocation(350);
        rightSplit.setResizeWeight(0.4);
        rightSplit.setOneTouchExpandable(true);  // Flechitas para expandir/colapsar

        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightSplit);
        mainSplit.setDividerLocation(280);
        mainSplit.setResizeWeight(0.2);
        mainSplit.setOneTouchExpandable(true);  // Flechitas para expandir/colapsar

        centerPanel.add(mainSplit, BorderLayout.CENTER);

        contentPanel.add(centerPanel, BorderLayout.CENTER);
        mainPanel.add(contentPanel, BorderLayout.CENTER);

        // Barra de estado inferior
        JPanel statusBar = createStatusBar();
        mainPanel.add(statusBar, BorderLayout.SOUTH);

        setContentPane(mainPanel);
    }

    private JMenuBar createMenuBar() {
        JMenuBar menuBar = new JMenuBar();

        // Menu Archivo
        JMenu menuFile = new JMenu("Archivo");
        JMenuItem miLoadScl = new JMenuItem("Cargar SCL/CID...");
        miLoadScl.addActionListener(e -> {
            if (currentMode == AppMode.SERVER) {
                selectSclFile();
            } else {
                // En modo cliente, permitir cargar SCL para GoCBs
                loadSclForGoCBs();
            }
        });
        menuFile.add(miLoadScl);
        menuFile.addSeparator();
        JMenuItem miExit = new JMenuItem("Salir");
        miExit.addActionListener(e -> System.exit(0));
        menuFile.add(miExit);
        menuBar.add(menuFile);

        // Menu Herramientas
        JMenu menuTools = new JMenu("Herramientas");
        JMenuItem miGetCid = new JMenuItem("Obtener CID del IED...");
        miGetCid.addActionListener(e -> obtenerCidDelIed());
        menuTools.add(miGetCid);
        JMenuItem miSaveCid = new JMenuItem("Guardar CID...");
        miSaveCid.addActionListener(e -> guardarCid());
        menuTools.add(miSaveCid);
        menuBar.add(menuTools);

        // Menu Ayuda
        JMenu menuHelp = new JMenu("Ayuda");
        JMenuItem miAbout = new JMenuItem("Acerca de...");
        miAbout.addActionListener(e -> showAboutDialog());
        menuHelp.add(miAbout);
        menuBar.add(menuHelp);

        return menuBar;
    }

    private void showAboutDialog() {
        String nativeStatus = nativeLibAvailable ?
            "<span style='color: green;'>✓ libiec61850 disponible</span>" :
            "<span style='color: red;'>✗ libiec61850 no encontrada</span>";

        String message =
            "<html><body style='width: 380px; padding: 10px;'>" +
            "<h2 style='color: #2E86AB; margin-bottom: 5px;'>IED Navigator</h2>" +
            "<p style='color: #666; font-size: 11px;'>Version 2.0 - Hybrid Edition</p>" +
            "<hr style='margin: 10px 0;'>" +
            "<p><b>IEC 61850 Explorer Tool</b></p>" +
            "<p>Herramienta profesional para explorar, monitorear y configurar " +
            "dispositivos IED compatibles con el estandar IEC 61850.</p>" +
            "<br>" +
            "<p><b>Caracteristicas:</b></p>" +
            "<ul>" +
            "<li>Cliente/Servidor MMS (iec61850bean)</li>" +
            "<li>Monitoreo de datos en tiempo real</li>" +
            "<li>Reports (URCB/BRCB)</li>" +
            "<li>GOOSE Subscriber/Publisher</li>" +
            "<li><b>Sampled Values (SMV)</b> - via libiec61850</li>" +
            "<li>Carga y descarga de archivos SCL/CID</li>" +
            "</ul>" +
            "<p style='font-size: 10px;'>" + nativeStatus + "</p>" +
            "<hr style='margin: 10px 0;'>" +
            "<p><b>Desarrollado por:</b></p>" +
            "<p style='color: #2E86AB; font-size: 13px;'><b>Emilio Medina</b></p>" +
            "<p style='font-size: 11px;'>Técnico Superior en Electrónica</p>" +
            "<br>" +
            "<p style='color: #888; font-size: 10px;'>" +
            "Bibliotecas: iec61850bean (MMS), libiec61850 (GOOSE/SV), pcap4j, JNA<br>" +
            "&copy; 2024 - Todos los derechos reservados</p>" +
            "</body></html>";

        JOptionPane.showMessageDialog(this, message, "Acerca de IED Navigator",
            JOptionPane.INFORMATION_MESSAGE);
    }

    private void loadSclForGoCBs() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Cargar SCL para GoCBs");
        fc.setFileFilter(new javax.swing.filechooser.FileFilter() {
            public boolean accept(File f) {
                if (f.isDirectory()) return true;
                String name = f.getName().toLowerCase();
                return name.endsWith(".icd") || name.endsWith(".cid") ||
                       name.endsWith(".scd") || name.endsWith(".scl");
            }
            public String getDescription() {
                return "SCL Files (*.icd, *.cid, *.scd, *.scl)";
            }
        });

        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = fc.getSelectedFile();
            log("Cargando SCL para GoCBs: " + file.getName());

            // Delegate to goosePanel (handles IED detection, parsing, and table refresh)
            if (goosePanel != null) {
                goosePanel.loadSclFile(file);
                log("GoCBs cargados: " + sclGoCBs.size());
            }
        }
    }

    private JToolBar createToolbar() {
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, Color.GRAY),
            BorderFactory.createEmptyBorder(5, 10, 5, 10)
        ));

        // Logo / Titulo
        JLabel lblTitle = new JLabel("  IED Navigator  ");
        lblTitle.setFont(new Font("Arial", Font.BOLD, 14));
        lblTitle.setForeground(new Color(0, 80, 160));
        toolbar.add(lblTitle);
        toolbar.addSeparator(new Dimension(20, 0));

        // Botones de toolbar
        JButton btnNewConnection = new JButton("Nueva Conexion");
        btnNewConnection.setToolTipText("Conectar a un IED");
        btnNewConnection.addActionListener(e -> {
            rbClient.setSelected(true);
            switchToClientMode();
        });
        toolbar.add(btnNewConnection);

        JButton btnSimulate = new JButton("Simular IED");
        btnSimulate.setToolTipText("Cargar SCL y simular un IED");
        btnSimulate.addActionListener(e -> {
            rbServer.setSelected(true);
            switchToServerMode();
        });
        toolbar.add(btnSimulate);

        toolbar.addSeparator();

        JButton btnClearLog = new JButton("Limpiar Log");
        btnClearLog.addActionListener(e -> logArea.setText(""));
        toolbar.add(btnClearLog);

        toolbar.add(Box.createHorizontalGlue());

        // Info version
        JLabel lblVersion = new JLabel("v1.0 | IEC 61850  ");
        lblVersion.setForeground(Color.GRAY);
        toolbar.add(lblVersion);

        return toolbar;
    }

    private JPanel createStatusBar() {
        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, Color.GRAY),
            BorderFactory.createEmptyBorder(3, 10, 3, 10)
        ));
        statusBar.setBackground(new Color(240, 240, 240));

        JLabel lblReady = new JLabel("Listo");
        statusBar.add(lblReady, BorderLayout.WEST);

        // Placa de identificación del IED (se llena tras conectar con FC=DC)
        lblIedInfo = new JLabel(" ");
        lblIedInfo.setFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN, 11));
        lblIedInfo.setForeground(new Color(40, 80, 160));
        statusBar.add(lblIedInfo, BorderLayout.CENTER);

        JLabel lblTime = new JLabel();
        javax.swing.Timer timer = new javax.swing.Timer(1000, e -> {
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
            lblTime.setText(sdf.format(new Date()));
        });
        timer.start();
        statusBar.add(lblTime, BorderLayout.EAST);

        return statusBar;
    }

    // ─── SECTION: PANEL CREATION METHODS ────────────────────────────────────────────
    // TODO-REFACTOR F4: Cada createXxxPanel() → clase independiente en ui/panels/

    /** Thin wrapper so that IEDExplorerApp code outside GoosePanel can still call logGoose(). */
    private void logGoose(String msg) {
        if (goosePanel != null) goosePanel.logGoose(msg);
    }

    /** Build the GoosePanel.Context implementation, wiring all dependencies. */
    private GoosePanel.Context createGooseContext() {
        IEDExplorerApp self = this;
        return new GoosePanel.Context() {
            public void log(String msg) { self.log(msg); }
            public IEC61850Server getServer() { return server; }
            public IEC61850Client getClient() { return client; }
            public boolean isConnected() { return self.isConnected; }
            public boolean isServerRunning() { return self.isServerRunning; }
            public boolean isServerMode() { return currentMode == AppMode.SERVER; }
            public java.util.concurrent.ExecutorService backgroundExecutor() { return backgroundExecutor; }
            public void updateSingleNodeInTree(String ref) { self.updateSingleNodeInTree(ref); }
            public void updateServerMonitorValues() { self.updateServerMonitorValues(); }
            public String formatReference(String ref) { return self.formatReference(ref); }
            public Component parentWindow() { return self; }
            public int showIEDSelectionDialog(java.util.List<String> names, String fileName) {
                return self.showIEDSelectionDialog(names, fileName);
            }
            public String getLoadedIedName() { return loadedIedName; }
            public void setLoadedIedName(String name) { loadedIedName = name; }
            public String[] getLoadedIedNameplate() { return loadedIedNameplate; }
            public void setLoadedIedNameplate(String[] np) { loadedIedNameplate = np; }
            public java.io.File getLoadedSclFile() { return loadedSclFile; }
            public void setLoadedSclFile(java.io.File f) { loadedSclFile = f; }
            public java.util.List<SclGoCB> getSclGoCBs() { return sclGoCBs; }
            public void setSclGoCBs(java.util.List<SclGoCB> gcbs) { sclGoCBs = gcbs; }
            public java.util.List<SclDataSet> getSclDataSets() { return sclDataSets; }
            public void setSclDataSets(java.util.List<SclDataSet> datasets) { sclDataSets = datasets; }
            public void setSclEnumMaps(
                    java.util.Map<String, java.util.LinkedHashMap<Integer, String>> enumTypes,
                    java.util.Map<String, String> daEnumType,
                    java.util.Map<String, java.util.Map<String, String>> lnTypeDoTypes,
                    java.util.Map<String, String> lnClassToLnType) {
                sclEnumTypes = enumTypes;
                sclDaEnumType = daEnumType;
                sclLnTypeDoTypes = lnTypeDoTypes;
                sclLnClassToLnType = lnClassToLnType;
            }
            public java.util.Map<String, java.util.LinkedHashMap<Integer, String>> getSclEnumTypes() { return sclEnumTypes; }
            public java.util.Map<String, String> getSclDaEnumType() { return sclDaEnumType; }
            public java.util.Map<String, java.util.Map<String, String>> getSclLnTypeDoTypes() { return sclLnTypeDoTypes; }
            public java.util.Map<String, String> getSclLnClassToLnType() { return sclLnClassToLnType; }
            public void onSclLoaded() {
                // Trigger tree rebuild if in server mode
                if (currentMode == AppMode.SERVER && server != null && server.getServerModel() != null) {
                    SwingUtilities.invokeLater(() -> buildTree(server.getServerModel()));
                }
            }
        };
    }

    private JPanel createServerPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createTitledBorder("Simulador IED (Servidor IEC 61850)"));

        // Fila 1: Seleccionar archivo SCL
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        btnSelectFile = new JButton("Cargar SCL/ICD/CID...");
        btnSelectFile.setToolTipText("Cargar archivo SCL, SCD, ICD o CID para simular IED");
        lblFileName = new JLabel("Ningun archivo");
        lblFileName.setForeground(Color.GRAY);
        row1.add(btnSelectFile);
        row1.add(lblFileName);
        panel.add(row1);

        // Fila 2: Puerto
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        row2.add(new JLabel("Puerto:"));
        tfServerPort = new JTextField("102", 6);
        row2.add(tfServerPort);

        // Info
        JLabel lblInfo = new JLabel("(102=MMS, 49151=pruebas)");
        lblInfo.setForeground(Color.GRAY);
        lblInfo.setFont(lblInfo.getFont().deriveFont(Font.ITALIC, 10f));
        row2.add(lblInfo);
        panel.add(row2);

        // Fila 3: Boton Start/Stop
        JPanel row3 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        btnStartStop = new JButton("Iniciar Simulacion");
        btnStartStop.setPreferredSize(new Dimension(200, 35));
        btnStartStop.setEnabled(false);
        btnStartStop.setToolTipText("Iniciar servidor IEC 61850 para simular el IED");
        row3.add(btnStartStop);
        panel.add(row3);

        // Fila 4: Informacion de uso
        JPanel row4 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JLabel lblUsage = new JLabel("<html><small>1. Carga SCL/ICD  2. Inicia servidor  3. Conecta cliente</small></html>");
        lblUsage.setForeground(new Color(100, 100, 150));
        row4.add(lblUsage);
        panel.add(row4);

        return panel;
    }

    private JPanel createClientPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createTitledBorder("Cliente IEC 61850"));

        // Fila 1: Host
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        row1.add(new JLabel("Host:"));
        tfHost = new JTextField("192.168.1.100", 15);
        row1.add(tfHost);
        panel.add(row1);

        // Fila 2: Puerto
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        row2.add(new JLabel("Puerto:"));
        tfClientPort = new JTextField("102", 6);
        row2.add(tfClientPort);
        panel.add(row2);

        // Fila 3: Boton Connect
        JPanel row3 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        btnConnect = new JButton("Conectar");
        btnConnect.setPreferredSize(new Dimension(200, 35));
        row3.add(btnConnect);
        panel.add(row3);

        // Fila 4: Polling
        JPanel row4 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        cbPolling = new JCheckBox("Polling");
        cbPolling.setEnabled(false);
        row4.add(cbPolling);
        row4.add(new JLabel("Intervalo (ms):"));
        spinnerInterval = new JSpinner(new SpinnerNumberModel(2000, 500, 60000, 500));
        spinnerInterval.setEnabled(false);
        row4.add(spinnerInterval);
        panel.add(row4);

        // Fila 5: Watchlist info
        JPanel row5 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        lblWatchlistCount = new JLabel("Watchlist: 0 nodos");
        lblWatchlistCount.setForeground(new Color(0, 100, 180));
        row5.add(lblWatchlistCount);
        JButton btnClearWatchlist = new JButton("Limpiar");
        btnClearWatchlist.setMargin(new Insets(2, 5, 2, 5));
        btnClearWatchlist.addActionListener(e -> clearWatchlist());
        row5.add(btnClearWatchlist);
        panel.add(row5);

        // Fila 6: Obtener/Guardar CID
        JPanel row6 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton btnGetCid = new JButton("Obtener CID");
        btnGetCid.setMargin(new Insets(2, 5, 2, 5));
        btnGetCid.setToolTipText("Buscar y descargar archivo CID del IED");
        btnGetCid.addActionListener(e -> obtenerCidDelIed());
        row6.add(btnGetCid);
        JButton btnSaveCid = new JButton("Guardar CID");
        btnSaveCid.setMargin(new Insets(2, 5, 2, 5));
        btnSaveCid.setToolTipText("Guardar el CID descargado en disco");
        btnSaveCid.addActionListener(e -> guardarCid());
        row6.add(btnSaveCid);
        panel.add(row6);

        return panel;
    }

    private void clearWatchlist() { monitorManager.clearWatchlist(); } // F24

    private void updateWatchlistLabel() {
        lblWatchlistCount.setText("Watchlist: " + watchlist.size() + " nodos");
    }

    // ─── SECTION: MONITOR PANEL ──────────────────────────────────────────────────────
    private JPanel createMonitorPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createTitledBorder("Activity Monitor (Drag & Drop)"));
        panel.setMinimumSize(new Dimension(300, 200));

        // Tabla con mas columnas como IEDScout
        String[] columns = {"Nombre", "FC", "Tipo", "Valor", "Estado"};
        monitorTableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        monitorTable = new JTable(monitorTableModel);
        monitorTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        monitorTable.setRowHeight(22);
        monitorTable.setShowGrid(true);
        monitorTable.setGridColor(new Color(220, 220, 220));

        // Configurar anchos de columna
        monitorTable.getColumnModel().getColumn(0).setPreferredWidth(180);  // Nombre
        monitorTable.getColumnModel().getColumn(1).setPreferredWidth(35);   // FC
        monitorTable.getColumnModel().getColumn(2).setPreferredWidth(70);   // Tipo
        monitorTable.getColumnModel().getColumn(3).setPreferredWidth(100);  // Valor
        monitorTable.getColumnModel().getColumn(4).setPreferredWidth(60);   // Estado

        // Renderer para colorear valores
        monitorTable.setDefaultRenderer(Object.class, new MonitorTableRenderer());

        // Sorter para filtrado
        monitorSorter = new TableRowSorter<>(monitorTableModel);
        monitorTable.setRowSorter(monitorSorter);

        // El drop se configura en setupDragAndDrop()
        monitorTable.setFillsViewportHeight(true);

        JScrollPane tableScroll = new JScrollPane(monitorTable);
        tableScroll.getViewport().setBackground(Color.WHITE);

        // Panel de area de drop visual
        JPanel dropHintPanel = new JPanel(new BorderLayout());
        dropHintPanel.setBackground(new Color(245, 250, 255));
        dropHintPanel.setBorder(BorderFactory.createDashedBorder(
            new Color(100, 150, 200), 2, 5, 3, true));
        JLabel lblDropHint = new JLabel("<html><center>Arrastra nodos desde el arbol<br>para monitorear valores</center></html>");
        lblDropHint.setForeground(new Color(100, 150, 200));
        lblDropHint.setHorizontalAlignment(SwingConstants.CENTER);
        dropHintPanel.add(lblDropHint, BorderLayout.CENTER);
        dropHintPanel.setPreferredSize(new Dimension(250, 60));

        // Header fila 1: conteo + botones
        JPanel headerRow1 = new JPanel(new BorderLayout());
        JLabel lblCount = new JLabel(" Items: 0");
        lblCount.setFont(lblCount.getFont().deriveFont(Font.BOLD));
        headerRow1.add(lblCount, BorderLayout.WEST);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 2));
        JButton btnRemove = new JButton("Quitar");
        btnRemove.setMargin(new Insets(2, 8, 2, 8));
        btnRemove.addActionListener(e -> removeSelectedFromMonitor());
        btnPanel.add(btnRemove);
        JButton btnClear = new JButton("Limpiar");
        btnClear.setMargin(new Insets(2, 8, 2, 8));
        btnClear.addActionListener(e -> clearMonitor());
        btnPanel.add(btnClear);
        headerRow1.add(btnPanel, BorderLayout.EAST);

        // Header fila 2: filtros
        JPanel headerRow2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        headerRow2.add(new JLabel("FC:"));
        monitorFcFilter = new JComboBox<>(new String[]{
            "Todos", "ST", "MX", "CF", "DC", "SP", "SV", "BL", "CO", "SE", "SG", "EX", "RP", "BR", "OR"
        });
        monitorFcFilter.setPreferredSize(new Dimension(70, 22));
        monitorFcFilter.addActionListener(e -> applyMonitorFilter());
        headerRow2.add(monitorFcFilter);
        headerRow2.add(new JLabel("  Nombre:"));
        monitorNameFilter = new javax.swing.JTextField(14);
        monitorNameFilter.setToolTipText("Filtrar por nombre (p.ej. MMXU, stVal)");
        monitorNameFilter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { applyMonitorFilter(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { applyMonitorFilter(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { applyMonitorFilter(); }
        });
        headerRow2.add(monitorNameFilter);
        JButton btnClearFilter = new JButton("✕");
        btnClearFilter.setMargin(new Insets(1, 4, 1, 4));
        btnClearFilter.setToolTipText("Limpiar filtros");
        btnClearFilter.addActionListener(e -> {
            monitorFcFilter.setSelectedIndex(0);
            monitorNameFilter.setText("");
        });
        headerRow2.add(btnClearFilter);

        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.add(headerRow1, BorderLayout.NORTH);
        headerPanel.add(headerRow2, BorderLayout.SOUTH);

        // Combinar todo
        JPanel contentPanel = new JPanel(new BorderLayout());
        contentPanel.add(tableScroll, BorderLayout.CENTER);
        contentPanel.add(dropHintPanel, BorderLayout.SOUTH);

        panel.add(headerPanel, BorderLayout.NORTH);
        panel.add(contentPanel, BorderLayout.CENTER);

        // Guardar referencia al label de conteo para actualizar
        monitorCountLabel = lblCount;

        return panel;
    }

    private JLabel monitorCountLabel;
    private TableRowSorter<DefaultTableModel> monitorSorter;
    private JComboBox<String> monitorFcFilter;
    private javax.swing.JTextField monitorNameFilter;

    private void clearMonitor() { monitorManager.clearMonitor(); } // F24

    // â”€â”€â”€ SECTION: REPORTS PANEL â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    // Fase 6: ReportsPanel extraido a archivo propio.

    // --- SECTION: SCL PARSING (F10: delegado a SclFileProcessor.java) ---
    private void parseGoCBsFromScl(File sclFile) {
        SclFileProcessor.SclParsingResult r = SclFileProcessor.parseFirstIED(sclFile, this::log);
        applySclResult(r);
    }

    private void parseGoCBsFromScl(File sclFile, int iedIndex) {
        SclFileProcessor.SclParsingResult r = SclFileProcessor.parseIEDByIndex(sclFile, iedIndex, this::log);
        if (r == null) {
            parseGoCBsFromScl(sclFile);
            return;
        }
        applySclResult(r);
    }

    private void applySclResult(SclFileProcessor.SclParsingResult r) {
        loadedIedName      = r.iedName;
        loadedIedNameplate = r.iedNameplate;
        sclGoCBs.clear();    sclGoCBs.addAll(r.goCBs);
        sclDataSets.clear(); sclDataSets.addAll(r.dataSets);
        sclReports.clear();  sclReports.addAll(r.reports);
        sclEnumTypes.clear();       sclEnumTypes.putAll(r.enumTypes);
        sclDaEnumType.clear();      sclDaEnumType.putAll(r.daEnumType);
        sclLnTypeDoTypes.clear();   sclLnTypeDoTypes.putAll(r.lnTypeDoTypes);
        sclLnClassToLnType.clear(); sclLnClassToLnType.putAll(r.lnClassToLnType);
    }

    private void checkNativeLibrary() {
        try {
            String libPath = System.getProperty("jna.library.path");
            log("JNA library path: " + (libPath != null ? libPath : "no configurado"));
            java.io.File dllFile = new java.io.File("lib/iec61850.dll");
            if (dllFile.exists()) {
                log("DLL encontrada: " + dllFile.getAbsolutePath() + " (" + dllFile.length() + " bytes)");
            } else {
                log("ADVERTENCIA: lib/iec61850.dll no encontrada");
            }
            nativeLibAvailable = NativeSVSubscriber.isNativeLibraryAvailable();
            if (nativeLibAvailable) {
                log("libiec61850 nativa CARGADA - SV y GOOSE nativo habilitados");
            } else {
                log("libiec61850 NO pudo cargar - usando pcap4j para GOOSE");
                log("Nota: SV (Sampled Values) requiere libiec61850");
            }
        } catch (UnsatisfiedLinkError e) {
            nativeLibAvailable = false;
            log("Error cargando libiec61850: " + e.getMessage());
        } catch (Exception e) {
            nativeLibAvailable = false;
            log("Error verificando libiec61850: " + e.getMessage());
        }
    }


    // --- SECTION: MONITOR OPERATIONS (F12: delegado a MonitorManager.java) ---
    private MonitorManager monitorManager;

    private void addNodeToMonitor(DefaultMutableTreeNode node)  { monitorManager.addNodeToMonitor(node); }
    private void removeSelectedFromMonitor()                    { monitorManager.removeSelectedFromMonitor(); }
    private void refreshMonitorTable()                          { monitorManager.refreshMonitorTable(); }
    private void applyMonitorFilter()                           { monitorManager.applyMonitorFilter(); }
    void         updateMonitorValues()                          { monitorManager.updateMonitorValues(); }
    private String formatReference(String ref)                  { return MonitorManager.formatReference(ref); }

    private MonitorManager.Context createMonitorContext() {
        return new MonitorManager.Context() {
            public void log(String msg)                                           { IEDExplorerApp.this.log(msg); }
            public java.util.Map<String, MonitorItem> getMonitorItems()           { return monitorItems; }
            public java.util.Set<String> getWatchlist()                           { return watchlist; }
            public javax.swing.JTree getModelTree()                               { return modelTree; }
            public javax.swing.JTable getMonitorTable()                           { return monitorTable; }
            public javax.swing.table.DefaultTableModel getMonitorTableModel()     { return monitorTableModel; }
            public javax.swing.JComboBox<String> getMonitorFcFilter()             { return monitorFcFilter; }
            public javax.swing.JTextField getMonitorNameFilter()                  { return monitorNameFilter; }
            public javax.swing.table.TableRowSorter<javax.swing.table.DefaultTableModel> getMonitorSorter() { return monitorSorter; }
            public javax.swing.JLabel getMonitorCountLabel()                      { return monitorCountLabel; }
            public String formatEnumValue(com.beanit.iec61850bean.ModelNode node, String rawValue) {
                return IEDExplorerApp.this.formatEnumValue(node, rawValue);
            }
            public void updateWatchlistLabel()                                    { IEDExplorerApp.this.updateWatchlistLabel(); }
            public com.beanit.iec61850bean.ServerModel getServerModel()           { return server != null ? server.getServerModel() : null; }
        };
    }


    private void setupListeners() {
        // Cambio de modo
        rbServer.addActionListener(e -> switchToServerMode());
        rbClient.addActionListener(e -> switchToClientMode());

        // Servidor
        btnSelectFile.addActionListener(e -> selectSclFile());
        btnStartStop.addActionListener(e -> toggleServer());

        // Cliente
        btnConnect.addActionListener(e -> toggleConnection());
        cbPolling.addActionListener(e -> togglePolling());

        // Crear popup menu para watchlist
        createTreePopupMenu();

        // Doble click en arbol para leer/editar valor, click derecho para menu
        modelTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    if (currentMode == AppMode.CLIENT && isConnected) {
                        readSelectedNode();
                    } else if (currentMode == AppMode.SERVER && isServerRunning) {
                        // En modo servidor, doble click abre editor de valor
                        setSelectedNodeCustomValue();
                    }
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                handleTreePopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                handleTreePopup(e);
            }
        });

        // Listener del cliente
        client.setValueChangeListener(new IEC61850Client.ValueChangeListener() {
            @Override
            public void onValueChanged(String reference, String value, String type) {
                SwingUtilities.invokeLater(() -> updateNodeValue(reference, value));
            }

            @Override
            public void onError(String reference, String error) {
                log("ERROR: " + reference + " - " + error);
            }

            @Override
            public void onConnectionClosed(String reason) {
                SwingUtilities.invokeLater(() -> {
                    handleDisconnect();
                    log("Conexion cerrada: " + reason);
                });
            }
        });
    }

    private JPopupMenu serverPopupMenu;  // Menu para modo servidor

    // ─── SECTION: TREE POPUP & VALUE EDITING ──────────────────────────────────────────
    private void createTreePopupMenu() {
        // === Menu para modo CLIENTE ===
        treePopupMenu = new JPopupMenu();

        JMenuItem miAddToWatchlist = new JMenuItem("Agregar a Watchlist");
        miAddToWatchlist.addActionListener(e -> addSelectedToWatchlist());
        treePopupMenu.add(miAddToWatchlist);

        JMenuItem miRemoveFromWatchlist = new JMenuItem("Quitar de Watchlist");
        miRemoveFromWatchlist.addActionListener(e -> removeSelectedFromWatchlist());
        treePopupMenu.add(miRemoveFromWatchlist);

        treePopupMenu.addSeparator();

        JMenuItem miReadValue = new JMenuItem("Leer Valor");
        miReadValue.addActionListener(e -> readSelectedNode());
        treePopupMenu.add(miReadValue);

        JMenuItem miAddToMonitor = new JMenuItem("Agregar a Monitor");
        miAddToMonitor.addActionListener(e -> {
            TreePath path = modelTree.getSelectionPath();
            if (path != null) {
                addNodeToMonitor((DefaultMutableTreeNode) path.getLastPathComponent());
            }
        });
        treePopupMenu.add(miAddToMonitor);

        treePopupMenu.addSeparator();

        // FC=BL: Bloquear / Desbloquear valor del DO
        JMenuItem miBlock   = new JMenuItem("Bloquear valor (blkEna=true)");
        JMenuItem miUnblock = new JMenuItem("Desbloquear valor (blkEna=false)");
        miBlock.addActionListener(e   -> toggleBlocking(true));
        miUnblock.addActionListener(e -> toggleBlocking(false));
        treePopupMenu.add(miBlock);
        treePopupMenu.add(miUnblock);

        // Mostrar/ocultar ítems BL según el nodo seleccionado
        treePopupMenu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                boolean hasBlk = getSelectedBlkEnaNode() != null;
                miBlock.setVisible(hasBlk);
                miUnblock.setVisible(hasBlk);
            }
            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {}
            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {}
        });

        // === Menu para modo SERVIDOR se construye dinamicamente ===
        serverPopupMenu = new JPopupMenu();
    }

    /**
     * Build the server popup menu dynamically based on the selected node's data type.
     */
    private void buildServerPopupForNode(DefaultMutableTreeNode treeNode) {
        serverPopupMenu.removeAll();

        Object userObj = treeNode.getUserObject();
        if (!(userObj instanceof NodeInfo)) return;
        NodeInfo info = (NodeInfo) userObj;

        // Detect data type
        boolean isBoolean = (info.node instanceof BdaBoolean)
            || (info.type != null && info.type.equalsIgnoreCase("Boolean"));
        boolean isDbpos = (info.node instanceof BdaDoubleBitPos)
            || (info.type != null && info.type.contains("DoubleBit"));
        boolean isTapCmd = (info.node instanceof BdaTapCommand);
        LinkedHashMap<Integer, String> precomputedEnumVals = getEnumOptionsForNode(info.node);
        boolean isEnum  = (precomputedEnumVals != null);
        boolean isDO = (info.node instanceof FcDataObject);

        // If it's a DO, check child stVal type
        if (isDO) {
            for (ModelNode child : info.node.getChildren()) {
                if ("stVal".equalsIgnoreCase(child.getName())) {
                    if (child instanceof BdaBoolean) isBoolean = true;
                    else if (child instanceof BdaDoubleBitPos) isDbpos = true;
                    else if (child instanceof BdaTapCommand) isTapCmd = true;
                    else if (child instanceof BdaInt8 || child instanceof BdaInt8U) {
                        precomputedEnumVals = getEnumOptionsForNode(child);
                        if (precomputedEnumVals != null) isEnum = true;
                    }
                    break;
                }
            }
        }

        if (isBoolean) {
            JMenuItem miTrue = new JMenuItem("Establecer TRUE");
            miTrue.addActionListener(e -> setSelectedNodeValue("true"));
            serverPopupMenu.add(miTrue);

            JMenuItem miFalse = new JMenuItem("Establecer FALSE");
            miFalse.addActionListener(e -> setSelectedNodeValue("false"));
            serverPopupMenu.add(miFalse);
        } else if (isDbpos) {
            JMenuItem miOn = new JMenuItem("Establecer ON (cerrado)");
            miOn.addActionListener(e -> setSelectedNodeValue("on"));
            serverPopupMenu.add(miOn);

            JMenuItem miOff = new JMenuItem("Establecer OFF (abierto)");
            miOff.addActionListener(e -> setSelectedNodeValue("off"));
            serverPopupMenu.add(miOff);

            JMenuItem miInter = new JMenuItem("Establecer INTERMEDIATE");
            miInter.addActionListener(e -> setSelectedNodeValue("intermediate"));
            serverPopupMenu.add(miInter);

            JMenuItem miBad = new JMenuItem("Establecer BAD_STATE");
            miBad.addActionListener(e -> setSelectedNodeValue("bad"));
            serverPopupMenu.add(miBad);
        } else if (isTapCmd) {
            for (String cmd : new String[]{"STOP", "LOWER", "HIGHER", "RESERVED"}) {
                JMenuItem mi = new JMenuItem("Establecer " + cmd);
                mi.addActionListener(e -> setSelectedNodeValue(cmd.toLowerCase()));
                serverPopupMenu.add(mi);
            }
        } else if (isEnum) {
            // Enum: show dropdown with enum values from SCL DataTypeTemplates
            JMenuItem miEnum = new JMenuItem("Establecer valor (enum)...");
            miEnum.addActionListener(e -> setSelectedNodeCustomValue());
            serverPopupMenu.add(miEnum);
        } else {
            // Generic: show custom value dialog
            JMenuItem miSet = new JMenuItem("Establecer valor...");
            miSet.addActionListener(e -> setSelectedNodeCustomValue());
            serverPopupMenu.add(miSet);
        }

        serverPopupMenu.addSeparator();

        JMenuItem miSetCustom = new JMenuItem("Valor personalizado...");
        miSetCustom.addActionListener(e -> setSelectedNodeCustomValue());
        serverPopupMenu.add(miSetCustom);

        serverPopupMenu.addSeparator();

        JMenuItem miPublishGoose = new JMenuItem("Publicar GOOSE (cambio de estado)");
        miPublishGoose.addActionListener(e -> publishGooseFromSelection());
        serverPopupMenu.add(miPublishGoose);
    }

    private void handleTreePopup(MouseEvent e) {
        if (!e.isPopupTrigger()) return;

        // Seleccionar el nodo bajo el cursor
        TreePath path = modelTree.getPathForLocation(e.getX(), e.getY());
        if (path == null) return;

        modelTree.setSelectionPath(path);

        // Check if it's an FCDA node (DataSet member) -> special popup
        DefaultMutableTreeNode clickedNode = (DefaultMutableTreeNode) path.getLastPathComponent();
        Object userObj = clickedNode.getUserObject();
        if (userObj instanceof NodeInfo) {
            NodeInfo info = (NodeInfo) userObj;
            if ("FCDA".equals(info.prefix)) {
                JPopupMenu fcdaPopup = new JPopupMenu();
                JMenuItem miNavigate = new JMenuItem("Ver en modelo de datos");
                miNavigate.addActionListener(ev -> navigateToFcdaInModel(info.name));
                fcdaPopup.add(miNavigate);

                // If server model is loaded, also add type-aware state change options
                if (currentMode == AppMode.SERVER && server != null && server.getServerModel() != null) {
                    fcdaPopup.addSeparator();
                    GoosePublisher.DataValue.Type fcdaType = inferDataType(info.name);
                    String[][] opts;
                    if (fcdaType == GoosePublisher.DataValue.Type.BOOLEAN) {
                        opts = new String[][]{{"Establecer TRUE", "true"}, {"Establecer FALSE", "false"}};
                    } else if (fcdaType == GoosePublisher.DataValue.Type.DBPOS) {
                        opts = new String[][]{{"Establecer ON", "on"}, {"Establecer OFF", "off"},
                            {"Establecer INTERMEDIATE", "intermediate"}, {"Establecer BAD", "bad"}};
                    } else {
                        opts = new String[][]{{"Establecer TRUE", "true"}, {"Establecer FALSE", "false"}};
                    }
                    for (String[] opt : opts) {
                        JMenuItem mi = new JMenuItem(opt[0]);
                        String val = opt[1];
                        mi.addActionListener(ev -> {
                            DefaultMutableTreeNode targetNode = findNodeInModel(info.name);
                            if (targetNode != null) {
                                modelTree.setSelectionPath(new TreePath(targetNode.getPath()));
                                setSelectedNodeValue(val);
                            }
                        });
                        fcdaPopup.add(mi);
                    }
                }

                fcdaPopup.show(modelTree, e.getX(), e.getY());
                return;
            }
        }

        if (currentMode == AppMode.CLIENT && isConnected) {
            treePopupMenu.show(modelTree, e.getX(), e.getY());
        } else if (currentMode == AppMode.SERVER && server != null && server.getServerModel() != null) {
            DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) path.getLastPathComponent();
            buildServerPopupForNode(selectedNode);
            serverPopupMenu.show(modelTree, e.getX(), e.getY());
        }
    }

    // F22: tree navigation — delegado a ModelTreeBuilder.java
    private void navigateToFcdaInModel(String fcdaName) {
        ModelTreeBuilder.navigateToFcdaInModel(modelTree, fcdaName, this::log, this::logGoose);
    }

    private DefaultMutableTreeNode findNodeInModel(String fcdaName) {
        return ModelTreeBuilder.findNodeByFcda(modelTree, fcdaName);
    }

    // F25: common "set + propagate" helper (extracted to eliminate duplication)
    private void applyServerValue(String ref, String value) {
        boolean success = server.setDataValue(ref, value);
        if (success) {
            log("SET: " + formatReference(ref) + " = " + value);
            updateSingleNodeInTree(ref);
            updateServerMonitorValues();
            GoosePublisher gp = goosePanel != null ? goosePanel.getGoosePublisher() : null;
            if (gp != null && gp.isPublishing() && updateGoosePublisherValues()) {
                gp.publishStateChange();
                logGoose("GOOSE publicado: " + formatReference(ref) + " = " + value);
            }
            if (goosePanel != null && !goosePanel.getActivePublishers().isEmpty()) {
                propagateValueToPublishers(ref, value);
            }
        } else {
            log("Error estableciendo valor: nodo no encontrado en el modelo (" + ref + ")");
        }
    }

    // Establecer valor en servidor (para simulacion)
    private void setSelectedNodeValue(String value) {
        TreePath path = modelTree.getSelectionPath();
        if (path == null || server == null || server.getServerModel() == null) return;
        DefaultMutableTreeNode treeNode = (DefaultMutableTreeNode) path.getLastPathComponent();
        Object userObj = treeNode.getUserObject();
        if (userObj instanceof NodeInfo) {
            NodeInfo info = (NodeInfo) userObj;
            String ref = info.node.getReference().toString();
            if (info.node instanceof FcDataObject) ref = ref + ".stVal";
            applyServerValue(ref, value);
        }
    }

    // ─── SECTION: GOOSE-MODEL SYNC (bidireccional) ────────────────────────────────────
    // Ver CLAUDE.md sección "GOOSE-DataModel Bidirectional Sync Architecture"

    // Thin wrappers delegating enum/infer helpers to GoosePanel
    private LinkedHashMap<Integer, String> getEnumOptionsForNode(ModelNode node) {
        return goosePanel != null ? goosePanel.getEnumOptionsForNode(node) : null;
    }
    private String formatEnumValue(ModelNode node, String rawValue) {
        return goosePanel != null ? goosePanel.formatEnumValue(node, rawValue) : rawValue;
    }
    private String showEnumDialog(String daName, int currentOrd, LinkedHashMap<Integer, String> enumVals) {
        return goosePanel != null ? goosePanel.showEnumDialog(daName, currentOrd, enumVals) : null;
    }
    private GoosePublisher.DataValue.Type inferDataType(String memberName) {
        return goosePanel != null ? goosePanel.inferDataType(memberName) : GoosePublisher.DataValue.Type.BOOLEAN;
    }
    private void publishGooseFromSelection() {
        if (goosePanel != null) goosePanel.publishGooseFromSelection();
    }
    private void autoSelectGooseInterface(String ip) {
        if (goosePanel != null) goosePanel.autoSelectGooseInterface(ip);
    }
    private void refreshGooseControlBlocks() {
        if (goosePanel != null) goosePanel.refreshGooseControlBlocksPublic();
    }
    // F21: delegado a GoosePanel.java
    private boolean updateGoosePublisherValues() {
        if (goosePanel == null || server == null) return false;
        ServerModel model = server.getServerModel();
        return model != null && goosePanel.updateGoosePublisherValues(model);
    }

    private void propagateValueToPublishers(String ref, String value) {
        if (goosePanel == null || server == null) return;
        ServerModel model = server.getServerModel();
        if (model != null) goosePanel.propagateValueToPublishers(ref, model);
    }

    // Actualizar valores del monitor en modo servidor
    // F16: delegado a MonitorManager.java
    private void updateServerMonitorValues() { monitorManager.updateServerMonitorValues(); }

    private void setSelectedNodeCustomValue() {
        TreePath path = modelTree.getSelectionPath();
        if (path == null || server == null || server.getServerModel() == null) return;

        DefaultMutableTreeNode treeNode = (DefaultMutableTreeNode) path.getLastPathComponent();
        Object userObj = treeNode.getUserObject();

        if (userObj instanceof NodeInfo) {
            NodeInfo info = (NodeInfo) userObj;
            String ref = info.node.getReference().toString();
            String currentValue = info.value != null ? info.value : "";

            // Determinar el tipo de dato para mostrar opciones apropiadas
            String newValue = null;

            if (info.node instanceof BdaDoubleBitPos ||
                (info.type != null && info.type.contains("DoubleBit"))) {
                // Mostrar dropdown para DoubleBitPos
                newValue = showDoubleBitPosDialog(info.name, currentValue);
            } else if (info.node instanceof BdaBoolean ||
                       (info.type != null && info.type.equals("Boolean"))) {
                // Mostrar dropdown para Boolean
                newValue = showBooleanDialog(info.name, currentValue);
            } else if (info.node instanceof BdaTapCommand) {
                // Mostrar dropdown para TapCommand
                newValue = showTapCommandDialog(info.name, currentValue);
            } else if (info.node instanceof BdaInt8 || info.node instanceof BdaInt8U) {
                // Puede ser un enum (bType="Enum" → BdaInt8 en iec61850bean)
                int currentOrd = 0;
                try {
                    if (info.node instanceof BdaInt8) currentOrd = ((BdaInt8) info.node).getValue();
                    else currentOrd = ((BdaInt8U) info.node).getValue();
                } catch (Exception ignore) {}
                LinkedHashMap<Integer, String> enumVals = getEnumOptionsForNode(info.node);
                if (enumVals != null && !enumVals.isEmpty()) {
                    newValue = showEnumDialog(info.name, currentOrd, enumVals);
                } else {
                    // No es enum conocido — input numérico
                    newValue = JOptionPane.showInputDialog(this,
                        "Nuevo valor para " + info.name + " (entero):", String.valueOf(currentOrd));
                }
            } else {
                // Otros tipos: usar input de texto
                newValue = JOptionPane.showInputDialog(this,
                    "Nuevo valor para " + info.name + ":",
                    currentValue);
            }

            if (newValue != null && !newValue.isEmpty()) {
                applyServerValue(ref, newValue);
            }
        }
    }

    // Diálogos para edición de valores — F13: delegados a ValueDialogs.java
    private String showDoubleBitPosDialog(String name, String currentValue) { return ValueDialogs.showDoubleBitPosDialog(this, name, currentValue); }
    private String showBooleanDialog(String name, String currentValue)      { return ValueDialogs.showBooleanDialog(this, name, currentValue); }
    private String showTapCommandDialog(String name, String currentValue)   { return ValueDialogs.showTapCommandDialog(this, name, currentValue); }

    // F17: delegado a ModelTreeBuilder.java
    private void updateSingleNodeInTree(String reference) {
        ModelTreeBuilder.updateSingleNodeInTree(reference, nodeMap, treeModel, this::formatEnumValue);
    }

    // F15: watchlist operations — delegadas a MonitorManager.java
    private void addSelectedToWatchlist()                              { monitorManager.addSelectedToWatchlist(); }
    private void addNodeToWatchlist(DefaultMutableTreeNode treeNode)  { monitorManager.addNodeToWatchlist(treeNode); }
    private void removeSelectedFromWatchlist()                         { monitorManager.removeSelectedFromWatchlist(); }
    private void removeNodeFromWatchlist(DefaultMutableTreeNode node)  { monitorManager.removeNodeFromWatchlist(node); }

    // --- SECTION: CONNECTION MANAGEMENT (F7: delegado a ConnectionManager.java) ---
    // F26: delegado a ConnectionManager.java (misma lógica)
    private void handleDisconnect() { connectionManager.handleDisconnect(); }

    private void switchToServerMode()  { connectionManager.switchToServerMode(); }
    private void switchToClientMode()  { connectionManager.switchToClientMode(); }
    private void obtenerCidDelIed()    { connectionManager.obtenerCidDelIed(); }
    private void guardarCid()          { connectionManager.guardarCid(); }
    private void selectSclFile()       { connectionManager.selectSclFile(); }
    private void toggleServer()        { connectionManager.toggleServer(); }
    private void toggleConnection()    { connectionManager.toggleConnection(); }

    private void updateConnectionInfo(String host, int port) {
        if (host == null || host.isEmpty() || port == 0) {
            lblConnectionInfo.setText("Sin conexion");
            lblConnectionInfo.setForeground(Color.GRAY);
        } else {
            lblConnectionInfo.setText(host + ":" + port);
            lblConnectionInfo.setForeground(new Color(0, 100, 0));
        }
    }

    private int showIEDSelectionDialog(List<String> ieds, String fileName) {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JLabel lblInfo = new JLabel("<html>El archivo <b>" + fileName + "</b> contiene " +
            ieds.size() + " IEDs.<br>Seleccione cu\u00e1l desea simular:</html>");
        panel.add(lblInfo, BorderLayout.NORTH);
        DefaultListModel<String> listModel = new DefaultListModel<>();
        for (int i = 0; i < ieds.size(); i++) {
            listModel.addElement((i + 1) + ". " + ieds.get(i));
        }
        JList<String> iedList = new JList<>(listModel);
        iedList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        iedList.setSelectedIndex(0);
        iedList.setVisibleRowCount(Math.min(ieds.size(), 8));
        iedList.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JScrollPane scrollPane = new JScrollPane(iedList);
        scrollPane.setPreferredSize(new Dimension(350, 150));
        panel.add(scrollPane, BorderLayout.CENTER);
        int result = JOptionPane.showConfirmDialog(this, panel,
            "Seleccionar IED", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (result == JOptionPane.OK_OPTION) {
            return iedList.getSelectedIndex();
        }
        return -1;
    }

    private ConnectionManager.Context createConnectionContext() {
        return new ConnectionManager.Context() {
            public void log(String msg) { IEDExplorerApp.this.log(msg); }
            public void updateStatus(boolean active, String msg) { IEDExplorerApp.this.updateStatus(active, msg); }
            public Component parentWindow() { return IEDExplorerApp.this; }
            public ExecutorService backgroundExecutor() { return backgroundExecutor; }
            public IEC61850Client getClient() { return client; }
            public void setClient(IEC61850Client c) { client = c; }
            public IEC61850Server getServer() { return server; }
            public void setServer(IEC61850Server s) { server = s; }
            public boolean isConnected() { return IEDExplorerApp.this.isConnected; }
            public void setConnected(boolean v) { isConnected = v; }
            public boolean isServerRunning() { return IEDExplorerApp.this.isServerRunning; }
            public void setServerRunning(boolean v) { isServerRunning = v; }
            public File getLoadedSclFile() { return loadedSclFile; }
            public void setLoadedSclFile(File f) { loadedSclFile = f; }
            public String getLoadedIedName() { return loadedIedName; }
            public void setLoadedIedName(String n) { loadedIedName = n; }
            public String[] getLoadedIedNameplate() { return loadedIedNameplate; }
            public void setLoadedIedNameplate(String[] np) { loadedIedNameplate = np; }
            public List<SclGoCB> getSclGoCBs() { return sclGoCBs; }
            public void switchUiToServerMode() {
                currentMode = AppMode.SERVER;
                cardLayout.show(cardPanel, "SERVER");
            }
            public void switchUiToClientMode() {
                currentMode = AppMode.CLIENT;
                cardLayout.show(cardPanel, "CLIENT");
            }
            public void onConnected(String host, int port, String localIp) {}
            public void onDisconnected() {}
            public void onServerStarted(String localIp, int port) {}
            public void onServerStopped() {}
            public void displayServerModel() { IEDExplorerApp.this.displayServerModel(); }
            public void displayClientModel() { IEDExplorerApp.this.displayClientModel(); }
            public void refreshGooseControlBlocks() { IEDExplorerApp.this.refreshGooseControlBlocks(); }
            public void autoSelectGooseInterface(String localIp) { IEDExplorerApp.this.autoSelectGooseInterface(localIp); }
            public void parseGoCBsFromScl(File f) { IEDExplorerApp.this.parseGoCBsFromScl(f); }
            public void parseGoCBsFromScl(File f, int iedIndex) { IEDExplorerApp.this.parseGoCBsFromScl(f, iedIndex); }
            public int showIEDSelectionDialog(List<String> iedNames, String fileName) {
                return IEDExplorerApp.this.showIEDSelectionDialog(iedNames, fileName);
            }
            public void stopPolling() { IEDExplorerApp.this.stopPolling(); }
            public String getTfHost() { return tfHost.getText(); }
            public String getTfClientPort() { return tfClientPort.getText(); }
            public String getTfServerPort() { return tfServerPort.getText(); }
            public void setLblFileName(String text) { lblFileName.setText(text); }
            public void setStatusIndicatorConnecting() { statusIndicator.setBackground(COLOR_CONNECTING); }
            public void setBtnConnectEnabled(boolean v) { btnConnect.setEnabled(v); }
            public void setBtnConnectText(String text) { btnConnect.setText(text); }
            public void setBtnStartStopText(String text) { btnStartStop.setText(text); }
            public void setBtnStartStopEnabled(boolean v) { btnStartStop.setEnabled(v); }
            public void setCbPollingEnabled(boolean v) { cbPolling.setEnabled(v); }
            public void setCbPollingSelected(boolean v) { cbPolling.setSelected(v); }
            public void setSpinnerIntervalEnabled(boolean v) { spinnerInterval.setEnabled(v); }
            public void setLblIedInfo(String text) { lblIedInfo.setText(text); }
            public void updateConnectionInfo(String host, int port) {
                IEDExplorerApp.this.updateConnectionInfo(host, port);
            }
            public void clearModel() { IEDExplorerApp.this.clearModel(); }
            public Map<String, String> readDeviceNameplate() {
                return client != null ? client.readDeviceNameplate() : Collections.emptyMap();
            }
        };
    }


    // --- SECTION: POLLING (F8: delegado a PollingManager.java) ---
    private void togglePolling() { pollingManager.toggle(cbPolling.isSelected()); }
    private void stopPolling()   { pollingManager.stop(); }

    // updateVisibleTreeNodes kept accessible for MONITOR OPERATIONS section
    private void updateVisibleTreeNodes(DefaultMutableTreeNode treeNode) {
        pollingManager.updateVisibleTreeNodes(treeNode);
    }

    // F27: delegados a PollingManager.java
    private void readSelectedNode()                                                           { pollingManager.readSelectedNode(); }
    private void updateTreeNodeRecursive(DefaultMutableTreeNode n)                           { ModelTreeBuilder.updateTreeNodeRecursive(n, treeModel); }
    private com.beanit.iec61850bean.FcModelNode getSelectedBlkEnaNode()                      { return pollingManager.getSelectedBlkEnaNode(); }
    private void toggleBlocking(boolean block)                                               { pollingManager.toggleBlocking(block); }

    private PollingManager.Context createPollingContext() {
        return new PollingManager.Context() {
            public void log(String msg) { IEDExplorerApp.this.log(msg); }
            public boolean isConnected() { return IEDExplorerApp.this.isConnected; }
            public IEC61850Client getClient() { return client; }
            public java.util.Set<String> getWatchlist() { return watchlist; }
            public DefaultMutableTreeNode getRootNode() { return rootNode; }
            public JTree getModelTree() { return modelTree; }
            public DefaultTreeModel getTreeModel() { return treeModel; }
            public String formatEnumValue(ModelNode node, String rawValue) {
                return IEDExplorerApp.this.formatEnumValue(node, rawValue);
            }
            public void updateMonitorValues() { IEDExplorerApp.this.updateMonitorValues(); }
            public int getPollingInterval() { return (Integer) spinnerInterval.getValue(); }
            public java.util.concurrent.ExecutorService backgroundExecutor() { return backgroundExecutor; }
        };
    }


    // --- SECTION: MODEL TREE BUILDING (F9: delegado a ModelTreeBuilder.java) ---
    private java.util.function.BiFunction<ModelNode, String, String> enumFormatterFn() {
        return (node, raw) -> IEDExplorerApp.this.formatEnumValue(node, raw);
    }

    // Cache para DataSets y Reports parseados del SCL
    private List<SclDataSet> sclDataSets = new ArrayList<>();
    private List<SclReport> sclReports = new ArrayList<>();

    private void displayServerModel() {
        ServerModel model = server.getServerModel();
        if (model == null) { log("ERROR: ServerModel es null"); return; }
        log("ServerModel tiene " + model.getChildren().size() + " LDs");
        buildTree(model);
    }

    private void displayClientModel() {
        ServerModel model = client.getServerModel();
        if (model == null) { log("ERROR: Cliente ServerModel es null"); return; }
        log("Cliente ServerModel tiene " + model.getChildren().size() + " LDs");
        buildTree(model);
    }

    private void buildTree(ServerModel model) {
        ModelTreeBuilder.buildTree(model, rootNode, treeModel, nodeMap,
            sclDataSets, sclReports, sclGoCBs, modelTree, this::log, enumFormatterFn());
    }

    private void clearModel() {
        ModelTreeBuilder.clearModel(rootNode, nodeMap, treeModel);
    }

    private void updateNodeValue(String reference, String value) {
        ModelTreeBuilder.updateNodeValue(reference, value, nodeMap, treeModel);
    }

    private void updateTreeValues(ServerModel model) {
        ModelTreeBuilder.updateTreeValues(model, nodeMap, treeModel);
    }

    private void updateStatus(boolean active, String message) {
        statusIndicator.setBackground(active ? COLOR_RUNNING : COLOR_STOPPED);
        lblStatus.setText(message);
    }

    private void log(String message) {
        SwingUtilities.invokeLater(() -> {
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
            logArea.append("[" + sdf.format(new Date()) + "] " + message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }


    // â”€â”€â”€ SECTION: SETTING GROUPS / DATASET / DATA MODEL PANELS â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    // Fase 4: SettingGroupsPanel, DatasetPanel, DataModelPanel extraidos a archivos propios.

    // ─── SECTION: ENTRY POINT ─────────────────────────────────────────────────────────
    public static void main(String[] args) {
        // Look and Feel
        try {
            UIManager.setLookAndFeel("com.formdev.flatlaf.FlatLightLaf");
        } catch (Exception e) {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ex) {}
        }

        SwingUtilities.invokeLater(() -> {
            IEDExplorerApp app = new IEDExplorerApp();
            app.setVisible(true);
        });
    }
}
