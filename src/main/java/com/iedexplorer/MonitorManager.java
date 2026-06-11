package com.iedexplorer;

import com.beanit.iec61850bean.*;
import javax.swing.*;
import javax.swing.table.*;
import javax.swing.tree.DefaultMutableTreeNode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Fase 12: Gestión de la tabla de monitor de valores IEC 61850.
 * Extraído de IEDExplorerApp.java — sección MONITOR OPERATIONS.
 */
class MonitorManager {

    interface Context {
        void log(String msg);
        Map<String, MonitorItem> getMonitorItems();
        Set<String> getWatchlist();
        JTree getModelTree();
        JTable getMonitorTable();
        DefaultTableModel getMonitorTableModel();
        JComboBox<String> getMonitorFcFilter();
        JTextField getMonitorNameFilter();
        TableRowSorter<DefaultTableModel> getMonitorSorter();
        JLabel getMonitorCountLabel();
        String formatEnumValue(ModelNode node, String rawValue);
        void updateWatchlistLabel();
        ServerModel getServerModel();
    }

    private final Context ctx;

    MonitorManager(Context ctx) {
        this.ctx = ctx;
    }

    void addNodeToMonitor(DefaultMutableTreeNode treeNode) {
        Object userObj = treeNode.getUserObject();
        if (userObj instanceof NodeInfo) {
            NodeInfo info = (NodeInfo) userObj;
            if (info.node instanceof FcModelNode) {
                FcModelNode fcNode = (FcModelNode) info.node;
                String ref = info.node.getReference().toString();
                Fc fc = fcNode.getFc();
                String fullRef = ref + "$" + fc.toString();

                if (!ctx.getMonitorItems().containsKey(fullRef)) {
                    String type = "";
                    if (info.node instanceof BasicDataAttribute) {
                        type = info.node.getClass().getSimpleName().replace("Bda", "");
                    } else {
                        type = info.node.getClass().getSimpleName()
                            .replace("FcDataObject", "DO")
                            .replace("ConstructedDataAttribute", "SDO");
                    }

                    String displayName = formatReference(ref);
                    MonitorItem item = new MonitorItem(ref, displayName, fc.toString(), type, fcNode);
                    if (info.node instanceof BasicDataAttribute) {
                        item.value = ctx.formatEnumValue(info.node, ((BasicDataAttribute) info.node).getValueString());
                        if (item.value == null) item.value = "";
                    }
                    ctx.getMonitorItems().put(fullRef, item);
                    ctx.getWatchlist().add(fullRef);
                    ctx.updateWatchlistLabel();
                    refreshMonitorTable();
                    ctx.log("Monitor: + " + displayName + " [" + fc + "]");
                }
            }
        }
        // Agregar hijos también (si es un DO) — solo BasicDataAttributes
        for (int i = 0; i < treeNode.getChildCount(); i++) {
            addNodeToMonitor((DefaultMutableTreeNode) treeNode.getChildAt(i));
        }
        ctx.getModelTree().repaint();
    }

    /** Formatea referencia para mostrar de forma legible.
     *  Entrada: "TEMPLATEControl/XCBR1.Pos.stVal" → "XCBR1/Pos/stVal" */
    static String formatReference(String ref) {
        if (ref == null) return "";
        int slashIdx = ref.indexOf('/');
        if (slashIdx > 0) {
            return ref.substring(slashIdx + 1).replace(".", "/");
        }
        return ref.replace(".", "/");
    }

    void removeSelectedFromMonitor() {
        int[] rows = ctx.getMonitorTable().getSelectedRows();
        if (rows.length == 0) return;

        List<String> keysToRemove = new ArrayList<>();
        List<MonitorItem> itemsList = new ArrayList<>(ctx.getMonitorItems().values());

        for (int row : rows) {
            if (row >= 0 && row < itemsList.size()) {
                MonitorItem item = itemsList.get(row);
                keysToRemove.add(item.reference + "$" + item.fc);
            }
        }

        for (String key : keysToRemove) {
            ctx.getMonitorItems().remove(key);
            ctx.getWatchlist().remove(key);
        }

        ctx.updateWatchlistLabel();
        refreshMonitorTable();
        ctx.getModelTree().repaint();
    }

    void refreshMonitorTable() {
        ctx.getMonitorTableModel().setRowCount(0);
        for (MonitorItem item : ctx.getMonitorItems().values()) {
            String status = "";
            if (item.lastChangeTime > 0) {
                long ago = System.currentTimeMillis() - item.lastChangeTime;
                if (ago < 5000) status = "CHANGED";
            }
            ctx.getMonitorTableModel().addRow(new Object[]{
                item.name, item.fc, item.type, item.value, status
            });
        }
        if (ctx.getMonitorCountLabel() != null) {
            ctx.getMonitorCountLabel().setText(" Items: " + ctx.getMonitorItems().size());
        }
        applyMonitorFilter();
    }

    /** Aplica filtro de FC y nombre. Actualiza el contador con visibles/total. */
    void applyMonitorFilter() {
        TableRowSorter<DefaultTableModel> sorter = ctx.getMonitorSorter();
        if (sorter == null) return;

        JComboBox<String> fcBox = ctx.getMonitorFcFilter();
        JTextField nameBox = ctx.getMonitorNameFilter();

        String fc   = fcBox   != null ? (String) fcBox.getSelectedItem()           : "Todos";
        String name = nameBox != null ? nameBox.getText().trim().toLowerCase()      : "";

        boolean fcAll   = fc == null || fc.equals("Todos");
        boolean nameAll = name.isEmpty();

        if (fcAll && nameAll) {
            sorter.setRowFilter(null);
        } else {
            final String fcFinal = fc;
            List<RowFilter<Object, Object>> filters = Arrays.asList(
                fcAll   ? null : RowFilter.regexFilter("(?i)^" + fcFinal + "$", 1),
                nameAll ? null : RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(name), 0)
            ).stream().filter(f -> f != null).collect(Collectors.toList());
            sorter.setRowFilter(RowFilter.andFilter(filters));
        }

        JLabel lbl = ctx.getMonitorCountLabel();
        if (lbl != null) {
            int visible = ctx.getMonitorTable().getRowCount();
            int total   = ctx.getMonitorItems().size();
            lbl.setText(visible == total
                ? " Items: " + total
                : " Items: " + total + "  (visible: " + visible + ")");
        }
    }

    // ── Watchlist operations ──────────────────────────────────────────────────────────

    void addSelectedToWatchlist() {
        javax.swing.tree.TreePath path = ctx.getModelTree().getSelectionPath();
        if (path == null) return;
        addNodeToWatchlist((DefaultMutableTreeNode) path.getLastPathComponent());
        ctx.updateWatchlistLabel();
        ctx.getModelTree().repaint();
    }

    void addNodeToWatchlist(DefaultMutableTreeNode treeNode) {
        Object userObj = treeNode.getUserObject();
        if (userObj instanceof NodeInfo) {
            NodeInfo info = (NodeInfo) userObj;
            if (info.node instanceof FcModelNode) {
                String ref    = info.node.getReference().toString();
                Fc     fc     = ((FcModelNode) info.node).getFc();
                String fullRef = ref + "$" + fc.toString();
                if (ctx.getWatchlist().add(fullRef)) {
                    ctx.log("Agregado a watchlist: " + info.name + " [" + fc + "]");
                }
            }
        }
        for (int i = 0; i < treeNode.getChildCount(); i++) {
            addNodeToWatchlist((DefaultMutableTreeNode) treeNode.getChildAt(i));
        }
    }

    void removeSelectedFromWatchlist() {
        javax.swing.tree.TreePath path = ctx.getModelTree().getSelectionPath();
        if (path == null) return;
        removeNodeFromWatchlist((DefaultMutableTreeNode) path.getLastPathComponent());
        ctx.updateWatchlistLabel();
        ctx.getModelTree().repaint();
    }

    void removeNodeFromWatchlist(DefaultMutableTreeNode treeNode) {
        Object userObj = treeNode.getUserObject();
        if (userObj instanceof NodeInfo) {
            NodeInfo info = (NodeInfo) userObj;
            if (info.node instanceof FcModelNode) {
                String ref    = info.node.getReference().toString();
                Fc     fc     = ((FcModelNode) info.node).getFc();
                String fullRef = ref + "$" + fc.toString();
                if (ctx.getWatchlist().remove(fullRef)) {
                    ctx.log("Quitado de watchlist: " + info.name);
                }
            }
        }
        for (int i = 0; i < treeNode.getChildCount(); i++) {
            removeNodeFromWatchlist((DefaultMutableTreeNode) treeNode.getChildAt(i));
        }
    }

    /** Actualiza el monitor en modo Servidor consultando el ServerModel directamente. */
    void updateServerMonitorValues() {
        ServerModel model = ctx.getServerModel();
        if (model == null) return;

        int row = 0;
        for (MonitorItem item : ctx.getMonitorItems().values()) {
            if (row >= ctx.getMonitorTableModel().getRowCount()) break;
            try {
                Fc fc = Fc.valueOf(item.fc);
                ModelNode node = model.findModelNode(item.reference, fc);
                if (node instanceof BasicDataAttribute) {
                    BasicDataAttribute bda = (BasicDataAttribute) node;
                    String newVal = ctx.formatEnumValue(node, bda.getValueString());
                    if (newVal == null) newVal = "";
                    if (!newVal.equals(item.value)) {
                        item.oldValue = item.value;
                        item.value = newVal;
                        item.lastChangeTime = System.currentTimeMillis();
                        ctx.getMonitorTableModel().setValueAt(newVal,    row, 3);
                        ctx.getMonitorTableModel().setValueAt("CHANGED", row, 4);
                    }
                }
            } catch (Exception ignore) {}
            row++;
        }
        ctx.getMonitorTable().repaint();
    }

    void updateMonitorValues() {
        int row = 0;
        for (MonitorItem item : ctx.getMonitorItems().values()) {
            if (row >= ctx.getMonitorTableModel().getRowCount()) break;

            if (item.node instanceof BasicDataAttribute) {
                BasicDataAttribute bda = (BasicDataAttribute) item.node;
                String newVal = bda.getValueString();
                if (newVal == null) newVal = "";

                if (!newVal.equals(item.value)) {
                    item.oldValue = item.value;
                    item.value = newVal;
                    item.lastChangeTime = System.currentTimeMillis();

                    ctx.getMonitorTableModel().setValueAt(item.name,  row, 0);
                    ctx.getMonitorTableModel().setValueAt(item.fc,    row, 1);
                    ctx.getMonitorTableModel().setValueAt(item.type,  row, 2);
                    ctx.getMonitorTableModel().setValueAt(newVal,     row, 3);
                    ctx.getMonitorTableModel().setValueAt("CHANGED",  row, 4);

                    ctx.log("CAMBIO: " + item.name + " = " + newVal);
                } else {
                    long ago = System.currentTimeMillis() - item.lastChangeTime;
                    if (ago > 3000 && item.lastChangeTime > 0) {
                        ctx.getMonitorTableModel().setValueAt("", row, 4);
                    }
                }
            }
            row++;
        }
        ctx.getMonitorTable().repaint();
    }

    // F24: moved from IEDExplorerApp.java
    void clearMonitor() {
        ctx.getMonitorItems().clear();
        ctx.getWatchlist().clear();
        ctx.updateWatchlistLabel();
        refreshMonitorTable();
        ctx.getModelTree().repaint();
        ctx.log("Monitor limpiado");
    }

    void clearWatchlist() {
        ctx.getWatchlist().clear();
        ctx.getMonitorItems().clear();
        ctx.updateWatchlistLabel();
        refreshMonitorTable();
        ctx.log("Watchlist limpiada");
        ctx.getModelTree().repaint();
    }
}
