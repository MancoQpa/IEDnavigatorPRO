package com.iednavigator;

import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Distribución de una barra de una fila: un grupo a la izquierda y otro a la derecha que nunca se
 * pisan. Con BorderLayout WEST/EAST, al angostar la ventana los dos grupos se encimaban (la barra
 * del monitor: "Watchlist: 0 nodos" sobre el filtro, 2026-11-20).
 *
 * Cuando falta lugar, en este orden: se acorta el componente flexible (el filtro) hasta su mínimo;
 * se ocultan los prescindibles (de cualquiera de los dos grupos), el primero de la lista antes;
 * el flexible baja a su mínimo extremo; y si aun así no entra, lo que queda a la izquierda se
 * recorta antes de llegar a la derecha. Ocultar es darle
 * tamaño cero, no setVisible: así la distribución no se invalida a sí misma ni entra en un ciclo,
 * y la visibilidad que manejan otros (la watchlist solo en modo Cliente) se respeta.
 */
final class BarraAdaptable implements LayoutManager {

    private final int hueco, separacion;
    private final List<Component> izquierda = new ArrayList<>();
    private final List<Component> derecha = new ArrayList<>();
    private final List<Component> prescindibles = new ArrayList<>();
    private Component flexible;
    private int flexMin, flexExtremo;

    /** @param hueco entre componentes de un grupo; @param separacion mínima entre los dos grupos */
    BarraAdaptable(int hueco, int separacion) {
        this.hueco = hueco;
        this.separacion = separacion;
    }

    BarraAdaptable izquierda(Component... c) { izquierda.clear(); izquierda.addAll(Arrays.asList(c)); return this; }
    BarraAdaptable derecha(Component... c) { derecha.clear(); derecha.addAll(Arrays.asList(c)); return this; }
    /** Se ocultan en este orden cuando no hay lugar. */
    BarraAdaptable prescindibles(Component... c) { prescindibles.clear(); prescindibles.addAll(Arrays.asList(c)); return this; }
    /** Se acorta hasta {@code minimo} antes de ocultar nada, y hasta {@code extremo} cuando ya no queda nada que ocultar. */
    BarraAdaptable flexible(Component c, int minimo, int extremo) { flexible = c; flexMin = minimo; flexExtremo = extremo; return this; }

    /** Si el componente quedó oculto por falta de lugar (tamaño cero), para las pruebas. */
    static boolean oculto(Component c) { return c.getWidth() == 0; }

    @Override public void addLayoutComponent(String name, Component comp) {}
    @Override public void removeLayoutComponent(Component comp) {}

    private int ancho(List<Component> grupo, List<Component> fuera, int anchoFlex) {
        int w = 0, n = 0;
        for (Component c : grupo) {
            if (!c.isVisible() || fuera.contains(c)) continue;
            w += c == flexible ? anchoFlex : c.getPreferredSize().width;
            n++;
        }
        return n == 0 ? 0 : w + hueco * (n - 1);
    }

    private int alto() {
        int h = 0;
        for (Component c : izquierda) if (c.isVisible()) h = Math.max(h, c.getPreferredSize().height);
        for (Component c : derecha) if (c.isVisible()) h = Math.max(h, c.getPreferredSize().height);
        return h;
    }

    @Override public Dimension preferredLayoutSize(Container p) {
        Insets in = p.getInsets();
        int flex = flexible == null ? 0 : flexible.getPreferredSize().width;
        int w = ancho(izquierda, List.of(), flex) + separacion + ancho(derecha, List.of(), flex);
        return new Dimension(w + in.left + in.right, alto() + in.top + in.bottom);
    }

    @Override public Dimension minimumLayoutSize(Container p) {
        Insets in = p.getInsets();
        return new Dimension(in.left + in.right, alto() + in.top + in.bottom);
    }

    @Override public void layoutContainer(Container p) {
        Insets in = p.getInsets();
        int disponible = p.getWidth() - in.left - in.right;
        int altoFila = p.getHeight() - in.top - in.bottom;
        int flexPref = flexible == null ? 0 : flexible.getPreferredSize().width;

        // 1) acortar el flexible; 2) ocultar prescindibles uno a uno
        List<Component> fuera = new ArrayList<>();
        int flex = flexPref;
        for (int paso = 0; ; paso++) {
            int sinFlex = ancho(izquierda, fuera, 0) + separacion + ancho(derecha, fuera, 0);
            flex = Math.max(Math.min(flexPref, disponible - sinFlex), Math.min(flexMin, flexPref));
            int total = ancho(izquierda, fuera, flex) + separacion + ancho(derecha, fuera, flex);
            if (total <= disponible) break;
            if (paso >= prescindibles.size()) {
                flex = Math.max(Math.min(flexPref, disponible - sinFlex), Math.min(flexExtremo, flexPref));
                break;
            }
            fuera.add(prescindibles.get(paso));
        }

        // Derecha, alineada al borde derecho; si ni ella sola entra, empieza en el borde izquierdo
        // y lo que sobra se corta a la derecha
        int x = Math.max(in.left + disponible, in.left + ancho(derecha, fuera, flex));
        for (int i = derecha.size() - 1; i >= 0; i--) {
            Component c = derecha.get(i);
            if (!c.isVisible()) continue;
            if (fuera.contains(c)) { c.setBounds(0, 0, 0, 0); continue; }
            int w = c == flexible ? flex : c.getPreferredSize().width;
            x -= w;
            colocar(c, x, w, in.top, altoFila);
            x -= hueco;
        }
        int limite = x + hueco - separacion;

        // Izquierda: lo que no entra antes de la derecha queda en cero
        x = in.left;
        for (Component c : izquierda) {
            if (!c.isVisible()) continue;
            int w = c.getPreferredSize().width;
            if (fuera.contains(c) || x + w > limite) { c.setBounds(0, 0, 0, 0); continue; }
            colocar(c, x, w, in.top, altoFila);
            x += w + hueco;
        }
    }

    private static void colocar(Component c, int x, int w, int y0, int altoFila) {
        int h = Math.min(c.getPreferredSize().height, altoFila);
        c.setBounds(x, y0 + (altoFila - h) / 2, w, h);
    }
}
