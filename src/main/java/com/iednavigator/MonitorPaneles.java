package com.iednavigator;

import com.beanit.iec61850bean.*;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Vista "Paneles" del monitor: cada señal es una tarjeta que se dibuja según su tipo
 * (CDC de la norma), en vez de una fila de tabla. Replica la vista que agregó un colaborador
 * en su paquete ui46 (solo binario): se reconstruyó a partir de su comportamiento.
 *
 *  - Interruptor (DPS, DPC): símbolo y "Cerrado/Abierto/Intermedio".
 *  - LED (SPS, SPC, ACT, ACD): punto y true/false; rojo si es un disparo u operación activa.
 *  - Número (MV, CMV, INS, INC, BCR, APC, BAC): valor grande con su unidad SI.
 *  - Etiqueta (ENS, ENC, ENG): punto de color y el nombre del enumerado (on, Ok, test...).
 *  - Fasor (WYE, DEL): diagrama de fasores de las tres fases, ocupa dos celdas.
 *  - Texto: lo demás.
 *
 * Lee los nodos del modelo de cada MonitorItem (el del servidor, o el del cliente que
 * actualizan el sondeo y los reportes) y repinta cada medio segundo mientras se ve.
 */
final class MonitorPaneles extends JPanel implements Scrollable {

    static final int CELDA_W = 176, CELDA_H = 104, GAP = 12, MARGEN = 12;

    private static final Color ROJO = new Color(0xD64545);
    private static final Color VERDE = new Color(0x2F8F4E);
    private static final Color AMBAR = new Color(0xD9891C);
    private static final Color AZUL = new Color(0x2F6FC4);
    private static final Color FASE_A = new Color(0xD85A30);
    private static final Color FASE_B = new Color(0x1D9E75);
    private static final Color FASE_C = new Color(0x378ADD);

    enum Tipo { FASOR, INTERRUPTOR, LED, NUMERO, ETIQUETA, TEXTO }

    private final Supplier<Collection<MonitorItem>> items;
    private final BiFunction<ModelNode, String, String> formatoEnum;
    private final Timer timer;
    /** Referencias de las tarjetas armadas: si cambia el conjunto de señales, se rearma. */
    private String firma = "";

    MonitorPaneles(Supplier<Collection<MonitorItem>> items, BiFunction<ModelNode, String, String> formatoEnum) {
        super(null);
        this.items = items;
        this.formatoEnum = formatoEnum;
        setLayout(new Grilla());
        setOpaque(false);
        timer = new Timer(500, e -> actualizar());
        // Solo trabaja mientras se ve (la pestaña Paneles elegida y el monitor a la vista)
        addHierarchyListener(e -> {
            if (isShowing()) {
                actualizar();
                timer.start();
            } else {
                timer.stop();
            }
        });
    }

    /** Rearma las tarjetas si cambió el conjunto de señales y repinta los valores. */
    void actualizar() {
        List<MonitorItem> v = visibles(new ArrayList<>(items.get()));
        StringBuilder f = new StringBuilder();
        for (MonitorItem it : v) f.append(it.reference).append('$').append(it.fc).append('\n');
        if (!f.toString().equals(firma)) {
            firma = f.toString();
            removeAll();
            for (MonitorItem it : v) add(new Tarjeta(it));
            revalidate();
        }
        repaint();
    }

    /**
     * Una tarjeta por señal: los DO, y los atributos sueltos que no son de un DO ya presente.
     * Antes de la vista de paneles el monitor agregaba cada atributo de un DO por separado.
     */
    static List<MonitorItem> visibles(List<MonitorItem> todos) {
        List<String> dos = new ArrayList<>();
        for (MonitorItem it : todos) {
            if (it.node instanceof FcDataObject && !dos.contains(it.reference)) dos.add(it.reference);
        }
        List<MonitorItem> r = new ArrayList<>();
        Set<String> vistos = new HashSet<>();
        for (MonitorItem it : todos) {
            if (it.reference == null) continue;
            boolean deUnDo = false;
            if (!(it.node instanceof FcDataObject)) {
                for (String d : dos) {
                    if (it.reference.startsWith(d + ".")) { deUnDo = true; break; }
                }
            }
            if (deUnDo || !vistos.add(it.reference)) continue;
            r.add(it);
        }
        return r;
    }

    // ── Lectura del modelo ──────────────────────────────────────────────────

    /** El nodo y, si es un DO agrupado por FC, sus otras variantes (ST, MX, CF...). */
    private static List<ModelNode> variantes(ModelNode n) {
        List<ModelNode> r = new ArrayList<>();
        if (n instanceof FcDataObject) {
            r.addAll(TiposIec61850.variantes((FcDataObject) n));
            if (!r.contains(n)) r.add(0, n);
        } else if (n != null) {
            r.add(n);
        }
        return r;
    }

    /** Hijo por camino ("cVal", "mag") en cualquiera de las variantes de FC del nodo. */
    static ModelNode buscar(ModelNode n, String... camino) {
        for (ModelNode v : variantes(n)) {
            ModelNode x = v;
            for (String c : camino) {
                x = x == null ? null : x.getChild(c);
            }
            if (x != null) return x;
        }
        return null;
    }

    static Double numero(ModelNode n) {
        if (n instanceof BdaFloat32) { Float f = ((BdaFloat32) n).getFloat(); return f == null ? null : f.doubleValue(); }
        if (n instanceof BdaFloat64) { Double d = ((BdaFloat64) n).getDouble(); return d; }
        if (n instanceof BdaInt8) return (double) ((BdaInt8) n).getValue();
        if (n instanceof BdaInt8U) return (double) ((BdaInt8U) n).getValue();
        if (n instanceof BdaInt16) return (double) ((BdaInt16) n).getValue();
        if (n instanceof BdaInt16U) return (double) ((BdaInt16U) n).getValue();
        if (n instanceof BdaInt32) return (double) ((BdaInt32) n).getValue();
        if (n instanceof BdaInt32U) return (double) ((BdaInt32U) n).getValue();
        if (n instanceof BdaInt64) return (double) ((BdaInt64) n).getValue();
        return null;
    }

    /** Valor numérico de un AnalogueValue (f o i) o de un atributo numérico. */
    static Double analogico(ModelNode n) {
        if (n == null) return null;
        Double d = numero(n);
        if (d != null) return d;
        d = numero(n.getChild("f"));
        return d != null ? d : numero(n.getChild("i"));
    }

    /** Valor principal de un DO de medida o contador: mag, cVal.mag, instMag, stVal, actVal. */
    static Double analogicoDe(ModelNode n) {
        String[][] caminos = {{"mag"}, {"cVal", "mag"}, {"instMag"}, {"stVal"}, {"actVal"}, {"general"}};
        for (String[] c : caminos) {
            Double d = analogico(buscar(n, c));
            if (d != null) return d;
        }
        return analogico(n);
    }

    /** Nombre del enumerado (on, Ok, test...) o null si el valor no es un enumerado conocido. */
    String nombreEnum(ModelNode n) {
        if (!(n instanceof BasicDataAttribute)) return null;
        Double num = numero(n);
        if (num == null) return null;
        String crudo = String.valueOf(num.longValue());
        String f = formatoEnum == null ? null : formatoEnum.apply(n, crudo);
        if (f != null) {
            int a = f.indexOf('['), b = f.lastIndexOf(']');
            if (a >= 0 && b > a) return f.substring(a + 1, b);
        }
        FcModelNode fn = (FcModelNode) n;
        String c = CatalogoIec61850.nombreEnumerado(n.getReference().toString(), fn.getFc(), num.longValue());
        if (c == null) return null;
        int p = c.lastIndexOf(" (");
        return p > 0 ? c.substring(0, p) : c;
    }

    Tipo tipoDe(MonitorItem it) {
        Double num = numero(it.node);
        if (num != null && !(it.node instanceof BdaFloat32) && !(it.node instanceof BdaFloat64)) {
            return nombreEnum(it.node) != null ? Tipo.ETIQUETA : Tipo.NUMERO;
        }
        return tipoBase(it);
    }

    static Tipo tipoBase(MonitorItem it) {
        ModelNode n = it.node;
        if (n instanceof FcDataObject) {
            String cdc = TiposIec61850.cdcDe((FcDataObject) n);
            if (cdc == null) return Tipo.TEXTO;
            switch (cdc) {
                case "WYE": case "DEL": return Tipo.FASOR;
                case "DPS": case "DPC": return Tipo.INTERRUPTOR;
                case "SPS": case "SPC": case "ACT": case "ACD": return Tipo.LED;
                case "MV": case "CMV": case "INS": case "INC": case "BCR": case "APC": case "BAC": return Tipo.NUMERO;
                case "ENS": case "ENC": case "ENG": return Tipo.ETIQUETA;
                default: return Tipo.TEXTO;
            }
        }
        if (n instanceof BdaBoolean) return Tipo.LED;
        if (n instanceof BdaDoubleBitPos) return Tipo.INTERRUPTOR;
        if (n instanceof BdaFloat32 || n instanceof BdaFloat64 || numero(n) != null) return Tipo.NUMERO;
        if (n instanceof ConstructedDataAttribute && (n.getChild("phsA") != null || n.getChild("phsAB") != null)) return Tipo.FASOR;
        if (n instanceof ConstructedDataAttribute && analogicoDe(n) != null) return Tipo.NUMERO;
        return Tipo.TEXTO;
    }

    /** Unidad SI del DO con su multiplicador ("kV", "A", "Hz"), o "" si no la declara. */
    static String unidad(ModelNode n) {
        ModelNode u = buscar(n, "units", "SIUnit");
        if (u == null) {
            ModelNode p = n == null ? null : n.getParent();
            if (p != null && !(n instanceof FcDataObject)) u = buscar(p, "units", "SIUnit");
        }
        Double cod = numero(u);
        if (cod == null) return "";
        String nombre = CatalogoIec61850.nombreEnumerado(u.getReference().toString(), Fc.CF, cod.longValue());
        if (nombre == null) return "";
        int p = nombre.lastIndexOf(" (");
        String unidad = p > 0 ? nombre.substring(0, p) : nombre;
        if (unidad.equals("none") || unidad.equals("1")) unidad = "";
        ModelNode m = u.getParent() == null ? null : u.getParent().getChild("multiplier");
        Double mult = numero(m);
        if (mult != null && mult != 0) {
            String pref = CatalogoIec61850.nombreEnumerado(m.getReference().toString(), Fc.CF, mult.longValue());
            if (pref != null) {
                int q = pref.lastIndexOf(" (");
                unidad = (q > 0 ? pref.substring(0, q) : pref) + unidad;
            }
        }
        return unidad;
    }

    /** Calidad para el rótulo de la tarjeta: {texto, "e" si es error} o null si es buena. */
    static String[] calidad(ModelNode n) {
        ModelNode q = n instanceof FcDataObject ? buscar(n, "q") : null;
        if (q == null && n != null && n.getParent() != null) q = n.getParent().getChild("q");
        if (!(q instanceof BdaQuality)) return null;
        BdaQuality bq = (BdaQuality) q;
        try {
            switch (bq.getValidity()) {
                case INVALID: return new String[]{"invalid", "e"};
                case QUESTIONABLE: return new String[]{"questionable", ""};
                default: break;
            }
            if (bq.isSubstituted()) return new String[]{I18n.t("mon.panels.substituted"), ""};
            if (bq.isTest()) return new String[]{"test", ""};
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    /** "LD/XCBR1.Pos" → "XCBR1.Pos". */
    static String titulo(String ref) {
        if (ref == null) return "";
        int i = ref.indexOf('/');
        return i >= 0 ? ref.substring(i + 1) : ref;
    }

    static String formato(double v) {
        double a = Math.abs(v);
        if (v == Math.rint(v) && a < 1e9) return String.valueOf((long) v);
        if (a >= 1000) return String.format(Locale.ROOT, "%.1f", v);
        if (a >= 10) return String.format(Locale.ROOT, "%.2f", v);
        return String.format(Locale.ROOT, "%.3f", v);
    }

    // ── Colores según el tema ───────────────────────────────────────────────

    static boolean oscuro() {
        Color c = UIManager.getColor("Panel.background");
        return c != null && (c.getRed() * 299 + c.getGreen() * 587 + c.getBlue() * 114) / 1000 < 128;
    }

    private static Color texto() {
        Color c = UIManager.getColor("Label.foreground");
        return c != null ? c : new Color(0x1F2328);
    }

    private static Color texto2() {
        return oscuro() ? new Color(0x9AA3AE) : new Color(0x5F6875);
    }

    private static Color linea() {
        return oscuro() ? new Color(0x3C4148) : new Color(0xDDE1E7);
    }

    private static Color apagado() {
        return oscuro() ? new Color(0x5B6169) : new Color(0xA9B4C4);
    }

    private static Color fondoTarjeta() {
        Color c = UIManager.getColor("Table.background");
        return c != null ? c : Color.WHITE;
    }

    private static String recortar(Graphics2D g, String s, int ancho) {
        FontMetrics fm = g.getFontMetrics();
        if (s == null) return "";
        if (fm.stringWidth(s) <= ancho) return s;
        while (s.length() > 1 && fm.stringWidth(s + "…") > ancho) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    @Override protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (getComponentCount() > 0) return;
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setFont(Estilo.ui(Font.PLAIN, 13));
        g2.setColor(texto2());
        String s = I18n.t("mon.panels.empty");
        Rectangle v = getVisibleRect();
        FontMetrics fm = g2.getFontMetrics();
        s = recortar(g2, s, Math.max(40, v.width - 40));
        g2.drawString(s, v.x + (v.width - fm.stringWidth(s)) / 2, v.y + Math.max(60, v.height / 3));
        g2.dispose();
    }

    // ── Scrollable: ocupa el ancho del visor y crece hacia abajo ────────────

    @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
    @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 24; }
    @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return CELDA_H + GAP; }
    @Override public boolean getScrollableTracksViewportWidth() { return true; }
    @Override public boolean getScrollableTracksViewportHeight() {
        return getParent() instanceof JViewport && getParent().getHeight() > getPreferredSize().height;
    }

    // ── Tarjeta ─────────────────────────────────────────────────────────────

    final class Tarjeta extends JComponent {
        final MonitorItem it;
        final Tipo tipo;

        Tarjeta(MonitorItem it) {
            this.it = it;
            this.tipo = tipoDe(it);
            setToolTipText(it.reference + "  [" + it.fc + "]");
        }

        /** Celdas de ancho y de alto que ocupa: el fasor, 2x2 (como en la vista del colaborador). */
        int span() { return tipo == Tipo.FASOR ? 2 : 1; }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            RoundRectangle2D caja = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 10, 10);
            g.setColor(fondoTarjeta());
            g.fill(caja);
            boolean cambio = it.resaltarDesde > 0 && System.currentTimeMillis() - it.resaltarDesde < 1500;
            g.setColor(cambio ? AMBAR : linea());
            g.setStroke(new BasicStroke(cambio ? 2f : 1f));
            g.draw(caja);

            // Calidad (solo si no es buena), arriba a la derecha
            int finTitulo = w - 14;
            String[] q = calidad(it.node);
            if (q != null) {
                g.setFont(Estilo.ui(Font.BOLD, 10));
                int tw = g.getFontMetrics().stringWidth(q[0]);
                Color c = "e".equals(q[1]) ? ROJO : AMBAR;
                g.setColor(c);
                g.fillOval(w - 14 - tw - 10, 15, 6, 6);
                g.drawString(q[0], w - 14 - tw, 21);
                finTitulo = w - 14 - tw - 16;
            }
            g.setFont(Estilo.ui(Font.BOLD, 11));
            g.setColor(texto2());
            g.drawString(recortar(g, titulo(it.reference), finTitulo - 14), 14, 21);

            switch (tipo) {
                case LED: pintarLed(g, w, h); break;
                case INTERRUPTOR: pintarInterruptor(g, w, h); break;
                case NUMERO: pintarNumero(g, w, h); break;
                case ETIQUETA: pintarEtiqueta(g, w, h); break;
                case FASOR: pintarFasor(g, w, h); break;
                default: pintarTexto(g, w, h);
            }
            g.dispose();
        }

        /** Línea de base del valor principal. */
        private int base(int h) { return h - 26; }

        private void sinLectura(Graphics2D g, int y) {
            g.setFont(Estilo.ui(Font.PLAIN, 14));
            g.setColor(apagado());
            g.drawString(I18n.t("mon.panels.noread"), 14, y);
        }

        private void pintarLed(Graphics2D g, int w, int h) {
            ModelNode n = it.node instanceof BdaBoolean ? it.node : buscar(it.node, "stVal");
            if (n == null) n = buscar(it.node, "general");
            Boolean v = n instanceof BdaBoolean ? ((BdaBoolean) n).getValue() : null;
            int y = base(h);
            if (v == null) { sinLectura(g, y); return; }
            String ref = it.reference;
            boolean disparo = ref.endsWith(".Op") || ref.endsWith(".Tr") || ref.contains(".Op.") || ref.contains(".Tr.");
            Color c = v ? (disparo ? ROJO : VERDE) : apagado();
            if (v) {
                g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 50));
                g.fillOval(10, y - 20, 24, 24);
            }
            g.setColor(c);
            g.fillOval(14, y - 16, 16, 16);
            g.setFont(Estilo.ui(Font.PLAIN, 22));
            g.setColor(v ? texto() : apagado());
            g.drawString(v ? "true" : "false", 40, y);
        }

        private void pintarInterruptor(Graphics2D g, int w, int h) {
            ModelNode n = it.node instanceof BdaDoubleBitPos ? it.node : buscar(it.node, "stVal");
            int y = base(h);
            if (!(n instanceof BdaDoubleBitPos)) { sinLectura(g, y); return; }
            BdaDoubleBitPos.DoubleBitPos p = ((BdaDoubleBitPos) n).getDoubleBitPos();
            Color c;
            String txt;
            switch (p) {
                case ON:  c = ROJO;  txt = I18n.t("mon.panels.closed"); break;
                case OFF: c = VERDE; txt = I18n.t("mon.panels.open"); break;
                case INTERMEDIATE_STATE: c = AMBAR; txt = I18n.t("mon.panels.intermediate"); break;
                default:  c = ROJO;  txt = "bad-state";
            }
            // Símbolo: línea vertical con el aparato en el medio
            int x = 16, top = y - 40, alto = 44;
            g.setColor(c);
            g.setStroke(new BasicStroke(1.6f));
            g.drawLine(x + 6, top, x + 6, top + 9);
            g.drawLine(x + 6, top + alto - 9, x + 6, top + alto);
            Rectangle r = new Rectangle(x, top + 9, 12, alto - 18);
            if (p == BdaDoubleBitPos.DoubleBitPos.ON) {
                g.fill(r);
            } else {
                g.draw(r);
                if (p == BdaDoubleBitPos.DoubleBitPos.OFF) g.drawLine(r.x + 2, r.y + r.height - 2, r.x + r.width - 2, r.y + 2);
                if (p == BdaDoubleBitPos.DoubleBitPos.BAD_STATE) {
                    g.drawLine(r.x, r.y, r.x + r.width, r.y + r.height);
                    g.drawLine(r.x + r.width, r.y, r.x, r.y + r.height);
                }
            }
            g.setFont(Estilo.ui(Font.PLAIN, 22));
            g.setColor(texto());
            g.drawString(recortar(g, txt, w - 50), 42, y);
        }

        private void pintarNumero(Graphics2D g, int w, int h) {
            Double v = it.node instanceof BasicDataAttribute ? numero(it.node) : analogicoDe(it.node);
            int y = base(h);
            if (v == null) { sinLectura(g, y); return; }
            String s = formato(v);
            String u = unidad(it.node);
            g.setFont(Estilo.ui(Font.PLAIN, 28));
            g.setColor(texto());
            g.drawString(recortar(g, s, w - 28 - (u.isEmpty() ? 0 : 30)), 14, y);
            if (!u.isEmpty()) {
                int x = 14 + Math.min(g.getFontMetrics().stringWidth(s), w - 58) + 6;
                g.setFont(Estilo.ui(Font.PLAIN, 13));
                g.setColor(texto2());
                g.drawString(u, x, y);
            }
        }

        private void pintarEtiqueta(Graphics2D g, int w, int h) {
            ModelNode n = it.node instanceof BasicDataAttribute ? it.node : buscar(it.node, "stVal");
            int y = base(h);
            Double num = numero(n);
            if (num == null) { sinLectura(g, y); return; }
            String nombre = nombreEnum(n);
            String txt = nombre != null ? nombre : String.valueOf(num.longValue());
            String k = txt.toLowerCase(Locale.ROOT);
            Color c;
            if (k.equals("on") || k.equals("ok")) c = VERDE;
            else if (k.startsWith("test") || k.equals("warning")) c = AMBAR;
            else if (k.contains("blocked") || k.equals("off") || k.equals("alarm")) c = ROJO;
            else c = AZUL;
            g.setColor(c);
            g.fillOval(14, y - 13, 10, 10);
            g.setFont(Estilo.ui(Font.PLAIN, 22));
            g.setColor(texto());
            g.drawString(recortar(g, txt, w - 46), 32, y);
        }

        private void pintarTexto(Graphics2D g, int w, int h) {
            String s = it.value;
            if ((s == null || s.isEmpty()) && it.node instanceof BasicDataAttribute) {
                s = ValorBda.texto((BasicDataAttribute) it.node);
            }
            if ((s == null || s.isEmpty()) && it.node != null) {
                ModelNode sv = buscar(it.node, "stVal");
                if (sv instanceof BasicDataAttribute) s = ValorBda.texto((BasicDataAttribute) sv);
            }
            int y = base(h);
            if (s == null || s.isEmpty()) { sinLectura(g, y); return; }
            g.setFont(Estilo.ui(Font.PLAIN, 16));
            g.setColor(texto());
            g.drawString(recortar(g, s, w - 28), 14, y);
        }

        /**
         * Plano fasorial como en la vista del colaborador: tarjeta de 2x2 celdas, círculo a la
         * izquierda y las tres fases a la derecha. Los vectores se dibujan solo con el ángulo
         * medido; si el equipo publica solo magnitudes (ION 7400: su vector trae mag y no ang),
         * el plano queda vacío y se dice por qué, en vez de inventar 0/-120/+120.
         */
        private void pintarFasor(Graphics2D g, int w, int h) {
            boolean estrella = buscar(it.node, "phsA") != null;
            String[] fases = estrella ? new String[]{"phsA", "phsB", "phsC"} : new String[]{"phsAB", "phsBC", "phsCA"};
            String[] nombres = estrella ? new String[]{"A", "B", "C"} : new String[]{"AB", "BC", "CA"};
            Color[] colores = {FASE_A, FASE_B, FASE_C};
            Double[] mag = new Double[3], ang = new Double[3];
            double max = 0;
            boolean hayAngulo = false;
            for (int i = 0; i < 3; i++) {
                ModelNode cv = buscar(it.node, fases[i], "cVal");
                if (cv == null) continue;
                mag[i] = analogico(cv.getChild("mag"));
                ang[i] = analogico(cv.getChild("ang"));
                if (mag[i] != null) max = Math.max(max, Math.abs(mag[i]));
                if (ang[i] != null) hayAngulo = true;
            }
            ModelNode fase0 = buscar(it.node, fases[0]);
            String u = unidad(fase0 != null ? fase0 : it.node);

            // Plano: círculo con sus ejes, a la izquierda
            int r = Math.max(30, Math.min((h - 46) / 2, (w / 2 - 30) / 2));
            int cx = 16 + r, cy = 30 + (h - 30) / 2 - 2;
            g.setColor(linea());
            g.setStroke(new BasicStroke(1f));
            g.drawOval(cx - r, cy - r, 2 * r, 2 * r);
            g.drawLine(cx - r, cy, cx + r, cy);
            g.drawLine(cx, cy - r, cx, cy + r);
            if (hayAngulo && max > 0) {
                g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                for (int i = 0; i < 3; i++) {
                    if (mag[i] == null || ang[i] == null) continue;
                    double a = Math.toRadians(ang[i]);
                    double l = r * Math.abs(mag[i]) / max;
                    int x2 = cx + (int) Math.round(l * Math.cos(a));
                    int y2 = cy - (int) Math.round(l * Math.sin(a));
                    g.setColor(colores[i]);
                    g.drawLine(cx, cy, x2, y2);
                    flecha(g, cx, cy, x2, y2);
                }
            }

            // Fases a la derecha
            int x = cx + r + 26;
            int ancho = w - x - 12;
            if (max <= 0) {
                g.setFont(Estilo.ui(Font.PLAIN, 12));
                g.setColor(texto2());
                g.drawString(I18n.t("mon.panels.noread"), x, cy + 4);
                return;
            }
            int y = cy - 26;
            for (int i = 0; i < 3; i++) {
                int yy = y + i * 26;
                g.setFont(Estilo.ui(Font.BOLD, 13));
                g.setColor(colores[i]);
                g.drawString(nombres[i], x, yy);
                g.setFont(Estilo.ui(Font.PLAIN, 16));
                g.setColor(texto());
                String s = mag[i] == null ? "—" : formato(mag[i]);
                int xs = x + 30;
                int anchoMag = ancho - 30 - 62;
                g.drawString(recortar(g, s, anchoMag), xs, yy);
                int fin = xs + Math.min(g.getFontMetrics().stringWidth(s), anchoMag);
                g.setFont(Estilo.ui(Font.PLAIN, 11));
                g.setColor(texto2());
                if (!u.isEmpty()) g.drawString(u, fin + 4, yy);
                String an = ang[i] == null ? "∠ —" : "∠ " + formato(ang[i]) + "°";
                g.drawString(an, x + ancho - g.getFontMetrics().stringWidth(an), yy);
            }
            if (!hayAngulo) {
                g.setFont(Estilo.ui(Font.PLAIN, 10));
                g.setColor(texto2());
                // En dos líneas si hace falta: la columna de fases es angosta
                String nota = I18n.t("mon.panels.noangle");
                FontMetrics fm = g.getFontMetrics();
                String l1 = nota, l2 = null;
                if (fm.stringWidth(nota) > ancho) {
                    int corte = nota.lastIndexOf(' ', nota.length() / 2 + 6);
                    if (corte > 0) { l1 = nota.substring(0, corte); l2 = nota.substring(corte + 1); }
                }
                g.drawString(recortar(g, l1, ancho), x, y + 3 * 26 + 2);
                if (l2 != null) g.drawString(recortar(g, l2, ancho), x, y + 3 * 26 + 15);
            }
        }

        private void flecha(Graphics2D g, int x1, int y1, int x2, int y2) {
            double a = Math.atan2(y2 - y1, x2 - x1);
            if (Math.hypot(x2 - x1, y2 - y1) < 8) return;
            int l = 7;
            int xa = x2 - (int) Math.round(l * Math.cos(a - 0.45)), ya = y2 - (int) Math.round(l * Math.sin(a - 0.45));
            int xb = x2 - (int) Math.round(l * Math.cos(a + 0.45)), yb = y2 - (int) Math.round(l * Math.sin(a + 0.45));
            g.fillPolygon(new int[]{x2, xa, xb}, new int[]{y2, ya, yb}, 3);
        }
    }

    // ── Grilla: celdas fijas que se acomodan al ancho; el fasor ocupa 2x2 ──

    private final class Grilla implements LayoutManager {
        @Override public void addLayoutComponent(String name, Component comp) { }
        @Override public void removeLayoutComponent(Component comp) { }
        @Override public Dimension minimumLayoutSize(Container c) { return new Dimension(CELDA_W + 2 * MARGEN, CELDA_H); }

        @Override public Dimension preferredLayoutSize(Container c) {
            int ancho = c.getWidth() > 0 ? c.getWidth() : 4 * (CELDA_W + GAP) + 2 * MARGEN;
            return new Dimension(ancho, ubicar(c, ancho, false));
        }

        @Override public void layoutContainer(Container c) { ubicar(c, c.getWidth(), true); }

        /**
         * Coloca cada tarjeta en el primer hueco libre, recorriendo por filas (una ocupación
         * de celdas, así una chica llena el lugar al lado de un fasor). Devuelve el alto total.
         */
        private int ubicar(Container c, int ancho, boolean mover) {
            int cols = Math.max(1, (ancho - 2 * MARGEN + GAP) / (CELDA_W + GAP));
            List<boolean[]> ocupado = new ArrayList<>();
            int filas = 0;
            for (Component k : c.getComponents()) {
                int span = k instanceof Tarjeta ? Math.min(cols, ((Tarjeta) k).span()) : 1;
                int alto = k instanceof Tarjeta ? ((Tarjeta) k).span() : 1;
                int[] lugar = lugar(ocupado, cols, span, alto);
                for (int f = lugar[0]; f < lugar[0] + alto; f++) {
                    while (ocupado.size() <= f) ocupado.add(new boolean[cols]);
                    for (int col = lugar[1]; col < lugar[1] + span; col++) ocupado.get(f)[col] = true;
                }
                filas = Math.max(filas, lugar[0] + alto);
                if (mover) {
                    k.setBounds(MARGEN + lugar[1] * (CELDA_W + GAP), MARGEN + lugar[0] * (CELDA_H + GAP),
                            span * CELDA_W + (span - 1) * GAP, alto * CELDA_H + (alto - 1) * GAP);
                }
            }
            return 2 * MARGEN + filas * (CELDA_H + GAP);
        }

        /** Primera {fila, columna} con span x alto celdas libres. */
        private int[] lugar(List<boolean[]> ocupado, int cols, int span, int alto) {
            for (int f = 0; ; f++) {
                for (int col = 0; col + span <= cols; col++) {
                    boolean libre = true;
                    for (int ff = f; ff < f + alto && libre; ff++) {
                        if (ff >= ocupado.size()) continue;
                        for (int cc = col; cc < col + span; cc++) {
                            if (ocupado.get(ff)[cc]) { libre = false; break; }
                        }
                    }
                    if (libre) return new int[]{f, col};
                }
            }
        }
    }
}
