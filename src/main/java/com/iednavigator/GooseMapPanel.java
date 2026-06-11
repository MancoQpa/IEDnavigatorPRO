package com.iednavigator;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Panel "Mapa GOOSE": muestra publicadores (GSEControl) y suscriptores
 * (ExtRef / LGOS) de un archivo SCL, idealmente un SCD multi-IED.
 */
class GooseMapPanel {

    private final Component parent;
    private final Consumer<String> log;

    private File sclFile;
    private JLabel lblFile, lblSummary;
    private DefaultTableModel pubModel, subModel;
    private JTable pubTable, subTable;
    private TableRowSorter<DefaultTableModel> subSorter;
    private JComboBox<String> iedFilter;
    private GooseMapAnalyzer.Result lastResult;

    GooseMapPanel(Component parent, Consumer<String> log) {
        this.parent = parent;
        this.log = log;
    }

    JPanel createPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        // ─── Toolbar ───
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JButton btnLoad = new JButton("Cargar SCL/SCD...");
        btnLoad.addActionListener(e -> loadFile());
        lblFile = new JLabel("(sin archivo)");
        iedFilter = new JComboBox<>();
        iedFilter.addItem("Todos los IEDs");
        iedFilter.addActionListener(e -> applyFilter());
        JButton btnExport = new JButton("Exportar CSV");
        btnExport.addActionListener(e -> exportCsv());
        top.add(btnLoad);
        top.add(lblFile);
        top.add(new JLabel("  Filtro:"));
        top.add(iedFilter);
        top.add(btnExport);
        panel.add(top, BorderLayout.NORTH);

        // ─── Tabla publicadores ───
        pubModel = new DefaultTableModel(
            new String[]{"IED", "LD/GoCB", "DataSet", "Miembros", "MAC", "APPID", "Suscriptores"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        pubTable = new JTable(pubModel);
        pubTable.setAutoCreateRowSorter(true);
        JScrollPane pubScroll = new JScrollPane(pubTable);
        pubScroll.setBorder(BorderFactory.createTitledBorder("Publicadores (GSEControl)"));

        // ─── Tabla suscripciones ───
        subModel = new DefaultTableModel(
            new String[]{"Publicador (GoCB)", "Dato / GoCBRef", "Suscriptor", "Destino (intAddr/LGOS)", "Vía", "Estado"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        subTable = new JTable(subModel);
        subSorter = new TableRowSorter<>(subModel);
        subTable.setRowSorter(subSorter);
        subTable.getColumnModel().getColumn(0).setPreferredWidth(200);
        subTable.getColumnModel().getColumn(1).setPreferredWidth(280);
        subTable.getColumnModel().getColumn(2).setPreferredWidth(110);
        subTable.getColumnModel().getColumn(3).setPreferredWidth(220);
        subTable.getColumnModel().getColumn(4).setPreferredWidth(50);
        subTable.getColumnModel().getColumn(5).setPreferredWidth(80);
        subTable.setDefaultRenderer(Object.class, new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (!isSelected) {
                    String estado = (String) table.getValueAt(row, 5);
                    c.setForeground("OK".equals(estado) ? new Color(0x1B5E20) : new Color(0xB71C1C));
                }
                return c;
            }
        });
        JScrollPane subScroll = new JScrollPane(subTable);
        subScroll.setBorder(BorderFactory.createTitledBorder("Suscripciones (ExtRef / LGOS)"));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, pubScroll, subScroll);
        split.setResizeWeight(0.35);
        panel.add(split, BorderLayout.CENTER);

        lblSummary = new JLabel("Cargue un archivo SCL (idealmente SCD multi-IED)");
        panel.add(lblSummary, BorderLayout.SOUTH);

        return panel;
    }

    private void loadFile() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
            "Archivos SCL (*.scd, *.cid, *.icd, *.iid, *.sed)", "scd", "cid", "icd", "iid", "sed"));
        if (sclFile != null) fc.setCurrentDirectory(sclFile.getParentFile());
        if (fc.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return;
        sclFile = fc.getSelectedFile();
        lblFile.setText(sclFile.getName());
        analyze();
    }

    private void analyze() {
        try {
            lastResult = GooseMapAnalyzer.analyze(sclFile);

            pubModel.setRowCount(0);
            for (GooseMapAnalyzer.Publisher p : lastResult.publishers) {
                pubModel.addRow(new Object[]{
                    p.iedName, p.ldInst + "/" + p.cbName, p.datSet,
                    p.members.size(), p.mac, p.appidHex, p.subscriberCount});
            }

            subModel.setRowCount(0);
            int unresolved = 0;
            for (GooseMapAnalyzer.Subscription s : lastResult.subscriptions) {
                if (!s.resolved) unresolved++;
                subModel.addRow(new Object[]{
                    s.pubRef(), s.dataRef, s.subscriberIed, s.target, s.via,
                    s.resolved ? "OK" : "No resuelto"});
            }

            iedFilter.removeAllItems();
            iedFilter.addItem("Todos los IEDs");
            for (String n : lastResult.iedNames) iedFilter.addItem(n);

            String summary = String.format(
                "%d IEDs  |  %d GoCBs publicadores  |  %d suscripciones (%d no resueltas)",
                lastResult.iedNames.size(), lastResult.publishers.size(),
                lastResult.subscriptions.size(), unresolved);
            lblSummary.setText(summary);
            log.accept("[MapaGOOSE] " + sclFile.getName() + ": " + summary);
        } catch (Exception ex) {
            log.accept("[MapaGOOSE] ERROR: " + ex.getMessage());
            JOptionPane.showMessageDialog(parent, "Error analizando archivo:\n" + ex.getMessage(),
                "Mapa GOOSE", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Filtra suscripciones donde el IED participa como publicador o suscriptor. */
    private void applyFilter() {
        String sel = (String) iedFilter.getSelectedItem();
        if (sel == null || sel.startsWith("Todos")) {
            subSorter.setRowFilter(null);
            return;
        }
        final String ied = sel;
        subSorter.setRowFilter(new RowFilter<DefaultTableModel, Integer>() {
            @Override
            public boolean include(Entry<? extends DefaultTableModel, ? extends Integer> entry) {
                String pub = entry.getStringValue(0);
                String subscriber = entry.getStringValue(2);
                return subscriber.equals(ied) || pub.startsWith(ied + " ");
            }
        });
    }

    private void exportCsv() {
        if (lastResult == null || lastResult.subscriptions.isEmpty()) {
            JOptionPane.showMessageDialog(parent, "No hay suscripciones que exportar");
            return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("mapa_goose.csv"));
        if (fc.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return;
        try (PrintWriter pw = new PrintWriter(fc.getSelectedFile(), StandardCharsets.UTF_8)) {
            pw.println("Publicador;Dato;Suscriptor;Destino;Via;Estado");
            for (GooseMapAnalyzer.Subscription s : lastResult.subscriptions) {
                pw.println(csv(s.pubRef()) + ";" + csv(s.dataRef) + ";" + csv(s.subscriberIed)
                    + ";" + csv(s.target) + ";" + s.via + ";" + (s.resolved ? "OK" : "No resuelto"));
            }
            log.accept("[MapaGOOSE] Exportado: " + fc.getSelectedFile().getAbsolutePath());
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
