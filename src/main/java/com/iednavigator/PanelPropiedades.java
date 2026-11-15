package com.iednavigator;

import com.beanit.iec61850bean.*;
import com.formdev.flatlaf.util.UIScale;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Panel Propiedades, debajo del árbol del modelo (rediseño de 2026-11). Reemplaza al
 * Explorador del Data Model, que era un segundo árbol del mismo modelo con sus propios
 * errores (tipos de Java en la columna Tipo, un "Exportar XML" que no escribía nada).
 *
 * Muestra el nodo seleccionado en términos de la norma:
 *
 *   Referencia  LD/PDOP1.Beh.stVal            [Copiar]
 *   DO          Beh · ENS (del SCL)
 *   Tipo        ENUMERATED (BehaviourModeKind)
 *   FC          ST — Estado
 *   Valor       1 · on
 *   Calidad     good
 *   Hora        2026-11-15 10:27:31.204
 *
 * En un LN: clase y descripción. En un DO: CDC con su origen, descripción, FC y cantidad de
 * atributos. En un DA: tipo, FC, valor (con el nombre del enumerado), calidad y hora del
 * mismo objeto, en ámbar si son anormales. Plegable (⌃) y redimensionable; la ventana
 * recuerda el estado.
 */
final class PanelPropiedades extends JPanel {

    /** Una fila: etiqueta, valor, en aviso, monoespaciada, con botón Copiar. */
    static final class Fila {
        final String clave, valor;
        final boolean aviso, mono, copiar;
        Fila(String clave, String valor, boolean aviso, boolean mono, boolean copiar) {
            this.clave = clave; this.valor = valor == null ? "" : valor;
            this.aviso = aviso; this.mono = mono; this.copiar = copiar;
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Fila)) return false;
            Fila f = (Fila) o;
            return clave.equals(f.clave) && valor.equals(f.valor) && aviso == f.aviso;
        }
        @Override public int hashCode() { return Objects.hash(clave, valor, aviso); }
    }

    private final ArbolCeldaRenderer renderer;
    private final JPanel cuerpo = new JPanel(new GridBagLayout());
    private final JScrollPane scroll = new JScrollPane(cuerpo);
    private final JButton plegar = new JButton();
    private final JLabel vacio = new JLabel();
    private NodeInfo actual;
    private List<Fila> mostradas = new ArrayList<>();
    private boolean plegado;
    private Consumer<Boolean> alPlegar = p -> { };

    PanelPropiedades(ArbolCeldaRenderer renderer) {
        super(new BorderLayout());
        this.renderer = renderer;
        setOpaque(false);

        JPanel cabecera = new JPanel(new BorderLayout());
        cabecera.setOpaque(false);
        cabecera.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(6), UIScale.scale(10), UIScale.scale(4), UIScale.scale(6)));
        JLabel titulo = new JLabel(I18n.t("props.title"));
        titulo.setFont(Estilo.ui(Font.BOLD, 12));
        cabecera.add(titulo, BorderLayout.WEST);
        plegar.putClientProperty("JButton.buttonType", "toolBarButton");
        plegar.setFont(Estilo.ui(Font.PLAIN, 12));
        plegar.addActionListener(e -> ponerPlegado(!plegado));
        cabecera.add(plegar, BorderLayout.EAST);
        add(cabecera, BorderLayout.NORTH);

        cuerpo.setOpaque(false);
        cuerpo.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(2), UIScale.scale(10), UIScale.scale(8), UIScale.scale(10)));
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        add(scroll, BorderLayout.CENTER);
        vacio.setFont(Estilo.ui(Font.PLAIN, 12));
        vacio.setForeground(Estilo.TEXTO_2);
        ponerPlegado(false);
        mostrar(null);
    }

    /** La ventana se entera de plegar/desplegar para mover el separador. */
    void alPlegar(Consumer<Boolean> c) { alPlegar = c == null ? p -> { } : c; }

    boolean plegado() { return plegado; }

    void ponerPlegado(boolean p) {
        plegado = p;
        scroll.setVisible(!p);
        plegar.setText(p ? "▴" : "▾");
        plegar.setToolTipText(I18n.t(p ? "props.expand" : "props.collapse"));
        revalidate();
        if (!p && mostradas != null) refrescar();
        alPlegar.accept(p);
    }

    /** Alto de la cabecera: lo que ocupa plegado. */
    int altoCabecera() { return getComponent(0).getPreferredSize().height + UIScale.scale(2); }

    /** Muestra el nodo (null: ninguno seleccionado). */
    void mostrar(NodeInfo info) {
        actual = info;
        refrescar();
    }

    /** Vuelve a leer el nodo actual (los valores cambian); no toca nada si quedó igual. */
    void refrescar() {
        if (plegado || !isShowing() && actual != null && !mostradas.isEmpty()) return;
        List<Fila> filas = actual == null ? new ArrayList<>() : filas(actual, renderer);
        if (filas.equals(mostradas) && cuerpo.getComponentCount() > 0) return;
        mostradas = filas;
        cuerpo.removeAll();
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.NORTHWEST;
        g.insets = new Insets(UIScale.scale(2), 0, UIScale.scale(2), UIScale.scale(10));
        if (filas.isEmpty()) {
            vacio.setText(I18n.t("props.empty"));
            g.gridx = 0; g.gridy = 0; g.weightx = 1; g.fill = GridBagConstraints.HORIZONTAL;
            cuerpo.add(vacio, g);
        }
        int y = 0;
        for (Fila f : filas) {
            g.gridy = y++;
            g.gridx = 0; g.weightx = 0; g.fill = GridBagConstraints.NONE;
            JLabel k = new JLabel(f.clave);
            k.setFont(Estilo.ui(Font.PLAIN, 11));
            k.setForeground(Estilo.TEXTO_2);
            cuerpo.add(k, g);
            g.gridx = 1; g.weightx = 1; g.fill = GridBagConstraints.HORIZONTAL;
            JTextField v = new JTextField(f.valor);
            v.setColumns(1);   // que el ancho lo dé la grilla: así el botón Copiar entra entero
            v.setEditable(false);
            v.setBorder(BorderFactory.createEmptyBorder());
            v.setOpaque(false);
            v.setFont(f.mono ? Estilo.mono(Font.PLAIN, 11) : Estilo.ui(Font.PLAIN, 12));
            v.setForeground(f.aviso ? SimbolosArbol.VALOR_ADVERTENCIA : Estilo.TEXTO);
            v.setToolTipText(f.valor);
            v.setCaretPosition(0);
            cuerpo.add(v, g);
            if (f.copiar) {
                g.gridx = 2; g.weightx = 0; g.fill = GridBagConstraints.NONE;
                JButton c = new JButton(I18n.t("props.copy"));
                c.putClientProperty("JButton.buttonType", "borderless");
                c.setForeground(Estilo.ACENTO);
                c.setFont(Estilo.ui(Font.PLAIN, 11));
                c.setMargin(new Insets(0, 4, 0, 4));
                c.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new StringSelection(f.valor), null));
                cuerpo.add(c, g);
            }
        }
        // Relleno: las filas arriba
        g.gridy = y; g.gridx = 0; g.weighty = 1; g.gridwidth = 3; g.fill = GridBagConstraints.BOTH;
        cuerpo.add(Box.createGlue(), g);
        cuerpo.revalidate();
        cuerpo.repaint();
    }

    // ── Contenido ───────────────────────────────────────────────────────────

    /** Las filas para un nodo, en términos de la norma (sin nombres de clases de Java). */
    static List<Fila> filas(NodeInfo info, ArbolCeldaRenderer r) {
        List<Fila> f = new ArrayList<>();
        ModelNode n = info.node;
        if (n == null) return f;
        String pre = info.prefix == null ? "" : info.prefix;
        String ref = n.getReference().toString();
        f.add(new Fila(I18n.t("props.ref"), ref, false, true, true));

        switch (pre) {
            case "LD": {
                f.add(new Fila(I18n.t("props.element"), I18n.t("dm.elem.ld"), false, false, false));
                String d = r == null ? null : r.descripcionDe(info);
                if (d != null && !d.isEmpty()) f.add(new Fila(I18n.t("props.desc"), d, false, false, false));
                f.add(new Fila(I18n.t("props.lns"), String.valueOf(n.getChildren().size()), false, false, false));
                break;
            }
            case "LN": {
                f.add(new Fila(I18n.t("props.element"), I18n.t("dm.elem.ln"), false, false, false));
                String clase = Iec61850Dictionary.inferLnClass(n.getName());
                if (clase == null && n.getName().startsWith("LLN0")) clase = "LLN0";
                if (clase != null) f.add(new Fila(I18n.t("props.class"), clase + nombre(clase), false, false, false));
                String d = r == null ? null : r.descripcionDe(info);
                if (d != null && !d.isEmpty() && !ArbolCeldaRenderer.repiteNombre(d, n.getName())) {
                    f.add(new Fila(I18n.t("props.desc"), d, false, false, false));
                }
                Set<String> dos = new LinkedHashSet<>();
                for (ModelNode h : n.getChildren()) dos.add(h.getName());
                f.add(new Fila(I18n.t("props.dos"), String.valueOf(dos.size()), false, false, false));
                break;
            }
            case "DO":
            case "SDO": {
                String elem = "SDO".equals(pre) ? I18n.t("dm.elem.sdo") : I18n.t("dm.elem.do");
                f.add(new Fila(I18n.t("props.element"), elem, false, false, false));
                String[] c = r == null ? cdcSinScl(n) : r.cdcDe(info);
                f.add(new Fila(I18n.t("props.cdc"), c == null ? I18n.t("dm.cdc.unknown")
                        : c[0] + nombre(c[0]) + "  (" + ("scl".equals(c[1]) ? I18n.t("props.cdc.scl") : I18n.t("props.cdc.inferred")) + ")",
                        false, false, false));
                String d = r == null ? null : r.descripcionDe(info);
                if (d != null && !d.isEmpty()) f.add(new Fila(I18n.t("props.desc"), d, false, false, false));
                if (n instanceof FcDataObject) {
                    f.add(new Fila(I18n.t("props.fcs"), TiposIec61850.fcsDe((FcDataObject) n), false, true, false));
                    Set<String> atr = new LinkedHashSet<>();
                    for (FcDataObject v : TiposIec61850.variantes((FcDataObject) n)) {
                        for (ModelNode h : v.getChildren()) atr.add(h.getName());
                    }
                    f.add(new Fila(I18n.t("props.attrs"), String.valueOf(atr.size()), false, false, false));
                }
                break;
            }
            default: {
                String elem = "BDA".equals(pre) ? I18n.t("dm.elem.bda") : "DA".equals(pre) ? I18n.t("dm.elem.da") : I18n.t("dm.elem.node");
                f.add(new Fila(I18n.t("props.element"), elem, false, false, false));
                FcDataObject dueno = dueno(n);
                if (dueno != null) {
                    String[] c = r == null ? cdcSinScl(dueno) : r.cdcDe(nodo(dueno));
                    f.add(new Fila(I18n.t("props.of"), dueno.getName() + (c == null ? "" : " · " + c[0]
                            + ("deducido".equals(c[1]) ? "*" : "")), false, true, false));
                }
                String tipo = TiposIec61850.tipoDe(n);
                if (!tipo.isEmpty()) f.add(new Fila(I18n.t("props.type"), tipo, false, true, false));
                if (n instanceof FcModelNode) {
                    String fc = String.valueOf(((FcModelNode) n).getFc());
                    f.add(new Fila(I18n.t("props.fc"), fc + nombre(fc), false, false, false));
                    f.add(new Fila(I18n.t("props.mmsref"), referenciaMms(ref, fc), false, true, false));
                }
                if (n instanceof BasicDataAttribute) {
                    BasicDataAttribute bda = (BasicDataAttribute) n;
                    boolean sinLeer = r != null && r.sinLeer.test(n);
                    if (sinLeer) {
                        f.add(new Fila(I18n.t("props.value"), I18n.t("props.unread"), true, true, false));
                    } else {
                        f.add(valor(I18n.t("props.value"), bda, info, r));
                    }
                    String mms = TiposIec61850.bType(bda.getBasicType());
                    if (!mms.equals(tipo) && !"Struct".equals(mms)) {
                        f.add(new Fila(I18n.t("props.mms"), mms, false, true, false));
                    }
                }
                // Calidad y hora del mismo objeto (lo que acompaña al valor)
                if (dueno != null && !(n instanceof BdaQuality) && !(n instanceof BdaTimestamp)
                        && n.getParent() instanceof FcDataObject) {
                    ModelNode q = n.getParent().getChild("q");
                    ModelNode t = n.getParent().getChild("t");
                    if (q instanceof BdaQuality && !(r != null && r.sinLeer.test(q))) f.add(valor(I18n.t("props.quality"), (BdaQuality) q, null, r));
                    if (t instanceof BdaTimestamp && !(r != null && r.sinLeer.test(t))) f.add(valor(I18n.t("props.time"), (BdaTimestamp) t, null, r));
                }
            }
        }
        return f;
    }

    /** Valor de un atributo: enumerados con su nombre, calidad, hora completa; ámbar si es anormal. */
    private static Fila valor(String clave, BasicDataAttribute bda, NodeInfo info, ArbolCeldaRenderer r) {
        if (bda instanceof BdaQuality) {
            String mal = ArbolCeldaRenderer.calidadNoBuena((BdaQuality) bda);
            return new Fila(clave, mal == null ? "good" : mal, mal != null, true, false);
        }
        if (bda instanceof BdaTimestamp) {
            BdaTimestamp ts = (BdaTimestamp) bda;
            java.time.Instant i = null;
            try { i = ts.getInstant(); } catch (Exception ignorada) { }
            if (i == null || i.getEpochSecond() == 0) return new Fila(clave, I18n.t("tree.t.nosync"), true, true, false);
            String txt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(Date.from(i));
            boolean sinSync = false;
            try { sinSync = ts.getClockNotSynchronized(); } catch (Exception ignorada) { }
            return new Fila(clave, sinSync ? txt + "  (" + I18n.t("tree.t.nosync") + ")" : txt, sinSync, true, false);
        }
        String txt;
        if (info != null && r != null && info.value != null && !info.value.isEmpty()) {
            txt = r.textoValor(info);
        } else {
            NodeInfo tmp = new NodeInfo();
            tmp.node = bda;
            tmp.value = ValorBda.texto(bda);
            txt = tmp.value == null ? "" : (r != null ? r.textoValor(tmp) : tmp.value);
        }
        return new Fila(clave, txt, txt.endsWith(")") && txt.contains("· ("), true, false);
    }

    /** El DO o SDO al que pertenece un atributo. */
    private static FcDataObject dueno(ModelNode n) {
        ModelNode p = n.getParent();
        while (p != null && !(p instanceof FcDataObject)) p = p.getParent();
        return (FcDataObject) p;
    }

    private static NodeInfo nodo(FcDataObject d) {
        NodeInfo i = new NodeInfo();
        i.node = d;
        i.name = d.getName();
        i.prefix = ModelTreeBuilder.getNodePrefix(d);
        return i;
    }

    private static String[] cdcSinScl(ModelNode n) {
        if (!(n instanceof FcDataObject)) return null;
        String c = TiposIec61850.cdcDe((FcDataObject) n);
        return c == null ? null : new String[]{c, "deducido"};
    }

    /** " — Estado (Status)" para un término del diccionario (FC, CDC, clase de LN), o "". */
    static String nombre(String termino) {
        Iec61850Dictionary.Entry e = Iec61850Dictionary.lookup(termino);
        if (e == null) return "";
        String n = I18n.currentTag().startsWith("es") ? e.fullNameES : e.fullNameEN;
        return n == null || n.isEmpty() ? "" : " — " + n;
    }

    /** "LD/LN.DO.DA" con FC "ST" → "LD/LN$ST$DO$DA" (el nombre que viaja por MMS). */
    static String referenciaMms(String ref, String fc) {
        int barra = ref.indexOf('/');
        int punto = ref.indexOf('.', barra + 1);
        if (barra < 0 || punto < 0) return ref;
        return ref.substring(0, punto) + "$" + fc + "$" + ref.substring(punto + 1).replace('.', '$');
    }
}
