package com.iedexplorer;

import com.beanit.iec61850bean.*;
import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Panel de Datasets del modelo IEC 61850.
 * Extraído de IEDExplorerApp.java — Fase 4 de refactorización.
 */
class DatasetPanel {

    private final Component parent;
    private final Consumer<String> log;
    private final Supplier<ServerModel> modelSupplier;

    private JTable datasetTable;
    private DefaultTableModel datasetTableModel;
    private JTable datasetMembersTable;
    private DefaultTableModel datasetMembersTableModel;

    DatasetPanel(Component parent, Consumer<String> log, Supplier<ServerModel> modelSupplier) {
        this.parent = parent;
        this.log = log;
        this.modelSupplier = modelSupplier;
    }

    JPanel createPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        JButton btnRefreshDS = new JButton("Actualizar");
        btnRefreshDS.addActionListener(e -> refreshDatasets());
        toolbar.add(btnRefreshDS);
        toolbar.addSeparator();
        JButton btnCreateDS = new JButton("Crear Dataset");
        btnCreateDS.addActionListener(e -> createNewDataset());
        toolbar.add(btnCreateDS);
        JButton btnDeleteDS = new JButton("Eliminar Dataset");
        btnDeleteDS.addActionListener(e -> deleteSelectedDataset());
        toolbar.add(btnDeleteDS);
        panel.add(toolbar, BorderLayout.NORTH);

        String[] dsColumns = {"Nombre", "Miembros", "Referencia", "Deletable"};
        datasetTableModel = new DefaultTableModel(dsColumns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        datasetTable = new JTable(datasetTableModel);
        datasetTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        datasetTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showDatasetMembers();
        });
        JScrollPane dsScroll = new JScrollPane(datasetTable);
        dsScroll.setBorder(BorderFactory.createTitledBorder("Datasets"));

        String[] memberColumns = {"#", "Referencia", "FC", "Valor"};
        datasetMembersTableModel = new DefaultTableModel(memberColumns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        datasetMembersTable = new JTable(datasetMembersTableModel);
        JScrollPane memberScroll = new JScrollPane(datasetMembersTable);
        memberScroll.setBorder(BorderFactory.createTitledBorder("Miembros del Dataset"));

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, dsScroll, memberScroll);
        splitPane.setDividerLocation(200);
        splitPane.setResizeWeight(0.4);
        panel.add(splitPane, BorderLayout.CENTER);

        return panel;
    }

    private void refreshDatasets() {
        datasetTableModel.setRowCount(0);

        ServerModel model = modelSupplier.get();
        if (model == null) {
            log.accept("No hay modelo cargado para Datasets");
            return;
        }

        Collection<DataSet> datasets = model.getDataSets();
        if (datasets != null && !datasets.isEmpty()) {
            for (DataSet ds : datasets) {
                int memberCount = ds.getMembers() != null ? ds.getMembers().size() : 0;
                datasetTableModel.addRow(new Object[]{
                    ds.getReferenceStr(),
                    memberCount,
                    ds.getReferenceStr(),
                    ds.isDeletable() ? "Si" : "No"
                });
            }
            log.accept("Datasets encontrados: " + datasetTableModel.getRowCount());
        } else {
            log.accept("Buscando datasets en LLN0...");
            for (ModelNode ld : model.getChildren()) {
                for (ModelNode ln : ld.getChildren()) {
                    if (ln.getName().equalsIgnoreCase("LLN0")) {
                        for (ModelNode node : ln.getChildren()) {
                            String nodeName = node.getName();
                            if (nodeName.toLowerCase().contains("dataset") ||
                                nodeName.startsWith("DS") ||
                                nodeName.startsWith("ds")) {
                                String ref = ld.getName() + "/" + ln.getName() + "." + nodeName;
                                int memberCount = node.getChildren() != null ? node.getChildren().size() : 0;
                                datasetTableModel.addRow(new Object[]{
                                    ref, memberCount, ref, "No"
                                });
                            }
                        }
                    }
                }
            }
            log.accept("Datasets encontrados (busqueda manual): " + datasetTableModel.getRowCount());
        }
    }

    private void showDatasetMembers() {
        datasetMembersTableModel.setRowCount(0);
        int row = datasetTable.getSelectedRow();
        if (row < 0) return;

        String dsRef = (String) datasetTableModel.getValueAt(row, 2);
        ServerModel model = modelSupplier.get();
        if (model == null) return;

        for (DataSet ds : model.getDataSets()) {
            if (ds.getReferenceStr().equals(dsRef)) {
                List<FcModelNode> members = ds.getMembers();
                if (members != null) {
                    int idx = 1;
                    for (FcModelNode member : members) {
                        String value = "";
                        if (member instanceof BasicDataAttribute) {
                            value = ((BasicDataAttribute) member).getValueString();
                        }
                        datasetMembersTableModel.addRow(new Object[]{
                            idx++,
                            member.getReference().toString(),
                            member.getFc().toString(),
                            value
                        });
                    }
                }
                break;
            }
        }
    }

    private void createNewDataset() {
        String name = JOptionPane.showInputDialog(parent,
            "Nombre del nuevo Dataset:", "Crear Dataset", JOptionPane.PLAIN_MESSAGE);
        if (name != null && !name.isEmpty()) {
            log.accept("Creando Dataset: " + name);
            JOptionPane.showMessageDialog(parent,
                "Dataset creado: " + name + "\n(Funcionalidad en desarrollo)");
            refreshDatasets();
        }
    }

    private void deleteSelectedDataset() {
        int row = datasetTable.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(parent, "Seleccione un Dataset");
            return;
        }
        String name      = (String) datasetTableModel.getValueAt(row, 0);
        String deletable = (String) datasetTableModel.getValueAt(row, 3);

        if (!"Si".equals(deletable)) {
            JOptionPane.showMessageDialog(parent,
                "Este Dataset no puede ser eliminado", "Error", JOptionPane.WARNING_MESSAGE);
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(parent,
            "¿Eliminar Dataset: " + name + "?",
            "Confirmar eliminacion", JOptionPane.YES_NO_OPTION);
        if (confirm == JOptionPane.YES_OPTION) {
            log.accept("Eliminando Dataset: " + name);
            datasetTableModel.removeRow(row);
        }
    }
}
