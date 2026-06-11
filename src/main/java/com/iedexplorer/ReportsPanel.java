package com.iedexplorer;

import com.beanit.iec61850bean.*;
import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Panel de Reports (RCB/URCB/BRCB) del cliente IEC 61850.
 * Extraído de IEDExplorerApp.java — Fase 6 de refactorización.
 *
 * Recibe los datos del reporte via handleReportReceived() que es registrado
 * como listener en client.enableReporting(rcb, listener).
 */
class ReportsPanel {

    private final Component parent;
    private final Consumer<String> log;
    private final Supplier<ServerModel> modelSupplier;
    private final Supplier<IEC61850Client> clientSupplier;
    private final ExecutorService backgroundExecutor;
    private final Consumer<String> onNodeUpdate;   // updateSingleNodeInTree(ref)

    private JTable reportsTable;
    private DefaultTableModel reportsTableModel;
    private JTable reportDataTable;
    private DefaultTableModel reportDataTableModel;
    private Map<String, Rcb> rcbMap = new HashMap<>();
    private Map<String, String> previousReportValues = new HashMap<>();

    ReportsPanel(Component parent,
                 Consumer<String> log,
                 Supplier<ServerModel> modelSupplier,
                 Supplier<IEC61850Client> clientSupplier,
                 ExecutorService backgroundExecutor,
                 Consumer<String> onNodeUpdate) {
        this.parent            = parent;
        this.log               = log;
        this.modelSupplier     = modelSupplier;
        this.clientSupplier    = clientSupplier;
        this.backgroundExecutor = backgroundExecutor;
        this.onNodeUpdate      = onNodeUpdate;
    }

    JPanel createPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        // ===== TOP: RCB List with controls =====
        JPanel rcbPanel = new JPanel(new BorderLayout());
        rcbPanel.setBorder(BorderFactory.createTitledBorder("Report Control Blocks (URCB/BRCB)"));

        String[] rcbColumns = {"RCB Reference", "Tipo", "Dataset", "TrgOps", "IntgPd", "Estado"};
        reportsTableModel = new DefaultTableModel(rcbColumns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        reportsTable = new JTable(reportsTableModel);
        reportsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        reportsTable.setRowHeight(22);
        reportsTable.getColumnModel().getColumn(0).setPreferredWidth(250);
        reportsTable.getColumnModel().getColumn(1).setPreferredWidth(60);
        reportsTable.getColumnModel().getColumn(2).setPreferredWidth(150);
        reportsTable.getColumnModel().getColumn(3).setPreferredWidth(80);
        reportsTable.getColumnModel().getColumn(4).setPreferredWidth(60);
        reportsTable.getColumnModel().getColumn(5).setPreferredWidth(100);

        reportsTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (!isSelected) {
                    String status = (String) table.getValueAt(row, 5);
                    if ("Habilitado".equals(status)) {
                        c.setBackground(new Color(200, 255, 200));
                        if (column == 5) setForeground(new Color(0, 120, 0));
                    } else {
                        c.setBackground(Color.WHITE);
                        if (column == 5) setForeground(Color.GRAY);
                    }
                }
                return c;
            }
        });

        JScrollPane rcbScroll = new JScrollPane(reportsTable);
        rcbScroll.setPreferredSize(new Dimension(300, 180));

        JPanel rcbBtnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));

        JButton btnRefreshRcb = new JButton("Cargar RCBs");
        btnRefreshRcb.setToolTipText("Cargar Report Control Blocks del modelo");
        btnRefreshRcb.addActionListener(e -> refreshReportControlBlocks());
        rcbBtnPanel.add(btnRefreshRcb);

        rcbBtnPanel.add(new JSeparator(SwingConstants.VERTICAL));

        JButton btnEnableRcb = new JButton("Habilitar");
        btnEnableRcb.setBackground(new Color(0, 150, 0));
        btnEnableRcb.setForeground(Color.WHITE);
        btnEnableRcb.setToolTipText("Habilitar el RCB seleccionado para recibir reportes");
        btnEnableRcb.addActionListener(e -> enableSelectedReport(true));
        rcbBtnPanel.add(btnEnableRcb);

        JButton btnDisableRcb = new JButton("Deshabilitar");
        btnDisableRcb.setToolTipText("Deshabilitar el RCB seleccionado");
        btnDisableRcb.addActionListener(e -> enableSelectedReport(false));
        rcbBtnPanel.add(btnDisableRcb);

        JButton btnEnableAll = new JButton("Habilitar Todos");
        btnEnableAll.setToolTipText("Habilitar todos los RCBs");
        btnEnableAll.addActionListener(e -> enableAllReports(true));
        rcbBtnPanel.add(btnEnableAll);

        JLabel lblRcbCount = new JLabel("  RCBs: 0");
        rcbBtnPanel.add(lblRcbCount);

        rcbPanel.add(rcbScroll, BorderLayout.CENTER);
        rcbPanel.add(rcbBtnPanel, BorderLayout.SOUTH);

        // ===== BOTTOM: Received Report Data =====
        JPanel dataPanel = new JPanel(new BorderLayout());
        dataPanel.setBorder(BorderFactory.createTitledBorder("Datos de Reportes Recibidos (Tiempo Real)"));

        String[] dataColumns = {"Timestamp", "RCB", "SeqNum", "Referencia", "Valor Anterior", "Valor Nuevo", "Razon"};
        reportDataTableModel = new DefaultTableModel(dataColumns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        reportDataTable = new JTable(reportDataTableModel);
        reportDataTable.setRowHeight(20);
        reportDataTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        reportDataTable.getColumnModel().getColumn(0).setPreferredWidth(90);
        reportDataTable.getColumnModel().getColumn(1).setPreferredWidth(120);
        reportDataTable.getColumnModel().getColumn(2).setPreferredWidth(60);
        reportDataTable.getColumnModel().getColumn(3).setPreferredWidth(200);
        reportDataTable.getColumnModel().getColumn(4).setPreferredWidth(100);
        reportDataTable.getColumnModel().getColumn(5).setPreferredWidth(100);
        reportDataTable.getColumnModel().getColumn(6).setPreferredWidth(80);

        reportDataTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (!isSelected && column == 5) {
                    c.setBackground(new Color(255, 255, 200));
                    setFont(getFont().deriveFont(Font.BOLD));
                } else if (!isSelected) {
                    c.setBackground(Color.WHITE);
                }
                return c;
            }
        });

        JScrollPane dataScroll = new JScrollPane(reportDataTable);

        JPanel dataBtnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));

        JButton btnClearReports = new JButton("Limpiar");
        btnClearReports.addActionListener(e -> reportDataTableModel.setRowCount(0));
        dataBtnPanel.add(btnClearReports);

        JCheckBox cbAutoScroll = new JCheckBox("Auto-scroll", true);
        dataBtnPanel.add(cbAutoScroll);

        JLabel lblReportCount = new JLabel("  Reportes: 0");
        dataBtnPanel.add(lblReportCount);

        javax.swing.Timer reportTimer = new javax.swing.Timer(500, e -> {
            lblRcbCount.setText("  RCBs: " + reportsTableModel.getRowCount());
            lblReportCount.setText("  Reportes: " + reportDataTableModel.getRowCount());
        });
        reportTimer.start();

        dataPanel.add(dataScroll, BorderLayout.CENTER);
        dataPanel.add(dataBtnPanel, BorderLayout.SOUTH);

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, rcbPanel, dataPanel);
        splitPane.setDividerLocation(250);
        splitPane.setResizeWeight(0.4);
        panel.add(splitPane, BorderLayout.CENTER);

        return panel;
    }

    private void enableAllReports(boolean enable) {
        IEC61850Client cl = clientSupplier.get();
        if (cl == null) {
            log.accept("Error: no hay conexion activa con el IED");
            return;
        }

        List<String> names = new ArrayList<>();
        List<Rcb> rcbs = new ArrayList<>();
        for (int i = 0; i < reportsTableModel.getRowCount(); i++) {
            String rcbName = (String) reportsTableModel.getValueAt(i, 0);
            Rcb rcb = rcbMap.get(rcbName);
            if (rcb != null) { names.add(rcbName); rcbs.add(rcb); }
        }

        backgroundExecutor.submit(() -> {
            int ok = 0, fail = 0;
            for (int i = 0; i < rcbs.size(); i++) {
                final String rcbName = names.get(i);
                final Rcb rcb = rcbs.get(i);
                final int rowIdx = i;
                try {
                    if (enable) {
                        cl.enableReporting(rcb, report ->
                            SwingUtilities.invokeLater(() -> handleReportReceived(report)));
                    } else {
                        cl.disableReporting(rcb);
                    }
                    SwingUtilities.invokeLater(() ->
                        reportsTableModel.setValueAt(enable ? "Habilitado" : "Deshabilitado", rowIdx, 5));
                    ok++;
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() ->
                        log.accept("Error en " + rcbName + ": " + e.getMessage()));
                    fail++;
                }
            }
            final int finalOk = ok, finalFail = fail;
            SwingUtilities.invokeLater(() ->
                log.accept((enable ? "Habilitar" : "Deshabilitar") + " todos: "
                    + finalOk + " OK, " + finalFail + " error(es)"));
        });
    }

    private void refreshReportControlBlocks() {
        reportsTableModel.setRowCount(0);
        rcbMap.clear();

        ServerModel model = modelSupplier.get();
        if (model == null) {
            log.accept("No hay modelo cargado para obtener RCBs");
            return;
        }

        try {
            log.accept("Buscando RCBs en el modelo...");
            for (ModelNode ld : model.getChildren()) {
                String ldName = ld.getName();
                for (ModelNode ln : ld.getChildren()) {
                    if (ln.getChildren() == null) continue;
                    String lnName = ln.getName();
                    if (lnName.equals("LLN0")) {
                        log.accept("LN " + ldName + "/" + lnName + " tiene " + ln.getChildren().size() + " hijos");
                    }
                    for (ModelNode node : ln.getChildren()) {
                        searchForRcbs(node, ldName, lnName);
                    }
                }
            }
            log.accept("RCBs encontrados: " + rcbMap.size());
            if (rcbMap.isEmpty()) {
                log.accept("Nota: Este IED puede no tener RCBs configurados, o pueden estar en una estructura diferente.");
            }
        } catch (Exception e) {
            log.accept("Error obteniendo RCBs: " + e.getMessage());
        }
    }

    private void searchForRcbs(ModelNode node, String ldName, String lnName) {
        if (node instanceof Urcb) {
            Urcb urcb = (Urcb) node;
            String name = ldName + "/" + lnName + "." + urcb.getName();
            String dataset = urcb.getDatSet() != null ? urcb.getDatSet().getStringValue() : "";
            boolean enabled = urcb.getRptEna() != null && urcb.getRptEna().getValue();
            String trgOps = buildTrgOpsString(urcb.getTrgOps());
            String intgPd = urcb.getIntgPd() != null ? String.valueOf(urcb.getIntgPd().getValue()) : "";
            rcbMap.put(name, urcb);
            reportsTableModel.addRow(new Object[]{name, "URCB", dataset, trgOps, intgPd, enabled ? "Habilitado" : "Deshabilitado"});
        } else if (node instanceof Brcb) {
            Brcb brcb = (Brcb) node;
            String name = ldName + "/" + lnName + "." + brcb.getName();
            String dataset = brcb.getDatSet() != null ? brcb.getDatSet().getStringValue() : "";
            boolean enabled = brcb.getRptEna() != null && brcb.getRptEna().getValue();
            String trgOps = buildTrgOpsString(brcb.getTrgOps());
            String intgPd = brcb.getIntgPd() != null ? String.valueOf(brcb.getIntgPd().getValue()) : "";
            rcbMap.put(name, brcb);
            reportsTableModel.addRow(new Object[]{name, "BRCB", dataset, trgOps, intgPd, enabled ? "Habilitado" : "Deshabilitado"});
        }
        if (node.getChildren() != null) {
            for (ModelNode child : node.getChildren()) {
                searchForRcbs(child, ldName, lnName);
            }
        }
    }

    private String buildTrgOpsString(BdaTriggerConditions tc) {
        if (tc == null) return "";
        StringBuilder sb = new StringBuilder();
        if (tc.isDataChange())           sb.append("dchg ");
        if (tc.isQualityChange())        sb.append("qchg ");
        if (tc.isDataUpdate())           sb.append("dupd ");
        if (tc.isIntegrity())            sb.append("intg ");
        if (tc.isGeneralInterrogation()) sb.append("gi ");
        return sb.toString().trim();
    }

    private void enableSelectedReport(boolean enable) {
        int row = reportsTable.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(parent, "Selecciona un RCB primero");
            return;
        }

        IEC61850Client cl = clientSupplier.get();
        if (cl == null) {
            log.accept("Error: no hay conexion activa con el IED");
            return;
        }

        String rcbName = (String) reportsTableModel.getValueAt(row, 0);
        Rcb rcb = rcbMap.get(rcbName);
        if (rcb == null) return;

        final int tableRow = row;
        backgroundExecutor.submit(() -> {
            try {
                if (enable) {
                    cl.enableReporting(rcb, report ->
                        SwingUtilities.invokeLater(() -> handleReportReceived(report)));
                    SwingUtilities.invokeLater(() -> {
                        reportsTableModel.setValueAt("Habilitado", tableRow, 5);
                        log.accept("Report habilitado: " + rcbName);
                    });
                } else {
                    cl.disableReporting(rcb);
                    SwingUtilities.invokeLater(() -> {
                        reportsTableModel.setValueAt("Deshabilitado", tableRow, 5);
                        log.accept("Report deshabilitado: " + rcbName);
                    });
                }
            } catch (Exception e) {
                SwingUtilities.invokeLater(() ->
                    log.accept("Error " + (enable ? "habilitando" : "deshabilitando")
                        + " report " + rcbName + ": " + e.getMessage()));
            }
        });
    }

    private void handleReportReceived(Report report) {
        try {
            String timestamp = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
            String rcbRef = report.getRptId();
            String seqNum = report.getSqNum() != null ? String.valueOf(report.getSqNum()) : "";

            List<FcModelNode> values = report.getValues();
            List<BdaReasonForInclusion> reasons = report.getReasonCodes();

            if (values != null) {
                for (int i = 0; i < values.size(); i++) {
                    FcModelNode node = values.get(i);
                    String ref = node.getReference().toString();
                    String newValue = "";
                    if (node instanceof BasicDataAttribute) {
                        newValue = ((BasicDataAttribute) node).getValueString();
                    }

                    String prevValue = previousReportValues.getOrDefault(ref, "");

                    String reason = "";
                    if (reasons != null && i < reasons.size()) {
                        BdaReasonForInclusion r = reasons.get(i);
                        if (r.isDataChange())           reason = "dchg";
                        else if (r.isQualityChange())   reason = "qchg";
                        else if (r.isDataUpdate())      reason = "dupd";
                        else if (r.isIntegrity())       reason = "intg";
                        else if (r.isGeneralInterrogation()) reason = "gi";
                    }

                    reportDataTableModel.insertRow(0, new Object[]{
                        timestamp, rcbRef, seqNum, ref, prevValue, newValue, reason
                    });
                    previousReportValues.put(ref, newValue);
                    onNodeUpdate.accept(ref);
                }
            }
            log.accept("Report recibido: " + rcbRef + " (SeqNum: " + seqNum
                + ", " + (values != null ? values.size() : 0) + " valores)");
        } catch (Exception e) {
            log.accept("Error procesando report: " + e.getMessage());
        }
    }
}
