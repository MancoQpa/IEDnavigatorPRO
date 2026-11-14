package com.iednavigator;

import com.beanit.iec61850bean.*;
import com.formdev.flatlaf.util.UIScale;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreeCellRenderer;
import java.awt.*;
import java.awt.font.TextAttribute;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.Supplier;

/**
 * Fila del arbol del modelo (rediseño de 2026-11). Orden fijo:
 *
 *   marca de tipo · nombre · FC · valor · descripcion
 *
 *   LN  PDOP1  Sobrepotencia direccional
 *   DA  stVal  ST  = on
 *
 * - Marca: LD / LN / DO / DA en monoespaciada de 9 px, color propio, sin recuadro.
 * - Nombre: LD/LN/DO en sans de 12 px; DA en monoespaciada de 11 px.
 * - FC y valor solo en los DA. La calidad (q) solo si no es good, en color de aviso; los
 *   timestamps (t), solo la hora con milisegundos.
 * - Descripcion en gris al final, la primera en recortarse con "…": siempre en LD y LN; en
 *   los DO solo con el mouse encima o la fila seleccionada; nunca si repite el nombre o la
 *   clase del LN. Se apaga con Ver › Mostrar descripciones.
 *
 * Como maximo tres niveles: nombre oscuro, valor en acento, descripcion gris. Los fondos
 * (hover, seleccion, resalte de un cambio) los pinta ArbolUI en todo el ancho.
 */
final class ArbolCeldaRenderer implements TreeCellRenderer {

    private static final Color MARCA_LD = new Color(0x24467E), MARCA_LN = new Color(0x1E5A44),
            MARCA_DO = new Color(0x4A525D), MARCA_DA = new Color(0x626A76);   // 4,8:1 o mas sobre hover, seleccion y ambar
    private static final Color FC_FONDO = new Color(0xEEF0F3), FC_TINTA = new Color(0x3F4752);

    private final Set<String> watchlist;
    private final Supplier<File> scl;
    private final JPanel celda = new JPanel() {
        /** Nunca mas ancha que lo visible del arbol: asi la descripcion se recorta con "…". */
        @Override public Dimension getPreferredSize() {
            Dimension d = super.getPreferredSize();
            if (limite > 0 && d.width > limite) d.width = Math.max(limite, minimo());
            return d;
        }
    };
    private int limite;
    private final JLabel tipo = new JLabel();
    private final JLabel nombre = new JLabel();
    private final Insignia fc = new Insignia(Estilo.mono(Font.PLAIN, 10)).alto(14);
    private final JLabel valor = new JLabel();
    private final JLabel desc = new JLabel();
    private final Component sepFc = Box.createHorizontalStrut(UIScale.scale(6)),
            sepValor = Box.createHorizontalStrut(UIScale.scale(6)),
            sepDesc = Box.createHorizontalStrut(UIScale.scale(8));
    /** Ver › Mostrar descripciones. */
    boolean mostrarDescripciones = true;
    private final Map<String, String> descripciones = new HashMap<>();
    private File sclDeLasDescripciones;
    /** Descripciones del SCL, leidas en segundo plano (un SCD grande tarda). */
    private volatile DescripcionesScl delScl;
    private final SimpleDateFormat hora = new SimpleDateFormat("HH:mm:ss.SSS");
    private final Font fuenteNombre = Estilo.ui(Font.PLAIN, 12).deriveFont(
            Collections.singletonMap(TextAttribute.WEIGHT, TextAttribute.WEIGHT_MEDIUM));
    private final Font fuenteNombreDa = Estilo.mono(Font.PLAIN, 11);

    ArbolCeldaRenderer(Set<String> watchlist, Supplier<File> scl) {
        this.watchlist = watchlist;
        this.scl = scl;
        celda.setLayout(new BoxLayout(celda, BoxLayout.X_AXIS));
        celda.setOpaque(false);
        tipo.setFont(Estilo.mono(Font.BOLD, 9));
        tipo.setHorizontalAlignment(SwingConstants.LEFT);
        Dimension dt = new Dimension(UIScale.scale(18), UIScale.scale(14));
        tipo.setPreferredSize(dt);
        tipo.setMinimumSize(dt);
        tipo.setMaximumSize(dt);
        valor.setFont(Estilo.mono(Font.PLAIN, 11));
        desc.setFont(Estilo.ui(Font.PLAIN, 11));
        desc.setForeground(Estilo.TEXTO_2);
        desc.setMinimumSize(new Dimension(0, 0));
        // Cada separador se oculta con su componente: BoxLayout cuenta lo invisible como cero
        celda.add(tipo);
        celda.add(nombre);
        celda.add(sepFc);
        celda.add(fc);
        celda.add(sepValor);
        celda.add(valor);
        celda.add(sepDesc);
        celda.add(desc);
        for (Component c : celda.getComponents()) ((JComponent) c).setAlignmentY(0.5f);
    }

    /** Ancho sin la descripcion: lo minimo que se muestra entero. */
    private int minimo() {
        int w = 0;
        for (Component c : celda.getComponents()) {
            if (c == desc || c == sepDesc || !c.isVisible()) continue;
            w += c.getPreferredSize().width;
        }
        return w;
    }

    @Override
    public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded,
                                                  boolean leaf, int row, boolean hasFocus) {
        Object uo = value instanceof DefaultMutableTreeNode ? ((DefaultMutableTreeNode) value).getUserObject() : value;
        fc.setVisible(false);
        valor.setVisible(false);
        desc.setVisible(false);
        sepFc.setVisible(false);
        sepValor.setVisible(false);
        sepDesc.setVisible(false);
        nombre.setForeground(Estilo.TEXTO);
        limite = limiteDeAncho(tree, value);
        if (!(uo instanceof NodeInfo)) {
            tipo.setVisible(false);
            nombre.setFont(Estilo.ui(Font.BOLD, 12));
            nombre.setText(String.valueOf(uo));
            return celda;
        }
        NodeInfo info = (NodeInfo) uo;
        tipo.setVisible(true);
        String pre = info.prefix == null ? "" : info.prefix;
        boolean esDa = "DA".equals(pre) || "SDO".equals(pre);
        if ("LD".equals(pre))      marca("LD", MARCA_LD);
        else if ("LN".equals(pre)) marca("LN", MARCA_LN);
        else if ("DO".equals(pre)) marca("DO", MARCA_DO);
        else if (esDa)             marca("DA", MARCA_DA);
        else                       marca(pre.length() > 3 ? pre.substring(0, 3) : pre, MARCA_DO);

        nombre.setText(info.name != null ? info.name : info.toString());
        nombre.setFont(esDa ? fuenteNombreDa : fuenteNombre);

        if (esDa) {
            if (info.fc != null && !info.fc.isEmpty()) {
                fc.poner(info.fc, FC_FONDO, FC_TINTA, null);
                fc.setVisible(true);
                sepFc.setVisible(true);
            }
            ponerValor(info);
        } else if (mostrarDescripciones) {
            boolean mostrar = !"DO".equals(pre) || sel || Objects.equals(tree.getClientProperty(ArbolUI.FILA_HOVER), row);
            String d = mostrar ? descripcion(info, tree) : null;
            if (d != null && !d.isEmpty() && !repiteNombre(d, info.name)) {
                desc.setText(d);
                desc.setVisible(true);
                sepDesc.setVisible(true);
            }
        }

        // En el monitor: el nombre en el color de acento
        if (info.node instanceof FcModelNode) {
            String ref = info.node.getReference() + "$" + ((FcModelNode) info.node).getFc();
            if (watchlist.contains(ref)) nombre.setForeground(Estilo.ACENTO);
        }
        return celda;
    }

    private void marca(String t, Color c) {
        tipo.setText(t);
        tipo.setForeground(c);
    }

    /** "= valor" en acento; q solo si no es good (en aviso); t solo la hora. */
    private void ponerValor(NodeInfo info) {
        ModelNode n = info.node;
        String texto = null;
        Color color = Estilo.ACENTO;
        if (n instanceof BdaQuality) {
            texto = calidadNoBuena((BdaQuality) n);
            color = Estilo.AVISO;
        } else if (n instanceof BdaTimestamp) {
            try {
                java.time.Instant i = ((BdaTimestamp) n).getInstant();
                if (i != null) texto = hora.format(Date.from(i));
            } catch (Exception e) {
                texto = info.value;
            }
        } else if (info.value != null && !info.value.isEmpty()) {
            texto = info.value;
        }
        if (texto == null || texto.isEmpty()) return;
        valor.setText(n instanceof BdaQuality ? texto : "= " + texto);
        valor.setForeground(color);
        valor.setVisible(true);
        sepValor.setVisible(true);
    }

    /** "invalid", "questionable", "test", "test · blocked"…, o null si es good y sin marcas. */
    static String calidadNoBuena(BdaQuality q) {
        try {
            java.util.List<String> partes = new ArrayList<>();
            BdaQuality.Validity v = q.getValidity();
            if (v != BdaQuality.Validity.GOOD) partes.add(v == null ? "?" : v.name().toLowerCase(Locale.ROOT));
            if (q.isTest()) partes.add("test");
            if (q.isOperatorBlocked()) partes.add("blocked");
            if (q.isSubstituted()) partes.add("substituted");
            return partes.isEmpty() ? null : String.join(" · ", partes);
        } catch (Exception e) {
            return null;
        }
    }

    /** "LLN0 LLN0", "LPHD1 LPHD", "Q0CSWI1 CSWI": la descripcion no agrega nada. */
    static boolean repiteNombre(String d, String nombre) {
        if (nombre == null) return false;
        String dn = d.trim().toUpperCase(Locale.ROOT);
        String n = nombre.toUpperCase(Locale.ROOT);
        if (dn.equals(n)) return true;
        String sinNumero = n.replaceAll("\\d+$", "");
        return dn.length() >= 4 && dn.matches("[A-Z]{4}") && sinNumero.endsWith(dn);
    }

    /** Ancho visible del arbol menos la sangria de esta fila (0 si no se sabe). */
    private static int limiteDeAncho(JTree tree, Object value) {
        int visible = tree.getParent() instanceof JViewport ? tree.getParent().getWidth() : tree.getWidth();
        if (visible <= 0 || !(value instanceof DefaultMutableTreeNode)) return 0;
        int nivel = ((DefaultMutableTreeNode) value).getLevel() - (tree.isRootVisible() ? 0 : 1);
        int sangria = UIManager.getInt("Tree.leftChildIndent") + UIManager.getInt("Tree.rightChildIndent");
        if (sangria <= 0) sangria = UIScale.scale(20);
        return visible - (nivel + 1) * sangria - UIScale.scale(8);
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
