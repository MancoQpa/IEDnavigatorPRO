package com.iedexplorer;

import javax.swing.*;
import java.awt.*;

/**
 * Fase 13: Diálogos simples para edición de valores en modo Servidor.
 * Extraído de IEDExplorerApp.java — sección GOOSE-MODEL SYNC (helpers de diálogo).
 */
class ValueDialogs {

    private ValueDialogs() {}

    /** Diálogo con dropdown para DoubleBitPos. Retorna cadena en minúsculas o null si cancelado. */
    static String showDoubleBitPosDialog(Component parent, String name, String currentValue) {
        String[] options = {"INTERMEDIATE_STATE", "OFF", "ON", "BAD_STATE"};
        JComboBox<String> combo = new JComboBox<>(options);
        combo.setSelectedItem(currentValue.toUpperCase().replace(" ", "_"));

        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.add(new JLabel("Seleccionar estado para " + name + ":"), BorderLayout.NORTH);
        panel.add(combo, BorderLayout.CENTER);

        int result = JOptionPane.showConfirmDialog(parent, panel,
            "Establecer Estado", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result == JOptionPane.OK_OPTION) {
            String selected = (String) combo.getSelectedItem();
            switch (selected) {
                case "ON":                 return "on";
                case "OFF":                return "off";
                case "INTERMEDIATE_STATE": return "intermediate";
                case "BAD_STATE":          return "bad";
                default:                   return selected.toLowerCase();
            }
        }
        return null;
    }

    /** Diálogo con dropdown para Boolean. Retorna "true"/"false" o null si cancelado. */
    static String showBooleanDialog(Component parent, String name, String currentValue) {
        String[] options = {"true", "false"};
        JComboBox<String> combo = new JComboBox<>(options);
        combo.setSelectedItem(currentValue.toLowerCase());

        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.add(new JLabel("Seleccionar valor para " + name + ":"), BorderLayout.NORTH);
        panel.add(combo, BorderLayout.CENTER);

        int result = JOptionPane.showConfirmDialog(parent, panel,
            "Establecer Valor", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        return result == JOptionPane.OK_OPTION ? (String) combo.getSelectedItem() : null;
    }

    /** Diálogo con dropdown para TapCommand. Retorna cadena en minúsculas o null si cancelado. */
    static String showTapCommandDialog(Component parent, String name, String currentValue) {
        String[] options = {"STOP", "LOWER", "HIGHER", "RESERVED"};
        JComboBox<String> combo = new JComboBox<>(options);
        combo.setSelectedItem(currentValue.toUpperCase());

        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.add(new JLabel("Seleccionar comando para " + name + ":"), BorderLayout.NORTH);
        panel.add(combo, BorderLayout.CENTER);

        int result = JOptionPane.showConfirmDialog(parent, panel,
            "Establecer Comando", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        return result == JOptionPane.OK_OPTION
            ? ((String) combo.getSelectedItem()).toLowerCase()
            : null;
    }
}
