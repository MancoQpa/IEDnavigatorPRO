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
        /** Una fila quedo resaltada: la ventana arranca el Timer que la desvanece. */
        default void alResaltar() { }
    }

    private final Context ctx;

    MonitorManager(Context ctx) {
        this.ctx = ctx;
    }

    /**
     * Agrega al monitor lo elegido en el árbol, por señales (como la vista de paneles del
     * colaborador, ui46): un DO entra como UNA señal, su variante de estado (ST) o de medida
     * (MX), con su valor principal; un LN o un LD agregan cada uno de sus DO; un atributo suelto
     * entra solo. Antes un DO agregaba además cada atributo por separado.
     */
    void addNodeToMonitor(DefaultMutableTreeNode treeNode) {
        agregar(treeNode, true);
        ctx.getModelTree().repaint();
    }

    private void agregar(DefaultMutableTreeNode treeNode, boolean raiz) {
        Object userObj = treeNode.getUserObject();
        if (userObj instanceof NodeInfo && ((NodeInfo) userObj).node instanceof FcModelNode) {
            ModelNode n = ((NodeInfo) userObj).node;
            if (n instanceof FcDataObject) {
                FcDataObject p = principal((FcDataObject) n);
                if (p != null) agregarSenal(p);
                // Un DO sin estado ni medida (p. ej. solo CF) entra si se lo eligió a él
                else if (raiz) agregarSenal((FcModelNode) n);
            } else {
                agregarSenal((FcModelNode) n);
            }
            return;
        }
        for (int i = 0; i < treeNode.getChildCount(); i++) {
            agregar((DefaultMutableTreeNode) treeNode.getChildAt(i), false);
        }
    }

    /** Variante del DO que es la señal: la de estado (ST) o, si no hay, la de medida (MX). */
    static FcDataObject principal(FcDataObject d) {
        FcDataObject mx = null;
        for (FcDataObject v : TiposIec61850.variantes(d)) {
            if (v.getFc() == Fc.ST) return v;
            if (v.getFc() == Fc.MX && mx == null) mx = v;
        }
        if (mx == null && (d.getFc() == Fc.ST || d.getFc() == Fc.MX)) return d;
        return mx;
    }

    private void agregarSenal(FcModelNode fcNode) {
        String ref = fcNode.getReference().toString();
        Fc fc = fcNode.getFc();
        String fullRef = ref + "$" + fc.toString();
        if (ctx.getMonitorItems().containsKey(fullRef)) return;
        // Tipo de la norma (CDC del DO, tipo básico o construido del DA), no la
        // clase de Java de la biblioteca ("Int8", "FcDataObject").
        String type = TiposIec61850.tipoCorto(fcNode);
        String displayName = formatReference(ref);
        MonitorItem item = new MonitorItem(ref, displayName, fc.toString(), type, fcNode);
        item.value = valorResumen(fcNode);
        ctx.getMonitorItems().put(fullRef, item);
        ctx.getWatchlist().add(fullRef);
        leerAuxiliares(fcNode);
        ctx.updateWatchlistLabel();
        refreshMonitorTable();
        ctx.log(I18n.t("log.monitor.added", displayName, fc));
    }

    /**
     * Las corrientes por fase (A) necesitan el PF de su LN para que la vista de paneles calcule
     * sus ángulos cuando el equipo publica solo magnitudes (ION 7400): se lo lee también, sin
     * tarjeta propia (va a la watchlist, que es lo que lee el sondeo).
     */
    private void leerAuxiliares(FcModelNode n) {
        if (!(n instanceof FcDataObject) || !"A".equals(n.getName())) return;
        ModelNode ln = n.getParent();
        if (!(ln instanceof LogicalNode)) return;
        ModelNode pf = ((LogicalNode) ln).getChild("PF", Fc.MX);
        if (pf == null) return;
        if (ctx.getWatchlist().add(pf.getReference() + "$MX")) {
            ctx.log(I18n.t("log.monitor.auxpf", formatReference(pf.getReference().toString())));
        }
    }

    /**
     * Valor de una señal para la tabla: el del atributo, o el principal del DO (stVal, general,
     * la medida, o las tres fases de un WYE/DEL).
     */
    String valorResumen(ModelNode n) {
        if (n instanceof BasicDataAttribute) {
            String v = ctx.formatEnumValue(n, ValorBda.texto((BasicDataAttribute) n));
            return v == null ? "" : v;
        }
        if (n == null) return "";
        if (MonitorPaneles.buscar(n, "phsA") != null || MonitorPaneles.buscar(n, "phsAB") != null) {
            String[] fases = MonitorPaneles.buscar(n, "phsA") != null
                    ? new String[]{"phsA", "phsB", "phsC"} : new String[]{"phsAB", "phsBC", "phsCA"};
            StringBuilder sb = new StringBuilder();
            for (String f : fases) {
                ModelNode cv = MonitorPaneles.buscar(n, f, "cVal");
                Double m = cv == null ? null : MonitorPaneles.analogico(cv.getChild("mag"));
                if (m == null) continue;
                if (sb.length() > 0) sb.append("  ");
                sb.append(f.substring(3)).append(' ').append(MonitorPaneles.formato(m));
            }
            return sb.toString();
        }
        for (String c : new String[]{"stVal", "general"}) {
            ModelNode h = MonitorPaneles.buscar(n, c);
            if (h instanceof BasicDataAttribute) return valorResumen(h);
        }
        Double d = MonitorPaneles.analogicoDe(n);
        return d == null ? "" : MonitorPaneles.formato(d);
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
                if (node instanceof FcModelNode) {
                    String newVal = valorResumen(node);
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

            if (item.node != null) {
                String newVal = item.node instanceof BasicDataAttribute
                        ? ValorBda.texto((BasicDataAttribute) item.node) : valorResumen(item.node);
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
        ctx.alResaltar();
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
