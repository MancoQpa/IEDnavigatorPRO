package com.iedexplorer;

import com.beanit.iec61850bean.*;
import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Panel de Setting Groups (SGCB) del cliente IEC 61850.
 * Extraído de IEDExplorerApp.java — Fase 4 de refactorización.
 */
class SettingGroupsPanel {

    private final Component parent;
    private final Consumer<String> log;
    private final Supplier<ServerModel> modelSupplier;
    private final Supplier<IEC61850Client> clientSupplier;
    private final ExecutorService backgroundExecutor;

    private JTable settingGroupsTable;
    private DefaultTableModel settingGroupsTableModel;
    private JTable sgValuesTable;
    private DefaultTableModel sgValuesTableModel;

    SettingGroupsPanel(Component parent, Consumer<String> log,
                       Supplier<ServerModel> modelSupplier,
                       Supplier<IEC61850Client> clientSupplier,
                       ExecutorService backgroundExecutor) {
        this.parent = parent;
        this.log = log;
        this.modelSupplier = modelSupplier;
        this.clientSupplier = clientSupplier;
        this.backgroundExecutor = backgroundExecutor;
    }

    JPanel createPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        JButton btnRefreshSG = new JButton("Actualizar");
        btnRefreshSG.addActionListener(e -> refreshSettingGroups());
        toolbar.add(btnRefreshSG);
        toolbar.addSeparator();
        JButton btnActivateSG = new JButton("Activar Grupo");
        btnActivateSG.addActionListener(e -> activateSelectedSettingGroup());
        toolbar.add(btnActivateSG);
        JButton btnEditSG = new JButton("Editar Grupo");
        btnEditSG.addActionListener(e -> editSelectedSettingGroup());
        toolbar.add(btnEditSG);
        panel.add(toolbar, BorderLayout.NORTH);

        String[] sgColumns = {"Nombre", "Grupo Activo", "Grupo Editable", "Tipo", "Referencia"};
        settingGroupsTableModel = new DefaultTableModel(sgColumns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        settingGroupsTable = new JTable(settingGroupsTableModel);
        settingGroupsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        settingGroupsTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showSettingGroupValues();
        });
        JScrollPane sgScroll = new JScrollPane(settingGroupsTable);
        sgScroll.setBorder(BorderFactory.createTitledBorder("Setting Groups (SGCB)"));

        String[] valColumns = {"Atributo", "Valor", "FC", "Tipo"};
        sgValuesTableModel = new DefaultTableModel(valColumns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        sgValuesTable = new JTable(sgValuesTableModel);
        JScrollPane valScroll = new JScrollPane(sgValuesTable);
        valScroll.setBorder(BorderFactory.createTitledBorder("Valores del Setting Group"));

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, sgScroll, valScroll);
        splitPane.setDividerLocation(200);
        splitPane.setResizeWeight(0.4);
        panel.add(splitPane, BorderLayout.CENTER);

        return panel;
    }

    private void refreshSettingGroups() {
        settingGroupsTableModel.setRowCount(0);

        ServerModel model = modelSupplier.get();
        if (model == null) {
            log.accept("No hay modelo cargado para Setting Groups");
            return;
        }

        for (ModelNode ld : model.getChildren()) {
            for (ModelNode ln : ld.getChildren()) {
                if (ln instanceof LogicalNode) {
                    findSettingGroups((LogicalNode) ln, ld.getName() + "/" + ln.getName());
                }
            }
        }
        log.accept("Setting Groups: " + settingGroupsTableModel.getRowCount() + " nodos encontrados");

        IEC61850Client cl = clientSupplier.get();
        if (cl != null) {
            backgroundExecutor.submit(() -> {
                for (ModelNode ld : model.getChildren()) {
                    String ldName = ld.getName();
                    int[] vals = cl.readSGCBValues(ldName);
                    if (vals != null) {
                        final int actSg  = vals[0];
                        final int numSgs = vals[1];
                        SwingUtilities.invokeLater(() -> {
                            log.accept(String.format("[SGCB] %s → actSG=%d, numOfSGs=%d", ldName, actSg, numSgs));
                            for (int r = 0; r < settingGroupsTableModel.getRowCount(); r++) {
                                String ref = (String) settingGroupsTableModel.getValueAt(r, 0);
                                if (ref != null && ref.startsWith(ldName + "/")) {
                                    settingGroupsTableModel.setValueAt(String.valueOf(actSg),  r, 1);
                                    settingGroupsTableModel.setValueAt(String.valueOf(numSgs), r, 2);
                                }
                            }
                        });
                    }
                }
            });
        }
    }

    private void findSettingGroups(LogicalNode ln, String path) {
        for (ModelNode child : ln.getChildren()) {
            if (child instanceof FcDataObject) {
                FcDataObject fcdo = (FcDataObject) child;
                Fc fc = fcdo.getFc();
                if (fc == Fc.SE || fc == Fc.SG || fc == Fc.SP) {
                    settingGroupsTableModel.addRow(new Object[]{
                        path + "." + child.getName(),
                        "1",
                        "1",
                        fc.toString(),
                        child.getReference().toString()
                    });
                }
            }
        }
    }

    private void showSettingGroupValues() {
        sgValuesTableModel.setRowCount(0);
        int row = settingGroupsTable.getSelectedRow();
        if (row < 0) return;

        String ref = (String) settingGroupsTableModel.getValueAt(row, 4);
        ServerModel model = modelSupplier.get();
        if (model == null) return;

        FcModelNode node = (FcModelNode) model.findModelNode(ref, null);
        if (node != null) addSettingGroupValues(node, "");
    }

    private void addSettingGroupValues(ModelNode node, String prefix) {
        if (node instanceof BasicDataAttribute) {
            BasicDataAttribute bda = (BasicDataAttribute) node;
            sgValuesTableModel.addRow(new Object[]{
                prefix + node.getName(),
                bda.getValueString(),
                bda.getFc().toString(),
                bda.getClass().getSimpleName().replace("Bda", "")
            });
        }
        for (ModelNode child : node.getChildren()) {
            addSettingGroupValues(child, prefix + node.getName() + ".");
        }
    }

    private void activateSelectedSettingGroup() {
        int row = settingGroupsTable.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(parent, "Seleccione un Setting Group");
            return;
        }

        IEC61850Client cl = clientSupplier.get();
        if (cl == null) {
            JOptionPane.showMessageDialog(parent,
                "Activar grupo disponible solo en modo Cliente conectado.",
                "No disponible", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String ref    = (String) settingGroupsTableModel.getValueAt(row, 0);
        String numStr = (String) settingGroupsTableModel.getValueAt(row, 2);
        String ldName = ref.contains("/") ? ref.split("/")[0] : "LD0";

        int numSgs = 1;
        try { numSgs = Integer.parseInt(numStr); } catch (Exception ignored) {}

        String[] opciones = new String[numSgs];
        for (int i = 0; i < numSgs; i++) opciones[i] = "Grupo " + (i + 1);

        String sel = (String) JOptionPane.showInputDialog(parent,
            "Seleccione el grupo de ajuste a activar en " + ldName + ":\n\n"
            + "⚠  ATENCIÓN: esto modifica la protección activa en tiempo real.",
            "Activar Setting Group", JOptionPane.WARNING_MESSAGE,
            null, opciones, opciones[0]);

        if (sel == null) return;

        int groupNum = Integer.parseInt(sel.replace("Grupo ", ""));

        int confirm = JOptionPane.showConfirmDialog(parent,
            "¿Confirma activar el " + sel + " en " + ldName + "?\n"
            + "Esta acción cambia la configuración de protección activa.",
            "Confirmar cambio de grupo",
            JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

        if (confirm != JOptionPane.YES_OPTION) return;

        final String finalLd = ldName;
        final int    finalGn = groupNum;
        backgroundExecutor.submit(() -> {
            try {
                cl.selectActiveSG(finalLd, finalGn);
                SwingUtilities.invokeLater(() -> {
                    log.accept("[SGCB] Grupo " + finalGn + " activado en " + finalLd);
                    JOptionPane.showMessageDialog(parent,
                        "Grupo " + finalGn + " activado en " + finalLd,
                        "OK", JOptionPane.INFORMATION_MESSAGE);
                    refreshSettingGroups();
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    log.accept("[ERROR SGCB] " + ex.getMessage());
                    JOptionPane.showMessageDialog(parent,
                        "Error al activar grupo: " + ex.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
                });
            }
        });
    }

    private void editSelectedSettingGroup() {
        int row = settingGroupsTable.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(parent, "Seleccione un Setting Group");
            return;
        }
        String ref = (String) settingGroupsTableModel.getValueAt(row, 0);
        String fc  = (String) settingGroupsTableModel.getValueAt(row, 3);
        JOptionPane.showMessageDialog(parent,
            "Edición de ajustes (FC=" + fc + ") para:\n" + ref
            + "\n\nUse la pestaña 'Data Model' o el árbol del modelo\n"
            + "para modificar valores individuales con doble clic.",
            "Editar Setting Group", JOptionPane.INFORMATION_MESSAGE);
    }
}
