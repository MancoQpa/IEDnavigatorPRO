package com.iednavigator;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

/**
 * Panel "Comparar SCL": diff entre dos archivos CID/ICD/SCD.
 * Útil en puesta en servicio para detectar cambios entre revisiones
 * de configuración (ajustes, datasets, GoCBs, direcciones de red...).
 */
class SclComparePanel {

    private final Component parent;
    private final Consumer<String> log;

    private File fileA, fileB;
    private JLabel lblFileA, lblFileB, lblSummary;
    private JTable diffTable;
    private DefaultTableModel diffTableModel;
    private TableRowSorter<DefaultTableModel> sorter;
    private JComboBox<String> categoryFilter;
    private JCheckBox chkIgnoreName;
    private List<SclCompare.Difference> lastDiffs;

    SclComparePanel(Component parent, Consumer<String> log) {
        this.parent = parent;
        this.log = log;
    }

    JPanel createPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        // ─── Toolbar: selección de archivos y acciones ───
        JPanel top = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(2, 4, 2, 4);
        gc.anchor = GridBagConstraints.WEST;

        JButton btnFileA = new JButton("Archivo A...");
        btnFileA.addActionListener(e -> {
            File f = chooseFile();
            if (f != null) { fileA = f; lblFileA.setText(f.getName()); }
        });
        JButton btnFileB = new JButton("Archivo B...");
        btnFileB.addActionListener(e -> {
            File f = chooseFile();
            if (f != null) { fileB = f; lblFileB.setText(f.getName()); }
        });
        lblFileA = new JLabel("(sin seleccionar)");
        lblFileB = new JLabel("(sin seleccionar)");
        JButton btnCompare = new JButton("Comparar");
        btnCompare.addActionListener(e -> runCompare());
        JButton btnExport = new JButton("Exportar CSV");
        btnExport.addActionListener(e -> exportCsv());

        categoryFilter = new JComboBox<>();
        categoryFilter.addItem("Todas las categorías");
        for (String c : SclCompare.CATEGORIES) categoryFilter.addItem(c);
        categoryFilter.addActionListener(e -> applyFilter());

        chkIgnoreName = new JCheckBox("Ignorar nombre de IED");
        chkIgnoreName.setToolTipText("Compara por posición (IED#1, IED#2...) en lugar de por nombre. "
            + "Útil cuando solo cambió el nombre del IED entre revisiones.");

        gc.gridx = 0; gc.gridy = 0; top.add(btnFileA, gc);
        gc.gridx = 1; gc.weightx = 1; gc.fill = GridBagConstraints.HORIZONTAL; top.add(lblFileA, gc);
        gc.gridx = 2; gc.weightx = 0; gc.fill = GridBagConstraints.NONE; top.add(btnCompare, gc);
        gc.gridx = 3; top.add(categoryFilter, gc);
        gc.gridx = 0; gc.gridy = 1; top.add(btnFileB, gc);
        gc.gridx = 1; gc.weightx = 1; gc.fill = GridBagConstraints.HORIZONTAL; top.add(lblFileB, gc);
        gc.gridx = 2; gc.weightx = 0; gc.fill = GridBagConstraints.NONE; top.add(btnExport, gc);
        gc.gridx = 3; top.add(chkIgnoreName, gc);
        panel.add(top, BorderLayout.NORTH);

        // ─── Tabla de diferencias ───
        String[] cols = {"Categoría", "Elemento", "Valor A", "Valor B", "Estado"};
        diffTableModel = new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        diffTable = new JTable(diffTableModel);
        diffTable.setAutoCreateRowSorter(false);
        sorter = new TableRowSorter<>(diffTableModel);
        diffTable.setRowSorter(sorter);
        diffTable.getColumnModel().getColumn(0).setPreferredWidth(90);
        diffTable.getColumnModel().getColumn(1).setPreferredWidth(380);
        diffTable.getColumnModel().getColumn(2).setPreferredWidth(160);
        diffTable.getColumnModel().getColumn(3).setPreferredWidth(160);
        diffTable.getColumnModel().getColumn(4).setPreferredWidth(80);

        // Colorear según estado
        diffTable.setDefaultRenderer(Object.class, new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (!isSelected) {
                    String status = (String) table.getValueAt(row, 4);
                    if ("Solo en A".equals(status))      c.setForeground(new Color(0xB71C1C)); // rojo: eliminado en B
                    else if ("Solo en B".equals(status)) c.setForeground(new Color(0x1B5E20)); // verde: nuevo en B
                    else                                  c.setForeground(new Color(0xE65100)); // naranja: modificado
                }
                return c;
            }
        });

        panel.add(new JScrollPane(diffTable), BorderLayout.CENTER);

        lblSummary = new JLabel("Seleccione dos archivos SCL y pulse Comparar");
        panel.add(lblSummary, BorderLayout.SOUTH);

        return panel;
    }

    private File chooseFile() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
            "Archivos SCL (*.cid, *.icd, *.scd, *.iid, *.sed)", "cid", "icd", "scd", "iid", "sed"));
        if (fileA != null) fc.setCurrentDirectory(fileA.getParentFile());
        return fc.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION ? fc.getSelectedFile() : null;
    }

    private void runCompare() {
        if (fileA == null || fileB == null) {
            JOptionPane.showMessageDialog(parent, "Seleccione ambos archivos (A y B)",
                "Comparar SCL", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            lastDiffs = SclCompare.compare(fileA, fileB, chkIgnoreName.isSelected());
            diffTableModel.setRowCount(0);
            int soloA = 0, soloB = 0, modif = 0;
            for (SclCompare.Difference d : lastDiffs) {
                String st = d.status();
                if ("Solo en A".equals(st)) soloA++;
                else if ("Solo en B".equals(st)) soloB++;
                else modif++;
                diffTableModel.addRow(new Object[]{
                    d.category, d.element,
                    d.valueA != null ? d.valueA : "—",
                    d.valueB != null ? d.valueB : "—",
                    st
                });
            }
            applyFilter();
            String summary = String.format(
                "%d diferencias  |  %d solo en A (%s)  |  %d solo en B (%s)  |  %d modificadas",
                lastDiffs.size(), soloA, fileA.getName(), soloB, fileB.getName(), modif);
            lblSummary.setText(summary);
            log.accept("[CompararSCL] " + fileA.getName() + " vs " + fileB.getName() + ": " + summary);
            if (lastDiffs.isEmpty()) {
                JOptionPane.showMessageDialog(parent,
                    "Los archivos son equivalentes en las categorías comparadas",
                    "Comparar SCL", JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (Exception ex) {
            log.accept("[CompararSCL] ERROR: " + ex.getMessage());
            JOptionPane.showMessageDialog(parent, "Error comparando archivos:\n" + ex.getMessage(),
                "Comparar SCL", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void applyFilter() {
        String sel = (String) categoryFilter.getSelectedItem();
        if (sel == null || sel.startsWith("Todas")) {
            sorter.setRowFilter(null);
        } else {
            sorter.setRowFilter(RowFilter.regexFilter("^" + java.util.regex.Pattern.quote(sel) + "$", 0));
        }
    }

    private void exportCsv() {
        if (lastDiffs == null || lastDiffs.isEmpty()) {
            JOptionPane.showMessageDialog(parent, "No hay diferencias que exportar");
            return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("diff_scl.csv"));
        if (fc.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return;
        try (PrintWriter pw = new PrintWriter(fc.getSelectedFile(), StandardCharsets.UTF_8)) {
            pw.println("Categoria;Elemento;ValorA;ValorB;Estado");
            for (SclCompare.Difference d : lastDiffs) {
                pw.println(csv(d.category) + ";" + csv(d.element) + ";"
                    + csv(d.valueA) + ";" + csv(d.valueB) + ";" + d.status());
            }
            log.accept("[CompararSCL] Exportado: " + fc.getSelectedFile().getAbsolutePath());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(parent, "Error exportando: " + ex.getMessage());
        }
    }

    private static String csv(String s) {
        if (s == null) return "";
        return s.contains(";") || s.contains("\"")
            ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
