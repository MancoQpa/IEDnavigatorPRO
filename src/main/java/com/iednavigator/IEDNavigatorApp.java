package com.iednavigator;

import com.beanit.iec61850bean.*;
import com.iednavigator.native_lib.*;

import javax.swing.*;
import javax.swing.table.*;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.net.BindException;
import java.net.ServerSocket;
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
public class IEDNavigatorApp extends JFrame {

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
    private JButton btnCheckPort;
    private JButton btnReleasePort;

    // Panel Client
    private JTextField tfHost;
    private JTextField tfClientPort;
    private JButton btnConnect;
    private JCheckBox cbPolling;
    private JSpinner spinnerInterval;
    private JSpinner spinnerTimeout;

    // Comunes
    private JLabel lblStatus;
    private JLabel lblIedInfo;      // placa de identificación del IED (FC=DC) - barra inferior
    private JLabel lblIedDisplay;   // nombre del IED destacado - esquina superior derecha
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
    private ReportsPanel reportsPanel;

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
                    log(I18n.t("log.app.droperror", e.getMessage()));
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
    public IEDNavigatorApp() {
        client = new IEC61850Client();
        server = new IEC61850Server();

        // Wire up ServerListener so client writes refresh the server UI
        server.setServerListener(new IEC61850Server.ServerListener() {
            @Override public void onServerStarted(int port) {}
            @Override public void onServerStopped() {}
            @Override public void onError(String message) { log(I18n.t("log.app.servererror", message)); }
            @Override public void onClientWrite(String nodeRef, String value) {
                SwingUtilities.invokeLater(() -> {
                    log(I18n.t("log.app.serverclientwrite", nodeRef, value));
                    updateSingleNodeInTree(nodeRef);
                    updateServerMonitorValues();
                    // Propagate to GOOSE publishers if active
                    GoosePublisher gp = goosePanel != null ? goosePanel.getGoosePublisher() : null;
                    if (gp != null && gp.isPublishing() && updateGoosePublisherValues()) {
                        gp.publishStateChange();
                        logGoose(I18n.t("log.app.goosepubremote", nodeRef, value));
                    }
                    if (goosePanel != null && !goosePanel.getActivePublishers().isEmpty()) {
                        propagateValueToPublishers(nodeRef, value);
                    }
                });
            }
        });

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
        setTitle(I18n.t("app.title"));
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1200, 800);
        setLocationRelativeTo(null);

        // Icono de la aplicación (múltiples tamaños para barra de título, taskbar, Alt+Tab)
        java.util.List<java.awt.Image> icons = new java.util.ArrayList<>();
        for (int sz : new int[]{16, 32, 48, 64, 256}) {
            java.net.URL url = getClass().getResource("/app_icon_" + sz + ".png");
            if (url != null) icons.add(new javax.swing.ImageIcon(url).getImage());
        }
        if (!icons.isEmpty()) setIconImages(icons);

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
        modePanel.setBorder(BorderFactory.createTitledBorder(I18n.t("mode.border")));
        ButtonGroup modeGroup = new ButtonGroup();
        rbServer = new JRadioButton(I18n.t("mode.server"));
        rbClient = new JRadioButton(I18n.t("mode.client"), true);
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
        lblStatus = new JLabel(I18n.t("status.disconnected"));
        lblStatus.setFont(lblStatus.getFont().deriveFont(Font.BOLD));
        statusPanel.add(statusIndicator);
        statusPanel.add(lblStatus);

        // Separador
        JSeparator sep = new JSeparator(SwingConstants.VERTICAL);
        sep.setPreferredSize(new Dimension(2, 20));
        statusPanel.add(sep);

        // Info de conexion (IP:Puerto)
        lblConnectionInfo = new JLabel(I18n.t("status.noconn"));
        lblConnectionInfo.setFont(new Font("Monospaced", Font.BOLD, 12));
        lblConnectionInfo.setForeground(new Color(0, 80, 160));
        statusPanel.add(new JLabel(I18n.t("lbl.connection")));
        statusPanel.add(lblConnectionInfo);

        topPanel.add(statusPanel, BorderLayout.CENTER);

        // Panel EAST: nombre del IED/equipo activo destacado
        lblIedDisplay = new JLabel("  " + I18n.t("status.nodevice") + "  ");
        lblIedDisplay.setFont(new Font("Dialog", Font.BOLD, 15));
        lblIedDisplay.setForeground(new Color(15, 55, 120));
        lblIedDisplay.setHorizontalAlignment(SwingConstants.CENTER);
        lblIedDisplay.setVerticalAlignment(SwingConstants.CENTER);
        JPanel iedDisplayPanel = new JPanel(new BorderLayout());
        iedDisplayPanel.setBackground(new Color(220, 232, 252));
        iedDisplayPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 2, 1, 1, new Color(80, 120, 200)),
            BorderFactory.createEmptyBorder(4, 12, 4, 12)));
        iedDisplayPanel.add(lblIedDisplay, BorderLayout.CENTER);
        topPanel.add(iedDisplayPanel, BorderLayout.EAST);

        contentPanel.add(topPanel, BorderLayout.NORTH);

        // === Panel Central: Cards (Server/Client) + Tree ===
        JPanel centerPanel = new JPanel(new BorderLayout(5, 5));

        // Cards para Server/Client
        cardLayout = new CardLayout();
        cardPanel = new JPanel(cardLayout);
        cardPanel.add(createServerPanel(), "SERVER");
        cardPanel.add(createClientPanel(), "CLIENT");
        // No fijar altura: CardLayout toma el max de ambas cards (server ~210, client ~280)

        // Panel izquierdo (cards + log)
        JPanel leftPanel = new JPanel(new BorderLayout(5, 5));
        leftPanel.add(cardPanel, BorderLayout.NORTH);

        // Log
        logArea = new JTextArea(8, 30);
        logArea.setEditable(false);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        // Estilo consola: fondo levemente diferenciado (solo cosmético)
        logArea.setBackground(new Color(0xF7F9FB));
        logArea.setForeground(new Color(0x37474F));
        logArea.setMargin(new Insets(4, 8, 4, 8));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder(I18n.t("border.log")));
        leftPanel.add(logScroll, BorderLayout.CENTER);

        // Tree del modelo con soporte Drag
        rootNode = new DefaultMutableTreeNode(I18n.t("tree.root"));
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
        treeScroll.setBorder(BorderFactory.createTitledBorder(I18n.t("border.datamodel")));

        // Panel derecho con tabs: Activity Monitor, Reports, GOOSE, Setting Groups, Dataset, Data Model
        JTabbedPane rightTabbedPane = new JTabbedPane();
        rightTabbedPane.addTab(I18n.t("tab.monitor"), createMonitorPanel());
        Supplier<ServerModel> panelModelSupplier = () -> {
            if (currentMode == AppMode.SERVER && server != null) return server.getServerModel();
            if (currentMode == AppMode.CLIENT && client != null && isConnected) return client.getServerModel();
            return null;
        };
        Supplier<IEC61850Client> panelClientSupplier = () ->
            (currentMode == AppMode.CLIENT && isConnected && client != null) ? client : null;
        reportsPanel = new ReportsPanel(this, this::log, panelModelSupplier, panelClientSupplier,
                backgroundExecutor, this::updateSingleNodeInTree, this::navigateToFcdaInModel);
        rightTabbedPane.addTab(I18n.t("tab.reports"), reportsPanel.createPanel());
        goosePanel = new GoosePanel(createGooseContext());
        rightTabbedPane.addTab(I18n.t("tab.goose"), goosePanel.createPanel());
        // rightTabbedPane.addTab("SV (SMV)", createSampledValuesPanel()); // SIN SMV
        rightTabbedPane.addTab(I18n.t("tab.sg"),
            new SettingGroupsPanel(this, this::log, panelModelSupplier, panelClientSupplier,
                backgroundExecutor, this::navigateToFcdaInModel).createPanel());
        rightTabbedPane.addTab(I18n.t("tab.sp"),
            new ProtectionSettingsPanel(this, this::log, panelModelSupplier, panelClientSupplier,
                backgroundExecutor, this::navigateToFcdaInModel).createPanel());
        rightTabbedPane.addTab(I18n.t("tab.dataset"),
            new DatasetPanel(this, this::log, panelModelSupplier, panelClientSupplier,
                backgroundExecutor, this::navigateToFcdaInModel).createPanel());
        rightTabbedPane.addTab(I18n.t("tab.datamodel"),
            new DataModelPanel(this, this::log, panelModelSupplier, iconCache).createPanel());
        rightTabbedPane.addTab(I18n.t("tab.sclcmp"),
            new SclComparePanel(this, this::log).createPanel());
        rightTabbedPane.addTab(I18n.t("tab.goosemap"),
            new GooseMapPanel(this, this::log).createPanel());
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
        mainSplit.setDividerLocation(310);
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
        JMenu menuFile = new JMenu(I18n.t("menu.file"));
        JMenuItem miLoadScl = new JMenuItem(I18n.t("menu.file.loadscl"));
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
        JMenuItem miExit = new JMenuItem(I18n.t("menu.file.exit"));
        miExit.addActionListener(e -> System.exit(0));
        menuFile.add(miExit);
        menuBar.add(menuFile);

        // Menu Herramientas
        JMenu menuTools = new JMenu(I18n.t("menu.tools"));
        JMenuItem miGetCid = new JMenuItem(I18n.t("menu.tools.getcid"));
        miGetCid.addActionListener(e -> obtenerCidDelIed());
        menuTools.add(miGetCid);
        JMenuItem miSaveCid = new JMenuItem(I18n.t("menu.tools.savecid"));
        miSaveCid.addActionListener(e -> guardarCid());
        menuTools.add(miSaveCid);
        menuTools.addSeparator();
        JMenuItem miHtmlReport = new JMenuItem(I18n.t("menu.tools.htmlreport"));
        miHtmlReport.addActionListener(e -> generateModelReport());
        menuTools.add(miHtmlReport);
        menuBar.add(menuTools);

        // Menu Ayuda
        JMenu menuHelp = new JMenu(I18n.t("menu.help"));
        JMenuItem miAbout = new JMenuItem(I18n.t("menu.help.about"));
        miAbout.addActionListener(e -> showAboutDialog());
        menuHelp.add(miAbout);
        JMenuItem miLegend = new JMenuItem(I18n.t("menu.help.legend"));
        miLegend.addActionListener(e -> showLegendDialog());
        menuHelp.add(miLegend);
        menuBar.add(menuHelp);

        // Menú de idioma (Idioma / Language / 语言). Los nombres van en su propio idioma.
        JMenu menuLang = new JMenu(I18n.t("menu.lang"));
        ButtonGroup langGroup = new ButtonGroup();
        String[][] langs = { {"es", "Español"}, {"zh", "中文 (简体)"}, {"en", "English"}, {"pt", "Português"} };
        String currentTag = I18n.currentTag();
        for (String[] lg : langs) {
            final String tag = lg[0];
            JRadioButtonMenuItem mi = new JRadioButtonMenuItem(lg[1]);
            if (currentTag.startsWith(tag)) mi.setSelected(true);
            mi.addActionListener(e -> {
                I18n.setLocaleAndSave(tag);
                JOptionPane.showMessageDialog(this, I18n.t("lang.restart"),
                        I18n.t("menu.lang"), JOptionPane.INFORMATION_MESSAGE);
            });
            langGroup.add(mi);
            menuLang.add(mi);
        }
        menuBar.add(menuLang);

        return menuBar;
    }

    private void generateModelReport() {
        ServerModel model = null;
        String sourceDesc = null;
        if (currentMode == AppMode.SERVER && server != null) {
            model = server.getServerModel();
            sourceDesc = loadedIedName != null ? loadedIedName + " (servidor simulado)" : "Servidor simulado";
        } else if (currentMode == AppMode.CLIENT && client != null && isConnected) {
            model = client.getServerModel();
            sourceDesc = client.getHost();
        }
        if (model == null) {
            JOptionPane.showMessageDialog(this,
                I18n.t("report.nomodel"),
                I18n.t("report.title"), JOptionPane.WARNING_MESSAGE);
            return;
        }

        int opt = JOptionPane.showConfirmDialog(this,
            I18n.t("report.includevalues"),
            I18n.t("report.title"), JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (opt == JOptionPane.CANCEL_OPTION || opt == JOptionPane.CLOSED_OPTION) return;
        boolean includeValues = (opt == JOptionPane.YES_OPTION);

        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new java.io.File("reporte_ied.html"));
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("HTML (*.html)", "html"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        java.io.File out = fc.getSelectedFile();
        if (!out.getName().toLowerCase().endsWith(".html")) {
            out = new java.io.File(out.getParentFile(), out.getName() + ".html");
        }

        final ServerModel fModel = model;
        final String fSource = sourceDesc;
        final java.io.File fOut = out;
        backgroundExecutor.submit(() -> {
            try {
                ModelReportGenerator.generate(fOut, fModel, fSource, loadedIedNameplate, includeValues);
                log(I18n.t("log.app.reportgen", fOut.getAbsolutePath()));
                SwingUtilities.invokeLater(() -> {
                    try {
                        java.awt.Desktop.getDesktop().browse(fOut.toURI());
                    } catch (Exception ex) {
                        JOptionPane.showMessageDialog(this,
                            I18n.t("report.saved") + "\n" + fOut.getAbsolutePath(), I18n.t("report.title"),
                            JOptionPane.INFORMATION_MESSAGE);
                    }
                });
            } catch (Exception ex) {
                log(I18n.t("log.app.reporterror", ex.getMessage()));
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                    I18n.t("report.error") + "\n" + ex.getMessage(), I18n.t("report.title"),
                    JOptionPane.ERROR_MESSAGE));
            }
        });
    }

    private void showAboutDialog() {
        String nativeStatus = nativeLibAvailable ?
            "<span style='color: green;'>✓ " + I18n.t("about.native.ok") + "</span>" :
            "<span style='color: red;'>✗ " + I18n.t("about.native.missing") + "</span>";

        String message =
            "<html><body style='width: 380px; padding: 10px;'>" +
            "<h2 style='color: #2E86AB; margin-bottom: 5px;'>IED Navigator</h2>" +
            "<p style='color: #666; font-size: 11px;'>Version 2.0 - Hybrid Edition</p>" +
            "<hr style='margin: 10px 0;'>" +
            "<p><b>IEC 61850 Explorer Tool</b></p>" +
            "<p>" + I18n.t("about.desc") + "</p>" +
            "<br>" +
            "<p><b>" + I18n.t("about.features") + "</b></p>" +
            "<ul>" +
            "<li>Cliente/Servidor MMS (iec61850bean)</li>" +
            "<li>" + I18n.t("about.feat.monitor") + "</li>" +
            "<li>Reports (URCB/BRCB)</li>" +
            "<li>GOOSE Subscriber/Publisher</li>" +
            "<li>" + I18n.t("about.feat.scl") + "</li>" +
            "</ul>" +
            "<p style='font-size: 10px;'>" + nativeStatus + "</p>" +
            "<hr style='margin: 10px 0;'>" +
            "<p><b>" + I18n.t("about.devby") + "</b></p>" +
            "<p style='color: #2E86AB; font-size: 13px;'><b>Emilio Medina</b></p>" +
            "<p style='font-size: 11px;'>" + I18n.t("about.role") + "</p>" +
            "<p style='font-size: 11px;'>\uD83C\uDDF5\uD83C\uDDFE Paraguay</p>" +
            "<br>" +
            "<p style='color: #B71C1C; font-size: 10px;'>" +
            "<b>" + I18n.t("about.edu.title") + "</b> " + I18n.t("about.edu.body") + "</p>" +
            "<p style='color: #888; font-size: 10px;'>" +
            I18n.t("about.libs") + " iec61850bean (MMS), libiec61850 (GOOSE/SV), pcap4j, JNA<br>" +
            "&copy; 2024 - " + I18n.t("about.rights") + "</p>" +
            "</body></html>";

        JOptionPane.showMessageDialog(this, message, I18n.t("about.title"),
            JOptionPane.INFORMATION_MESSAGE);
    }

    private void showLegendDialog() {
        JPanel main = buildLegendContent();
        JScrollPane scroll = new JScrollPane(main);
        scroll.setPreferredSize(new java.awt.Dimension(560, 580));
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setBorder(null);

        JDialog dlg = new JDialog(this, I18n.t("legend.title"), true);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dlg.add(scroll);
        dlg.pack();
        dlg.setLocationRelativeTo(this);
        dlg.setVisible(true);
    }

    private JPanel buildLegendContent() {
        JPanel main = new JPanel();
        main.setLayout(new BoxLayout(main, BoxLayout.Y_AXIS));
        main.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));

        main.add(legendTitle(I18n.t("legend.nodetypes.title")));
        main.add(legendRow(IconFactory.createNodeIcon("LD", new Color(100,100,200)),
                I18n.t("legend.nodetypes.ld")));
        main.add(legendRow(IconFactory.createNodeIcon("DO", new Color(150,150,200)),
                I18n.t("legend.nodetypes.do")));
        main.add(legendRow(IconFactory.createCircleIcon(new Color(100,180,100), 14),
                I18n.t("legend.nodetypes.da")));
        main.add(Box.createVerticalStrut(8));

        main.add(legendTitle(I18n.t("legend.lngroups.title")));
        main.add(legendRow(IconFactory.createNodeIcon("LN", new Color(200, 50, 50)),
                I18n.t("legend.lngroups.x")));
        main.add(legendRow(IconFactory.createNodeIcon("LN", new Color(200,100, 50)),
                I18n.t("legend.lngroups.xc")));
        main.add(legendRow(IconFactory.createNodeIcon("LN", new Color(150,100,200)),
                I18n.t("legend.lngroups.c")));
        main.add(legendRow(IconFactory.createMeterIcon(new Color(0,100,200)),
                I18n.t("legend.lngroups.m1")));
        main.add(legendRow(IconFactory.createMeterIcon(new Color(0,150,100)),
                I18n.t("legend.lngroups.m2")));
        main.add(legendRow(IconFactory.createShieldIcon(new Color(180,30,30)),
                I18n.t("legend.lngroups.p")));
        main.add(legendRow(IconFactory.createShieldIcon(new Color(130,30,170)),
                I18n.t("legend.lngroups.r")));
        main.add(legendRow(IconFactory.createGearIcon(new Color(0,150,170)),
                I18n.t("legend.lngroups.a")));
        main.add(legendRow(IconFactory.createDiamondIcon(new Color(70,70,70)),
                I18n.t("legend.lngroups.l")));
        main.add(legendRow(IconFactory.createDiamondIcon(new Color(90,90,90)),
                I18n.t("legend.lngroups.g")));
        main.add(legendRow(IconFactory.createMeterIcon(new Color(20,140,120)),
                I18n.t("legend.lngroups.s")));
        main.add(legendRow(IconFactory.createMeterIcon(new Color(140,80,0)),
                I18n.t("legend.lngroups.t")));
        main.add(legendRow(IconFactory.createNodeIcon("LN", new Color(50,90,200)),
                I18n.t("legend.lngroups.i")));
        main.add(legendRow(IconFactory.createNodeIcon("LN", new Color(80,80,150)),
                I18n.t("legend.lngroups.z")));
        main.add(legendRow(IconFactory.createNodeIcon("LN", new Color(100,150,100)),
                I18n.t("legend.lngroups.unclassified")));
        main.add(Box.createVerticalStrut(8));

        main.add(legendTitle(I18n.t("legend.breaker.title")));
        main.add(legendRow(IconFactory.createBreakerIcon("on"),
                I18n.t("legend.breaker.on")));
        main.add(legendRow(IconFactory.createBreakerIcon("off"),
                I18n.t("legend.breaker.off")));
        main.add(legendRow(IconFactory.createBreakerIcon("intermediate"),
                I18n.t("legend.breaker.intermediate")));
        main.add(Box.createVerticalStrut(8));

        main.add(legendTitle(I18n.t("legend.colors.title")));
        main.add(legendColorRow(new Color(0,150,0),
                I18n.t("legend.colors.green")));
        main.add(legendColorRow(new Color(200,0,0),
                I18n.t("legend.colors.red")));
        main.add(legendColorRow(new Color(255,140,0),
                I18n.t("legend.colors.orange")));
        main.add(legendColorRow(new Color(120,80,180),
                I18n.t("legend.colors.purple")));
        main.add(legendColorRow(new Color(0,100,200),
                I18n.t("legend.colors.blue")));
        main.add(Box.createVerticalStrut(8));

        main.add(legendTitle(I18n.t("legend.fc.title")));
        main.add(legendFcRow("ST", new Color(21,101,192),
                I18n.t("legend.fc.st")));
        main.add(legendFcRow("MX", new Color(0,105,92),
                I18n.t("legend.fc.mx")));
        main.add(legendFcRow("CO", new Color(183,28,28),
                I18n.t("legend.fc.co")));
        main.add(legendFcRow("CF", new Color(74,20,140),
                I18n.t("legend.fc.cf")));
        main.add(legendFcRow("DC", new Color(55,71,79),
                I18n.t("legend.fc.dc")));
        main.add(legendFcRow("SP", new Color(230,81,0),
                I18n.t("legend.fc.sp")));
        main.add(legendFcRow("SG", new Color(245,127,23),
                I18n.t("legend.fc.sg")));
        main.add(legendFcRow("SE", new Color(130,119,23),
                I18n.t("legend.fc.se")));
        main.add(legendFcRow("BL", new Color(78,52,46),
                I18n.t("legend.fc.bl")));
        main.add(legendFcRow("EX", new Color(84,110,122),
                I18n.t("legend.fc.ex")));
        main.add(legendFcRow("OR", new Color(27,94,32),
                I18n.t("legend.fc.or")));
        main.add(legendFcRow("RP", new Color(0,96,100),
                I18n.t("legend.fc.rp")));
        main.add(legendFcRow("BR", new Color(1,87,155),
                I18n.t("legend.fc.br")));
        main.add(legendFcRow("GO", new Color(136,14,79),
                I18n.t("legend.fc.go")));
        main.add(Box.createVerticalStrut(8));

        main.add(legendTitle(I18n.t("legend.cdc.title")));

        main.add(legendCdcHeader(I18n.t("legend.cdc.hdr.binary")));
        main.add(legendCdcRow("SPS", I18n.t("legend.cdc.sps")));
        main.add(legendCdcRow("DPS", I18n.t("legend.cdc.dps")));
        main.add(legendCdcRow("ACT", I18n.t("legend.cdc.act")));
        main.add(legendCdcRow("ACD", I18n.t("legend.cdc.acd")));

        main.add(legendCdcHeader(I18n.t("legend.cdc.hdr.intenum")));
        main.add(legendCdcRow("INS", I18n.t("legend.cdc.ins")));
        main.add(legendCdcRow("ENS", I18n.t("legend.cdc.ens")));
        main.add(legendCdcRow("BCR", I18n.t("legend.cdc.bcr")));

        main.add(legendCdcHeader(I18n.t("legend.cdc.hdr.analog")));
        main.add(legendCdcRow("MV", I18n.t("legend.cdc.mv")));
        main.add(legendCdcRow("CMV", I18n.t("legend.cdc.cmv")));
        main.add(legendCdcRow("WYE", I18n.t("legend.cdc.wye")));
        main.add(legendCdcRow("DEL", I18n.t("legend.cdc.del")));
        main.add(legendCdcRow("SEQ", I18n.t("legend.cdc.seq")));
        main.add(legendCdcRow("HMV", I18n.t("legend.cdc.hmv")));

        main.add(legendCdcHeader(I18n.t("legend.cdc.hdr.control")));
        main.add(legendCdcRow("SPC", I18n.t("legend.cdc.spc")));
        main.add(legendCdcRow("DPC", I18n.t("legend.cdc.dpc")));
        main.add(legendCdcRow("APC", I18n.t("legend.cdc.apc")));
        main.add(legendCdcRow("BSC", I18n.t("legend.cdc.bsc")));

        main.add(legendCdcHeader(I18n.t("legend.cdc.hdr.settings")));
        main.add(legendCdcRow("ING", I18n.t("legend.cdc.ing")));
        main.add(legendCdcRow("SPG", I18n.t("legend.cdc.spg")));
        main.add(legendCdcRow("ASG", I18n.t("legend.cdc.asg")));
        main.add(legendCdcRow("ENG", I18n.t("legend.cdc.eng")));

        main.add(legendCdcHeader(I18n.t("legend.cdc.hdr.nameplate")));
        main.add(legendCdcRow("DPL", I18n.t("legend.cdc.dpl")));
        main.add(legendCdcRow("LPL", I18n.t("legend.cdc.lpl")));
        main.add(Box.createVerticalStrut(8));

        return main;
    }

    /**
     * Abre la leyenda no-modal, posicionada al lado de un diálogo existente.
     * Llamado desde el diálogo de descripción IEC 61850.
     */
    private void showLegendDialogBeside(java.awt.Dialog infoDialog) {
        JPanel main = buildLegendContent();
        JScrollPane scroll = new JScrollPane(main);
        scroll.setPreferredSize(new java.awt.Dimension(480, 580));
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setBorder(null);

        JDialog dlg = new JDialog(this, "Leyenda de íconos y colores — IED Navigator", false);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dlg.add(scroll);
        dlg.pack();

        if (infoDialog != null && infoDialog.isShowing()) {
            java.awt.Rectangle screen = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration().getBounds();
            java.awt.Insets insets = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(
                getGraphicsConfiguration());
            int usableWidth = screen.width - insets.left - insets.right;
            int usableX = screen.x + insets.left;
            int usableY = screen.y + insets.top;
            int usableH = screen.height - insets.top - insets.bottom;
            int half = usableWidth / 2;

            // Descripción ocupa mitad izquierda, leyenda mitad derecha
            infoDialog.setBounds(usableX, usableY, half, usableH);
            dlg.setBounds(usableX + half, usableY, usableWidth - half, usableH);

            // Cerrar la leyenda automáticamente al cerrar la descripción
            infoDialog.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override public void windowClosed(java.awt.event.WindowEvent e) {
                    dlg.dispose();
                }
            });
        } else {
            dlg.setLocationRelativeTo(this);
        }
        dlg.setVisible(true);
        // Traer ambos al frente en orden: primero leyenda, luego descripción encima
        SwingUtilities.invokeLater(() -> {
            dlg.toFront();
            infoDialog.toFront();
        });
    }

    /** Título de sección para el diálogo de leyenda. */
    private static JLabel legendTitle(String text) {
        JLabel lbl = new JLabel("<html><b style='font-size:11px;color:#2E86AB;'>" + text + "</b></html>");
        lbl.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(180,210,240)),
            BorderFactory.createEmptyBorder(6, 0, 3, 0)));
        lbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        return lbl;
    }

    /** Fila de ícono + descripción para el diálogo de leyenda. */
    private static JPanel legendRow(Icon icon, String html) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel iconLbl = new JLabel(icon);
        iconLbl.setPreferredSize(new java.awt.Dimension(20, 18));
        row.add(iconLbl);
        row.add(new JLabel("<html>" + html + "</html>"));
        return row;
    }

    /** Fila de color de texto + descripción para el diálogo de leyenda. */
    private static JPanel legendColorRow(Color color, String description) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel sample = new JLabel("■ Abc");
        sample.setForeground(color);
        sample.setFont(sample.getFont().deriveFont(Font.BOLD, 12f));
        sample.setPreferredSize(new java.awt.Dimension(52, 18));
        row.add(sample);
        row.add(new JLabel(description));
        return row;
    }

    /** Fila de Functional Constraint con badge de color para el diálogo de leyenda. */
    private static JPanel legendFcRow(String fc, Color badge, String html) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel lbl = new JLabel(fc);
        lbl.setOpaque(true);
        lbl.setBackground(badge);
        lbl.setForeground(Color.WHITE);
        lbl.setFont(lbl.getFont().deriveFont(Font.BOLD, 10f));
        lbl.setBorder(BorderFactory.createEmptyBorder(1, 5, 1, 5));
        lbl.setPreferredSize(new java.awt.Dimension(30, 17));
        lbl.setHorizontalAlignment(SwingConstants.CENTER);
        row.add(lbl);
        row.add(new JLabel("<html>" + html + "</html>"));
        return row;
    }

    /** Encabezado de subcategoría CDC (texto en cursiva). */
    private static JLabel legendCdcHeader(String text) {
        JLabel lbl = new JLabel("<html><i style='color:#555555; font-size:10px;'>" + text + "</i></html>");
        lbl.setBorder(BorderFactory.createEmptyBorder(5, 14, 1, 0));
        lbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        return lbl;
    }

    /** Fila de CDC con nombre en monoespaciado y descripción. */
    private static JPanel legendCdcRow(String cdc, String html) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel name = new JLabel(String.format("<html><tt><b>%-4s</b></tt></html>", cdc));
        name.setPreferredSize(new java.awt.Dimension(46, 16));
        row.add(Box.createHorizontalStrut(26));
        row.add(name);
        row.add(new JLabel("<html>" + html + "</html>"));
        return row;
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
            log(I18n.t("log.app.loadingsclgocb", file.getName()));

            // Delegate to goosePanel (handles IED detection, parsing, and table refresh)
            if (goosePanel != null) {
                goosePanel.loadSclFile(file);
                log(I18n.t("log.app.gocbsloaded", sclGoCBs.size()));
            }
        }
    }

    private JToolBar createToolbar() {
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0xE0E4E8)),
            BorderFactory.createEmptyBorder(6, 12, 6, 12)
        ));
        toolbar.setBackground(Color.WHITE);

        // Logo / Titulo (icono + texto bicolor, estilo marca comercial)
        java.net.URL iconUrl = getClass().getResource("/app_icon_32.png");
        if (iconUrl != null) {
            javax.swing.ImageIcon appIcon = new javax.swing.ImageIcon(iconUrl);
            JLabel lblIcon = new JLabel(appIcon);
            lblIcon.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 4));
            toolbar.add(lblIcon);
        }
        JLabel lblTitle = new JLabel("<html><span style='color:#37474F;'>IED</span>"
            + "<span style='color:#1976D2;'>Navigator</span>"
            + "&nbsp;<span style='color:#90A4AE;font-size:9px;'>PRO</span></html>");
        lblTitle.setFont(new Font("Segoe UI", Font.BOLD, 16));
        toolbar.add(lblTitle);
        toolbar.addSeparator(new Dimension(20, 0));

        // Botones de toolbar
        JButton btnNewConnection = new JButton(I18n.t("toolbar.newconn"));
        btnNewConnection.setToolTipText(I18n.t("toolbar.newconn.tip"));
        btnNewConnection.addActionListener(e -> {
            rbClient.setSelected(true);
            switchToClientMode();
        });
        toolbar.add(btnNewConnection);

        JButton btnSimulate = new JButton(I18n.t("toolbar.simulate"));
        btnSimulate.setToolTipText(I18n.t("toolbar.simulate.tip"));
        btnSimulate.addActionListener(e -> {
            rbServer.setSelected(true);
            switchToServerMode();
        });
        toolbar.add(btnSimulate);

        toolbar.addSeparator();

        JButton btnClearLog = new JButton(I18n.t("toolbar.clearlog"));
        btnClearLog.addActionListener(e -> logArea.setText(""));
        toolbar.add(btnClearLog);

        toolbar.add(Box.createHorizontalGlue());

        return toolbar;
    }

    private JPanel createStatusBar() {
        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0xE0E4E8)),
            BorderFactory.createEmptyBorder(4, 12, 4, 12)
        ));
        statusBar.setBackground(new Color(0xF7F9FB));

        JLabel lblReady = new JLabel(I18n.t("status.ready"));
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

    /** Thin wrapper so that IEDNavigatorApp code outside GoosePanel can still call logGoose(). */
    private void logGoose(String msg) {
        if (goosePanel != null) goosePanel.logGoose(msg);
    }

    /** Build the GoosePanel.Context implementation, wiring all dependencies. */
    private GoosePanel.Context createGooseContext() {
        IEDNavigatorApp self = this;
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
        panel.setBorder(BorderFactory.createTitledBorder(I18n.t("border.server")));

        // Fila 1: Seleccionar archivo SCL
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        btnSelectFile = new JButton(I18n.t("btn.loadscl"));
        btnSelectFile.setToolTipText(I18n.t("btn.loadscl.tip"));
        btnSelectFile.setMargin(new Insets(2, 6, 2, 6));
        lblFileName = new JLabel(I18n.t("lbl.nofile"));
        lblFileName.setForeground(Color.GRAY);
        row1.add(btnSelectFile);
        row1.add(lblFileName);
        panel.add(row1);

        // Fila 2: Puerto
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        row2.add(new JLabel(I18n.t("lbl.port")));
        tfServerPort = new JTextField("102", 6);
        row2.add(tfServerPort);

        // Info
        JLabel lblInfo = new JLabel(I18n.t("lbl.porthint"));
        lblInfo.setForeground(Color.GRAY);
        lblInfo.setFont(lblInfo.getFont().deriveFont(Font.ITALIC, 10f));
        row2.add(lblInfo);
        panel.add(row2);

        // Fila 3: Boton Start/Stop
        JPanel row3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        btnStartStop = new JButton(I18n.t("btn.startsim"));
        btnStartStop.setPreferredSize(new Dimension(200, 30));
        btnStartStop.setEnabled(false);
        btnStartStop.setToolTipText(I18n.t("btn.startsim.tip"));
        row3.add(btnStartStop);
        panel.add(row3);

        // Fila 4: Verificar Puerto + Liberar Puerto
        JPanel row4 = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        btnCheckPort = new JButton(I18n.t("port.check"));
        btnCheckPort.setToolTipText(I18n.t("port.check.tip"));
        btnCheckPort.setForeground(new Color(0, 80, 160));
        btnCheckPort.setMargin(new Insets(2, 6, 2, 6));
        row4.add(btnCheckPort);

        btnReleasePort = new JButton(I18n.t("port.release"));
        btnReleasePort.setToolTipText(I18n.t("port.release.tip"));
        btnReleasePort.setForeground(new Color(160, 30, 0));
        btnReleasePort.setMargin(new Insets(2, 6, 2, 6));
        row4.add(btnReleasePort);
        panel.add(row4);

        // Fila 5: Info de uso
        JPanel row5 = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 1));
        JLabel lblUsage = new JLabel(I18n.t("server.usage"));
        lblUsage.setForeground(new Color(100, 100, 150));
        row5.add(lblUsage);
        panel.add(row5);

        return panel;
    }

    private JPanel createClientPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createTitledBorder(I18n.t("border.client")));

        // Fila 1: Host + Puerto
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        row1.add(new JLabel(I18n.t("lbl.host")));
        tfHost = new JTextField("192.168.1.100", 12);
        row1.add(tfHost);
        row1.add(new JLabel(I18n.t("lbl.port")));
        tfClientPort = new JTextField("102", 4);
        row1.add(tfClientPort);
        panel.add(row1);

        // Fila 2: Timeout de conexión
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        row2.add(new JLabel(I18n.t("lbl.timeout")));
        spinnerTimeout = new JSpinner(new SpinnerNumberModel(10, 5, 60, 5));
        ((JSpinner.DefaultEditor) spinnerTimeout.getEditor()).getTextField().setColumns(3);
        row2.add(spinnerTimeout);
        panel.add(row2);

        // Fila 3: Boton Connect
        JPanel row3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        btnConnect = new JButton(I18n.t("btn.connect"));
        btnConnect.setPreferredSize(new Dimension(200, 30));
        row3.add(btnConnect);
        panel.add(row3);

        // Fila 4: Polling
        JPanel row4 = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        cbPolling = new JCheckBox(I18n.t("cb.polling"));
        cbPolling.setEnabled(false);
        row4.add(cbPolling);
        row4.add(new JLabel(I18n.t("lbl.interval")));
        spinnerInterval = new JSpinner(new SpinnerNumberModel(2000, 500, 60000, 500));
        spinnerInterval.setEnabled(false);
        row4.add(spinnerInterval);
        panel.add(row4);

        // Fila 5: Watchlist + Obtener/Guardar CID
        JPanel row5 = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        lblWatchlistCount = new JLabel(I18n.t("watchlist.count", 0));
        lblWatchlistCount.setForeground(new Color(0, 100, 180));
        row5.add(lblWatchlistCount);
        JButton btnClearWatchlist = new JButton(I18n.t("btn.clear"));
        btnClearWatchlist.setMargin(new Insets(2, 5, 2, 5));
        btnClearWatchlist.addActionListener(e -> clearWatchlist());
        row5.add(btnClearWatchlist);
        JButton btnGetCid = new JButton(I18n.t("btn.getcid"));
        btnGetCid.setMargin(new Insets(2, 5, 2, 5));
        btnGetCid.setToolTipText(I18n.t("btn.getcid.tip"));
        btnGetCid.addActionListener(e -> obtenerCidDelIed());
        row5.add(btnGetCid);
        JButton btnSaveCid = new JButton(I18n.t("btn.savecid"));
        btnSaveCid.setMargin(new Insets(2, 5, 2, 5));
        btnSaveCid.setToolTipText(I18n.t("btn.savecid.tip"));
        btnSaveCid.addActionListener(e -> guardarCid());
        row5.add(btnSaveCid);
        panel.add(row5);

        return panel;
    }

    private void clearWatchlist() { monitorManager.clearWatchlist(); } // F24

    private void updateWatchlistLabel() {
        lblWatchlistCount.setText(I18n.t("watchlist.count", watchlist.size()));
    }

    // ─── SECTION: MONITOR PANEL ──────────────────────────────────────────────────────
    private JPanel createMonitorPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createTitledBorder(I18n.t("border.monitor")));
        panel.setMinimumSize(new Dimension(300, 200));

        // Tabla con mas columnas como IEDScout
        String[] columns = {I18n.t("col.name"), "FC", I18n.t("col.type"), I18n.t("col.value"), I18n.t("col.status")};
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
        JLabel lblDropHint = new JLabel(I18n.t("monitor.drophint"));
        lblDropHint.setForeground(new Color(100, 150, 200));
        lblDropHint.setHorizontalAlignment(SwingConstants.CENTER);
        dropHintPanel.add(lblDropHint, BorderLayout.CENTER);
        dropHintPanel.setPreferredSize(new Dimension(250, 60));

        // Header fila 1: conteo + botones
        JPanel headerRow1 = new JPanel(new BorderLayout());
        JLabel lblCount = new JLabel(" " + I18n.t("monitor.items", 0));
        lblCount.setFont(lblCount.getFont().deriveFont(Font.BOLD));
        headerRow1.add(lblCount, BorderLayout.WEST);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 2));
        JButton btnRemove = new JButton(I18n.t("btn.remove"));
        btnRemove.setMargin(new Insets(2, 8, 2, 8));
        btnRemove.addActionListener(e -> removeSelectedFromMonitor());
        btnPanel.add(btnRemove);
        JButton btnClear = new JButton(I18n.t("btn.clear"));
        btnClear.setMargin(new Insets(2, 8, 2, 8));
        btnClear.addActionListener(e -> clearMonitor());
        btnPanel.add(btnClear);
        headerRow1.add(btnPanel, BorderLayout.EAST);

        // Header fila 2: filtros
        JPanel headerRow2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        headerRow2.add(new JLabel("FC:"));
        monitorFcFilter = new JComboBox<>(new String[]{
            I18n.t("filter.all"), "ST", "MX", "CF", "DC", "SP", "SV", "BL", "CO", "SE", "SG", "EX", "RP", "BR", "OR"
        });
        monitorFcFilter.setPreferredSize(new Dimension(70, 22));
        monitorFcFilter.addActionListener(e -> applyMonitorFilter());
        headerRow2.add(monitorFcFilter);
        headerRow2.add(new JLabel("  " + I18n.t("col.name") + ":"));
        monitorNameFilter = new javax.swing.JTextField(14);
        monitorNameFilter.setToolTipText(I18n.t("monitor.namefilter.tip"));
        monitorNameFilter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { applyMonitorFilter(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { applyMonitorFilter(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { applyMonitorFilter(); }
        });
        headerRow2.add(monitorNameFilter);
        JButton btnClearFilter = new JButton("✕");
        btnClearFilter.setMargin(new Insets(1, 4, 1, 4));
        btnClearFilter.setToolTipText(I18n.t("monitor.clearfilter.tip"));
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
            log(I18n.t("log.app.jnapath", (libPath != null ? libPath : I18n.t("log.app.jnapath.notset"))));
            java.io.File dllFile = new java.io.File("lib/iec61850.dll");
            if (dllFile.exists()) {
                log(I18n.t("log.app.dllfound", dllFile.getAbsolutePath(), dllFile.length()));
            } else {
                log(I18n.t("log.app.dllnotfound"));
            }
            nativeLibAvailable = NativeSVSubscriber.isNativeLibraryAvailable();
            if (nativeLibAvailable) {
                log(I18n.t("log.app.nativeloaded"));
            } else {
                log(I18n.t("log.app.nativefailed"));
                log(I18n.t("log.app.svnote"));
            }
        } catch (UnsatisfiedLinkError e) {
            nativeLibAvailable = false;
            log(I18n.t("log.app.nativeloaderror", e.getMessage()));
        } catch (Exception e) {
            nativeLibAvailable = false;
            log(I18n.t("log.app.nativecheckerror", e.getMessage()));
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
            public void log(String msg)                                           { IEDNavigatorApp.this.log(msg); }
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
                return IEDNavigatorApp.this.formatEnumValue(node, rawValue);
            }
            public void updateWatchlistLabel()                                    { IEDNavigatorApp.this.updateWatchlistLabel(); }
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
        btnCheckPort.addActionListener(e -> checkPort());
        btnReleasePort.addActionListener(e -> releasePort());

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
                log(I18n.t("log.app.readerror", reference, error));
            }

            @Override
            public void onConnectionClosed(String reason) {
                SwingUtilities.invokeLater(() -> {
                    handleDisconnect();
                    log(I18n.t("log.app.connclosed", reason));
                });
            }
        });
    }

    private JPopupMenu serverPopupMenu;  // Menu para modo servidor

    // ─── SECTION: TREE POPUP & VALUE EDITING ──────────────────────────────────────────
    private void createTreePopupMenu() {
        // === Menu para modo CLIENTE ===
        treePopupMenu = new JPopupMenu();

        JMenuItem miAddToWatchlist = new JMenuItem(I18n.t("ctx.addwatchlist"));
        miAddToWatchlist.addActionListener(e -> addSelectedToWatchlist());
        treePopupMenu.add(miAddToWatchlist);

        JMenuItem miRemoveFromWatchlist = new JMenuItem(I18n.t("ctx.removewatchlist"));
        miRemoveFromWatchlist.addActionListener(e -> removeSelectedFromWatchlist());
        treePopupMenu.add(miRemoveFromWatchlist);

        treePopupMenu.addSeparator();

        JMenuItem miReadValue = new JMenuItem(I18n.t("ctx.readvalue"));
        miReadValue.addActionListener(e -> readSelectedNode());
        treePopupMenu.add(miReadValue);

        JMenuItem miAddToMonitor = new JMenuItem(I18n.t("ctx.addmonitor"));
        miAddToMonitor.addActionListener(e -> {
            TreePath path = modelTree.getSelectionPath();
            if (path != null) {
                addNodeToMonitor((DefaultMutableTreeNode) path.getLastPathComponent());
            }
        });
        treePopupMenu.add(miAddToMonitor);

        treePopupMenu.addSeparator();

        // FC=CO: Operar nodo de control (SBO o direct según ctlModel del IED)
        JMenuItem miOperate = new JMenuItem(I18n.t("ctx.operate"));
        miOperate.setFont(miOperate.getFont().deriveFont(Font.BOLD));
        miOperate.setForeground(new Color(183, 28, 28));
        miOperate.addActionListener(e -> {
            FcModelNode operNode = getOperNodeForSelection();
            if (operNode != null) showControlDialog(operNode);
        });
        treePopupMenu.add(miOperate);

        // FC=CO: Cancelar SELECT pendiente (solo para ctlModel SBO = 2 o 4)
        JMenuItem miCancelSelect = new JMenuItem(I18n.t("ctx.cancelselect"));
        miCancelSelect.setForeground(new Color(120, 50, 150));
        miCancelSelect.addActionListener(e -> {
            FcModelNode operNode = getOperNodeForSelection();
            if (operNode != null) showCancelDialog(operNode);
        });
        treePopupMenu.add(miCancelSelect);

        treePopupMenu.addSeparator();

        // FC=BL: Bloquear / Desbloquear valor del DO
        JMenuItem miBlock   = new JMenuItem(I18n.t("ctx.block"));
        JMenuItem miUnblock = new JMenuItem(I18n.t("ctx.unblock"));
        miBlock.addActionListener(e   -> toggleBlocking(true));
        miUnblock.addActionListener(e -> toggleBlocking(false));
        treePopupMenu.add(miBlock);
        treePopupMenu.add(miUnblock);

        // Mostrar/ocultar ítems según el nodo seleccionado
        treePopupMenu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                FcModelNode operNode = getOperNodeForSelection();
                miOperate.setVisible(operNode != null);
                boolean isSbo = operNode != null && client != null
                    && (client.getCtlModelValue(operNode) == 2 || client.getCtlModelValue(operNode) == 4);
                miCancelSelect.setVisible(isSbo);
                boolean hasBlk = getSelectedBlkEnaNode() != null;
                miBlock.setVisible(hasBlk);
                miUnblock.setVisible(hasBlk);
            }
            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {}
            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {}
        });

        treePopupMenu.addSeparator();
        JMenuItem miInfo = new JMenuItem("❓ " + I18n.t("ctx.whatis"));
        miInfo.setFont(miInfo.getFont().deriveFont(Font.BOLD));
        miInfo.addActionListener(e -> showDictionaryForSelectedNode());
        treePopupMenu.add(miInfo);
        JMenuItem miLegendTree = new JMenuItem("📖 " + I18n.t("menu.help.legend"));
        miLegendTree.addActionListener(e -> showLegendDialog());
        treePopupMenu.add(miLegendTree);

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
            JMenuItem miTrue = new JMenuItem(I18n.t("srv.set", "TRUE"));
            miTrue.addActionListener(e -> setSelectedNodeValue("true"));
            serverPopupMenu.add(miTrue);

            JMenuItem miFalse = new JMenuItem(I18n.t("srv.set", "FALSE"));
            miFalse.addActionListener(e -> setSelectedNodeValue("false"));
            serverPopupMenu.add(miFalse);
        } else if (isDbpos) {
            JMenuItem miOn = new JMenuItem(I18n.t("srv.set.on"));
            miOn.addActionListener(e -> setSelectedNodeValue("on"));
            serverPopupMenu.add(miOn);

            JMenuItem miOff = new JMenuItem(I18n.t("srv.set.off"));
            miOff.addActionListener(e -> setSelectedNodeValue("off"));
            serverPopupMenu.add(miOff);

            JMenuItem miInter = new JMenuItem(I18n.t("srv.set", "INTERMEDIATE"));
            miInter.addActionListener(e -> setSelectedNodeValue("intermediate"));
            serverPopupMenu.add(miInter);

            JMenuItem miBad = new JMenuItem(I18n.t("srv.set", "BAD_STATE"));
            miBad.addActionListener(e -> setSelectedNodeValue("bad"));
            serverPopupMenu.add(miBad);
        } else if (isTapCmd) {
            for (String cmd : new String[]{"STOP", "LOWER", "HIGHER", "RESERVED"}) {
                JMenuItem mi = new JMenuItem(I18n.t("srv.set", cmd));
                mi.addActionListener(e -> setSelectedNodeValue(cmd.toLowerCase()));
                serverPopupMenu.add(mi);
            }
        } else if (isEnum) {
            // Enum: show dropdown with enum values from SCL DataTypeTemplates
            JMenuItem miEnum = new JMenuItem(I18n.t("srv.set.enum"));
            miEnum.addActionListener(e -> setSelectedNodeCustomValue());
            serverPopupMenu.add(miEnum);
        } else {
            // Generic: show custom value dialog
            JMenuItem miSet = new JMenuItem(I18n.t("srv.set.value"));
            miSet.addActionListener(e -> setSelectedNodeCustomValue());
            serverPopupMenu.add(miSet);
        }

        serverPopupMenu.addSeparator();

        JMenuItem miSetCustom = new JMenuItem(I18n.t("ctx.customvalue"));
        miSetCustom.addActionListener(e -> setSelectedNodeCustomValue());
        serverPopupMenu.add(miSetCustom);

        serverPopupMenu.addSeparator();

        JMenuItem miPublishGoose = new JMenuItem(I18n.t("srv.publishgoose"));
        miPublishGoose.addActionListener(e -> publishGooseFromSelection());
        serverPopupMenu.add(miPublishGoose);

        serverPopupMenu.addSeparator();
        JMenuItem miInfoSrv = new JMenuItem("❓ " + I18n.t("ctx.whatis"));
        miInfoSrv.setFont(miInfoSrv.getFont().deriveFont(Font.BOLD));
        miInfoSrv.addActionListener(e -> showDictionaryForSelectedNode());
        serverPopupMenu.add(miInfoSrv);
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
                JMenuItem miNavigate = new JMenuItem(I18n.t("ctx.viewinmodel"));
                miNavigate.addActionListener(ev -> navigateToFcdaInModel(info.name));
                fcdaPopup.add(miNavigate);

                // If server model is loaded, also add type-aware state change options
                if (currentMode == AppMode.SERVER && server != null && server.getServerModel() != null) {
                    fcdaPopup.addSeparator();
                    GoosePublisher.DataValue.Type fcdaType = inferDataType(info.name);
                    String[][] opts;
                    if (fcdaType == GoosePublisher.DataValue.Type.BOOLEAN) {
                        opts = new String[][]{{I18n.t("srv.set", "TRUE"), "true"}, {I18n.t("srv.set", "FALSE"), "false"}};
                    } else if (fcdaType == GoosePublisher.DataValue.Type.DBPOS) {
                        opts = new String[][]{{I18n.t("srv.set", "ON"), "on"}, {I18n.t("srv.set", "OFF"), "off"},
                            {I18n.t("srv.set", "INTERMEDIATE"), "intermediate"}, {I18n.t("srv.set", "BAD"), "bad"}};
                    } else {
                        opts = new String[][]{{I18n.t("srv.set", "TRUE"), "true"}, {I18n.t("srv.set", "FALSE"), "false"}};
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

                fcdaPopup.addSeparator();
                JMenuItem miInfoFcda = new JMenuItem("❓ ¿Qué es esto? (IEC 61850)");
                miInfoFcda.setFont(miInfoFcda.getFont().deriveFont(Font.BOLD));
                miInfoFcda.addActionListener(ev -> showDictionaryForSelectedNode());
                fcdaPopup.add(miInfoFcda);

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

    /** Muestra el diálogo educativo IEC 61850 para el nodo actualmente seleccionado en el árbol. */
    private void showDictionaryForSelectedNode() {
        TreePath path = modelTree.getSelectionPath();
        if (path == null) return;
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        Object userObj = node.getUserObject();
        String name = (userObj instanceof NodeInfo) ? ((NodeInfo) userObj).name : node.toString();
        // Para nodos FCDA el name tiene formato "[1] LD/LN.DO [FC]" — extraer token DO/DA
        if (name != null && name.contains("]")) {
            String after = name.substring(name.lastIndexOf(']') + 1).trim();
            // Tomar el último segmento después del punto o la barra
            String[] parts = after.replaceAll("\\[.*?\\]", "").trim().split("[./]");
            if (parts.length > 0) name = parts[parts.length - 1].trim();
        }
        Iec61850Dictionary.showInfoDialog(this, name != null ? name : "",
            this::showLegendDialogBeside);
    }

    private DefaultMutableTreeNode findNodeInModel(String fcdaName) {
        return ModelTreeBuilder.findNodeByFcda(modelTree, fcdaName);
    }

    // F25: common "set + propagate" helper (extracted to eliminate duplication)
    private void applyServerValue(String ref, String value) {
        boolean success = server.setDataValue(ref, value);
        if (success) {
            log(I18n.t("log.app.set", formatReference(ref), value));
            updateSingleNodeInTree(ref);
            updateServerMonitorValues();
            GoosePublisher gp = goosePanel != null ? goosePanel.getGoosePublisher() : null;
            if (gp != null && gp.isPublishing() && updateGoosePublisherValues()) {
                gp.publishStateChange();
                logGoose(I18n.t("log.app.goosepub", formatReference(ref), value));
            }
            if (goosePanel != null && !goosePanel.getActivePublishers().isEmpty()) {
                propagateValueToPublishers(ref, value);
            }
        } else {
            log(I18n.t("log.app.setvalueerror", ref));
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
                        I18n.t("dlg.newvalue.int", info.name), String.valueOf(currentOrd));
                }
            } else {
                // Otros tipos: usar input de texto
                newValue = JOptionPane.showInputDialog(this,
                    I18n.t("dlg.newvalue", info.name),
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

    // ── Control de nodos FC=CO (SBO + Direct) ───────────────────────────────────────────

    /**
     * Busca el nodo Oper (FC=CO) a partir del nodo seleccionado en el árbol.
     * Soporta:
     *   - El propio nodo Oper (nombre="Oper", FC=CO)
     *   - Un FcDataObject con FC=CO que contiene un hijo "Oper"
     * Retorna null si el nodo seleccionado no tiene un punto de control operable.
     */
    private FcModelNode getOperNodeForSelection() {
        if (!isConnected || client == null) return null;
        TreePath path = modelTree.getSelectionPath();
        if (path == null) return null;
        DefaultMutableTreeNode treeNode = (DefaultMutableTreeNode) path.getLastPathComponent();
        if (!(treeNode.getUserObject() instanceof NodeInfo)) return null;
        NodeInfo info = (NodeInfo) treeNode.getUserObject();
        if (!(info.node instanceof FcModelNode)) return null;
        FcModelNode fcNode = (FcModelNode) info.node;

        // Caso 1: el nodo ES el Oper (FC=CO, nombre="Oper")
        if (fcNode.getFc() == Fc.CO && "Oper".equals(fcNode.getName())) {
            return fcNode;
        }

        // Caso 2: FcDataObject con FC=CO → buscar hijo llamado "Oper"
        if (fcNode.getFc() == Fc.CO && fcNode.getChildren() != null) {
            for (ModelNode child : fcNode.getChildren()) {
                if ("Oper".equals(child.getName()) && child instanceof FcModelNode) {
                    return (FcModelNode) child;
                }
            }
        }

        return null;
    }

    /**
     * Muestra el diálogo de control para un nodo Oper (FC=CO).
     *
     * El diálogo detecta el ctlModel del IED y muestra:
     *   - Referencia del nodo y modelo de control (direct / SBO)
     *   - Controles de valor adaptados al tipo de ctlVal (Boolean → ON/OFF, DoubleBit → 4 estados,
     *     Float → campo numérico, TapCommand → RAISE/LOWER/STOP, genérico → texto libre)
     *   - Checkbox "Modo Test" (el IED registra pero no actúa en hardware)
     *   - Campo identificador del operador (orIdent, opcional)
     *
     * La operación se ejecuta en el backgroundExecutor para no bloquear el EDT.
     */
    private void showControlDialog(FcModelNode operNode) {
        final String ref = operNode.getReference().toString();
        final int ctlModel = client.getCtlModelValue(operNode);
        final String ctlModelName = new String[]{
            "status-only", "direct-normal-security", "sbo-normal-security",
            "direct-enhanced-security", "sbo-enhanced-security"
        }[Math.min(ctlModel, 4)];
        final boolean isSbo = (ctlModel == 2 || ctlModel == 4);
        final String ctlValType = client.getOperCtlValType(operNode);

        // Comandos binarios (interruptor/seccionador) → botones Abrir/Cerrar.
        final boolean binary = "Boolean".equals(ctlValType)
            || "DoubleBitPos".equals(ctlValType) || "DoubleBit".equals(ctlValType);
        final String closeVal = "Boolean".equals(ctlValType) ? "true" : "on";
        final String openVal  = "Boolean".equals(ctlValType) ? "false" : "off";

        // ── Panel de contenido ───────────────────────────────────────────────────
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 6, 4, 6);
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;

        g.gridx = 0; g.gridy = 0; g.gridwidth = 2;
        panel.add(new JLabel("<html><b>" + ref + "</b></html>"), g);

        g.gridy = 1;
        JLabel lblModel = new JLabel(I18n.t("ctl.model") + " " + ctlModelName);
        lblModel.setForeground(isSbo ? new Color(183, 28, 28) : new Color(0, 100, 0));
        panel.add(lblModel, g);

        g.gridy = 2;
        JLabel lblSboNote = new JLabel(isSbo
            ? I18n.t("ctl.note.sbo")
            : I18n.t("ctl.note.direct"));
        lblSboNote.setFont(lblSboNote.getFont().deriveFont(Font.ITALIC, 11f));
        panel.add(lblSboNote, g);

        g.gridwidth = 1;

        // Selector de valor. inputs = componentes a bloquear mientras haya reserva.
        final java.util.List<JComponent> inputs = new java.util.ArrayList<>();
        final String[] selectedValue = {binary ? closeVal : null};

        g.gridy = 3; g.gridx = 0;
        panel.add(new JLabel(I18n.t("lbl.value")), g);
        g.gridx = 1;
        if (binary) {
            JToggleButton btnOpen  = new JToggleButton(I18n.t("ctl.open"));
            JToggleButton btnClose = new JToggleButton(I18n.t("ctl.close"));
            ButtonGroup bgVal = new ButtonGroup();
            bgVal.add(btnOpen); bgVal.add(btnClose);
            btnClose.setSelected(true);
            btnOpen.addActionListener(e -> selectedValue[0] = openVal);
            btnClose.addActionListener(e -> selectedValue[0] = closeVal);
            JPanel vp = new JPanel(new GridLayout(1, 2, 6, 0));
            vp.add(btnOpen); vp.add(btnClose);
            panel.add(vp, g);
            inputs.add(btnOpen); inputs.add(btnClose);
        } else if ("TapCommand".equals(ctlValType)) {
            JComboBox<String> combo = new JComboBox<>(new String[]{"stop", "lower", "higher"});
            panel.add(combo, g);
            selectedValue[0] = "stop";
            combo.addActionListener(e -> selectedValue[0] = (String) combo.getSelectedItem());
            inputs.add(combo);
        } else {
            JTextField tfVal = new JTextField("0", 12);
            panel.add(tfVal, g);
            selectedValue[0] = "0";
            tfVal.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent e)  { selectedValue[0] = tfVal.getText(); }
                public void removeUpdate(javax.swing.event.DocumentEvent e)  { selectedValue[0] = tfVal.getText(); }
                public void changedUpdate(javax.swing.event.DocumentEvent e) { selectedValue[0] = tfVal.getText(); }
            });
            inputs.add(tfVal);
        }

        g.gridy = 4; g.gridx = 0; g.gridwidth = 2;
        final JCheckBox cbTest = new JCheckBox(I18n.t("ctl.test"));
        cbTest.setForeground(new Color(150, 70, 0));
        panel.add(cbTest, g); inputs.add(cbTest);

        g.gridy = 5;
        JLabel lblTestWarn = new JLabel(I18n.t("ctl.test.warn"));
        lblTestWarn.setFont(lblTestWarn.getFont().deriveFont(Font.PLAIN, 11f));
        panel.add(lblTestWarn, g);

        g.gridy = 6;
        final JCheckBox cbSynchro = new JCheckBox(I18n.t("ctl.synchro"));
        cbSynchro.setToolTipText(I18n.t("ctl.synchro.tip"));
        panel.add(cbSynchro, g); inputs.add(cbSynchro);

        g.gridy = 7;
        final JCheckBox cbInterlock = new JCheckBox(I18n.t("ctl.interlock"));
        cbInterlock.setToolTipText(I18n.t("ctl.interlock.tip"));
        panel.add(cbInterlock, g); inputs.add(cbInterlock);

        g.gridy = 8; g.gridwidth = 1; g.gridx = 0;
        panel.add(new JLabel(I18n.t("lbl.orident")), g);
        g.gridx = 1;
        final JTextField tfOrIdent = new JTextField("IEDNavigator", 14);
        panel.add(tfOrIdent, g); inputs.add(tfOrIdent);

        // Indicador de estado del SBOw (se colorea y muestra la cuenta regresiva)
        g.gridy = 9; g.gridx = 0; g.gridwidth = 2;
        final JLabel sbowInd = new JLabel(isSbo
            ? I18n.t("ctl.sbo.idle") : "  " + I18n.t("ctl.direct.norsv"));
        sbowInd.setOpaque(true);
        sbowInd.setBackground(new Color(224, 224, 224));
        sbowInd.setForeground(Color.DARK_GRAY);
        sbowInd.setBorder(BorderFactory.createLineBorder(new Color(160, 160, 160)));
        sbowInd.setPreferredSize(new Dimension(440, 26));
        panel.add(sbowInd, g);

        g.gridy = 10;
        JLabel lblDisclaimer = new JLabel(I18n.t("ctl.disclaimer"));
        lblDisclaimer.setFont(lblDisclaimer.getFont().deriveFont(Font.PLAIN, 11f));
        panel.add(lblDisclaimer, g);

        // ── Diálogo + botonera ───────────────────────────────────────────────────
        final JDialog dlg = new JDialog(this,
            isSbo ? I18n.t("ctl.title.sbo") : I18n.t("ctl.title.direct"), true);
        final JButton btnSelect    = new JButton(I18n.t("ctl.select"));
        final JButton btnExec       = new JButton(I18n.t("ctl.execute"));
        final JButton btnCancelSel = new JButton(I18n.t("ctl.cancel"));
        final JButton btnClose      = new JButton(I18n.t("btn.close"));

        final boolean[] busy = {false};      // hay una operación MMS en vuelo
        final boolean[] reserved = {false};  // hay una selección SBOw vigente
        final javax.swing.Timer[] timer = {null};

        final Runnable stopTimer = () -> { if (timer[0] != null) { timer[0].stop(); timer[0] = null; } };

        final Runnable refreshButtons = () -> {
            boolean b = busy[0], r = reserved[0];
            if (isSbo) {
                btnSelect.setEnabled(!b && !r);
                btnExec.setEnabled(!b && r);
                btnCancelSel.setEnabled(!b && r);
            } else {
                btnSelect.setEnabled(false);
                btnExec.setEnabled(!b);
                btnCancelSel.setEnabled(false);
            }
            btnClose.setEnabled(!b);
            for (JComponent c : inputs) c.setEnabled(!b && !r);
        };

        final Runnable startCountdown = () -> {
            stopTimer.run();
            timer[0] = new javax.swing.Timer(200, null);
            timer[0].addActionListener(ev -> {
                IEC61850Client.PendingSelect ps = client.getPendingSelect();
                long rem = (ps != null) ? ps.remainingMs() : 0;
                if (ps == null || rem <= 0) {
                    stopTimer.run();
                    reserved[0] = false;
                    client.clearPendingSelect();
                    sbowInd.setBackground(new Color(189, 189, 189));
                    sbowInd.setForeground(Color.BLACK);
                    sbowInd.setText(I18n.t("ctl.sbo.expired"));
                    refreshButtons.run();
                    return;
                }
                boolean warn = rem <= 5000;
                sbowInd.setBackground(warn ? new Color(229, 57, 53) : new Color(255, 171, 64));
                sbowInd.setForeground(warn ? Color.WHITE : new Color(60, 30, 0));
                sbowInd.setText(String.format(
                    I18n.t("ctl.sbo.reserved"), rem / 1000.0));
            });
            timer[0].setInitialDelay(0);
            timer[0].start();
        };

        // Paso 1: SELECT (SBOw)
        btnSelect.addActionListener(e -> {
            final String ctlVal = selectedValue[0] != null ? selectedValue[0].trim() : "";
            final boolean testFlag = cbTest.isSelected();
            final boolean sync = cbSynchro.isSelected();
            final boolean ilk = cbInterlock.isSelected();
            final String orIdent = tfOrIdent.getText().trim();
            busy[0] = true; refreshButtons.run();
            sbowInd.setBackground(new Color(255, 224, 130));
            sbowInd.setForeground(new Color(60, 30, 0));
            sbowInd.setText(I18n.t("ctl.sbo.sending"));
            backgroundExecutor.submit(() -> {
                IEC61850Client.ControlResult cr;
                int sboTo = client.getSboTimeoutMs(operNode);
                try {
                    cr = client.selectControl(operNode, ctlVal, testFlag, orIdent, sync, ilk, sboTo);
                } catch (Exception ex) {
                    final String em = ex.getMessage();
                    SwingUtilities.invokeLater(() -> {
                        busy[0] = false; reserved[0] = false; refreshButtons.run();
                        sbowInd.setBackground(new Color(224, 224, 224));
                        sbowInd.setForeground(Color.DARK_GRAY);
                        sbowInd.setText(I18n.t("ctl.sbo.idle"));
                        log(I18n.t("log.app.selectexception", ref, em));
                        JOptionPane.showMessageDialog(dlg, I18n.t("err.comm") + "\n" + em,
                            I18n.t("err.select.title"), JOptionPane.ERROR_MESSAGE);
                    });
                    return;
                }
                final IEC61850Client.ControlResult fcr = cr;
                final int fto = sboTo;
                SwingUtilities.invokeLater(() -> {
                    busy[0] = false;
                    if (fcr.success) {
                        reserved[0] = true;
                        log(I18n.t("log.app.selectok", ref, ctlVal, (fto / 1000)));
                        startCountdown.run();
                    } else {
                        reserved[0] = false;
                        sbowInd.setBackground(new Color(189, 189, 189));
                        sbowInd.setForeground(Color.BLACK);
                        sbowInd.setText(I18n.t("ctl.sbo.rejected"));
                        log(I18n.t("log.app.selecterror", ref, fcr.error,
                            (fcr.lastApplError != null ? " | " + fcr.lastApplError : "")));
                        JOptionPane.showMessageDialog(dlg,
                            I18n.t("ctl.select.rejected.msg") + "\n\n  " + I18n.t("ctl.msg.node") + ": " + ref
                            + "\n  " + I18n.t("ctl.msg.error") + ": " + fcr.error
                            + (fcr.lastApplError != null ? "\n  LastApplError: " + fcr.lastApplError : ""),
                            I18n.t("ctl.select.rejected"), JOptionPane.ERROR_MESSAGE);
                    }
                    refreshButtons.run();
                });
            });
        });

        // Paso 2: EXECUTE (OPER)  — o control directo si no es SBO
        btnExec.addActionListener(e -> {
            final String ctlVal = selectedValue[0] != null ? selectedValue[0].trim() : "";
            if (ctlVal.isEmpty()) {
                JOptionPane.showMessageDialog(dlg, I18n.t("ctl.value.required"), I18n.t("ctl.value.required.title"),
                    JOptionPane.WARNING_MESSAGE);
                return;
            }
            final boolean testFlag = cbTest.isSelected();
            final boolean sync = cbSynchro.isSelected();
            final boolean ilk = cbInterlock.isSelected();
            final String orIdent = tfOrIdent.getText().trim();
            busy[0] = true; refreshButtons.run();
            backgroundExecutor.submit(() -> {
                IEC61850Client.ControlResult cr;
                try {
                    cr = isSbo ? client.executeControl(operNode)
                               : client.operateControl(operNode, ctlVal, testFlag, orIdent, sync, ilk);
                } catch (Exception ex) {
                    final String em = ex.getMessage();
                    SwingUtilities.invokeLater(() -> {
                        busy[0] = false; reserved[0] = false; stopTimer.run();
                        client.clearPendingSelect(); refreshButtons.run();
                        log(I18n.t("log.app.controlexception", ref, em));
                        JOptionPane.showMessageDialog(dlg, I18n.t("err.comm") + "\n" + em,
                            I18n.t("err.control.title"), JOptionPane.ERROR_MESSAGE);
                    });
                    return;
                }
                final IEC61850Client.ControlResult fcr = cr;
                if (fcr.success) {
                    SwingUtilities.invokeLater(() -> { stopTimer.run(); reserved[0] = false; dlg.dispose(); });
                    reportControlResult(operNode, ref, ctlVal, testFlag, sync, ilk, isSbo, fcr);
                } else {
                    SwingUtilities.invokeLater(() -> {
                        busy[0] = false; reserved[0] = false; stopTimer.run();
                        client.clearPendingSelect(); refreshButtons.run();
                        sbowInd.setBackground(new Color(189, 189, 189));
                        sbowInd.setForeground(Color.BLACK);
                        sbowInd.setText("  " + (isSbo ? I18n.t("ctl.oper.rejected.freed") : I18n.t("ctl.oper.rejected")));
                        StringBuilder msg = new StringBuilder(I18n.t("ctl.oper.rejected.msg") + "\n\n");
                        msg.append("  ").append(I18n.t("ctl.msg.node")).append(": ").append(ref).append("\n");
                        msg.append("  ").append(I18n.t("ctl.msg.model")).append(": ").append(fcr.ctlModelName).append("\n");
                        msg.append("  ").append(I18n.t("ctl.msg.error")).append(": ").append(fcr.error);
                        if (fcr.lastApplError != null) msg.append("\n  LastApplError: ").append(fcr.lastApplError);
                        if (testFlag) {
                            msg.append("\n\n  ").append(I18n.t("ctl.fb.testhint"));
                        }
                        log(I18n.t("log.app.controlerror", ref, fcr.error,
                            (fcr.lastApplError != null ? " | " + fcr.lastApplError : "")));
                        JOptionPane.showMessageDialog(dlg, msg.toString(), I18n.t("ctl.rejected.title"),
                            JOptionPane.ERROR_MESSAGE);
                    });
                }
            });
        });

        // Cancelar SELECT
        btnCancelSel.addActionListener(e -> {
            final String orIdent = tfOrIdent.getText().trim();
            busy[0] = true; refreshButtons.run();
            backgroundExecutor.submit(() -> {
                IEC61850Client.ControlResult cr = null;
                try { cr = client.cancelControl(operNode, orIdent); }
                catch (Exception ex) { log(I18n.t("log.app.cancelexception", ref, ex.getMessage())); }
                final IEC61850Client.ControlResult fcr = cr;
                SwingUtilities.invokeLater(() -> {
                    busy[0] = false; reserved[0] = false; stopTimer.run();
                    client.clearPendingSelect();
                    sbowInd.setBackground(new Color(224, 224, 224));
                    sbowInd.setForeground(Color.DARK_GRAY);
                    sbowInd.setText(I18n.t("ctl.sbo.cancelled"));
                    if (fcr != null && fcr.success) log(I18n.t("log.app.cancelok", ref));
                    else log(I18n.t("log.app.cancelerror2", ref, (fcr != null ? " — " + fcr.error : "")));
                    refreshButtons.run();
                });
            });
        });

        // Cerrar (si hay reserva vigente, la cancela best-effort antes de cerrar)
        final Runnable doClose = () -> {
            if (reserved[0]) {
                final String orIdent = tfOrIdent.getText().trim();
                backgroundExecutor.submit(() -> {
                    try { client.cancelControl(operNode, orIdent); } catch (Exception ignore) {}
                });
            }
            client.clearPendingSelect();
            stopTimer.run();
            dlg.dispose();
        };
        btnClose.addActionListener(e -> doClose.run());
        dlg.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        dlg.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent we) {
                if (!busy[0]) doClose.run();
            }
        });

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        btns.add(btnSelect); btns.add(btnExec); btns.add(btnCancelSel); btns.add(btnClose);

        dlg.getContentPane().setLayout(new BorderLayout());
        dlg.getContentPane().add(panel, BorderLayout.CENTER);
        dlg.getContentPane().add(btns, BorderLayout.SOUTH);
        refreshButtons.run();
        dlg.pack();
        dlg.setLocationRelativeTo(this);
        dlg.setVisible(true);
    }

    /**
     * Verifica la posición (stVal) tras un control aceptado y muestra el resultado.
     * Se invoca desde un hilo del backgroundExecutor: la verificación es bloqueante y el
     * diálogo final se muestra en el EDT. Extraído para compartir entre el flujo directo
     * (operateControl) y el SBO de dos pasos (executeControl).
     */
    private void reportControlResult(FcModelNode operNode, String ref, String ctlVal,
                                     boolean testFlag, boolean synchroCheck, boolean interlockCheck,
                                     boolean isSbo, IEC61850Client.ControlResult cr) {
        IEC61850Client.FeedbackResult fb = null;
        if (cr.success && !testFlag) {
            log(I18n.t("log.app.feedbackchecking", ref));
            fb = client.verifyControlFeedback(operNode, ctlVal, 10000);
        }
        final IEC61850Client.FeedbackResult fbf = fb;

        SwingUtilities.invokeLater(() -> {
            if (cr.success) {
                String checkInfo = (synchroCheck || interlockCheck)
                    ? "\n  Check: " + (synchroCheck ? "synchroChk " : "")
                      + (interlockCheck ? "interlkChk" : "") : "";

                String fbInfo, fbLog, dlgTitle;
                int dlgType;
                if (testFlag) {
                    fbInfo = "\n\n" + I18n.t("ctl.fb.test");
                    fbLog = " [TEST, sin verificación]";
                    dlgTitle = I18n.t("ctl.res.accepted.test");
                    dlgType = JOptionPane.INFORMATION_MESSAGE;
                } else if (fbf != null && fbf.verifiable && fbf.confirmed) {
                    String secs = String.format("%.1f", fbf.elapsedMs / 1000.0);
                    fbInfo = "\n\n" + I18n.t("ctl.fb.confirmed", fbf.observed, secs);
                    fbLog = " | posición confirmada: stVal=" + fbf.observed + " en " + secs + "s";
                    dlgTitle = I18n.t("ctl.res.confirmed");
                    dlgType = JOptionPane.INFORMATION_MESSAGE;
                } else if (fbf != null && fbf.verifiable) {
                    String secs = String.format("%.0f", fbf.elapsedMs / 1000.0);
                    fbInfo = "\n\n" + I18n.t("ctl.fb.noconf", secs, fbf.observed);
                    fbLog = " | SIN confirmación de posición (último stVal=" + fbf.observed + ")";
                    dlgTitle = I18n.t("ctl.res.accepted.noconf");
                    dlgType = JOptionPane.WARNING_MESSAGE;
                } else {
                    fbInfo = "\n\n" + I18n.t("ctl.fb.noverif");
                    fbLog = " (aceptado; sin stVal verificable)";
                    dlgTitle = I18n.t("ctl.res.accepted");
                    dlgType = JOptionPane.INFORMATION_MESSAGE;
                }

                String msg = I18n.t("ctl.msg.accepted") + "\n"
                    + "  " + I18n.t("ctl.msg.node") + ": " + ref + "\n"
                    + "  " + I18n.t("ctl.msg.value") + ": " + ctlVal + "\n"
                    + "  " + I18n.t("ctl.msg.model") + ": " + cr.ctlModelName
                    + (isSbo ? " (SELECT → OPERATE)" : "")
                    + (testFlag ? "\n  " + I18n.t("ctl.msg.testmode") : "")
                    + checkInfo + fbInfo;
                log(I18n.t("log.app.controlok", ref, ctlVal,
                    (testFlag ? " [TEST]" : ""),
                    (synchroCheck ? " [SYNCHRO]" : ""),
                    (interlockCheck ? " [INTERLOCK]" : ""),
                    cr.ctlModelName, fbLog));
                JOptionPane.showMessageDialog(IEDNavigatorApp.this, msg, dlgTitle, dlgType);
                updateSingleNodeInTree(ref.substring(0, ref.lastIndexOf('.')));
            } else {
                StringBuilder msg = new StringBuilder(I18n.t("ctl.oper.rejected.msg") + "\n\n");
                msg.append("  ").append(I18n.t("ctl.msg.node")).append(": ").append(ref).append("\n");
                msg.append("  ").append(I18n.t("ctl.msg.model")).append(": ").append(cr.ctlModelName).append("\n");
                msg.append("  ").append(I18n.t("ctl.msg.error")).append(": ").append(cr.error);
                if (cr.lastApplError != null) msg.append("\n  LastApplError: ").append(cr.lastApplError);
                log(I18n.t("log.app.controlerror", ref, cr.error,
                    (cr.lastApplError != null ? " | " + cr.lastApplError : "")));
                JOptionPane.showMessageDialog(IEDNavigatorApp.this, msg.toString(),
                    I18n.t("ctl.rejected.title"), JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    /**
     * Diálogo para cancelar un SELECT pendiente en el IED (ctlModel SBO = 2 o 4).
     * Envía el servicio CANCEL al nodo Cancel del DO de control.
     */
    private void showCancelDialog(FcModelNode operNode) {
        String ref = operNode.getReference().toString();
        int ctlModel = client.getCtlModelValue(operNode);
        String ctlModelName = new String[]{
            "status-only", "direct-normal-security", "sbo-normal-security",
            "direct-enhanced-security", "sbo-enhanced-security"
        }[Math.min(ctlModel, 4)];

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 6, 4, 6);
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;

        g.gridx = 0; g.gridy = 0; g.gridwidth = 2;
        panel.add(new JLabel("<html><b>" + ref + "</b></html>"), g);

        g.gridy = 1;
        JLabel lblModel = new JLabel(I18n.t("ctl.model") + " " + ctlModelName);
        lblModel.setForeground(new Color(120, 50, 150));
        panel.add(lblModel, g);

        g.gridy = 2;
        panel.add(new JLabel(I18n.t("ctl.cancel.note")), g);

        g.gridy = 3; g.gridwidth = 1; g.gridx = 0;
        panel.add(new JLabel(I18n.t("lbl.orident")), g);
        g.gridx = 1;
        JTextField tfOrIdent = new JTextField("IEDNavigator", 14);
        panel.add(tfOrIdent, g);

        int result = JOptionPane.showConfirmDialog(this, panel,
            I18n.t("ctx.cancelselect"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return;

        final String orIdent = tfOrIdent.getText().trim();
        backgroundExecutor.submit(() -> {
            try {
                IEC61850Client.ControlResult cr = client.cancelControl(operNode, orIdent);
                SwingUtilities.invokeLater(() -> {
                    if (cr.success) {
                        log(I18n.t("log.app.cancelokvia", ref, cr.ctlModelName));
                        JOptionPane.showMessageDialog(IEDNavigatorApp.this,
                            I18n.t("ctl.cancel.ok.msg") + "\n\n" + I18n.t("ctl.msg.node") + ": " + ref,
                            I18n.t("ctl.cancel.ok"), JOptionPane.INFORMATION_MESSAGE);
                    } else {
                        StringBuilder msg = new StringBuilder(I18n.t("ctl.cancel.rejected.msg") + "\n\n");
                        msg.append("  ").append(I18n.t("ctl.msg.node")).append(": ").append(ref).append("\n");
                        msg.append("  ").append(I18n.t("ctl.msg.error")).append(": ").append(cr.error);
                        if (cr.lastApplError != null)
                            msg.append("\n  LastApplError: ").append(cr.lastApplError);
                        log(I18n.t("log.app.cancelerror", ref, cr.error));
                        JOptionPane.showMessageDialog(IEDNavigatorApp.this, msg.toString(),
                            I18n.t("ctl.cancel.rejected"), JOptionPane.ERROR_MESSAGE);
                    }
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    log(I18n.t("log.app.cancelexception", ref, ex.getMessage()));
                    JOptionPane.showMessageDialog(IEDNavigatorApp.this,
                        I18n.t("err.comm") + "\n" + ex.getMessage(),
                        I18n.t("err.cancel.title"), JOptionPane.ERROR_MESSAGE);
                });
            }
        });
    }

    // ── Diagnostico de puerto ────────────────────────────────────────────────

    private void checkPort() {
        int port;
        try {
            port = Integer.parseInt(tfServerPort.getText().trim());
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, I18n.t("err.port.invalid"), I18n.t("err.title"), JOptionPane.ERROR_MESSAGE);
            return;
        }
        btnCheckPort.setEnabled(false);
        btnCheckPort.setText(I18n.t("port.checking"));
        final int p = port;
        new Thread(() -> {
            String result = diagnosePort(p);
            SwingUtilities.invokeLater(() -> {
                btnCheckPort.setEnabled(true);
                btnCheckPort.setText(I18n.t("port.check"));
                showPortDiagnosisDialog(p, result);
            });
        }, "port-check").start();
    }

    private String diagnosePort(int port) {
        // Intentar abrir el puerto: si funciona => libre, si BindException => ocupado o sin permisos
        try (ServerSocket ss = new ServerSocket(port)) {
            return I18n.t("port.res.free") + "\n\n"
                + I18n.t("port.res.free.body", String.valueOf(port)) + "\n";
        } catch (BindException be) {
            String msg = be.getMessage() != null ? be.getMessage().toLowerCase() : "";
            if (msg.contains("permission") || msg.contains("access is denied")
                    || msg.contains("acceso denegado") || msg.contains("errno=13")) {
                return I18n.t("port.res.denied") + "\n\n"
                    + I18n.t("port.res.denied.body", String.valueOf(port)) + "\n";
            }
            // Puerto ocupado
            return I18n.t("port.res.inuse") + "\n\n"
                + I18n.t("port.res.inuse.body", String.valueOf(port)) + "\n\n"
                + getPortOwnerInfo(port);
        } catch (Exception e) {
            return I18n.t("port.res.unexpected") + "\n\n"
                + I18n.t("port.res.unexpected.body", String.valueOf(port),
                    e.getClass().getSimpleName() + ": " + e.getMessage()) + "\n";
        }
    }

    private String getPortOwnerInfo(int port) {
        StringBuilder sb = new StringBuilder();
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        try {
            ProcessBuilder pb = isWindows
                    ? new ProcessBuilder("netstat", "-ano")
                    : new ProcessBuilder("ss", "-tlnp");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);

            if (isWindows) {
                String portToken = ":" + port + " ";
                StringBuilder matching = new StringBuilder();
                for (String line : output.split("\n")) {
                    if (line.contains(portToken) && line.toUpperCase().contains("LISTENING"))
                        matching.append(line.trim()).append("\n");
                }
                if (matching.length() > 0) {
                    sb.append(I18n.t("port.owner.found")).append("\n").append(matching);
                    // Extraer PID (ultima columna) y consultar nombre
                    for (String line : matching.toString().split("\n")) {
                        String[] parts = line.trim().split("\\s+");
                        if (parts.length >= 5) {
                            String pid = parts[4];
                            String name = getWindowsProcessName(pid);
                            if (!name.isEmpty())
                                sb.append("  PID ").append(pid).append(" = ").append(name).append("\n");
                        }
                    }
                } else {
                    sb.append(I18n.t("port.owner.notfound", String.valueOf(port))).append("\n");
                }
            } else {
                sb.append("Resultado de ss -tlnp:\n");
                for (String line : output.split("\n")) {
                    if (line.contains(":" + port + " ") || line.contains(":" + port + "\t"))
                        sb.append("  ").append(line.trim()).append("\n");
                }
                // fuser como complemento
                try {
                    Process f = new ProcessBuilder("fuser", port + "/tcp")
                            .redirectErrorStream(true).start();
                    String fout = new String(f.getInputStream().readAllBytes()).trim();
                    f.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
                    if (!fout.isBlank())
                        sb.append("fuser ").append(port).append("/tcp: PID ").append(fout).append("\n");
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            sb.append(I18n.t("port.owner.execfail")).append(" ").append(e.getMessage()).append("\n");
        }

        sb.append("\n").append(I18n.t("port.action", String.valueOf(port))).append("\n");
        if (isWindows) {
            sb.append("\n").append(I18n.t("port.manual")).append("\n");
            sb.append("  netstat -ano | findstr \":").append(port).append("\"\n");
        } else {
            sb.append("\n").append(I18n.t("port.manual")).append("\n");
            sb.append("  ss -tlnp sport = :").append(port).append("\n");
            sb.append("  sudo fuser ").append(port).append("/tcp\n");
        }
        return sb.toString();
    }

    private String getWindowsProcessName(String pid) {
        try {
            Process proc = new ProcessBuilder(
                    "tasklist", "/FI", "PID eq " + pid, "/FO", "CSV", "/NH")
                    .redirectErrorStream(true).start();
            String out = new String(proc.getInputStream().readAllBytes()).trim();
            proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            if (!out.isBlank() && !out.startsWith("INFO:")) {
                String[] parts = out.replace("\"", "").split(",");
                if (parts.length >= 1) return parts[0];
            }
        } catch (Exception ignored) {}
        return "";
    }

    private void showPortDiagnosisDialog(int port, String result) {
        JTextArea ta = new JTextArea(result);
        ta.setEditable(false);
        ta.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        ta.setBackground(new Color(248, 248, 248));
        ta.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        Color borderColor;
        if (result.startsWith(I18n.t("port.res.free")))         borderColor = new Color(0, 140, 0);
        else if (result.startsWith(I18n.t("port.res.denied")))  borderColor = new Color(200, 100, 0);
        else if (result.startsWith(I18n.t("port.res.inuse")))   borderColor = new Color(190, 0, 0);
        else                                                     borderColor = new Color(120, 0, 160);

        JScrollPane sp = new JScrollPane(ta);
        sp.setPreferredSize(new java.awt.Dimension(500, 300));
        sp.setBorder(BorderFactory.createLineBorder(borderColor, 2));

        JDialog dlg = new JDialog(this, I18n.t("port.diag.title") + " " + port, true);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dlg.add(sp);
        dlg.pack();
        dlg.setLocationRelativeTo(this);
        dlg.setVisible(true);
    }

    private void releasePort() {
        int port;
        try {
            port = Integer.parseInt(tfServerPort.getText().trim());
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, I18n.t("err.port.invalid"), I18n.t("err.title"), JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Buscar PIDs que usan el puerto
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        java.util.List<String[]> found = findPortPids(port, isWindows); // cada String[]: {pid, processName}

        if (found.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    I18n.t("port.release.none.msg", String.valueOf(port)),
                    I18n.t("port.release.none.title", String.valueOf(port)),
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // Construir mensaje de confirmacion
        StringBuilder msg = new StringBuilder();
        msg.append(I18n.t("port.release.confirm.hdr")).append("\n\n");
        for (String[] entry : found) {
            msg.append("  PID ").append(entry[0]);
            if (!entry[1].isEmpty()) msg.append("  (").append(entry[1]).append(")");
            msg.append("\n");
        }
        msg.append("\n").append(I18n.t("port.release.confirm.tail", String.valueOf(port)));

        int confirm = JOptionPane.showConfirmDialog(this, msg.toString(),
                I18n.t("port.release.title") + " " + port, JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;

        // Ejecutar kill en background
        btnReleasePort.setEnabled(false);
        btnReleasePort.setText(I18n.t("port.releasing"));
        final int finalPort = port;
        final boolean finalWin = isWindows;
        final java.util.List<String[]> finalFound = found;

        new Thread(() -> {
            StringBuilder result = new StringBuilder();
            for (String[] entry : finalFound) {
                String pid = entry[0];
                String name = entry[1];
                try {
                    ProcessBuilder pb = finalWin
                            ? new ProcessBuilder("taskkill", "/F", "/PID", pid)
                            : new ProcessBuilder("kill", "-9", pid);
                    pb.redirectErrorStream(true);
                    Process proc = pb.start();
                    String out = new String(proc.getInputStream().readAllBytes()).trim();
                    int code = proc.waitFor();
                    if (code == 0) {
                        result.append("OK  PID ").append(pid);
                        if (!name.isEmpty()) result.append(" (").append(name).append(")");
                        result.append(" ").append(I18n.t("port.release.terminated")).append("\n");
                    } else {
                        result.append("ERROR  PID ").append(pid).append(": ").append(out).append("\n");
                        if (finalWin) result.append("  ").append(I18n.t("port.release.tryadmin")).append("\n");
                        else result.append("  ").append(I18n.t("port.release.trysudo")).append("\n");
                    }
                } catch (Exception ex) {
                    result.append(I18n.t("port.release.exc")).append(" ").append(pid).append(": ").append(ex.getMessage()).append("\n");
                }
            }

            // Verificar si el puerto quedo libre
            boolean libre = false;
            try (ServerSocket ss = new ServerSocket(finalPort)) { libre = true; } catch (Exception ignored) {}
            result.append("\n").append(I18n.t("port.status")).append(" ").append(finalPort).append(": ")
                  .append(libre ? I18n.t("port.free") : I18n.t("port.stillused")).append("\n");

            final String finalResult = result.toString();
            final boolean finalLibre = libre;
            SwingUtilities.invokeLater(() -> {
                btnReleasePort.setEnabled(true);
                btnReleasePort.setText(I18n.t("port.release"));
                JOptionPane.showMessageDialog(this,
                        finalResult,
                        I18n.t("port.release.title") + " " + finalPort + " — "
                            + (finalLibre ? I18n.t("port.success") : I18n.t("port.review")),
                        finalLibre ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
            });
        }, "port-release").start();
    }

    /** Devuelve lista de {pid, processName} que usan el puerto en modo LISTENING (TCP). */
    private java.util.List<String[]> findPortPids(int port, boolean isWindows) {
        java.util.List<String[]> result = new java.util.ArrayList<>();
        try {
            if (isWindows) {
                Process proc = new ProcessBuilder("netstat", "-ano")
                        .redirectErrorStream(true).start();
                String output = new String(proc.getInputStream().readAllBytes());
                proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                for (String line : output.split("\n")) {
                    if (line.contains(":" + port + " ") && line.toUpperCase().contains("LISTENING")) {
                        String[] parts = line.trim().split("\\s+");
                        if (parts.length >= 5) {
                            String pid = parts[4].trim();
                            String name = getWindowsProcessName(pid);
                            result.add(new String[]{pid, name});
                        }
                    }
                }
            } else {
                // Linux: usar fuser para obtener PIDs directamente
                try {
                    Process proc = new ProcessBuilder("fuser", port + "/tcp")
                            .redirectErrorStream(true).start();
                    String out = new String(proc.getInputStream().readAllBytes()).trim();
                    proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                    for (String pid : out.split("\\s+")) {
                        pid = pid.trim();
                        if (!pid.isEmpty() && pid.matches("\\d+"))
                            result.add(new String[]{pid, ""});
                    }
                } catch (Exception ignored) {}

                // Fallback: ss -tlnp
                if (result.isEmpty()) {
                    Process proc = new ProcessBuilder("ss", "-tlnp")
                            .redirectErrorStream(true).start();
                    String output = new String(proc.getInputStream().readAllBytes());
                    proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                    for (String line : output.split("\n")) {
                        if (line.contains(":" + port + " ") || line.contains(":" + port + "\t")) {
                            // Extraer pid=XXXX del campo users
                            java.util.regex.Matcher m =
                                java.util.regex.Pattern.compile("pid=(\\d+)").matcher(line);
                            while (m.find()) result.add(new String[]{m.group(1), ""});
                        }
                    }
                }
            }
        } catch (Exception e) {
            log(I18n.t("log.app.findportprocerror", port, e.getMessage()));
        }
        return result;
    }

    /** Actualiza el label destacado del IED en la esquina superior derecha.
     *  @param infoText texto completo de la barra inferior, ej:
     *    "  IED: SEL_735_1  |  Fabricante: SEL  |  Tipo: SEL_735  cfg:ICD-735-R100..."
     *    "  IED: ?  |  FW: ?  |  Config: ?"
     *    " " (vacio cuando se desconecta)
     */
    private void updateIedDisplay(String infoText) {
        if (infoText == null || infoText.isBlank()) {
            lblIedDisplay.setText("  " + I18n.t("status.nodevice") + "  ");
            lblIedDisplay.setForeground(new Color(120, 120, 140));
            return;
        }

        // Extraer nombre del IED (primer campo tras "IED:", termina en "|" o "cfg:" o fin)
        String iedName = "";
        if (infoText.contains("IED:")) {
            int s = infoText.indexOf("IED:") + 4;
            int e = infoText.indexOf("|", s);
            int eCfg = infoText.indexOf("cfg:", s);
            if (e < 0) e = eCfg;
            else if (eCfg >= 0) e = Math.min(e, eCfg);
            iedName = (e > 0 ? infoText.substring(s, e) : infoText.substring(s)).trim();
        }

        // Extraer subtitulo: solo Tipo (modelo del equipo). cfg y Config NO van al cuadro.
        String sub = "";
        if (infoText.contains("Tipo:")) {
            int s = infoText.indexOf("Tipo:") + 5;
            int e = infoText.indexOf("|", s);
            sub = (e > 0 ? infoText.substring(s, e) : infoText.substring(s)).trim();
            // Recortar cfg: si esta pegado
            if (sub.contains("cfg:")) sub = sub.substring(0, sub.indexOf("cfg:")).trim();
        }

        if (!iedName.isEmpty() && iedName.equals("?")) iedName = "";

        if (!iedName.isEmpty()) {
            String html = "<html><div style='text-align:center'>"
                + "<b style='font-size:13px;'>" + iedName + "</b>";
            if (!sub.isEmpty() && !sub.equals("?"))
                html += "<br><span style='font-size:9px;color:#336699;'>" + sub + "</span>";
            html += "</div></html>";
            lblIedDisplay.setText(html);
            lblIedDisplay.setForeground(new Color(15, 55, 120));
        } else {
            lblIedDisplay.setText("  " + I18n.t("status.nodevice") + "  ");
            lblIedDisplay.setForeground(new Color(120, 120, 140));
        }
    }

    private void updateConnectionInfo(String host, int port) {
        if (host == null || host.isEmpty() || port == 0) {
            lblConnectionInfo.setText(I18n.t("status.noconn"));
            lblConnectionInfo.setForeground(Color.GRAY);
        } else {
            lblConnectionInfo.setText(host + ":" + port);
            lblConnectionInfo.setForeground(new Color(0, 100, 0));
        }
    }

    private int showIEDSelectionDialog(List<String> ieds, String fileName) {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JLabel lblInfo = new JLabel(I18n.t("ied.select.msg", fileName, String.valueOf(ieds.size())));
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
        // Doble clic en la lista equivale a Aceptar
        final int[] doubleClickIndex = {-1};
        iedList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && iedList.getSelectedIndex() >= 0) {
                    doubleClickIndex[0] = iedList.getSelectedIndex();
                    // Cerrar el JOptionPane buscando el diálogo contenedor
                    java.awt.Window w = SwingUtilities.getWindowAncestor(iedList);
                    if (w != null) w.dispose();
                }
            }
        });
        int result = JOptionPane.showConfirmDialog(this, panel,
            I18n.t("ied.select.title"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (doubleClickIndex[0] >= 0) {
            return doubleClickIndex[0];
        }
        if (result == JOptionPane.OK_OPTION) {
            return iedList.getSelectedIndex();
        }
        return -1;
    }

    private ConnectionManager.Context createConnectionContext() {
        return new ConnectionManager.Context() {
            public void log(String msg) { IEDNavigatorApp.this.log(msg); }
            public void updateStatus(boolean active, String msg) { IEDNavigatorApp.this.updateStatus(active, msg); }
            public Component parentWindow() { return IEDNavigatorApp.this; }
            public ExecutorService backgroundExecutor() { return backgroundExecutor; }
            public IEC61850Client getClient() { return client; }
            public void setClient(IEC61850Client c) { client = c; }
            public IEC61850Server getServer() { return server; }
            public void setServer(IEC61850Server s) { server = s; }
            public boolean isConnected() { return IEDNavigatorApp.this.isConnected; }
            public void setConnected(boolean v) { isConnected = v; }
            public boolean isServerRunning() { return IEDNavigatorApp.this.isServerRunning; }
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
            public void displayServerModel() { IEDNavigatorApp.this.displayServerModel(); }
            public void displayClientModel() { IEDNavigatorApp.this.displayClientModel(); }
            public void refreshGooseControlBlocks() { IEDNavigatorApp.this.refreshGooseControlBlocks(); }
            public void autoSelectGooseInterface(String localIp) { IEDNavigatorApp.this.autoSelectGooseInterface(localIp); }
            public void parseGoCBsFromScl(File f) { IEDNavigatorApp.this.parseGoCBsFromScl(f); }
            public void parseGoCBsFromScl(File f, int iedIndex) { IEDNavigatorApp.this.parseGoCBsFromScl(f, iedIndex); }
            public int showIEDSelectionDialog(List<String> iedNames, String fileName) {
                return IEDNavigatorApp.this.showIEDSelectionDialog(iedNames, fileName);
            }
            public void stopPolling() { IEDNavigatorApp.this.stopPolling(); }
            public String getTfHost() { return tfHost.getText(); }
            public String getTfClientPort() { return tfClientPort.getText(); }
            public int getConnectionTimeoutMs() { return (int) spinnerTimeout.getValue() * 1000; }
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
            public void setLblIedInfo(String text) {
                lblIedInfo.setText(text);
                IEDNavigatorApp.this.updateIedDisplay(text);
            }
            public void updateConnectionInfo(String host, int port) {
                IEDNavigatorApp.this.updateConnectionInfo(host, port);
            }
            public void clearModel() { IEDNavigatorApp.this.clearModel(); }
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
            public void log(String msg) { IEDNavigatorApp.this.log(msg); }
            public boolean isConnected() { return IEDNavigatorApp.this.isConnected; }
            public IEC61850Client getClient() { return client; }
            public java.util.Set<String> getWatchlist() { return watchlist; }
            public DefaultMutableTreeNode getRootNode() { return rootNode; }
            public JTree getModelTree() { return modelTree; }
            public DefaultTreeModel getTreeModel() { return treeModel; }
            public String formatEnumValue(ModelNode node, String rawValue) {
                return IEDNavigatorApp.this.formatEnumValue(node, rawValue);
            }
            public void updateMonitorValues() { IEDNavigatorApp.this.updateMonitorValues(); }
            public int getPollingInterval() { return (Integer) spinnerInterval.getValue(); }
            public java.util.concurrent.ExecutorService backgroundExecutor() { return backgroundExecutor; }
        };
    }


    // --- SECTION: MODEL TREE BUILDING (F9: delegado a ModelTreeBuilder.java) ---
    private java.util.function.BiFunction<ModelNode, String, String> enumFormatterFn() {
        return (node, raw) -> IEDNavigatorApp.this.formatEnumValue(node, raw);
    }

    // Cache para DataSets y Reports parseados del SCL
    private List<SclDataSet> sclDataSets = new ArrayList<>();
    private List<SclReport> sclReports = new ArrayList<>();

    private void displayServerModel() {
        ServerModel model = server.getServerModel();
        if (model == null) { log(I18n.t("log.app.servermodelnull")); return; }
        log(I18n.t("log.app.servermodellds", model.getChildren().size()));
        buildTree(model);
    }

    private void displayClientModel() {
        ServerModel model = client.getServerModel();
        if (model == null) { log(I18n.t("log.app.clientmodelnull")); return; }
        log(I18n.t("log.app.clientmodellds", model.getChildren().size()));
        buildTree(model);
    }

    private void buildTree(ServerModel model) {
        ModelTreeBuilder.buildTree(model, rootNode, treeModel, nodeMap,
            sclDataSets, sclReports, sclGoCBs, modelTree, this::log, enumFormatterFn());
    }

    private void clearModel() {
        ModelTreeBuilder.clearModel(rootNode, nodeMap, treeModel);
        // Limpiar GOOSE y Reports al cambiar de modelo
        if (goosePanel != null) goosePanel.clearAll();
        if (reportsPanel != null) reportsPanel.clearAll();
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

    /**
     * Pulido visual (solo "maquillaje"): propiedades de FlatLaf aplicadas antes de
     * construir la GUI. No altera layout, pestañas, iconos ni funcionalidad.
     * Para revertir cualquier ajuste basta con comentar la línea correspondiente.
     */
    private static void applyUiPolish() {
        // ── 1. Estilo global ──────────────────────────────────────────────
        // Esquinas redondeadas
        UIManager.put("Button.arc", 10);
        UIManager.put("Component.arc", 10);
        UIManager.put("TextComponent.arc", 10);
        UIManager.put("ProgressBar.arc", 10);
        UIManager.put("CheckBox.arc", 6);
        UIManager.put("Popup.dropShadowPainted", true);

        // Color de acento (azul eléctrico) — foco, selección, subrayados
        Color accent = new Color(0x1976D2);
        UIManager.put("Component.accentColor", accent);
        UIManager.put("Component.focusColor", new Color(0x1976D2));
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("Component.innerFocusWidth", 1);

        // Pestañas: subrayado de color en la activa, separadores sutiles
        UIManager.put("TabbedPane.tabSelectionHeight", 3);
        UIManager.put("TabbedPane.selectedBackground", Color.WHITE);
        UIManager.put("TabbedPane.underlineColor", accent);
        UIManager.put("TabbedPane.inactiveUnderlineColor", new Color(0x90CAF9));
        UIManager.put("TabbedPane.hoverColor", new Color(0xE3F2FD));
        UIManager.put("TabbedPane.showTabSeparators", true);
        UIManager.put("TabbedPane.tabSeparatorsFullHeight", false);

        // Scrollbars finas con hover
        UIManager.put("ScrollBar.width", 12);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.thumbInsets", new Insets(2, 2, 2, 2));
        UIManager.put("ScrollBar.track", new Color(0xFAFAFA));
        UIManager.put("ScrollBar.hoverThumbWithTrack", true);

        // ── 2. Tablas y árbol ─────────────────────────────────────────────
        UIManager.put("Table.alternateRowColor", new Color(0xF5F8FC));
        UIManager.put("Table.rowHeight", 22);
        UIManager.put("Table.showHorizontalLines", false);
        UIManager.put("Table.showVerticalLines", false);
        UIManager.put("Table.intercellSpacing", new Dimension(0, 0));
        UIManager.put("Table.selectionInactiveBackground", new Color(0xE3F2FD));
        UIManager.put("Table.selectionInactiveForeground", Color.BLACK);
        UIManager.put("TableHeader.height", 26);
        UIManager.put("TableHeader.showTrailingVerticalLine", true);
        UIManager.put("TableHeader.separatorColor", new Color(0xDDDDDD));
        UIManager.put("TableHeader.font", new Font("Dialog", Font.BOLD, 12));

        UIManager.put("Tree.rowHeight", 22);
        UIManager.put("Tree.selectionArc", 8);
        UIManager.put("Tree.wideSelection", true);
        UIManager.put("Tree.paintLines", false);
        UIManager.put("List.selectionArc", 8);

        // ── 3. Detalles de pulido ─────────────────────────────────────────
        // Fuente base con buen rendering en Windows
        UIManager.put("defaultFont", new Font("Dialog", Font.PLAIN, 13));
        // TitledBorders más sutiles
        UIManager.put("TitledBorder.titleColor", new Color(0x546E7A));
        // Toolbars con algo de aire
        UIManager.put("ToolBar.spacingBorder", new Insets(4, 6, 4, 6));
        UIManager.put("Button.margin", new Insets(4, 12, 4, 12));
        // Separadores y bordes suaves
        UIManager.put("Component.borderColor", new Color(0xD6D9DE));
        UIManager.put("Separator.foreground", new Color(0xE0E0E0));
        // Tooltips legibles
        UIManager.put("ToolTip.background", new Color(0xFFFDE7));

        // ── 4. Maquillaje extra (segunda ronda) ───────────────────────────
        // Menú superior: ítem activo con subrayado azul en vez de bloque gris
        UIManager.put("MenuBar.underlineSelectionColor", accent);
        UIManager.put("MenuBar.underlineSelectionBackground", new Color(0xE3F2FD));
        UIManager.put("MenuBar.underlineSelectionHeight", 3);
        UIManager.put("MenuItem.selectionType", "underline");
        UIManager.put("MenuItem.underlineSelectionColor", accent);
        UIManager.put("MenuItem.selectionArc", 6);
        UIManager.put("PopupMenu.borderCornerRadius", 8);

        // Botones: hover celeste y borde que se "enciende" al pasar el mouse
        UIManager.put("Button.hoverBackground", new Color(0xE3F2FD));
        UIManager.put("Button.hoverBorderColor", accent);
        UIManager.put("Button.pressedBackground", new Color(0xBBDEFB));
        UIManager.put("Button.default.boldText", true);
        UIManager.put("Button.borderColor", new Color(0xB9C4CE));

        // Pestaña activa: texto en azul + negrita para reforzar el subrayado
        UIManager.put("TabbedPane.selectedForeground", accent);
        UIManager.put("TabbedPane.font", new Font("Dialog", Font.PLAIN, 13));
        UIManager.put("TabbedPane.tabHeight", 30);

        // Selección de tabla/árbol en azul suave con texto negro (no invierte color)
        UIManager.put("Table.selectionBackground", new Color(0xCFE5F7));
        UIManager.put("Table.selectionForeground", Color.BLACK);
        UIManager.put("Tree.selectionBackground", new Color(0xCFE5F7));
        UIManager.put("Tree.selectionForeground", Color.BLACK);
        UIManager.put("List.selectionBackground", new Color(0xCFE5F7));
        UIManager.put("List.selectionForeground", Color.BLACK);

        // Campos de texto: borde gris que pasa a azul con foco, placeholder gris
        UIManager.put("TextField.placeholderForeground", new Color(0x9E9E9E));
        UIManager.put("ComboBox.buttonStyle", "mac"); // flecha sin caja separada
        UIManager.put("Spinner.buttonStyle", "mac");

        // Divisores de paneles más finos y con "agarre" sutil
        UIManager.put("SplitPane.dividerSize", 7);
        UIManager.put("SplitPaneDivider.gripDotCount", 3);
        UIManager.put("SplitPaneDivider.gripColor", new Color(0xB0BEC5));

        // TitledBorder con fuente más chica y semibold (look de sección)
        UIManager.put("TitledBorder.font", new Font("Dialog", Font.BOLD, 12));

        // ── 5. Barra de título moderna (decoraciones FlatLaf, estilo VS Code) ──
        // Menú embebido en la barra de título + fondo unificado con la ventana
        UIManager.put("TitlePane.unifiedBackground", true);
        UIManager.put("TitlePane.menuBarEmbedded", true);
        UIManager.put("TitlePane.titleMargins", new Insets(4, 8, 4, 8));
        UIManager.put("TitlePane.font", new Font("Dialog", Font.BOLD, 13));
        UIManager.put("TitlePane.foreground", new Color(0x37474F));
        // Para revertir a la barra de título clásica de Windows: comentar las
        // dos líneas setDefaultLookAndFeelDecorated(true) en main().
    }

    public static void main(String[] args) {
        // Look and Feel
        try {
            UIManager.setLookAndFeel("com.formdev.flatlaf.FlatLightLaf");
            applyUiPolish();
            // Decoraciones de ventana FlatLaf (barra de título moderna integrada)
            JFrame.setDefaultLookAndFeelDecorated(true);
            JDialog.setDefaultLookAndFeelDecorated(true);
        } catch (Exception e) {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ex) {}
        }

        SwingUtilities.invokeLater(() -> {
            IEDNavigatorApp app = new IEDNavigatorApp();
            app.setVisible(true);
        });
    }
}
