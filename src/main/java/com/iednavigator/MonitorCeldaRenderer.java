package com.iednavigator;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Celdas del monitor (rediseño de 2026-11):
 *
 *   ☐ · Señal (prefijo gris + atributo + FC) · Tipo · Valor · Calidad (pildora) · Ultimo cambio
 *
 * La fila cuyo valor acaba de cambiar se pinta de ambar y se desvanece en 1,5 s. La columna
 * Señal guarda el MonitorItem entero: de ahi salen la ruta, el FC y el momento del cambio.
 */
final class MonitorCeldaRenderer implements TableCellRenderer {

    static final int COL_SEL = 0, COL_SENAL = 1, COL_TIPO = 2, COL_VALOR = 3, COL_CALIDAD = 4, COL_HORA = 5;
    static final long DESVANECER_MS = 1500;

    private final DefaultTableCellRenderer texto = new DefaultTableCellRenderer();
    private final JCheckBox casilla = new JCheckBox();
    private final JPanel celdaCalidad = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    private final Insignia pildora = new Insignia(Estilo.ui(Font.BOLD, 10)).alto(16);
    private final SimpleDateFormat hora = new SimpleDateFormat("HH:mm:ss.SSS");

    MonitorCeldaRenderer() {
        casilla.setHorizontalAlignment(SwingConstants.CENTER);
        casilla.setOpaque(true);
        celdaCalidad.setOpaque(true);
        celdaCalidad.setBorder(BorderFactory.createEmptyBorder(4, 8, 0, 0));
        celdaCalidad.add(pildora);
    }

    static MonitorItem itemDeFila(JTable t, int filaVista) {
        Object o = t.getModel().getValueAt(t.convertRowIndexToModel(filaVista), COL_SENAL);
        return o instanceof MonitorItem ? (MonitorItem) o : null;
    }

    /** Fondo de la fila: seleccion, resaltado que se desvanece, o blanco. */
    static Color fondo(JTable t, int fila, boolean sel) {
        if (sel) return Estilo.ACENTO_SUAVE;
        MonitorItem it = itemDeFila(t, fila);
        Color base = Estilo.temaOscuro() ? t.getBackground() : Estilo.SUPERFICIE;
        if (it == null || it.resaltarDesde == 0) return base;
        long dt = System.currentTimeMillis() - it.resaltarDesde;
        if (dt >= DESVANECER_MS) return base;
        float f = 1f - dt / (float) DESVANECER_MS;   // 1 = ambar pleno, 0 = base
        Color r = Estilo.RESALTE;
        return new Color(
                Math.round(base.getRed() + (r.getRed() - base.getRed()) * f),
                Math.round(base.getGreen() + (r.getGreen() - base.getGreen()) * f),
                Math.round(base.getBlue() + (r.getBlue() - base.getBlue()) * f));
    }

    @Override
    public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foco, int fila, int col) {
        int c = t.convertColumnIndexToModel(col);
        Color fondo = fondo(t, fila, sel);
        if (c == COL_SEL) {
            casilla.setSelected(Boolean.TRUE.equals(v));
            casilla.setBackground(fondo);
            return casilla;
        }
        if (c == COL_CALIDAD) {
            String q = v == null ? "" : v.toString();
            if (q.isEmpty()) {
                pildora.poner("—", null, Estilo.TEXTO_2, null);
            } else if (q.startsWith("good")) {
                pildora.poner(q, new Color(0xE7F4EC), new Color(0x1E6B3E), null);
            } else if (q.startsWith("invalid")) {
                pildora.poner(q, new Color(0xFDECEA), Estilo.ERROR, null);
            } else {
                pildora.poner(q, new Color(0xFDF3E3), Estilo.AVISO, null);
            }
            celdaCalidad.setBackground(fondo);
            // Pildora centrada en la fila, sea cual sea la densidad
            int arriba = Math.max(0, (t.getRowHeight() - 16) / 2);
            celdaCalidad.setBorder(BorderFactory.createEmptyBorder(arriba, 8, 0, 0));
            return celdaCalidad;
        }
        texto.getTableCellRendererComponent(t, v, sel, false, fila, col);
        texto.setBackground(fondo);
        texto.setForeground(Estilo.TEXTO);
        texto.setHorizontalAlignment(SwingConstants.LEFT);
        texto.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        texto.setToolTipText(null);
        switch (c) {
            case COL_SENAL: {
                MonitorItem it = v instanceof MonitorItem ? (MonitorItem) v : null;
                texto.setFont(Estilo.mono(Font.PLAIN, 11));
                if (it == null) { texto.setText(""); break; }
                String ruta = senal(it.reference);
                int k = ruta.lastIndexOf('.');
                String pre = k >= 0 ? ruta.substring(0, k + 1) : "";
                String hoja = k >= 0 ? ruta.substring(k + 1) : ruta;
                texto.setText("<html><span style='color:#5B6470'>" + esc(pre) + "</span>" + esc(hoja)
                        + "&nbsp;&nbsp;<span style='background-color:#EEF0F3;color:#3F4752;font-size:9px'>&nbsp;"
                        + esc(it.fc) + "&nbsp;</span></html>");
                texto.setToolTipText(it.reference + " [" + it.fc + "]");
                break;
            }
            case COL_TIPO:
                texto.setFont(Estilo.ui(Font.PLAIN, 12));
                texto.setForeground(new Color(0x4A525D));
                break;
            case COL_VALOR:
                texto.setFont(Estilo.mono(Font.BOLD, 11));
                alinearBase(t);
                break;
            case COL_HORA:
                texto.setFont(Estilo.mono(Font.PLAIN, 11));
                texto.setForeground(new Color(0x4A525D));
                texto.setHorizontalAlignment(SwingConstants.RIGHT);
                alinearBase(t);
                break;
            default:
                break;
        }
        return texto;
    }

    /**
     * La monoespaciada centrada en la fila queda con la linea base 3-4 px mas arriba que la
     * Segoe UI de Tipo: se baja con un margen superior igual a la diferencia de metricas.
     */
    private void alinearBase(JTable t) {
        FontMetrics ui = t.getFontMetrics(Estilo.ui(Font.PLAIN, 12));
        FontMetrics mono = texto.getFontMetrics(texto.getFont());
        int dif = (ui.getAscent() - ui.getDescent()) - (mono.getAscent() - mono.getDescent());
        texto.setBorder(BorderFactory.createEmptyBorder(Math.max(0, dif), 8, 0, 8));
    }

    String hora(long ms) {
        return ms <= 0 ? "" : hora.format(new Date(ms));
    }

    /** "LD/PDOP1.Beh.stVal" -> "PDOP1.Beh.stVal" (sin el LD, como en el mockup). */
    static String senal(String ref) {
        if (ref == null) return "";
        int b = ref.indexOf('/');
        return b >= 0 ? ref.substring(b + 1) : ref;
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
