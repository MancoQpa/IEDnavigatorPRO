package com.iednavigator;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Barra del monitor con la ventana angosta (2026-11-20: "Watchlist: 0 nodos" se encimaba con el
 * filtro). Arma la barra con los mismos componentes que la app y, de 1200 a 300 px, verifica que
 * nada se pise, que el orden al faltar lugar sea filtro, watchlist, contador, y que la
 * distribución no cambie la visibilidad (la watchlist la maneja el modo).
 *
 * Uso: java -cp "classes;lib\*;test" com.iednavigator.TestBarraAdaptable
 */
public class TestBarraAdaptable {

    private static int ok, falla;

    private static void chk(String que, boolean cond) {
        if (cond) { ok++; System.out.println("OK     " + que); }
        else { falla++; System.out.println("FALLA  " + que); }
    }

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(TestBarraAdaptable::probar);
        System.out.println();
        System.out.println(ok + " pasaron, " + falla + " fallaron");
        if (falla == 0) System.out.println("TODO OK");
        System.exit(falla == 0 ? 0 : 1);
    }

    private static void probar() {
        BarraAdaptable d = new BarraAdaptable(8, 16);
        JPanel barra = new JPanel(d);
        barra.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        JPanel vista = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        vista.add(new JToggleButton("Tabla"));
        vista.add(new JToggleButton("Paneles"));
        JLabel cuenta = new JLabel("0 señales");
        JPanel watch = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        watch.add(new JLabel("Watchlist: 0 nodos"));
        watch.add(new JButton("Limpiar"));
        JTextField filtro = new JTextField(16);
        int fPref = filtro.getPreferredSize().width;   // con columnas, JTextField manda su ancho
        JLabel fc = new JLabel("FC");
        JComboBox<String> combo = new JComboBox<>(new String[]{"Todos", "ST", "MX"});
        JButton quitar = new JButton("Quitar"), limpiar = new JButton("Limpiar…");
        for (Component c : new Component[]{vista, cuenta, watch, filtro, fc, combo, quitar, limpiar}) barra.add(c);
        d.izquierda(vista, cuenta, watch).derecha(filtro, fc, combo, quitar, limpiar)
                .flexible(filtro, 110, 60).prescindibles(watch, cuenta, fc);

        Component[] todos = {vista, cuenta, watch, filtro, fc, combo, quitar, limpiar};
        int anchoLleno = barra.getPreferredSize().width;
        boolean nuncaSePisan = true, filtroAntes = true, ordenOk = true, dentro = true;
        String detalle = "";
        for (int w = 1200; w >= 300; w -= 10) {
            barra.setSize(w, 40);
            barra.doLayout();
            List<Rectangle> r = new ArrayList<>();
            for (Component c : todos) if (!BarraAdaptable.oculto(c)) r.add(c.getBounds());
            for (int i = 0; i < r.size(); i++) {
                if (r.get(i).x < 10) dentro = false;
                for (int j = i + 1; j < r.size(); j++)
                    if (r.get(i).intersects(r.get(j))) { nuncaSePisan = false; detalle = " (a " + w + " px)"; }
            }
            // La watchlist solo se oculta con el filtro ya en su mínimo; el contador, después de la watchlist
            // (oculta la watchlist, el filtro puede recuperar el lugar que ella dejó)
            if (BarraAdaptable.oculto(watch) && w - 20 >= anchoLleno - 20 - (fPref - 110)) filtroAntes = false;
            if (BarraAdaptable.oculto(cuenta) && !BarraAdaptable.oculto(watch)) ordenOk = false;
        }
        chk("de 1200 a 300 px nada se encima" + detalle, nuncaSePisan);
        chk("nada se sale por la izquierda", dentro);
        chk("primero se acorta el filtro, después se oculta la watchlist", filtroAntes);
        chk("el contador se oculta después de la watchlist", ordenOk);

        barra.setSize(anchoLleno + 50, 40);
        barra.doLayout();
        chk("con lugar se ve todo y el filtro tiene su ancho", !BarraAdaptable.oculto(watch) && !BarraAdaptable.oculto(cuenta)
                && filtro.getWidth() == fPref);
        barra.setSize(anchoLleno - 60, 40);
        barra.doLayout();
        chk("con 60 px menos solo se acorta el filtro", !BarraAdaptable.oculto(watch) && filtro.getWidth() == fPref - 60);
        barra.setSize(600, 40);
        barra.doLayout();
        chk("la distribución no toca la visibilidad", watch.isVisible() && cuenta.isVisible());
        chk("el selector Tabla|Paneles sigue a la vista a 600 px", !BarraAdaptable.oculto(vista));
        // Ancho mínimo con el selector: selector + separación + derecha sin FC y filtro a 60
        int minimo = vista.getPreferredSize().width + 16 + 60 + 8 + combo.getPreferredSize().width + 8
                + quitar.getPreferredSize().width + 8 + limpiar.getPreferredSize().width + 20;
        barra.setSize(minimo, 40);
        barra.doLayout();
        chk("a " + minimo + " px (lo justo) el selector sigue y la etiqueta FC se ocultó",
                !BarraAdaptable.oculto(vista) && BarraAdaptable.oculto(fc) && filtro.getWidth() == 60);

        // Modo Servidor: la watchlist invisible no ocupa lugar ni se le asigna tamaño
        watch.setVisible(false);
        barra.setSize(anchoLleno - 60, 40);
        barra.doLayout();
        chk("sin watchlist (modo Servidor) el filtro vuelve a su ancho", filtro.getWidth() == fPref);
    }
}
