package com.iednavigator;

import com.formdev.flatlaf.util.UIScale;

import javax.swing.*;
import java.awt.*;

/**
 * Boton "Registro" del extremo derecho de la fila de pestañas (rediseño de 2026-11): abre y
 * cierra el registro, y lleva un badge con los avisos y errores llegados desde la ultima vez
 * que se abrio. Ambar si hay avisos, rojo si hay al menos un error; sin badge si no hay nada.
 */
final class BotonRegistro extends JButton {

    private int avisos, errores;
    private boolean abierto;

    BotonRegistro() {
        super(I18n.t("log.title"));
        setIcon(new IconoLineas());
        setIconTextGap(UIScale.scale(6));
        setFont(Estilo.ui(Font.PLAIN, 12));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        poner(0, 0, false);
    }

    /** Estado del boton: avisos y errores sin ver, y si el registro esta abierto. */
    void poner(int avisos, int errores, boolean abierto) {
        this.avisos = avisos;
        this.errores = errores;
        this.abierto = abierto;
        int badge = anchoBadge();
        String margen = "0,8,0," + (8 + (badge > 0 ? UIScale.unscale(badge) + 6 : 0));
        putClientProperty("FlatLaf.style", abierto
                ? "arc:6; focusWidth:0; innerFocusWidth:0; borderColor:#1F5FBF; background:#EAF1FB;"
                  + " hoverBackground:#DCE8F8; foreground:#1F5FBF; margin:" + margen
                : "arc:6; focusWidth:0; innerFocusWidth:0; borderColor:#DDE1E7; background:#FFFFFF;"
                  + " hoverBackground:#F4F6F8; foreground:#3F4752; margin:" + margen);
        setFont(Estilo.ui(abierto ? Font.BOLD : Font.PLAIN, 12));
        int n = avisos + errores;
        String nombre = I18n.t("log.title") + " (Ctrl+L)" + (n > 0 ? " · " + n : "");
        setToolTipText(nombre);
        getAccessibleContext().setAccessibleName(nombre);
        revalidate();
        repaint();
    }

    @Override public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        return new Dimension(d.width, UIScale.scale(26));
    }

    private int anchoBadge() {
        int n = avisos + errores;
        if (n == 0) return 0;
        FontMetrics fm = getFontMetrics(Estilo.ui(Font.BOLD, 10));
        return Math.max(UIScale.scale(16), fm.stringWidth(texto(n)) + UIScale.scale(10));
    }

    private static String texto(int n) { return n > 99 ? "99+" : String.valueOf(n); }

    @Override protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        int n = avisos + errores;
        if (n == 0) return;
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        boolean rojo = errores > 0;
        int w = anchoBadge(), h = UIScale.scale(16);
        int x = getWidth() - UIScale.scale(8) - w, y = (getHeight() - h) / 2;
        g2.setColor(rojo ? new Color(0xFDECEA) : new Color(0xFDF3E3));
        g2.fillRoundRect(x, y, w, h, h, h);
        Font f = Estilo.ui(Font.BOLD, 10);
        FontMetrics fm = g2.getFontMetrics(f);
        g2.setFont(f);
        g2.setColor(rojo ? Estilo.ERROR : Estilo.AVISO);
        String t = texto(n);
        g2.drawString(t, x + (w - fm.stringWidth(t)) / 2, y + (h - fm.getHeight()) / 2 + fm.getAscent());
        g2.dispose();
    }

    /** Tres renglones: el icono del registro. */
    private static final class IconoLineas implements Icon {
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(c.getForeground());
            g2.setStroke(new BasicStroke(UIScale.scale(1.6f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int s = UIScale.scale(1);
            g2.drawLine(x + 2 * s, y + 3 * s, x + 12 * s, y + 3 * s);
            g2.drawLine(x + 2 * s, y + 7 * s, x + 12 * s, y + 7 * s);
            g2.drawLine(x + 2 * s, y + 11 * s, x + 9 * s, y + 11 * s);
            g2.dispose();
        }
        @Override public int getIconWidth() { return UIScale.scale(14); }
        @Override public int getIconHeight() { return UIScale.scale(14); }
    }
}
