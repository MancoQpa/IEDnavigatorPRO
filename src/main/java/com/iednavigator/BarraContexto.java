package com.iednavigator;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;

/**
 * Barra de contexto bajo el menu (rediseño de 2026-11): que IED se esta viendo, en que modo,
 * en que estado, y la accion principal.
 *
 *   izquierda: nombre del IED, "modelo · fabricante · cfg" y el archivo SCL (abre otro);
 *   derecha:   Servidor | Cliente, la pildora de estado con el engranaje de la conexion, y
 *              el boton Iniciar simulacion / Conectar (F5).
 *
 * Reemplaza al logo, al cuadro del IED y a los paneles Servidor/Cliente de la columna
 * izquierda. No tiene estado propio: lo lee de la ventana en cada refrescar().
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
        String destinoCliente();
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
        /** Panel con los campos de la conexion del modo actual (puerto, host, timeout...). */
        JComponent panelConexion();
    }

    private final Estado estado;
    private final Acciones acciones;
    private final JLabel lblIed = new JLabel();
    private final JLabel lblSub = new JLabel();
    private final JButton btnScl = new JButton();
    private final JToggleButton segServidor = new JToggleButton(I18n.t("ctx.seg.server"));
    private final JToggleButton segCliente = new JToggleButton(I18n.t("ctx.seg.client"));
    private final Pildora pildora = new Pildora();
    private final JButton btnAccion = new JButton();

    BarraContexto(Estado estado, Acciones acciones) {
        super(new BorderLayout(16, 0));
        this.estado = estado;
        this.acciones = acciones;
        setBackground(superficie());
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Estilo.BORDE),
                new EmptyBorder(10, 20, 10, 20)));

        // ── Izquierda: IED ──────────────────────────────────────────────────
        JPanel izq = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        izq.setOpaque(false);
        lblIed.setFont(Estilo.ui(Font.BOLD, 15));
        lblSub.setFont(Estilo.ui(Font.PLAIN, 13));
        lblSub.setForeground(Estilo.TEXTO_2);
        btnScl.setFont(Estilo.mono(Font.PLAIN, 12));
        btnScl.setIcon(new IconoArchivo());
        btnScl.putClientProperty("FlatLaf.style", "arc:6; margin:2,8,2,8");
        btnScl.setToolTipText(I18n.t("menu.file.open"));
        btnScl.addActionListener(e -> acciones.abrirScl());
        izq.add(lblIed);
        izq.add(lblSub);
        izq.add(btnScl);
        add(izq, BorderLayout.CENTER);

        // ── Derecha: modo, estado, accion ───────────────────────────────────
        JPanel der = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
        der.setOpaque(false);

        JPanel seg = new JPanel(new FlowLayout(FlowLayout.CENTER, 2, 0)) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Estilo.FONDO);
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 10, 10));
                g2.dispose();
            }
        };
        seg.setOpaque(false);
        seg.setBorder(new EmptyBorder(3, 3, 3, 3));
        ButtonGroup grupo = new ButtonGroup();
        for (JToggleButton b : new JToggleButton[]{segServidor, segCliente}) {
            b.putClientProperty("FlatLaf.style", "arc:6; borderWidth:0; focusWidth:0; innerFocusWidth:0;"
                    + " background:#EEF0F3; selectedBackground:#FFFFFF; selectedForeground:#1B1F24;"
                    + " hoverBackground:#E4E7EC; margin:4,14,4,14");
            b.setFont(Estilo.ui(Font.PLAIN, 13));
            grupo.add(b);
            seg.add(b);
        }
        segServidor.addActionListener(e -> { if (!estado.modoServidor()) acciones.elegirModo(true); });
        segCliente.addActionListener(e -> { if (estado.modoServidor()) acciones.elegirModo(false); });
        der.add(seg);

        der.add(pildora);

        btnAccion.setFont(Estilo.ui(Font.BOLD, 14));
        btnAccion.setForeground(Color.WHITE);
        btnAccion.setIcon(new IconoPlay());
        btnAccion.setIconTextGap(8);
        btnAccion.addActionListener(e -> acciones.accionPrincipal());
        der.add(btnAccion);
        add(der, BorderLayout.EAST);

        refrescar();
    }

    /** Lee el estado de la ventana y lo muestra. Lo llama un Timer de la ventana. */
    void refrescar() {
        boolean srv = estado.modoServidor();
        segServidor.setSelected(srv);
        segCliente.setSelected(!srv);
        segServidor.setFont(Estilo.ui(srv ? Font.BOLD : Font.PLAIN, 13));
        segCliente.setFont(Estilo.ui(!srv ? Font.BOLD : Font.PLAIN, 13));

        String ied = estado.ied();
        boolean hayIed = ied != null && !ied.isEmpty();
        lblIed.setText(hayIed ? ied : I18n.t("ctx.noied"));
        lblIed.setForeground(hayIed ? Estilo.TEXTO : Estilo.TEXTO_2);
        String sub = estado.subtitulo();
        lblSub.setText(sub == null ? "" : sub);
        lblSub.setVisible(sub != null && !sub.isEmpty());

        String scl = estado.archivoScl();
        btnScl.setVisible(srv);
        btnScl.setText((scl != null ? scl : I18n.t("menu.file.open")) + "  ▾");

        // Pildora de estado
        String err = estado.error();
        if (err != null) {
            pildora.poner(Estilo.ERROR, I18n.t("ctx.error"), err);
        } else if (srv) {
            boolean corre = estado.servidorCorriendo();
            pildora.poner(corre ? Estilo.EXITO : new Color(0x6B7480),
                    I18n.t(corre ? "ctx.srv.running" : "ctx.srv.stopped"),
                    I18n.t("ctx.detail.port", estado.puertoServidor()));
        } else if (estado.conectando()) {
            pildora.poner(Estilo.AVISO, I18n.t("ctx.cli.connecting"), estado.destinoCliente());
        } else {
            boolean con = estado.conectado();
            pildora.poner(con ? Estilo.EXITO : new Color(0x6B7480),
                    I18n.t(con ? "ctx.cli.connected" : "ctx.cli.disconnected"), estado.destinoCliente());
        }

        // Accion principal
        boolean activo = srv ? estado.servidorCorriendo() : estado.conectado();
        String texto = I18n.t(srv ? (activo ? "ctx.btn.stop" : "ctx.btn.start")
                                  : (activo ? "ctx.btn.disconnect" : "ctx.btn.connect"));
        btnAccion.setText("<html>" + texto + "&nbsp;&nbsp;<span style='font-family:Consolas,monospace;"
                + "font-size:9px;color:#DCE6F5'>F5</span></html>");
        btnAccion.setEnabled(estado.accionHabilitada());
        String fondo = activo ? "#A11F14" : "#1F5FBF";
        String hover = activo ? "#861A11" : "#174A96";
        btnAccion.putClientProperty("FlatLaf.style", "arc:8; borderWidth:0; focusWidth:0; innerFocusWidth:0;"
                + " background:" + fondo + "; hoverBackground:" + hover + "; pressedBackground:" + hover + ";"
                + " foreground:#FFFFFF; disabledText:#E8EDF5; margin:8,18,8,18");
        ((IconoPlay) btnAccion.getIcon()).detener = activo;
        repaint();
    }

    private static Color superficie() {
        return Estilo.temaOscuro() ? UIManager.getColor("Panel.background") : Estilo.SUPERFICIE;
    }

    // ── Pildora de estado ───────────────────────────────────────────────────

    private final class Pildora extends JPanel {
        private Color color = Color.GRAY;
        private final JLabel texto = new JLabel();
        private final JLabel detalle = new JLabel();
        private final JButton engranaje = new JButton(new IconoEngranaje());

        Pildora() {
            super(new FlowLayout(FlowLayout.LEFT, 6, 0));
            setOpaque(false);
            setBorder(new EmptyBorder(7, 24, 7, 6));
            texto.setFont(Estilo.ui(Font.BOLD, 13));
            detalle.setFont(Estilo.ui(Font.PLAIN, 13));
            detalle.setForeground(Estilo.TEXTO_2);
            engranaje.putClientProperty("JButton.buttonType", "toolBarButton");
            engranaje.setToolTipText(I18n.t("ctx.gear.tip"));
            engranaje.addActionListener(e -> {
                JPopupMenu pm = new JPopupMenu();
                JComponent c = acciones.panelConexion();
                c.setBorder(new EmptyBorder(10, 12, 10, 12));
                pm.add(c);
                pm.show(engranaje, 0, engranaje.getHeight() + 4);
            });
            add(texto);
            add(detalle);
            add(engranaje);
        }

        void poner(Color c, String t, String d) {
            color = c;
            texto.setText(t);
            detalle.setText(d == null || d.isEmpty() ? "" : "· " + d);
            setToolTipText(t + (d == null ? "" : " " + d));
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
            g2.fill(new Ellipse2D.Float(12, h / 2f - 4, 8, 8));
            g2.dispose();
        }
    }

    // ── Iconos dibujados (sin archivos de imagen) ──────────────────────────

    private static final class IconoPlay implements Icon {
        boolean detener;
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Color.WHITE);
            if (detener) {
                g2.fillRect(x + 2, y + 2, 10, 10);
            } else {
                g2.fillPolygon(new int[]{x + 3, x + 13, x + 3}, new int[]{y + 1, y + 7, y + 13}, 3);
            }
            g2.dispose();
        }
        @Override public int getIconWidth() { return 14; }
        @Override public int getIconHeight() { return 14; }
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

    private static final class IconoEngranaje implements Icon {
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Estilo.TEXTO_2);
            g2.setStroke(new BasicStroke(1.6f));
            int cx = x + 7, cy = y + 7;
            for (int i = 0; i < 8; i++) {
                double a = Math.PI / 4 * i;
                g2.drawLine((int) Math.round(cx + Math.cos(a) * 4.5), (int) Math.round(cy + Math.sin(a) * 4.5),
                        (int) Math.round(cx + Math.cos(a) * 6.5), (int) Math.round(cy + Math.sin(a) * 6.5));
            }
            g2.draw(new Ellipse2D.Float(cx - 4.5f, cy - 4.5f, 9, 9));
            g2.draw(new Ellipse2D.Float(cx - 1.8f, cy - 1.8f, 3.6f, 3.6f));
            g2.dispose();
        }
        @Override public int getIconWidth() { return 14; }
        @Override public int getIconHeight() { return 14; }
    }
}
