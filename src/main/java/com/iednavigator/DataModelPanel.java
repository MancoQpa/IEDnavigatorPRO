package com.iednavigator;

import com.beanit.iec61850bean.*;
import javax.swing.*;
import javax.swing.table.*;
import javax.swing.tree.*;
import java.awt.*;
import java.io.File;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Panel de Data Model (árbol detallado del modelo IEC 61850).
 * Extraído de IEDNavigatorApp.java — Fase 4 de refactorización.
 */
class DataModelPanel {

    private final Component parent;
    private final Consumer<String> log;
    private final Supplier<ServerModel> modelSupplier;
    private final Map<String, Icon> iconCache;

    private JTree dataModelTree;
    private DefaultMutableTreeNode dataModelRootNode;
    private DefaultTreeModel dataModelTreeModel;
    private JTable dataModelAttrTable;
    private DefaultTableModel dataModelAttrTableModel;

    DataModelPanel(Component parent, Consumer<String> log,
                   Supplier<ServerModel> modelSupplier,
                   Map<String, Icon> iconCache) {
        this.parent = parent;
        this.log = log;
        this.modelSupplier = modelSupplier;
        this.iconCache = iconCache;
    }

    JPanel createPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        JButton btnRefreshDM = new JButton("Actualizar");
        btnRefreshDM.addActionListener(e -> refreshDataModel());
        toolbar.add(btnRefreshDM);
        toolbar.addSeparator();
        JButton btnExpandAll = new JButton("Expandir Todo");
        btnExpandAll.addActionListener(e -> expandAllDataModel());
        toolbar.add(btnExpandAll);
        JButton btnCollapseAll = new JButton("Colapsar Todo");
        btnCollapseAll.addActionListener(e -> collapseAllDataModel());
        toolbar.add(btnCollapseAll);
        toolbar.addSeparator();
        JButton btnExportXML = new JButton("Exportar XML");
        btnExportXML.addActionListener(e -> exportDataModelToXML());
        toolbar.add(btnExportXML);
        panel.add(toolbar, BorderLayout.NORTH);

        dataModelRootNode = new DefaultMutableTreeNode("Data Model");
        dataModelTreeModel = new DefaultTreeModel(dataModelRootNode);
        dataModelTree = new JTree(dataModelTreeModel);
        dataModelTree.setRowHeight(20);
        dataModelTree.setCellRenderer(new DataModelTreeCellRenderer(iconCache));
        dataModelTree.addTreeSelectionListener(e -> showDataModelAttributes());
        JScrollPane treeScroll = new JScrollPane(dataModelTree);
        treeScroll.setBorder(BorderFactory.createTitledBorder("Estructura del Modelo"));

        String[] attrColumns = {"Propiedad", "Valor", "Tipo"};
        dataModelAttrTableModel = new DefaultTableModel(attrColumns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        dataModelAttrTable = new JTable(dataModelAttrTableModel);
        JScrollPane attrScroll = new JScrollPane(dataModelAttrTable);
        attrScroll.setBorder(BorderFactory.createTitledBorder("Propiedades"));

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeScroll, attrScroll);
        splitPane.setDividerLocation(400);
        splitPane.setResizeWeight(0.6);
        panel.add(splitPane, BorderLayout.CENTER);

        return panel;
    }

    private void refreshDataModel() {
        dataModelRootNode.removeAllChildren();

        ServerModel model = modelSupplier.get();
        if (model == null) {
            log.accept("No hay modelo cargado para Data Model");
            dataModelTreeModel.reload();
            return;
        }

        dataModelRootNode.setUserObject("Data Model - " + ModelTreeBuilder.countNodes(model) + " nodos");

        for (ModelNode ld : model.getChildren()) {
            DefaultMutableTreeNode ldNode = new DefaultMutableTreeNode(new DataModelNodeInfo(ld, "LD"));
            dataModelRootNode.add(ldNode);
            buildDataModelTree(ldNode, ld);
        }

        dataModelTreeModel.reload();
        dataModelTree.expandRow(0);
        log.accept("Data Model actualizado");
    }

    private void buildDataModelTree(DefaultMutableTreeNode parentNode, ModelNode modelNode) {
        for (ModelNode child : modelNode.getChildren()) {
            String type = getDataModelNodeType(child);
            DefaultMutableTreeNode childNode = new DefaultMutableTreeNode(new DataModelNodeInfo(child, type));
            parentNode.add(childNode);
            if (!(child instanceof BasicDataAttribute)) {
                buildDataModelTree(childNode, child);
            }
        }
    }

    private String getDataModelNodeType(ModelNode node) {
        if (node instanceof LogicalDevice)           return "LD";
        if (node instanceof LogicalNode)             return "LN";
        if (node instanceof FcDataObject)            return "DO";
        if (node instanceof ConstructedDataAttribute) return "DA";
        if (node instanceof BasicDataAttribute)      return "BDA";
        return "NODE";
    }

    private void showDataModelAttributes() {
        dataModelAttrTableModel.setRowCount(0);

        TreePath path = dataModelTree.getSelectionPath();
        if (path == null) return;

        DefaultMutableTreeNode treeNode = (DefaultMutableTreeNode) path.getLastPathComponent();
        Object userObj = treeNode.getUserObject();

        if (userObj instanceof DataModelNodeInfo) {
            DataModelNodeInfo info = (DataModelNodeInfo) userObj;
            ModelNode node = info.node;

            dataModelAttrTableModel.addRow(new Object[]{"Nombre",     node.getName(),                   "String"});
            dataModelAttrTableModel.addRow(new Object[]{"Referencia", node.getReference().toString(),   "ObjectReference"});
            dataModelAttrTableModel.addRow(new Object[]{"Tipo",       info.type,                        "String"});

            if (node instanceof FcModelNode) {
                FcModelNode fcNode = (FcModelNode) node;
                dataModelAttrTableModel.addRow(new Object[]{"Functional Constraint", fcNode.getFc().toString(), "Fc"});
            }

            if (node instanceof BasicDataAttribute) {
                BasicDataAttribute bda = (BasicDataAttribute) node;
                dataModelAttrTableModel.addRow(new Object[]{"Valor",     bda.getValueString(),              bda.getClass().getSimpleName()});
                dataModelAttrTableModel.addRow(new Object[]{"Clase BDA", bda.getClass().getSimpleName(),   "Class"});
            }

            dataModelAttrTableModel.addRow(new Object[]{"Hijos", node.getChildren().size(), "int"});
        }
    }

    private void expandAllDataModel() {
        for (int i = 0; i < dataModelTree.getRowCount(); i++) {
            dataModelTree.expandRow(i);
        }
    }

    private void collapseAllDataModel() {
        for (int i = dataModelTree.getRowCount() - 1; i > 0; i--) {
            dataModelTree.collapseRow(i);
        }
    }

    private void exportDataModelToXML() {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("datamodel_export.xml"));
        if (fc.showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) {
            File file = fc.getSelectedFile();
            log.accept("Exportando Data Model a: " + file.getName());
            JOptionPane.showMessageDialog(parent,
                "Data Model exportado a:\n" + file.getAbsolutePath()
                + "\n(Funcionalidad en desarrollo)",
                "Exportar XML", JOptionPane.INFORMATION_MESSAGE);
        }
    }
}
