package com.iednavigator;

import com.formdev.flatlaf.util.UIScale;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * Barra de contexto bajo el menu (rediseño de 2026-11): que IED se esta viendo, en que modo,
 * en que estado, y la accion principal. Una fila de 48 px, todo centrado en vertical.
 *
 *   izquierda (crece): nombre del IED, "modelo · fabricante · cfg", una ranura fija de 220 px
 *                      (Servidor: el archivo SCL, abre otro; Cliente: host:puerto editable) y,
 *                      solo en Servidor, el toggle Modo test al final.
 *   derecha (fija):    Servidor | Cliente, la pildora de estado y el boton principal (F5).
 *
 * Regla: al cambiar de modo nada se mueve. Por eso la ranura y todo el grupo derecho tienen
 * ancho fijo, calculado con el texto mas largo de los cuatro idiomas (no solo del actual).
 *
 * No tiene estado propio: lo lee de la ventana en cada refrescar().
 */
final class BarraContexto extends JPanel {

    /** Lo que la barra muestra; lo da la ventana principal. */
    interface Estado {
        boolean modoServidor();
        boolean servidorCorriendo();
        boolean conectado();
        boolean conectando();
        /** Texto del error vigente, o null si no hay. */
        String error();
        String puertoServidor();
        /** IP:puerto en que escucha el servidor simulado, o "" si no lo informo. */
        String destinoServidor();
        String host();
        String puertoCliente();
        String ied();
        String subtitulo();
        /** Nombre del SCL cargado, o null. */
        String archivoScl();
        boolean accionHabilitada();
    }

    /** Lo que la barra hace; lo resuelve la ventana principal. */
    interface Acciones {
        void abrirScl();
        void elegirModo(boolean servidor);
        void accionPrincipal();
        /** El campo host:puerto cambio: los mismos campos que lee la conexion. */
        void cambiarDestino(String host, String puerto);
    }

    private static final String[] IDIOMAS = {"es", "en", "pt", "zh"};

    private final Estado estado;
    private final Acciones acciones;
    private final JLabel lblIed = new JLabel();
    private final JLabel lblSub = new JLabel();
    private final CardLayout cartasRanura = new CardLayout();
    private final JPanel ranura = new JPanel(cartasRanura);
    private final BotonScl btnScl = new BotonScl();
    private final JTextField campoDestino = new JTextField();
    /** Modo test del IED: llega con la union con main (alternarModoTest); por ahora oculto. */
    final JToggleButton tglModoTest = new JToggleButton(I18n.t("ctx.testmode"), new IconoInterruptor());
    private final JToggleButton segServidor = new JToggleButton(I18n.t("ctx.seg.server"));
    private final JToggleButton segCliente = new JToggleButton(I18n.t("ctx.seg.client"));
    private final Pildora pildora = new Pildora();
    private final BotonAccion btnAccion = new BotonAccion();
    private boolean escribiendoDestino;

    BarraContexto(Estado estado, Acciones acciones) {
        super(new GridBagLayout());
        this.estado = estado;
        this.acciones = acciones;
        setBackground(superficie());
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Estilo.BORDE),
                new EmptyBorder(0, UIScale.scale(14), 0, UIScale.scale(14))));

        // ── Izquierda: IED, placa, ranura y modo test ───────────────────────
        JPanel izq = new JPanel();
        izq.setLayout(new BoxLayout(izq, BoxLayout.X_AXIS));
        izq.setOpaque(false);
        lblIed.setFont(Estilo.ui(Font.BOLD, 15));
        lblSub.setFont(Estilo.ui(Font.PLAIN, 12));
        lblSub.setForeground(Estilo.TEXTO_2);
        // Si no entra, se recortan con "…" las etiquetas; la ranura nunca
        lblIed.setMinimumSize(new Dimension(UIScale.scale(40), 0));
        lblSub.setMinimumSize(new Dimension(0, 0));

        btnScl.setFont(Estilo.mono(Font.PLAIN, 12));
        btnScl.setIcon(new IconoArchivo());
        btnScl.setIconTextGap(UIScale.scale(6));
        btnScl.setHorizontalAlignment(SwingConstants.LEFT);
        btnScl.putClientProperty("FlatLaf.style", "arc:6; focusWidth:0; innerFocusWidth:0; margin:0,8,0,24;"
                + " borderColor:#DDE1E7; background:#FFFFFF; foreground:#3F4752");
        btnScl.setToolTipText(I18n.t("menu.file.open"));
        btnScl.addActionListener(e -> acciones.abrirScl());

        campoDestino.setFont(Estilo.mono(Font.PLAIN, 12));
        campoDestino.putClientProperty("JTextField.leadingIcon", new IconoPantalla());
        campoDestino.putClientProperty("JTextField.placeholderText", I18n.t("ctx.dest.placeholder"));
        campoDestino.putClientProperty("FlatLaf.style", "arc:6; focusWidth:0; innerFocusWidth:1; borderColor:#C9CFD7");
        campoDestino.setToolTipText(I18n.t("ctx.dest.tip"));
        campoDestino.getAccessibleContext().setAccessibleName(I18n.t("ctx.dest.tip"));
        campoDestino.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { destinoEditado(); }
            @Override public void removeUpdate(DocumentEvent e) { destinoEditado(); }
            @Override public void changedUpdate(DocumentEvent e) { destinoEditado(); }
        });
        campoDestino.addActionListener(e -> { if (!estado.conectado() && !estado.conectando()) acciones.accionPrincipal(); });

        ranura.setOpaque(false);
        ranura.add(btnScl, "scl");
        ranura.add(campoDestino, "cli");
        fijar(ranura, UIScale.scale(220), UIScale.scale(30));

        tglModoTest.setFont(Estilo.ui(Font.PLAIN, 12));
        tglModoTest.setIconTextGap(UIScale.scale(6));
        tglModoTest.putClientProperty("FlatLaf.style", "arc:6; focusWidth:0; innerFocusWidth:0; margin:0,10,0,10;"
                + " borderColor:#DDE1E7; background:#FFFFFF; selectedBackground:#EAF1FB; foreground:#3F4752");
        fijar(tglModoTest, tglModoTest.getPreferredSize().width, UIScale.scale(30));
        tglModoTest.setVisible(false);

        izq.add(lblIed);
        izq.add(Box.createHorizontalStrut(UIScale.scale(10)));
        izq.add(lblSub);
        izq.add(Box.createHorizontalStrut(UIScale.scale(10)));
        izq.add(ranura);
        izq.add(Box.createHorizontalStrut(UIScale.scale(10)));
        izq.add(tglModoTest);
        izq.add(Box.createHorizontalGlue());
        for (Component c : izq.getComponents()) ((JComponent) c).setAlignmentY(CENTER_ALIGNMENT);

        GridBagConstraints g = new GridBagConstraints();
        g.gridy = 0;
        g.anchor = GridBagConstraints.CENTER;
        g.gridx = 0; g.weightx = 1; g.fill = GridBagConstraints.HORIZONTAL;
        add(izq, g);
        g.weightx = 0; g.fill = GridBagConstraints.NONE;

        // ── Derecha: modo, estado, accion (anchos fijos) ────────────────────
        JPanel seg = new JPanel(new GridLayout(1, 2, UIScale.scale(2), 0)) {
            @Override protected void paintComponent(Graphics gr) {
                Graphics2D g2 = (Graphics2D) gr.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Estilo.FONDO);
                float r = UIScale.scale(8f);
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), r * 2, r * 2));
                g2.dispose();
            }
        };
        seg.setOpaque(false);
        seg.setBorder(new EmptyBorder(UIScale.scale(3), UIScale.scale(3), UIScale.scale(3), UIScale.scale(3)));
        int anchoSeg = Math.max(UIScale.scale(92),
                maxAncho(Estilo.ui(Font.BOLD, 12), "ctx.seg.server", "ctx.seg.client") + UIScale.scale(24));
        ButtonGroup grupo = new ButtonGroup();
        for (JToggleButton b : new JToggleButton[]{segServidor, segCliente}) {
            b.putClientProperty("FlatLaf.style", "arc:6; borderWidth:0; focusWidth:0; innerFocusWidth:0; margin:0,0,0,0;"
                    + " background:#EEF0F3; selectedBackground:#FFFFFF; selectedForeground:#1B1F24;"
                    + " foreground:#4A525D; hoverBackground:#E4E7EC");
            b.setFont(Estilo.ui(Font.PLAIN, 12));
            fijar(b, anchoSeg, UIScale.scale(28));
            grupo.add(b);
            seg.add(b);
        }
        segServidor.addActionListener(e -> { if (!estado.modoServidor()) acciones.elegirModo(true); });
        segCliente.addActionListener(e -> { if (estado.modoServidor()) acciones.elegirModo(false); });
        g.gridx = 1; g.insets = new Insets(0, UIScale.scale(12), 0, 0);
        add(seg, g);

        int anchoPildora = Math.max(UIScale.scale(290),
                maxAncho(Estilo.ui(Font.BOLD, 13), "ctx.srv.running", "ctx.srv.stopped", "ctx.cli.connected",
                        "ctx.cli.disconnected", "ctx.cli.connecting", "ctx.error") + UIScale.scale(140));
        fijar(pildora, anchoPildora, UIScale.scale(32));
        g.gridx = 2;
        add(pildora, g);

        btnAccion.setFont(Estilo.ui(Font.BOLD, 13));
        btnAccion.setHorizontalAlignment(SwingConstants.LEFT);
        btnAccion.setIcon(new IconoPlay());
        btnAccion.setIconTextGap(UIScale.scale(8));
        btnAccion.addActionListener(e -> acciones.accionPrincipal());
        int anchoBoton = Math.max(UIScale.scale(190),
                maxAncho(Estilo.ui(Font.BOLD, 13), "ctx.btn.start", "ctx.btn.stop", "ctx.btn.connect", "ctx.btn.disconnect")
                        + UIScale.scale(14 + 8 + 12 + 12 + 34));
        fijar(btnAccion, anchoBoton, UIScale.scale(34));
        g.gridx = 3;
        add(btnAccion, g);

        refrescar();
    }

    @Override public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        return new Dimension(d.width, UIScale.scale(48));
    }

    @Override public Dimension getMinimumSize() {
        Dimension d = super.getMinimumSize();
        return new Dimension(d.width, UIScale.scale(48));
    }

    /** Lee el estado de la ventana y lo muestra. Lo llama un Timer de la ventana. */
    void refrescar() {
        boolean srv = estado.modoServidor();
        segServidor.setSelected(srv);
        segCliente.setSelected(!srv);
        segServidor.setFont(Estilo.ui(srv ? Font.BOLD : Font.PLAIN, 12));
        segCliente.setFont(Estilo.ui(!srv ? Font.BOLD : Font.PLAIN, 12));

        String ied = estado.ied();
        boolean hayIed = ied != null && !ied.isEmpty();
        lblIed.setText(hayIed ? ied : I18n.t("ctx.noied"));
        lblIed.setForeground(hayIed ? Estilo.TEXTO : Estilo.TEXTO_2);
        String sub = estado.subtitulo();
        lblSub.setText(sub == null ? "" : sub);
        lblSub.setVisible(sub != null && !sub.isEmpty());

        // Ranura fija: archivo SCL (Servidor) o host:puerto (Cliente)
        cartasRanura.show(ranura, srv ? "scl" : "cli");
        String scl = estado.archivoScl();
        btnScl.setText(scl != null ? scl : I18n.t("menu.file.open"));
        btnScl.setToolTipText(scl != null ? scl + " — " + I18n.t("menu.file.open") : I18n.t("menu.file.open"));
        boolean ocupado = estado.conectado() || estado.conectando();
        campoDestino.setEditable(!ocupado);
        String destino = estado.host() + ":" + estado.puertoCliente();
        if (!campoDestino.hasFocus() && !destino.equals(campoDestino.getText())) {
            escribiendoDestino = true;
            campoDestino.setText(destino);
            escribiendoDestino = false;
        }
        tglModoTest.setVisible(false);   // ver tglModoTest

        // Pildora de estado
        String err = estado.error();
        if (err != null) {
            pildora.poner(Estilo.ERROR, I18n.t("ctx.error"), err);
        } else if (srv) {
            boolean corre = estado.servidorCorriendo();
            String dest = estado.destinoServidor();
            pildora.poner(corre ? Estilo.EXITO : new Color(0x6B7480),
                    I18n.t(corre ? "ctx.srv.running" : "ctx.srv.stopped"),
                    corre && dest != null && !dest.isEmpty() ? dest : I18n.t("ctx.detail.port", estado.puertoServidor()));
        } else if (estado.conectando()) {
            pildora.poner(Estilo.AVISO, I18n.t("ctx.cli.connecting"), destino);
        } else {
            boolean con = estado.conectado();
            pildora.poner(con ? Estilo.EXITO : new Color(0x6B7480),
                    I18n.t(con ? "ctx.cli.connected" : "ctx.cli.disconnected"), con ? destino : "");
        }

        // Accion principal: rellena con el acento al iniciar o conectar, con contorno al detener
        boolean activo = srv ? estado.servidorCorriendo() : estado.conectado();
        btnAccion.setText(I18n.t(srv ? (activo ? "ctx.btn.stop" : "ctx.btn.start")
                                     : (activo ? "ctx.btn.disconnect" : "ctx.btn.connect")));
        btnAccion.setEnabled(estado.accionHabilitada());
        btnAccion.putClientProperty("FlatLaf.style", activo
                ? "arc:7; focusWidth:0; innerFocusWidth:0; borderWidth:1; borderColor:#C9CFD7; margin:0,12,0,12;"
                  + " background:#FFFFFF; hoverBackground:#F4F6F8; pressedBackground:#EEF0F3; foreground:#1B1F24"
                : "arc:7; focusWidth:0; innerFocusWidth:0; borderWidth:0; margin:0,12,0,12;"
                  + " background:#1F5FBF; hoverBackground:#174A96; pressedBackground:#174A96;"
                  + " foreground:#FFFFFF; disabledText:#E8EDF5");
        ((IconoPlay) btnAccion.getIcon()).detener = activo;
        repaint();
    }

    /** El usuario escribio en host:puerto: se pasa a los campos de la conexion. */
    private void destinoEditado() {
        if (escribiendoDestino) return;
        String t = campoDestino.getText().trim();
        int k = t.lastIndexOf(':');
        if (k > 0 && t.substring(k + 1).matches("\\d{1,5}")) {
            acciones.cambiarDestino(t.substring(0, k).trim(), t.substring(k + 1));
        } else {
            acciones.cambiarDestino(k > 0 ? t.substring(0, k).trim() : t, null);
        }
    }

    /** Ancho del texto mas largo de esas claves en los cuatro idiomas. */
    private int maxAncho(Font f, String... claves) {
        FontMetrics fm = getFontMetrics(f);
        int max = 0;
        for (String tag : IDIOMAS) {
            ResourceBundle b;
            try {
                b = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag(tag),
                        ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
            } catch (Exception e) { continue; }
            for (String k : claves) {
                try { max = Math.max(max, fm.stringWidth(b.getString(k))); } catch (Exception ignorada) { }
            }
        }
        for (String k : claves) max = Math.max(max, fm.stringWidth(I18n.t(k)));
        return max;
    }

    private static void fijar(JComponent c, int ancho, int alto) {
        Dimension d = new Dimension(ancho, alto);
        c.setPreferredSize(d);
        c.setMinimumSize(d);
        c.setMaximumSize(d);
    }

    private static Color superficie() {
        return Estilo.temaOscuro() ? UIManager.getColor("Panel.background") : Estilo.SUPERFICIE;
    }

    // ── Boton del SCL: nombre recortado con "…" y la flecha siempre visible ──

    private static final class BotonScl extends JButton {
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Estilo.TEXTO_2);
            g2.setStroke(new BasicStroke(UIScale.scale(1.6f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int cx = getWidth() - UIScale.scale(14), cy = getHeight() / 2;
            int r = UIScale.scale(3);
            g2.drawPolyline(new int[]{cx - r, cx, cx + r}, new int[]{cy - r / 2, cy + r / 2 + 1, cy - r / 2}, 3);
            g2.dispose();
        }
    }

    // ── Boton principal: etiqueta, y el atajo F5 en una tecla dibujada a la derecha ──

    private static final class BotonAccion extends JButton {
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Font f = Estilo.mono(Font.PLAIN, 10);
            FontMetrics fm = g2.getFontMetrics(f);
            int w = fm.stringWidth("F5") + UIScale.scale(8), h = fm.getHeight();
            int x = getWidth() - UIScale.scale(12) - w, y = (getHeight() - h) / 2;
            Color c = isEnabled() ? getForeground() : UIManager.getColor("Button.disabledText");
            g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 0xD9));
            g2.drawRoundRect(x, y, w - 1, h - 1, UIScale.scale(5), UIScale.scale(5));
            g2.setFont(f);
            g2.drawString("F5", x + UIScale.scale(4), y + fm.getAscent());
            g2.dispose();
        }
    }

    // ── Pildora de estado: punto de color, estado en negrita y detalle recortable ──

    private static final class Pildora extends JPanel {
        private Color color = Color.GRAY;
        private final JLabel texto = new JLabel();
        private final JLabel detalle = new JLabel();

        Pildora() {
            setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
            setOpaque(false);
            setBorder(new EmptyBorder(0, UIScale.scale(12 + 8 + 8), 0, UIScale.scale(12)));
            texto.setFont(Estilo.ui(Font.BOLD, 13));
            detalle.setFont(Estilo.mono(Font.PLAIN, 11));
            detalle.setForeground(Estilo.TEXTO_2);
            detalle.setMinimumSize(new Dimension(0, 0));
            texto.setAlignmentY(CENTER_ALIGNMENT);
            detalle.setAlignmentY(CENTER_ALIGNMENT);
            add(texto);
            add(Box.createHorizontalStrut(UIScale.scale(6)));
            add(detalle);
            add(Box.createHorizontalGlue());
        }

        void poner(Color c, String t, String d) {
            color = c;
            texto.setText(t);
            boolean hay = d != null && !d.isEmpty();
            detalle.setText(hay ? "· " + d : "");
            setToolTipText(t + (hay ? " · " + d : ""));
            getAccessibleContext().setAccessibleName(getToolTipText());
            revalidate();
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int h = getHeight();
            g2.setColor(new Color(0xF2F4F7));
            g2.fill(new RoundRectangle2D.Float(0, 0, getWidth() - 1, h - 1, h, h));
            g2.setColor(Estilo.BORDE);
            g2.draw(new RoundRectangle2D.Float(0, 0, getWidth() - 1, h - 1, h, h));
            g2.setColor(color);
            float d = UIScale.scale(8f);
            g2.fill(new Ellipse2D.Float(UIScale.scale(12f), h / 2f - d / 2, d, d));
            g2.dispose();
        }
    }

    // ── Iconos dibujados (sin archivos de imagen) ──────────────────────────

    private static final class IconoPlay implements Icon {
        boolean detener;
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(c.isEnabled() ? c.getForeground() : UIManager.getColor("Button.disabledText"));
            int s = UIScale.scale(1);
            if (detener) {
                g2.fillRect(x + 2 * s, y + 2 * s, 10 * s, 10 * s);
            } else {
                g2.fillPolygon(new int[]{x + 3 * s, x + 13 * s, x + 3 * s}, new int[]{y + s, y + 7 * s, y + 13 * s}, 3);
            }
            g2.dispose();
        }
        @Override public int getIconWidth() { return UIScale.scale(14); }
        @Override public int getIconHeight() { return UIScale.scale(14); }
    }

    private static final class IconoArchivo implements Icon {
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Estilo.TEXTO_2);
            g2.setStroke(new BasicStroke(1.4f));
            g2.drawPolygon(new int[]{x + 2, x + 8, x + 11, x + 11, x + 2}, new int[]{y + 1, y + 1, y + 4, y + 12, y + 12}, 5);
            g2.drawLine(x + 8, y + 1, x + 8, y + 4);
            g2.drawLine(x + 8, y + 4, x + 11, y + 4);
            g2.dispose();
        }
        @Override public int getIconWidth() { return 13; }
        @Override public int getIconHeight() { return 13; }
    }

    /** Monitor con pie: el IED al otro lado del cable, delante de host:puerto. */
    private static final class IconoPantalla implements Icon {
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Estilo.TEXTO_2);
            g2.setStroke(new BasicStroke(1.4f));
            g2.drawRoundRect(x + 1, y + 2, 11, 7, 2, 2);
            g2.drawLine(x + 6, y + 9, x + 6, y + 12);
            g2.drawLine(x + 4, y + 12, x + 9, y + 12);
            g2.dispose();
        }
        @Override public int getIconWidth() { return 13; }
        @Override public int getIconHeight() { return 13; }
    }

    /** Interruptor tipo switch del toggle Modo test: gris apagado, acento encendido. */
    private static final class IconoInterruptor implements Icon {
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            boolean on = c instanceof AbstractButton && ((AbstractButton) c).isSelected();
            int w = UIScale.scale(22), h = UIScale.scale(12), d = UIScale.scale(8), m = UIScale.scale(2);
            g2.setColor(on ? Estilo.ACENTO : new Color(0xC9CFD7));
            g2.fillRoundRect(x, y, w, h, h, h);
            g2.setColor(Color.WHITE);
            g2.fillOval(on ? x + w - m - d : x + m, y + m, d, d);
            g2.dispose();
        }
        @Override public int getIconWidth() { return UIScale.scale(22); }
        @Override public int getIconHeight() { return UIScale.scale(12); }
    }
}
