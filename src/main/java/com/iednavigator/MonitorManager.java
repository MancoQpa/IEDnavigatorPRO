package com.iednavigator;

import com.beanit.iec61850bean.*;
import javax.swing.*;
import javax.swing.table.*;
import javax.swing.tree.DefaultMutableTreeNode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Fase 12: Gestión de la tabla de monitor de valores IEC 61850.
 * Extraído de IEDNavigatorApp.java — sección MONITOR OPERATIONS.
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
                        item.value = ctx.formatEnumValue(info.node, ValorBda.texto((BasicDataAttribute) info.node));
                        if (item.value == null) item.value = "";
                    }
                    ctx.getMonitorItems().put(fullRef, item);
                    ctx.getWatchlist().add(fullRef);
                    ctx.updateWatchlistLabel();
                    refreshMonitorTable();
                    ctx.log(I18n.t("log.monitor.added", displayName, fc));
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

    /**
     * Quita las señales marcadas con la casilla; si no hay ninguna marcada, las seleccionadas.
     * Antes tomaba la fila visible como fila del modelo: con un filtro activo podia quitar
     * otra señal.
     */
    void removeSelectedFromMonitor() {
        JTable t = ctx.getMonitorTable();
        DefaultTableModel m = ctx.getMonitorTableModel();
        List<Integer> filas = new ArrayList<>();
        for (int r = 0; r < m.getRowCount(); r++) {
            if (Boolean.TRUE.equals(m.getValueAt(r, MonitorCeldaRenderer.COL_SEL))) filas.add(r);
        }
        if (filas.isEmpty()) {
            for (int v : t.getSelectedRows()) filas.add(t.convertRowIndexToModel(v));
        }
        if (filas.isEmpty()) return;

        List<String> keysToRemove = new ArrayList<>();
        for (int r : filas) {
            Object o = m.getValueAt(r, MonitorCeldaRenderer.COL_SENAL);
            if (o instanceof MonitorItem) {
                MonitorItem item = (MonitorItem) o;
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

    /** Cantidad de señales marcadas o seleccionadas (para habilitar Quitar). */
    int cantidadParaQuitar() {
        DefaultTableModel m = ctx.getMonitorTableModel();
        int n = 0;
        for (int r = 0; r < m.getRowCount(); r++) {
            if (Boolean.TRUE.equals(m.getValueAt(r, MonitorCeldaRenderer.COL_SEL))) n++;
        }
        return n > 0 ? n : ctx.getMonitorTable().getSelectedRowCount();
    }

    void refreshMonitorTable() {
        DefaultTableModel m = ctx.getMonitorTableModel();
        m.setRowCount(0);
        for (MonitorItem item : ctx.getMonitorItems().values()) {
            item.value = item.value == null ? "" : item.value;
            m.addRow(new Object[]{Boolean.FALSE, item, item.type, item.value, calidadDe(item), hora(item.ultimoCambio)});
        }
        applyMonitorFilter();
    }

    private static final java.text.SimpleDateFormat HORA = new java.text.SimpleDateFormat("HH:mm:ss.SSS");

    static String hora(long ms) {
        synchronized (HORA) { return ms <= 0 ? "" : HORA.format(new Date(ms)); }
    }

    /**
     * Calidad de la señal: la del q del mismo DO (y FC), o la propia si es el q. Vacio para lo
     * que no la tiene (ajustes, descripcion...).
     */
    static String calidadDe(MonitorItem item) {
        if (item == null || item.node == null) return "";
        ModelNode n = item.node;
        BdaQuality q = null;
        if (n instanceof BdaQuality) {
            q = (BdaQuality) n;
        } else {
            ModelNode base = n instanceof BasicDataAttribute ? n.getParent() : n;
            for (int i = 0; i < 3 && base != null && q == null; i++) {
                ModelNode c = base.getChild("q");
                if (c instanceof BdaQuality) q = (BdaQuality) c;
                else base = base.getParent();
            }
        }
        if (q == null) return "";
        try {
            switch (q.getValidity()) {
                case GOOD: return "good";
                case INVALID: return "invalid";
                case QUESTIONABLE: return "questionable";
                default: return "reserved";
            }
        } catch (Exception e) {
            return "";
        }
    }

    /** Aplica filtro de FC y nombre. Actualiza el contador con visibles/total. */
    void applyMonitorFilter() {
        TableRowSorter<DefaultTableModel> sorter = ctx.getMonitorSorter();
        if (sorter == null) return;

        JComboBox<String> fcBox = ctx.getMonitorFcFilter();
        JTextField nameBox = ctx.getMonitorNameFilter();
        String fc   = fcBox   != null ? (String) fcBox.getSelectedItem()      : null;
        String name = nameBox != null ? nameBox.getText().trim().toLowerCase() : "";
        boolean fcAll = fc == null || fc.equals(I18n.t("filter.all"));

        if (fcAll && name.isEmpty()) {
            sorter.setRowFilter(null);
        } else {
            sorter.setRowFilter(new RowFilter<DefaultTableModel, Integer>() {
                @Override public boolean include(Entry<? extends DefaultTableModel, ? extends Integer> e) {
                    Object o = e.getValue(MonitorCeldaRenderer.COL_SENAL);
                    if (!(o instanceof MonitorItem)) return true;
                    MonitorItem it = (MonitorItem) o;
                    if (!fcAll && !fc.equalsIgnoreCase(it.fc)) return false;
                    return name.isEmpty() || (it.reference != null && it.reference.toLowerCase().contains(name));
                }
            });
        }

        JLabel lbl = ctx.getMonitorCountLabel();
        if (lbl != null) {
            int visible = ctx.getMonitorTable().getRowCount();
            int total   = ctx.getMonitorItems().size();
            lbl.setText(visible == total
                ? I18n.t("monitor.signals", total)
                : I18n.t("monitor.signals.visible", visible, total));
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
                    ctx.log(I18n.t("log.watchlist.added", info.name, fc));
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
                    ctx.log(I18n.t("log.watchlist.removed", info.name));
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
                    String newVal = ctx.formatEnumValue(node, ValorBda.texto(bda));
                    if (newVal == null) newVal = "";
                    if (!newVal.equals(item.value)) {
                        item.oldValue = item.value;
                        item.value = newVal;
                        marcarCambio(item, row);
                    }
                    ctx.getMonitorTableModel().setValueAt(calidadDe(item), row, MonitorCeldaRenderer.COL_CALIDAD);
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
                String newVal = ValorBda.texto(bda);
                if (newVal == null) newVal = "";

                if (!newVal.equals(item.value)) {
                    item.oldValue = item.value;
                    item.value = newVal;
                    marcarCambio(item, row);
                    ctx.log(I18n.t("log.value.changed", item.name, newVal));
                }
                ctx.getMonitorTableModel().setValueAt(calidadDe(item), row, MonitorCeldaRenderer.COL_CALIDAD);
            }
            row++;
        }
        ctx.getMonitorTable().repaint();
    }

    /** Valor nuevo: hora del cambio y resaltado ambar que se desvanece (MonitorCeldaRenderer). */
    private void marcarCambio(MonitorItem item, int row) {
        long ahora = System.currentTimeMillis();
        item.lastChangeTime = ahora;
        item.ultimoCambio = ahora;
        item.resaltarDesde = ahora;
        DefaultTableModel m = ctx.getMonitorTableModel();
        m.setValueAt(item.value, row, MonitorCeldaRenderer.COL_VALOR);
        m.setValueAt(hora(ahora), row, MonitorCeldaRenderer.COL_HORA);
    }

    // F24: moved from IEDNavigatorApp.java
    void clearMonitor() {
        ctx.getMonitorItems().clear();
        ctx.getWatchlist().clear();
        ctx.updateWatchlistLabel();
        refreshMonitorTable();
        ctx.getModelTree().repaint();
        ctx.log(I18n.t("log.monitor.cleared"));
    }

    void clearWatchlist() {
        ctx.getWatchlist().clear();
        ctx.getMonitorItems().clear();
        ctx.updateWatchlistLabel();
        refreshMonitorTable();
        ctx.log(I18n.t("log.watchlist.cleared"));
        ctx.getModelTree().repaint();
    }
}
