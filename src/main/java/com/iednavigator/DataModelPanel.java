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
        JButton btnRefreshDM = new JButton(I18n.t("btn.refresh"));
        btnRefreshDM.addActionListener(e -> refreshDataModel());
        toolbar.add(btnRefreshDM);
        toolbar.addSeparator();
        JButton btnExpandAll = new JButton(I18n.t("tree.expandall"));
        btnExpandAll.addActionListener(e -> expandAllDataModel());
        toolbar.add(btnExpandAll);
        JButton btnCollapseAll = new JButton(I18n.t("tree.collapseall"));
        btnCollapseAll.addActionListener(e -> collapseAllDataModel());
        toolbar.add(btnCollapseAll);
        toolbar.addSeparator();
        JButton btnExportXML = new JButton(I18n.t("tree.exportxml"));
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
        treeScroll.setBorder(BorderFactory.createTitledBorder(I18n.t("tree.structure")));

        String[] attrColumns = {I18n.t("dm.col.prop"), I18n.t("dm.col.value")};
        dataModelAttrTableModel = new DefaultTableModel(attrColumns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        dataModelAttrTable = new JTable(dataModelAttrTableModel);
        JScrollPane attrScroll = new JScrollPane(dataModelAttrTable);
        attrScroll.setBorder(BorderFactory.createTitledBorder(I18n.t("tree.properties")));

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

        dataModelRootNode.setUserObject(I18n.t("datamodel.rootcount", ModelTreeBuilder.countNodes(model)));

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

    /**
     * Elemento según la norma y el SCL: DO (de un LN), SDO (de un DO), DA (atributo de un DO,
     * simple o construido) y BDA (componente de un DA construido, como {@code mag.f}).
     */
    static String getDataModelNodeType(ModelNode node) {
        if (node instanceof LogicalDevice)           return "LD";
        if (node instanceof LogicalNode)             return "LN";
        if (node instanceof FcDataObject) {
            return node.getParent() instanceof FcDataObject ? "SDO" : "DO";
        }
        if (node instanceof ConstructedDataAttribute || node instanceof BasicDataAttribute
                || node instanceof Array) {
            ModelNode padre = node.getParent();
            return padre instanceof FcDataObject || padre == null ? "DA" : "BDA";
        }
        return "NODE";
    }

    private void showDataModelAttributes() {
        dataModelAttrTableModel.setRowCount(0);

        TreePath path = dataModelTree.getSelectionPath();
        if (path == null) return;

        Object userObj = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
        if (!(userObj instanceof DataModelNodeInfo)) return;
        for (String[] fila : propiedades((DataModelNodeInfo) userObj)) {
            dataModelAttrTableModel.addRow(fila);
        }
    }

    /**
     * Propiedades de un nodo en términos de la norma: elemento, tipo (CDC, tipo construido o
     * tipo básico de IEC 61850-7-2/7-3), referencias, FC y valor. Nunca el nombre de la clase
     * de Java de la biblioteca MMS.
     */
    static java.util.List<String[]> propiedades(DataModelNodeInfo info) {
        java.util.List<String[]> f = new java.util.ArrayList<>();
        ModelNode node = info.node;

        f.add(new String[]{I18n.t("dm.prop.name"), node.getName()});
        f.add(new String[]{I18n.t("dm.prop.element"), elemento(info.type)});

        String tipo = TiposIec61850.tipoDe(node);
        if (node instanceof FcDataObject) {
            String cdc = TiposIec61850.cdcDe((FcDataObject) node);
            tipo = cdc == null ? I18n.t("dm.cdc.unknown") : "CDC " + cdc + nombreEnDiccionario(cdc);
        } else if (node instanceof LogicalNode && tipo.startsWith("LN ")) {
            tipo = tipo + nombreEnDiccionario(tipo.substring(3));
        }
        if (!tipo.isEmpty()) f.add(new String[]{I18n.t("dm.prop.type"), tipo});
        if (node instanceof FcDataObject && tipo.startsWith("CDC ")) {
            f.add(new String[]{I18n.t("dm.prop.cdcsrc"), I18n.t("dm.cdc.inferred")});
        }

        f.add(new String[]{I18n.t("dm.prop.ref"), node.getReference().toString()});

        if (node instanceof FcModelNode) {
            FcModelNode fcNode = (FcModelNode) node;
            String fc = String.valueOf(fcNode.getFc());
            f.add(new String[]{I18n.t("dm.prop.mmsref"), referenciaMms(node.getReference().toString(), fc)});
            f.add(new String[]{I18n.t("dm.prop.fc"), fc + nombreEnDiccionario(fc)});
            if (node instanceof FcDataObject) {
                f.add(new String[]{I18n.t("dm.prop.fcs"), TiposIec61850.fcsDe((FcDataObject) node)});
            }
        }

        if (node instanceof BasicDataAttribute) {
            BasicDataAttribute bda = (BasicDataAttribute) node;
            f.add(new String[]{I18n.t("dm.prop.value"), DataModelNodeInfo.valorMostrado(bda, true)});
            String mms = TiposIec61850.bType(bda.getBasicType());
            if (!mms.equals(tipo)) f.add(new String[]{I18n.t("dm.prop.mms"), mms});
        }

        if (!(node instanceof BasicDataAttribute)) {
            f.add(new String[]{I18n.t("dm.prop.children"), String.valueOf(node.getChildren().size())});
        }
        for (String[] fila : f) if (fila[1] == null) fila[1] = "";
        return f;
    }

    private static String elemento(String tipo) {
        switch (tipo) {
            case "LD":  return I18n.t("dm.elem.ld");
            case "LN":  return I18n.t("dm.elem.ln");
            case "DO":  return I18n.t("dm.elem.do");
            case "SDO": return I18n.t("dm.elem.sdo");
            case "DA":  return I18n.t("dm.elem.da");
            case "BDA": return I18n.t("dm.elem.bda");
            default:    return I18n.t("dm.elem.node");
        }
    }

    /** " — Estado (Status)" para un término del diccionario (FC, CDC, clase de LN), o "". */
    private static String nombreEnDiccionario(String termino) {
        Iec61850Dictionary.Entry e = Iec61850Dictionary.lookup(termino);
        if (e == null) return "";
        String n = I18n.currentTag().startsWith("es") ? e.fullNameES : e.fullNameEN;
        return n == null || n.isEmpty() ? "" : " — " + n;
    }

    /** "LD/LN.DO.DA" con FC "ST" → "LD/LN$ST$DO$DA" (el nombre que viaja por MMS). */
    static String referenciaMms(String ref, String fc) {
        int barra = ref.indexOf('/');
        int punto = ref.indexOf('.', barra + 1);
        if (barra < 0 || punto < 0) return ref;
        return ref.substring(0, punto) + "$" + fc + "$" + ref.substring(punto + 1).replace('.', '$');
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
