package com.iednavigator;

import com.beanit.iec61850bean.*;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Pestaña "Ajustes (SP)": tabla editable con todos los atributos FC=SP
 * (setting points de protección) del modelo. Permite refrescar los valores
 * desde el IED y escribir los cambios (con confirmación).
 */
class ProtectionSettingsPanel {

    private static final int COL_REF = 0, COL_TYPE = 1, COL_VALUE = 2, COL_NEW = 3;

    private final Component parent;
    private final Consumer<String> log;
    private final Supplier<ServerModel> modelSupplier;
    private final Supplier<IEC61850Client> clientSupplier;
    private final ExecutorService backgroundExecutor;
    private final Consumer<String> onNavigate;  // ref → navega al nodo en el árbol del modelo

    private DefaultTableModel tableModel;
    private JTable table;
    private TableRowSorter<DefaultTableModel> sorter;
    private JTextField filterField;
    private JLabel lblSummary;
    private final List<BasicDataAttribute> rowBdas = new ArrayList<>();

    ProtectionSettingsPanel(Component parent, Consumer<String> log,
                            Supplier<ServerModel> modelSupplier,
                            Supplier<IEC61850Client> clientSupplier,
                            ExecutorService backgroundExecutor,
                            Consumer<String> onNavigate) {
        this.parent = parent;
        this.log = log;
        this.modelSupplier = modelSupplier;
        this.clientSupplier = clientSupplier;
        this.backgroundExecutor = backgroundExecutor;
        this.onNavigate = onNavigate;
    }

    JPanel createPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JButton btnLoad = new JButton("Cargar ajustes");
        btnLoad.addActionListener(e -> loadSettings());
        JButton btnRead = new JButton("Leer del IED");
        btnRead.addActionListener(e -> readFromIed());
        JButton btnWrite = new JButton("Escribir cambios");
        btnWrite.addActionListener(e -> writeChanges());
        filterField = new JTextField(16);
        filterField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { applyFilter(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { applyFilter(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
        });
        top.add(btnLoad);
        top.add(btnRead);
        top.add(btnWrite);
        top.add(new JLabel("  Filtro:"));
        top.add(filterField);
        panel.add(top, BorderLayout.NORTH);

        tableModel = new DefaultTableModel(
            new String[]{"Referencia", "Tipo", "Valor actual", "Nuevo valor"}, 0) {
            @Override public boolean isCellEditable(int row, int column) { return column == COL_NEW; }
        };
        table = new JTable(tableModel) {
            @Override
            public javax.swing.table.TableCellEditor getCellEditor(int row, int column) {
                if (column == COL_NEW) {
                    int mRow = convertRowIndexToModel(row);
                    if (mRow < rowBdas.size()) {
                        String[] opts = optionsFor(rowBdas.get(mRow));
                        if (opts != null) {
                            JComboBox<String> combo = new JComboBox<>(opts);
                            Object actual = getModel().getValueAt(mRow, COL_VALUE);
                            if (actual != null) combo.setSelectedItem(String.valueOf(actual));
                            return new DefaultCellEditor(combo);
                        }
                    }
                }
                return super.getCellEditor(row, column);
            }
        };
        sorter = new TableRowSorter<>(tableModel);
        table.setRowSorter(sorter);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || onNavigate == null) return;
            int row = table.getSelectedRow();
            if (row < 0) return;
            String ref = (String) tableModel.getValueAt(table.convertRowIndexToModel(row), COL_REF);
            if (ref != null && !ref.isEmpty()) onNavigate.accept(ref);
        });
        table.getColumnModel().getColumn(COL_REF).setPreferredWidth(380);
        table.getColumnModel().getColumn(COL_TYPE).setPreferredWidth(110);
        table.getColumnModel().getColumn(COL_VALUE).setPreferredWidth(140);
        table.getColumnModel().getColumn(COL_NEW).setPreferredWidth(140);

        // Resaltar celdas con cambio pendiente
        table.setDefaultRenderer(Object.class, new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column);
                if (!isSelected) {
                    String nuevo = String.valueOf(t.getValueAt(row, COL_NEW));
                    boolean pending = nuevo != null && !nuevo.isEmpty() && !"null".equals(nuevo)
                        && !nuevo.equals(String.valueOf(t.getValueAt(row, COL_VALUE)));
                    c.setBackground(pending ? new Color(0xFFF3E0) : t.getBackground());
                    c.setForeground(pending && column == COL_NEW ? new Color(0xE65100) : t.getForeground());
                }
                return c;
            }
        });

        panel.add(new JScrollPane(table), BorderLayout.CENTER);

        lblSummary = new JLabel("Pulse 'Cargar ajustes' con un modelo cargado (cliente conectado o servidor)");
        panel.add(lblSummary, BorderLayout.SOUTH);

        return panel;
    }

    // ─── Carga desde el modelo ───

    private void loadSettings() {
        ServerModel model = modelSupplier.get();
        if (model == null) {
            JOptionPane.showMessageDialog(parent,
                "No hay modelo cargado.\nConéctate a un IED o carga un SCL en modo servidor.",
                "Ajustes SP", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (table.isEditing()) table.getCellEditor().stopCellEditing();
        tableModel.setRowCount(0);
        rowBdas.clear();
        for (ModelNode ld : model.getChildren()) {
            for (ModelNode ln : ld.getChildren()) {
                collectSpLeaves(ln);
            }
        }
        lblSummary.setText(rowBdas.size() + " ajustes FC=SP en el modelo");
        log.accept("[AjustesSP] Cargados " + rowBdas.size() + " atributos FC=SP");
        if (rowBdas.isEmpty()) {
            JOptionPane.showMessageDialog(parent,
                "El modelo no contiene atributos con FC=SP.",
                "Ajustes SP", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void collectSpLeaves(ModelNode node) {
        if (node instanceof BasicDataAttribute) {
            BasicDataAttribute bda = (BasicDataAttribute) node;
            if (bda.getFc() == Fc.SP) {
                rowBdas.add(bda);
                tableModel.addRow(new Object[]{
                    bda.getReference().toString(),
                    bda.getBasicType() != null ? bda.getBasicType().toString() : "",
                    valueOf(bda), ""});
            }
            return;
        }
        if (node.getChildren() != null) {
            for (ModelNode child : node.getChildren()) collectSpLeaves(child);
        }
    }

    private String valueOf(BasicDataAttribute bda) {
        IEC61850Client client = clientSupplier.get();
        try {
            if (client != null) return client.formatValue(bda);
            String v = bda.getValueString();
            return v != null ? v : "";
        } catch (Exception e) {
            return "";
        }
    }

    // ─── Lectura desde el IED ───

    private void readFromIed() {
        IEC61850Client client = clientSupplier.get();
        if (client == null) {
            JOptionPane.showMessageDialog(parent,
                "Se requiere modo cliente conectado para leer del IED.\n"
                + "(En modo servidor los valores ya son los del modelo local)",
                "Ajustes SP", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (rowBdas.isEmpty()) { loadSettings(); if (rowBdas.isEmpty()) return; }

        // Agrupar por nodo FcModelNode raíz para leer por bloques (menos peticiones MMS)
        Set<FcModelNode> roots = new LinkedHashSet<>();
        for (BasicDataAttribute bda : rowBdas) {
            ModelNode n = bda;
            FcModelNode root = bda;
            while (n.getParent() instanceof FcModelNode) {
                n = n.getParent();
                root = (FcModelNode) n;
            }
            roots.add(root);
        }
        lblSummary.setText("Leyendo " + roots.size() + " nodos SP del IED...");
        backgroundExecutor.submit(() -> {
            int ok = 0, err = 0;
            for (FcModelNode root : roots) {
                try { client.readNodeValues(root); ok++; }
                catch (Exception e) { err++; log.accept("[AjustesSP] Error leyendo " + root.getReference() + ": " + e.getMessage()); }
            }
            final int fOk = ok, fErr = err;
            SwingUtilities.invokeLater(() -> {
                refreshValuesColumn();
                lblSummary.setText(rowBdas.size() + " ajustes | leídos " + fOk + " nodos"
                    + (fErr > 0 ? " (" + fErr + " errores)" : ""));
                log.accept("[AjustesSP] Lectura completa: " + fOk + " nodos OK, " + fErr + " errores");
            });
        });
    }

    private void refreshValuesColumn() {
        for (int i = 0; i < rowBdas.size(); i++) {
            tableModel.setValueAt(valueOf(rowBdas.get(i)), i, COL_VALUE);
        }
    }

    // ─── Escritura ───

    private void writeChanges() {
        IEC61850Client client = clientSupplier.get();
        if (client == null) {
            JOptionPane.showMessageDialog(parent,
                "Se requiere modo cliente conectado para escribir ajustes.",
                "Ajustes SP", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (table.isEditing()) table.getCellEditor().stopCellEditing();

        List<int[]> changes = new ArrayList<>(); // índices de fila (modelo)
        StringBuilder resumen = new StringBuilder();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            String nuevo = String.valueOf(tableModel.getValueAt(i, COL_NEW)).trim();
            if (nuevo.isEmpty() || "null".equals(nuevo)) continue;
            String actual = String.valueOf(tableModel.getValueAt(i, COL_VALUE));
            if (nuevo.equals(actual)) continue;
            changes.add(new int[]{i});
            if (resumen.length() < 1200) {
                resumen.append(tableModel.getValueAt(i, COL_REF))
                       .append(":  ").append(actual).append("  ->  ").append(nuevo).append("\n");
            }
        }
        if (changes.isEmpty()) {
            JOptionPane.showMessageDialog(parent, "No hay cambios pendientes.\n"
                + "Edita la columna 'Nuevo valor' primero.", "Ajustes SP", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        JTextArea ta = new JTextArea(resumen.toString(), Math.min(12, changes.size() + 1), 60);
        ta.setEditable(false);
        int opt = JOptionPane.showConfirmDialog(parent, new Object[]{
                "ATENCIÓN: vas a escribir " + changes.size() + " ajuste(s) de protección en el IED.",
                new JScrollPane(ta), "¿Continuar?"},
            "Confirmar escritura de ajustes", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (opt != JOptionPane.YES_OPTION) return;

        backgroundExecutor.submit(() -> {
            int ok = 0, err = 0;
            for (int[] ch : changes) {
                int row = ch[0];
                String ref = (String) tableModel.getValueAt(row, COL_REF);
                String nuevo = String.valueOf(tableModel.getValueAt(row, COL_NEW)).trim();
                try {
                    client.writeValue(ref, Fc.SP, nuevo);
                    // Re-read from server to confirm the write took effect
                    try {
                        BasicDataAttribute bda = rowBdas.get(row);
                        ModelNode n = bda;
                        FcModelNode root = bda;
                        while (n.getParent() instanceof FcModelNode) {
                            n = n.getParent();
                            root = (FcModelNode) n;
                        }
                        client.readNodeValues(root);
                    } catch (Exception readEx) {
                        log.accept("[AjustesSP] Aviso: no se pudo re-leer valor: " + readEx.getMessage());
                    }
                    ok++;
                    final int fRow = row;
                    SwingUtilities.invokeLater(() -> {
                        tableModel.setValueAt(valueOf(rowBdas.get(fRow)), fRow, COL_VALUE);
                        tableModel.setValueAt("", fRow, COL_NEW);
                    });
                    log.accept("[AjustesSP] OK: " + ref + " = " + nuevo);
                } catch (Exception e) {
                    err++;
                    log.accept("[AjustesSP] ERROR escribiendo " + ref + ": " + e.getMessage());
                }
            }
            final int fOk = ok, fErr = err;
            SwingUtilities.invokeLater(() -> {
                lblSummary.setText("Escritura: " + fOk + " OK, " + fErr + " errores");
                if (fErr > 0) {
                    JOptionPane.showMessageDialog(parent,
                        fOk + " ajustes escritos, " + fErr + " errores (ver log)",
                        "Ajustes SP", JOptionPane.WARNING_MESSAGE);
                }
            });
        });
    }

    /**
     * Valores posibles para el editor de la columna "Nuevo valor".
     * null = texto libre (numéricos/strings impredecibles).
     * Las opciones coinciden con lo aceptado por IEC61850Client.setBasicDataAttributeValue().
     */
    private static String[] optionsFor(BasicDataAttribute bda) {
        if (bda instanceof BdaBoolean || bda instanceof BdaCheck) {
            return new String[]{"true", "false"};
        }
        if (bda instanceof BdaDoubleBitPos) {
            return new String[]{"intermediate", "off", "on", "bad"};
        }
        if (bda instanceof BdaTapCommand) {
            return new String[]{"stop", "lower", "higher", "reserved"};
        }
        return null;
    }

    private void applyFilter() {
        String text = filterField.getText().trim();
        sorter.setRowFilter(text.isEmpty() ? null
            : RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(text)));
    }
}
