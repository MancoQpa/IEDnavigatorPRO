package com.iednavigator;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Registro de la ventana (rediseño de 2026-11): panel acoplable, oculto por defecto, con
 * encabezado de contadores por nivel que sirven de filtro, Copiar, Exportar y una ✕ para
 * cerrarlo; cada linea es hora · nivel · mensaje, con ajuste de linea; los mensajes consecutivos
 * iguales se juntan con un contador "×N" (solo en pantalla).
 *
 * Cuenta tambien los avisos y errores llegados desde que se miro por ultima vez (marcarVisto):
 * es el badge del boton Registro de la fila de pestañas.
 *
 * El nivel no viene con el mensaje: los mensajes del programa son texto libre. Se deduce de
 * palabras como "error", "no se pudo" o "aviso"; es una aproximacion. El registro de sesion en
 * disco (SessionLog) no cambia: lo de aca es solo la vista.
 */
final class PanelRegistro extends JPanel {

    enum Nivel { INFO, AVISO, ERROR }

    private static final int MAX_ENTRADAS = 4000;

    private static final class Entrada {
        final String hora;
        final Nivel nivel;
        final String mensaje;
        int cuenta = 1;
        Entrada(String hora, Nivel nivel, String mensaje) { this.hora = hora; this.nivel = nivel; this.mensaje = mensaje; }
    }

    private final List<Entrada> entradas = new ArrayList<>();
    private final JTextPane texto = new JTextPane();
    private final JScrollPane scroll = new JScrollPane(texto);
    private final JToggleButton[] filtros = new JToggleButton[3];
    private final SimpleDateFormat formato = new SimpleDateFormat("HH:mm:ss");
    /** Avisos y errores llegados desde la ultima vez que se abrio el registro. */
    private int avisosNuevos, erroresNuevos;
    private Runnable alCambiarPendientes = () -> { };
    /** Donde empieza en el documento la ultima entrada mostrada (para actualizar su ×N). */
    private int inicioUltima = -1;

    private final SimpleAttributeSet estHora = new SimpleAttributeSet();
    private final SimpleAttributeSet estMensaje = new SimpleAttributeSet();
    private final SimpleAttributeSet estCuenta = new SimpleAttributeSet();
    private final SimpleAttributeSet[] estNivel = new SimpleAttributeSet[3];

    /**
     * @param exportar accion de Exportar (elige archivo y escribe getTexto())
     * @param cerrar   la ✕ del encabezado: la ventana oculta el panel
     */
    PanelRegistro(Runnable exportar, Runnable cerrar) {
        super(new BorderLayout());
        setOpaque(false);

        // ── Encabezado ──────────────────────────────────────────────────────
        JPanel enc = new JPanel(new BorderLayout(10, 0));
        enc.setOpaque(false);
        enc.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0xEEF0F3)),
                BorderFactory.createEmptyBorder(3, 10, 3, 6)));
        JPanel izq = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        izq.setOpaque(false);
        JLabel titulo = new JLabel(I18n.t("log.title"));
        titulo.setFont(Estilo.ui(Font.BOLD, 12));
        titulo.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 2));
        izq.add(titulo);
        String[] claves = {"log.level.info", "log.level.warn", "log.level.error"};
        Color[][] col = {
            {new Color(0xEAF1FB), new Color(0x174A96)},
            {new Color(0xFDF3E3), Estilo.AVISO},
            {new Color(0xFDECEA), Estilo.ERROR}};
        for (int i = 0; i < 3; i++) {
            // Pildora dibujada aca: FlatLaf no pinta el fondo de un toggle sin marcar
            JToggleButton b = new JToggleButton() {
                @Override protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(getBackground());
                    g2.fillRoundRect(0, 2, getWidth(), getHeight() - 4, getHeight() - 4, getHeight() - 4);
                    g2.dispose();
                    super.paintComponent(g);
                }
            };
            b.setContentAreaFilled(false);
            b.setOpaque(false);
            b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            b.setFont(Estilo.ui(Font.BOLD, 12));
            b.setForeground(col[i][1]);
            b.putClientProperty("FlatLaf.style", "arc:999; borderWidth:0; focusWidth:0; innerFocusWidth:0; margin:1,8,1,8");
            final Color claro = col[i][0], fuerte = col[i][1];
            b.setBackground(claro);
            // Marcado = filtro activo: colores invertidos
            b.addItemListener(ev -> {
                b.setBackground(b.isSelected() ? fuerte : claro);
                b.setForeground(b.isSelected() ? Color.WHITE : fuerte);
            });
            b.setToolTipText(I18n.t("log.level.tip"));
            b.addActionListener(e -> reconstruir());
            filtros[i] = b;
            izq.add(b);
        }
        enc.add(izq, BorderLayout.WEST);
        JPanel der = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        der.setOpaque(false);
        JButton copiar = enlace(I18n.t("log.copy"));
        copiar.addActionListener(e -> copiar());
        JButton exp = enlace(I18n.t("log.export"));
        exp.addActionListener(e -> exportar.run());
        JButton cerrarBtn = new JButton(new Cruz());
        cerrarBtn.putClientProperty("JButton.buttonType", "toolBarButton");
        cerrarBtn.setToolTipText(I18n.t("log.close"));
        cerrarBtn.getAccessibleContext().setAccessibleName(I18n.t("log.close"));
        cerrarBtn.addActionListener(e -> cerrar.run());
        der.add(copiar);
        der.add(exp);
        der.add(cerrarBtn);
        enc.add(der, BorderLayout.EAST);
        add(enc, BorderLayout.NORTH);

        // ── Lineas ──────────────────────────────────────────────────────────
        texto.setEditable(false);
        texto.setFont(Estilo.mono(Font.PLAIN, 12));
        texto.setMargin(new Insets(6, 14, 10, 14));
        texto.setBackground(Estilo.temaOscuro() ? UIManager.getColor("Panel.background") : Estilo.SUPERFICIE);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        add(scroll, BorderLayout.CENTER);

        StyleConstants.setForeground(estHora, Estilo.TEXTO_2);
        StyleConstants.setForeground(estMensaje, Estilo.temaOscuro() ? UIManager.getColor("TextPane.foreground") : Estilo.TEXTO);
        StyleConstants.setForeground(estCuenta, new Color(0x3F4752));
        StyleConstants.setBackground(estCuenta, new Color(0xEEF0F3));
        StyleConstants.setFontSize(estCuenta, 11);
        for (int i = 0; i < 3; i++) {
            estNivel[i] = new SimpleAttributeSet();
            StyleConstants.setBold(estNivel[i], true);
            StyleConstants.setFontSize(estNivel[i], 10);
            StyleConstants.setForeground(estNivel[i], col[i][1]);
            StyleConstants.setBackground(estNivel[i], col[i][0]);
        }
        actualizarContadores();
    }

    JTextPane areaTexto() { return texto; }

    /** Agrega un mensaje (en el hilo de Swing). */
    void agregar(String mensaje) {
        String m = mensaje == null ? "" : mensaje;
        Entrada ult = entradas.isEmpty() ? null : entradas.get(entradas.size() - 1);
        if (ult != null && ult.mensaje.equals(m)) {
            ult.cuenta++;
            contarNuevo(ult.nivel);
            if (visible(ult) && inicioUltima >= 0) {
                try {
                    StyledDocument d = texto.getStyledDocument();
                    d.remove(inicioUltima, d.getLength() - inicioUltima);
                    escribir(ult);
                } catch (BadLocationException e) {
                    reconstruir();
                }
            }
            actualizarContadores();
            return;
        }
        Entrada e = new Entrada(formato.format(new Date()), nivelDe(m), m);
        entradas.add(e);
        contarNuevo(e.nivel);
        if (entradas.size() > MAX_ENTRADAS) {
            entradas.subList(0, MAX_ENTRADAS / 8).clear();
            reconstruir();
        } else if (visible(e)) {
            inicioUltima = texto.getStyledDocument().getLength();
            escribir(e);
        }
        actualizarContadores();
    }

    void limpiar() {
        entradas.clear();
        reconstruir();
    }

    /** Todo el registro como texto, con el ×N de los repetidos (para Exportar y Copiar). */
    String getTexto() {
        StringBuilder sb = new StringBuilder();
        for (Entrada e : entradas) {
            sb.append(e.hora).append("  ").append(e.nivel).append("  ").append(e.mensaje);
            if (e.cuenta > 1) sb.append("  ×").append(e.cuenta);
            sb.append(System.lineSeparator());
        }
        return sb.toString();
    }

    void copiar() {
        String sel = texto.getSelectedText();
        String t = sel != null && !sel.isEmpty() ? sel : getTexto();
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(t), null);
    }


    /** El registro se abrio: los avisos y errores que habia ya se vieron. */
    void marcarVisto() {
        avisosNuevos = 0;
        erroresNuevos = 0;
        alCambiarPendientes.run();
    }

    int avisosNuevos() { return avisosNuevos; }

    int erroresNuevos() { return erroresNuevos; }

    /** Avisa a la ventana cuando cambian los avisos/errores sin ver (para el badge). */
    void alCambiarPendientes(Runnable r) { alCambiarPendientes = r == null ? () -> { } : r; }

    // ── Interno ─────────────────────────────────────────────────────────────

    private void contarNuevo(Nivel n) {
        if (n == Nivel.INFO || isShowing()) return;
        if (n == Nivel.AVISO) avisosNuevos++; else erroresNuevos++;
        alCambiarPendientes.run();
    }

    static Nivel nivelDe(String m) {
        String s = m.toLowerCase(Locale.ROOT);
        if (s.contains("error") || s.contains("exception") || s.contains("excepci") || s.contains("no se pudo")
                || s.contains("falló") || s.contains("fallo ") || s.contains("could not") || s.contains("failed")
                || s.contains("❌")) {
            return Nivel.ERROR;
        }
        if (s.contains("aviso") || s.contains("warn") || s.contains("advertencia") || s.contains("atenci")
                || s.contains("timeout") || s.contains("rechaz") || s.contains("⚠")) {
            return Nivel.AVISO;
        }
        return Nivel.INFO;
    }

    private boolean visible(Entrada e) {
        boolean alguno = false;
        for (JToggleButton b : filtros) alguno |= b.isSelected();
        return !alguno || filtros[e.nivel.ordinal()].isSelected();
    }

    private void reconstruir() {
        StyledDocument d = texto.getStyledDocument();
        try {
            d.remove(0, d.getLength());
            inicioUltima = -1;
            for (Entrada e : entradas) {
                if (!visible(e)) continue;
                inicioUltima = d.getLength();
                escribir(e);
            }
        } catch (BadLocationException ignorada) { }
        actualizarContadores();
    }

    private void escribir(Entrada e) {
        StyledDocument d = texto.getStyledDocument();
        try {
            d.insertString(d.getLength(), e.hora + "  ", estHora);
            // Mismo ancho para los tres niveles: asi los mensajes arrancan todos en la misma columna
            String n = e.nivel == Nivel.INFO ? "INFO " : e.nivel == Nivel.AVISO ? "AVISO" : "ERROR";
            d.insertString(d.getLength(), " " + n + " ", estNivel[e.nivel.ordinal()]);
            d.insertString(d.getLength(), "  " + e.mensaje, estMensaje);
            if (e.cuenta > 1) d.insertString(d.getLength(), "   ×" + e.cuenta + " ", estCuenta);
            d.insertString(d.getLength(), "\n", estMensaje);
        } catch (BadLocationException ignorada) { }
        texto.setCaretPosition(d.getLength());
    }

    private void actualizarContadores() {
        int[] n = new int[3];
        for (Entrada e : entradas) n[e.nivel.ordinal()] += e.cuenta;
        String[] claves = {"log.level.info", "log.level.warn", "log.level.error"};
        for (int i = 0; i < 3; i++) filtros[i].setText(I18n.t(claves[i]) + " " + n[i]);
    }

    private static JButton enlace(String t) {
        JButton b = new JButton(t);
        b.putClientProperty("JButton.buttonType", "borderless");
        b.setForeground(Estilo.ACENTO);
        b.setFont(Estilo.ui(Font.PLAIN, 12));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    /** La ✕ de cerrar, dibujada. */
    private static final class Cruz implements Icon {
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Estilo.TEXTO_2);
            g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawLine(x + 2, y + 2, x + 10, y + 10);
            g2.drawLine(x + 10, y + 2, x + 2, y + 10);
            g2.dispose();
        }
        @Override public int getIconWidth() { return 12; }
        @Override public int getIconHeight() { return 12; }
    }

    private static String hex(Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

}
