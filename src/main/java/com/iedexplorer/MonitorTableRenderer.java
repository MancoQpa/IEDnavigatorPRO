package com.iedexplorer;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;

/**
 * Extraído de IEDExplorerApp.java — Fase 1 de refactorización.
 * Originalmente era una clase interna (private class — no static).
 * No accede a ningún campo de la clase externa IEDExplorerApp, por lo que
 * puede extraerse sin dependencias adicionales.
 * Ver FASE1_REPORTE.md para detalles.
 *
 * Renderer personalizado para la tabla del Activity Monitor.
 * Colorea las celdas según el valor (ON/OFF/intermediate) y detecta cambios recientes.
 */
class MonitorTableRenderer extends DefaultTableCellRenderer {
    private final Color BG_CHANGED = new Color(255, 255, 200);  // Amarillo claro
    private final Color FG_ON = new Color(0, 150, 0);
    private final Color FG_OFF = new Color(200, 0, 0);
    private final Color FG_INTERMEDIATE = new Color(255, 140, 0);
    private final Color FG_CHANGED = new Color(0, 100, 200);

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value,
            boolean isSelected, boolean hasFocus, int row, int column) {
        super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

        setFont(getFont().deriveFont(Font.PLAIN));
        setForeground(Color.BLACK);
        setBackground(isSelected ? table.getSelectionBackground() : Color.WHITE);

        if (value == null) return this;
        String v = value.toString().toLowerCase();

        // Columna Valor (3)
        if (column == 3) {
            if (v.equals("on")) {
                setForeground(FG_ON);
                setFont(getFont().deriveFont(Font.BOLD));
            } else if (v.equals("off")) {
                setForeground(FG_OFF);
                setFont(getFont().deriveFont(Font.BOLD));
            } else if (v.contains("intermediate") || v.contains("bad")) {
                setForeground(FG_INTERMEDIATE);
                setFont(getFont().deriveFont(Font.BOLD));
            }
        }

        // Columna Estado (4)
        if (column == 4 && v.equals("changed")) {
            setForeground(FG_CHANGED);
            setFont(getFont().deriveFont(Font.BOLD));
            if (!isSelected) {
                setBackground(BG_CHANGED);
            }
        }

        // Columna FC (1) - color segun FC
        if (column == 1) {
            if (v.equals("st")) {
                setForeground(new Color(0, 100, 0));
            } else if (v.equals("mx")) {
                setForeground(new Color(0, 0, 150));
            } else if (v.equals("co")) {
                setForeground(new Color(150, 0, 0));
            }
        }

        return this;
    }
}
