package com.iednavigator;

import javax.swing.*;
import java.awt.*;

/**
 * FlowLayout que informa la altura real cuando los componentes saltan de linea.
 *
 * FlowLayout acomoda en varias lineas si el ancho no alcanza, pero su tamano preferido
 * siempre es el de una sola linea. El contenedor padre le da esa altura y las lineas que
 * sobran quedan dibujadas fuera de la vista. Paso en la pestana GOOSE: con una pantalla de
 * 1366x768, el boton "CAPTURAR GOOSE", el selector de Estado y "Cambio" no se veian.
 *
 * Aca el tamano preferido se calcula con el ancho disponible, contando las lineas que hacen
 * falta. Cuando el ancho cambia y con el la cantidad de lineas, se pide un nuevo layout para
 * que el padre ajuste la altura.
 */
public class WrapLayout extends FlowLayout {

    private Dimension ultimoPreferido;

    public WrapLayout(int align, int hgap, int vgap) {
        super(align, hgap, vgap);
    }

    @Override
    public Dimension preferredLayoutSize(Container target) {
        return medir(target, true);
    }

    @Override
    public Dimension minimumLayoutSize(Container target) {
        // El minimo deja achicar el ancho hasta el componente mas ancho; el resto salta de linea.
        Dimension d = medir(target, false);
        d.width -= (getHgap() + 1);
        return d;
    }

    @Override
    public void layoutContainer(Container target) {
        Dimension preferido = preferredLayoutSize(target);
        super.layoutContainer(target);
        if (ultimoPreferido != null && !preferido.equals(ultimoPreferido)) {
            // Cambio la cantidad de lineas: el padre tiene que volver a repartir la altura.
            SwingUtilities.invokeLater(() -> {
                target.invalidate();
                Container c = target.getParent();
                if (c instanceof JComponent) ((JComponent) c).revalidate();
                else if (c != null) c.validate();
            });
        }
        ultimoPreferido = preferido;
    }

    private Dimension medir(Container target, boolean preferido) {
        synchronized (target.getTreeLock()) {
            // Ancho disponible: el del propio panel, o el del primer ancestro que ya tenga ancho.
            int anchoDisponible = 0;
            for (Container c = target; c != null && anchoDisponible == 0; c = c.getParent()) {
                anchoDisponible = c.getSize().width;
            }
            if (anchoDisponible == 0) anchoDisponible = Integer.MAX_VALUE;

            int hgap = getHgap(), vgap = getVgap();
            Insets in = target.getInsets();
            int margenH = in.left + in.right + hgap * 2;
            int anchoMax = anchoDisponible - margenH;

            int ancho = 0, alto = 0, anchoFila = 0, altoFila = 0;
            for (int i = 0; i < target.getComponentCount(); i++) {
                Component m = target.getComponent(i);
                if (!m.isVisible()) continue;
                Dimension d = preferido ? m.getPreferredSize() : m.getMinimumSize();
                if (anchoFila > 0 && anchoFila + hgap + d.width > anchoMax) {
                    ancho = Math.max(ancho, anchoFila);
                    alto += altoFila + vgap;
                    anchoFila = 0;
                    altoFila = 0;
                }
                if (anchoFila > 0) anchoFila += hgap;
                anchoFila += d.width;
                altoFila = Math.max(altoFila, d.height);
            }
            ancho = Math.max(ancho, anchoFila);
            alto += altoFila;

            return new Dimension(ancho + margenH, alto + in.top + in.bottom + vgap * 2);
        }
    }
}
