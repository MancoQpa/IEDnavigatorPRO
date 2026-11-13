package com.iednavigator;

import com.beanit.iec61850bean.*;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreeCellRenderer;
import java.awt.*;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Fila del arbol del modelo (rediseño de 2026-11).
 *
 *   [LN] PDOP1  Sobrepotencia direccional
 *   [DA] stVal  ST  1 · on                      [+ Monitor]   (solo en la fila seleccionada)
 *
 * Iconos neutros por tipo en vez de los de clase de LN: el rojo con "!" de las protecciones
 * se leia como una alarma. El FC va solo junto a los atributos; los DO aparecen una vez
 * (ModelTreeBuilder junta las variantes por FC). La descripcion sale del SCL cargado o, si
 * no la trae, del diccionario de la norma.
 */
final class ArbolCeldaRenderer implements TreeCellRenderer {

    private static final Color LD_FONDO = new Color(0xE6ECF7), LD_TINTA = new Color(0x24467E);
    private static final Color LN_FONDO = new Color(0xE7F2EE), LN_TINTA = new Color(0x1E5A44);
    private static final Color OT_FONDO = new Color(0xF1F2F4), OT_TINTA = new Color(0x4A525D);
    private static final Color FC_FONDO = new Color(0xEEF0F3), FC_TINTA = new Color(0x3F4752);

    private final Set<String> watchlist;
    private final Supplier<File> scl;
    private final JPanel celda = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    private final Insignia tipo = new Insignia(Estilo.mono(Font.BOLD, 10)).anchoMinimo(26);
    private final JLabel nombre = new JLabel();
    private final Insignia fc = new Insignia(Estilo.mono(Font.PLAIN, 10)).alto(16);
    private final JLabel desc = new JLabel();
    /** Ver › Mostrar descripciones. */
    boolean mostrarDescripciones = true;
    private final JLabel valor = new JLabel();
    final Insignia monitor = new Insignia(Estilo.ui(Font.BOLD, 12)).alto(22);
    private final Map<String, String> descripciones = new HashMap<>();
    private File sclDeLasDescripciones;
    /** Descripciones del SCL, leidas en segundo plano (un SCD grande tarda). */
    private volatile DescripcionesScl delScl;

    ArbolCeldaRenderer(Set<String> watchlist, Supplier<File> scl) {
        this.watchlist = watchlist;
        this.scl = scl;
        celda.setOpaque(false);
        celda.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        desc.setFont(Estilo.ui(Font.PLAIN, 12));
        desc.setForeground(Estilo.TEXTO_2);
        valor.setFont(Estilo.mono(Font.PLAIN, 12));
        valor.setForeground(Estilo.TEXTO_2);
        monitor.poner("+ Monitor", Color.WHITE, Estilo.ACENTO, Estilo.ACENTO);
        celda.add(tipo);
        celda.add(nombre);
        celda.add(fc);
        celda.add(desc);
        celda.add(valor);
        celda.add(monitor);
    }

    @Override
    public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded,
                                                  boolean leaf, int row, boolean hasFocus) {
        Object uo = value instanceof DefaultMutableTreeNode ? ((DefaultMutableTreeNode) value).getUserObject() : value;
        fc.setVisible(false);
        desc.setVisible(false);
        valor.setVisible(false);
        monitor.setVisible(false);
        nombre.setForeground(Estilo.TEXTO);
        if (!(uo instanceof NodeInfo)) {
            tipo.setVisible(false);
            nombre.setFont(Estilo.ui(Font.BOLD, 13));
            nombre.setText(String.valueOf(uo));
            return celda;
        }
        NodeInfo info = (NodeInfo) uo;
        tipo.setVisible(true);
        String pre = info.prefix == null ? "" : info.prefix;
        boolean esDa = "DA".equals(pre) || "SDO".equals(pre);
        if ("LD".equals(pre)) tipo.poner("LD", LD_FONDO, LD_TINTA, null);
        else if ("LN".equals(pre)) tipo.poner("LN", LN_FONDO, LN_TINTA, null);
        else if ("DO".equals(pre)) tipo.poner("DO", OT_FONDO, OT_TINTA, null);
        else if (esDa) tipo.poner("DA", OT_FONDO, OT_TINTA, null);
        else tipo.poner(pre.length() > 4 ? pre.substring(0, 4) : pre, OT_FONDO, OT_TINTA, null);

        nombre.setText(info.name != null ? info.name : info.toString());
        nombre.setFont(esDa ? Estilo.mono(Font.PLAIN, 12) : Estilo.ui("LD".equals(pre) ? Font.BOLD : Font.PLAIN, 13));

        if (esDa && info.fc != null && !info.fc.isEmpty()) {
            fc.poner(info.fc, FC_FONDO, FC_TINTA, null);
            fc.setVisible(true);
        }
        if (esDa && info.value != null && !info.value.isEmpty()) {
            valor.setText(info.value);
            valor.setVisible(true);
        }
        if (!esDa && mostrarDescripciones) {
            String d = descripcion(info, tree);
            if (d != null && !d.isEmpty()) {
                desc.setText(d);
                desc.setVisible(true);
            }
        }

        // En el monitor: el nombre en el color de acento (antes, asterisco y azul)
        if (info.node instanceof FcModelNode) {
            String ref = info.node.getReference() + "$" + ((FcModelNode) info.node).getFc();
            if (watchlist.contains(ref)) nombre.setForeground(Estilo.ACENTO);
        }
        monitor.setVisible(sel && info.node instanceof FcModelNode);
        return celda;
    }

    /** Descripcion corta: la del SCL si la trae; si no, el nombre de la clase o del DO en la norma. */
    private String descripcion(NodeInfo info, JTree tree) {
        if (info.node == null) return null;
        File f = scl.get();
        if (f != sclDeLasDescripciones) {
            descripciones.clear();
            sclDeLasDescripciones = f;
            delScl = null;
            if (f != null) {
                Thread t = new Thread(() -> {
                    DescripcionesScl ds = DescripcionesScl.de(f);
                    SwingUtilities.invokeLater(() -> {
                        if (f != sclDeLasDescripciones) return;
                        delScl = ds;
                        descripciones.clear();
                        tree.repaint();
                    });
                }, "descripciones-scl");
                t.setDaemon(true);
                t.start();
            }
        }
        String ref = info.node.getReference().toString();
        if (descripciones.containsKey(ref)) return descripciones.get(ref);
        String d = null;
        DescripcionesScl ds = delScl;
        if (ds != null) {
            try { d = ds.descripcion(ref); } catch (Exception ignorada) { }
        }
        if (d == null || d.isEmpty() || d.length() > 60) {
            Iec61850Dictionary.Entry e = Iec61850Dictionary.lookup(info.name);
            boolean tipoOk = e != null && (("LN".equals(info.prefix) && e.type == Iec61850Dictionary.EntryType.LOGICAL_NODE)
                    || ("DO".equals(info.prefix) && e.type == Iec61850Dictionary.EntryType.DATA_OBJECT));
            if (tipoOk) {
                String n = I18n.currentTag().startsWith("es") ? e.fullNameES : e.fullNameEN;
                if (n != null && !n.isEmpty()) d = n;
            } else if ("LD".equals(info.prefix)) {
                String ld = Iec61850Dictionary.describeLogicalDevice(info.name);
                if (ld != null && ld.length() <= 60) d = ld;
            }
        }
        descripciones.put(ref, d);
        return d;
    }
}
