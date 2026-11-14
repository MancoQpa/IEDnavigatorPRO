package com.iednavigator;

import com.formdev.flatlaf.ui.FlatTreeUI;
import com.formdev.flatlaf.util.UIScale;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Pintura del arbol del modelo (rediseño de 2026-11), sobre la de FlatLaf:
 *
 *   - guias de indentacion: solo la linea vertical tenue de cada nivel (Tree.paintLines con
 *     Tree.hash), sin el tramo horizontal hacia cada fila;
 *   - la fila bajo el mouse con fondo #F4F6F8 en todo el ancho (el renderer solo cubre su
 *     celda); el numero de fila queda en la propiedad FILA_HOVER para que el renderer
 *     muestre la descripcion de los DO;
 *   - la seleccion (fondo de FlatLaf, Tree.selectionBackground) con una barra de acento de
 *     2 px a la izquierda;
 *   - la fila cuyo valor acaba de cambiar, en ambar que se desvanece en 1,5 s (como el
 *     monitor). Un Timer liviano repinta solo mientras haya resaltes vivos.
 */
final class ArbolUI extends FlatTreeUI {

    static final String FILA_HOVER = "arbol.filaHover";
    static final long DESVANECER_MS = 1500;
    private static final Color HOVER = new Color(0xF4F6F8);

    private int filaHover = -1;
    private Timer resaltes;
    private final MouseAdapter raton = new MouseAdapter() {
        @Override public void mouseMoved(MouseEvent e) { ponerHover(filaEn(e.getY())); }
        @Override public void mouseDragged(MouseEvent e) { ponerHover(filaEn(e.getY())); }
        @Override public void mouseExited(MouseEvent e) { ponerHover(-1); }
    };

    @Override protected void installListeners() {
        super.installListeners();
        tree.addMouseListener(raton);
        tree.addMouseMotionListener(raton);
        resaltes = new Timer(80, e -> {
            if (System.currentTimeMillis() - NodeInfo.ultimoResalte < DESVANECER_MS + 200 && tree.isShowing()) {
                tree.repaint(tree.getVisibleRect());
            }
        });
        resaltes.start();
    }

    @Override protected void uninstallListeners() {
        tree.removeMouseListener(raton);
        tree.removeMouseMotionListener(raton);
        if (resaltes != null) resaltes.stop();
        super.uninstallListeners();
    }

    /** Fila que ocupa esa altura en todo el ancho (no solo sobre la celda), o -1. */
    private int filaEn(int y) {
        int f = tree.getClosestRowForLocation(0, y);
        if (f < 0) return -1;
        Rectangle r = tree.getRowBounds(f);
        return r != null && y >= r.y && y < r.y + r.height ? f : -1;
    }

    private void ponerHover(int f) {
        if (f == filaHover) return;
        int vieja = filaHover;
        filaHover = f;
        tree.putClientProperty(FILA_HOVER, f);
        repintarFila(vieja);
        repintarFila(f);
    }

    private void repintarFila(int f) {
        if (f < 0) return;
        Rectangle r = tree.getRowBounds(f);
        if (r != null) tree.repaint(0, r.y, tree.getWidth(), r.height);
    }

    /** Sin tramo horizontal: las guias son solo verticales. */
    @Override protected void paintHorizontalLine(Graphics g, JComponent c, int y, int left, int right) { }

    @Override public void paint(Graphics g, JComponent c) {
        Rectangle clip = g.getClipBounds();
        if (clip == null) clip = tree.getVisibleRect();
        int desde = tree.getClosestRowForLocation(0, clip.y);
        int hasta = tree.getClosestRowForLocation(0, clip.y + clip.height - 1);
        long ahora = System.currentTimeMillis();
        Color base = tree.getBackground();

        // Fondos de todo el ancho, debajo de lo que pinta FlatLaf
        if (desde >= 0) {
            for (int f = desde; f <= hasta; f++) {
                Rectangle r = tree.getRowBounds(f);
                if (r == null) continue;
                if (tree.isRowSelected(f)) continue;   // la seleccion la pinta FlatLaf
                Color fondo = null;
                NodeInfo info = info(tree.getPathForRow(f));
                if (info != null && info.resaltarDesde > 0 && ahora - info.resaltarDesde < DESVANECER_MS) {
                    fondo = mezcla(Estilo.RESALTE, f == filaHover ? HOVER : base,
                            1f - (ahora - info.resaltarDesde) / (float) DESVANECER_MS);
                } else if (f == filaHover) {
                    fondo = HOVER;
                }
                if (fondo != null) {
                    g.setColor(fondo);
                    g.fillRect(0, r.y, tree.getWidth(), r.height);
                }
            }
        }

        super.paint(g, c);

        // Barra de acento de 2 px a la izquierda de cada fila seleccionada
        if (desde >= 0) {
            g.setColor(Estilo.ACENTO);
            int ancho = UIScale.scale(2);
            for (int f = desde; f <= hasta; f++) {
                if (!tree.isRowSelected(f)) continue;
                Rectangle r = tree.getRowBounds(f);
                if (r != null) g.fillRect(tree.getVisibleRect().x, r.y, ancho, r.height);
            }
        }
    }

    private static NodeInfo info(TreePath p) {
        if (p == null) return null;
        Object o = p.getLastPathComponent();
        Object uo = o instanceof DefaultMutableTreeNode ? ((DefaultMutableTreeNode) o).getUserObject() : null;
        return uo instanceof NodeInfo ? (NodeInfo) uo : null;
    }

    private static Color mezcla(Color a, Color b, float fa) {
        fa = Math.max(0f, Math.min(1f, fa));
        return new Color(
                Math.round(a.getRed() * fa + b.getRed() * (1 - fa)),
                Math.round(a.getGreen() * fa + b.getGreen() * (1 - fa)),
                Math.round(a.getBlue() * fa + b.getBlue() * (1 - fa)));
    }
}
