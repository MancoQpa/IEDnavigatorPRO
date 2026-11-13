package com.iednavigator;

import javax.swing.*;
import java.awt.*;

/**
 * Etiqueta chica de fondo redondeado: el tipo de nodo (LD, LN, DO, DA), el FC de un atributo
 * o el boton "+ Monitor" del arbol. Se dibuja sola; no usa imagenes.
 */
final class Insignia extends JComponent {

    private String texto = "";
    private Color fondo = new Color(0xF1F2F4);
    private Color tinta = new Color(0x4A525D);
    private Color borde;
    private int anchoMinimo;
    private int alto = 18;

    Insignia(Font fuente) {
        setFont(fuente);
    }

    Insignia poner(String texto, Color fondo, Color tinta, Color borde) {
        this.texto = texto == null ? "" : texto;
        this.fondo = fondo;
        this.tinta = tinta;
        this.borde = borde;
        return this;
    }

    Insignia anchoMinimo(int w) { this.anchoMinimo = w; return this; }

    Insignia alto(int h) { this.alto = h; return this; }

    @Override public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(getFont());
        return new Dimension(Math.max(anchoMinimo, fm.stringWidth(texto) + 10), alto);
    }

    @Override public Dimension getMinimumSize() { return getPreferredSize(); }

    @Override protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight();
        if (fondo != null) {
            g2.setColor(fondo);
            g2.fillRoundRect(0, 0, w - 1, h - 1, 7, 7);
        }
        if (borde != null) {
            g2.setColor(borde);
            g2.drawRoundRect(0, 0, w - 1, h - 1, 7, 7);
        }
        g2.setColor(tinta);
        g2.setFont(getFont());
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(texto, (w - fm.stringWidth(texto)) / 2, (h - fm.getHeight()) / 2 + fm.getAscent());
        g2.dispose();
    }
}
