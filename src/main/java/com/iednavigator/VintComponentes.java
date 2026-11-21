package com.iednavigator;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Piezas gráficas del interruptor virtual: LED, símbolo del interruptor, interruptor deslizante y
 * botones de mando de color.
 * Colores: rojo cerrado/energizado, verde abierto/desenergizado.
 */
final class VintComponentes {

    private VintComponentes() {}

    static final Color VERDE_LED = new Color(0x22C55E);
    static final Color VERDE = new Color(0x16A34A);
    static final Color ROJO = new Color(0xDC2626);
    static final Color AMARILLO = new Color(0xEAB308);
    static final Color NARANJA = new Color(0xD97706);
    static final Color VIOLETA = new Color(0xC04BD8);
    static final Color APAGADO = new Color(0xD3D6DB);
    static final Color TENUE = new Color(0x6B7280);
    static final Color ACENTO = new Color(0x1673E0);

    /** LED redondo de un color. */
    static final class Led extends JComponent {
        private Color color;

        Led(int tam) {
            this(APAGADO, tam);
        }

        Led(Color color, int tam) {
            this.color = color;
            Dimension d = new Dimension(tam, tam);
            setPreferredSize(d);
            setMinimumSize(d);
            setMaximumSize(d);
        }

        void poner(Color c) {
            if (c.equals(color)) return;
            color = c;
            repaint();
        }

        Color color() { return color; }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int t = Math.min(getWidth(), getHeight()) - 2;
            Ellipse2D e = new Ellipse2D.Double(1, 1, t, t);
            g2.setColor(color);
            g2.fill(e);
            g2.setColor(color.darker());
            g2.setStroke(new BasicStroke(1f));
            g2.draw(e);
            g2.dispose();
        }
    }

    /**
     * Símbolo unifilar del interruptor: la línea con el aparato en el medio. Cerrado: rectángulo
     * rojo lleno; abierto: verde; intermedio: contorno amarillo punteado; bad-state: violeta con
     * una cruz.
     */
    static final class Simbolo extends JComponent {
        /** CERRADO, ABIERTO, INTERMEDIO o DISCORDANTE (los textos de EstadoLocal). */
        private String estado = "INTERMEDIO";

        Simbolo() {
            setPreferredSize(new Dimension(110, 170));
            setMinimumSize(new Dimension(80, 150));
        }

        void poner(String e) {
            String n = e == null ? "INTERMEDIO" : e;
            if (n.equals(estado)) return;
            estado = n;
            repaint();
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            int cx = w / 2;
            int bw = 34, bh = 70;
            int y0 = (h - bh) / 2;
            Color linea = UIManager.getColor("Label.foreground");
            if (linea == null) linea = Color.DARK_GRAY;
            g2.setColor(linea);
            g2.setStroke(new BasicStroke(5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
            g2.drawLine(cx, 4, cx, y0);
            g2.drawLine(cx, y0 + bh, cx, h - 4);
            Rectangle r = new Rectangle(cx - bw / 2, y0, bw, bh);
            switch (estado) {
                case "CERRADO":
                    g2.setColor(ROJO);
                    g2.fill(r);
                    break;
                case "ABIERTO":
                    g2.setColor(VERDE);
                    g2.fill(r);
                    break;
                case "DISCORDANTE":
                    g2.setColor(VIOLETA);
                    g2.setStroke(new BasicStroke(3f));
                    g2.draw(r);
                    g2.drawLine(r.x, r.y, r.x + r.width, r.y + r.height);
                    g2.drawLine(r.x + r.width, r.y, r.x, r.y + r.height);
                    break;
                default:
                    g2.setColor(AMARILLO);
                    g2.setStroke(new BasicStroke(3f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
                            new float[]{6f, 4f}, 0f));
                    g2.draw(r);
            }
            g2.dispose();
        }
    }

    /** Interruptor deslizante encendido/apagado (naranja encendido). */
    static final class Deslizante extends JComponent {
        private boolean encendido;
        private final List<Consumer<Boolean>> oyentes = new ArrayList<>();

        Deslizante() {
            Dimension d = new Dimension(38, 20);
            setPreferredSize(d);
            setMinimumSize(d);
            setMaximumSize(d);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    if (!isEnabled() || !SwingUtilities.isLeftMouseButton(e)) return;
                    poner(!encendido, true);
                }
            });
        }

        boolean encendido() { return encendido; }

        /** Cambia el estado; con {@code avisar} llama a los oyentes (solo el usuario). */
        void poner(boolean on, boolean avisar) {
            if (on == encendido) return;
            encendido = on;
            repaint();
            if (avisar) for (Consumer<Boolean> o : oyentes) o.accept(on);
        }

        void alCambiar(Consumer<Boolean> o) { oyentes.add(o); }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            Color fondo = encendido ? NARANJA : new Color(0xC9CDD3);
            if (!isEnabled()) fondo = new Color(fondo.getRed(), fondo.getGreen(), fondo.getBlue(), 110);
            g2.setColor(fondo);
            g2.fill(new RoundRectangle2D.Double(0, 0, w, h, h, h));
            g2.setColor(Color.WHITE);
            int d = h - 4;
            g2.fillOval(encendido ? w - d - 2 : 2, 2, d, d);
            g2.dispose();
        }
    }

    /** Botón de mando lleno (ABRIR verde, CERRAR rojo), pintado a mano para que FlatLaf no lo cambie. */
    static final class BotonMando extends JButton {
        private final Color base;

        BotonMando(String texto, Color base) {
            super(texto);
            this.base = base;
            setForeground(Color.WHITE);
            setFont(getFont().deriveFont(Font.BOLD, 15f));
            setFocusPainted(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(BorderFactory.createEmptyBorder(11, 10, 11, 10));
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color c = base;
            if (!isEnabled()) c = mezclar(base, Color.WHITE, 0.78f);
            else if (getModel().isPressed()) c = base.darker();
            else if (getModel().isRollover()) c = mezclar(base, Color.BLACK, 0.12f);
            g2.setColor(c);
            g2.fill(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), 14, 14));
            g2.dispose();
            setForeground(isEnabled() ? Color.WHITE : mezclar(base, Color.WHITE, 0.35f));
            super.paintComponent(g);
        }
    }

    static Color mezclar(Color a, Color b, float t) {
        return new Color(
                Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    /**
     * Botón de un grupo segmentado (LOCAL | REMOTO, autoridad), pintado a mano: FlatLaf impone sus
     * propios colores al botón seleccionado y el texto quedaba negro sobre azul oscuro.
     * Elegido: acento con texto blanco; si no, fondo claro con borde gris.
     */
    static final class Segmento extends JToggleButton {
        /** -1 primero (esquinas izquierdas redondeadas), 1 último, 0 en el medio. */
        private final int posicion;

        Segmento(String texto, int posicion) {
            super(texto);
            this.posicion = posicion;
            setFont(getFont().deriveFont(Font.BOLD));
            setFocusPainted(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(BorderFactory.createEmptyBorder(7, 6, 7, 6));
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight(), r = 8;
            java.awt.geom.Area forma = new java.awt.geom.Area(new RoundRectangle2D.Double(0, 0, w - 1, h - 1, r * 2, r * 2));
            if (posicion >= 0) forma.add(new java.awt.geom.Area(new Rectangle(0, 0, r * 2, h - 1)));
            if (posicion <= 0) forma.add(new java.awt.geom.Area(new Rectangle(w - 1 - r * 2, 0, r * 2, h - 1)));
            Color fondo, borde, texto;
            if (isSelected()) {
                fondo = isEnabled() ? ACENTO : mezclar(ACENTO, Color.WHITE, 0.6f);
                borde = fondo;
                texto = Color.WHITE;
            } else {
                Color base = UIManager.getColor("Button.background");
                fondo = getModel().isRollover() && isEnabled() ? mezclar(base == null ? Color.WHITE : base, ACENTO, 0.08f)
                        : (base == null ? Color.WHITE : base);
                borde = new Color(0xC9CDD3);
                Color fg = UIManager.getColor("Button.foreground");
                texto = isEnabled() ? (fg == null ? Color.BLACK : fg) : APAGADO.darker();
            }
            g2.setColor(fondo);
            g2.fill(forma);
            g2.setColor(borde);
            g2.draw(forma);
            // El texto también a mano: FlatLaf usa su propio color para el seleccionado
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setFont(getFont());
            g2.setColor(texto);
            FontMetrics fm = g2.getFontMetrics();
            String s = getText();
            int ancho = w - 6;
            if (fm.stringWidth(s) > ancho) {
                while (s.length() > 1 && fm.stringWidth(s + "…") > ancho) s = s.substring(0, s.length() - 1);
                s += "…";
            }
            g2.drawString(s, (w - fm.stringWidth(s)) / 2, (h - fm.getHeight()) / 2 + fm.getAscent());
            g2.dispose();
        }
    }

    /** Par de botones de selección (LOCAL | REMOTO) unidos, con el elegido en acento. */
    static JToggleButton[] segmentado(String izq, String der) {
        JToggleButton a = new Segmento(izq, -1), b = new Segmento(der, 1);
        ButtonGroup g = new ButtonGroup();
        g.add(a);
        g.add(b);
        return new JToggleButton[]{a, b};
    }

    /** Se conserva por compatibilidad: los Segmento se pintan solos. */
    static void pintarSegmento(JToggleButton t) {
        t.repaint();
    }

    /**
     * Íconos dibujados (los emojis de la referencia no se ven en Swing sobre Windows):
     * "pin" (siempre visible), "estado" (LEDs de comunicación) y "candado" (mando bloqueado).
     */
    static Icon icono(String tipo, Color color) {
        return new Icon() {
            @Override public int getIconWidth() { return "candado".equals(tipo) ? 12 : 16; }
            @Override public int getIconHeight() { return "candado".equals(tipo) ? 14 : 16; }
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.translate(x, y);
                Color col = color != null ? color : UIManager.getColor("Label.foreground");
                g2.setColor(col);
                switch (tipo) {
                    case "pin":
                        g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        g2.fillOval(6, 1, 9, 9);
                        g2.drawLine(9, 7, 2, 14);
                        g2.drawLine(4, 6, 10, 12);
                        break;
                    case "estado":
                        Color[] cs = {VERDE_LED, AMARILLO, ROJO};
                        for (int i = 0; i < 3; i++) {
                            g2.setColor(cs[i]);
                            g2.fillOval(1 + i * 5, 5, 5, 5);
                        }
                        break;
                    default: // candado
                        g2.setStroke(new BasicStroke(1.8f));
                        g2.drawArc(2, 0, 8, 10, 0, 180);
                        g2.drawLine(2, 5, 2, 7);
                        g2.drawLine(10, 5, 10, 7);
                        g2.fillRoundRect(0, 6, 12, 8, 3, 3);
                }
                g2.dispose();
            }
        };
    }

    /** Etiqueta chica gris (texto secundario). */
    static JLabel tenue(String texto, float tam) {
        JLabel l = new JLabel(texto);
        l.setForeground(TENUE);
        l.setFont(l.getFont().deriveFont(tam));
        return l;
    }

    /** Título de sección en mayúsculas, como en el panel del instructor de referencia. */
    static JLabel seccion(String texto) {
        JLabel l = new JLabel(texto.toUpperCase());
        l.setForeground(NARANJA);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 11.5f));
        l.setBorder(BorderFactory.createEmptyBorder(10, 0, 4, 0));
        return l;
    }
}
