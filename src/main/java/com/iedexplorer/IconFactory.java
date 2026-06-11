package com.iedexplorer;

import javax.swing.*;
import java.awt.*;
import java.util.Map;

/**
 * Fase 11: Fábrica de iconos para el árbol de modelo IEC 61850.
 * Extraído de IEDExplorerApp.java — métodos createXxxIcon + initIcons.
 */
class IconFactory {

    private IconFactory() {}

    /** Puebla el mapa iconCache con todos los iconos estándar de la aplicación. */
    static void fillCache(Map<String, Icon> iconCache) {
        // Iconos para estados de breaker
        iconCache.put("breaker_on",           createBreakerIcon("on"));
        iconCache.put("breaker_off",          createBreakerIcon("off"));
        iconCache.put("breaker_intermediate", createBreakerIcon("intermediate"));

        // Iconos para tipos de LN
        iconCache.put("ln_xcbr",    createNodeIcon("LN", new Color(200,  50,  50)));
        iconCache.put("ln_xswi",    createNodeIcon("LN", new Color(200, 100,  50)));
        iconCache.put("ln_mmxu",    createMeterIcon(new Color(0, 100, 200)));
        iconCache.put("ln_mmtr",    createMeterIcon(new Color(0, 150, 100)));
        iconCache.put("ln_cswi",    createNodeIcon("LN", new Color(150, 100, 200)));
        iconCache.put("ln_default", createNodeIcon("LN", new Color(100, 150, 100)));

        // Iconos para nodos
        iconCache.put("ld", createNodeIcon("LD", new Color(100, 100, 200)));
        iconCache.put("do", createNodeIcon("DO", new Color(150, 150, 200)));
        iconCache.put("da", createCircleIcon(new Color(100, 180, 100), 12));

        // Iconos para GOOSE
        iconCache.put("goose_container", createNodeIcon("GO", new Color(255, 140, 0)));
        iconCache.put("goose_gcb",       createNodeIcon("GC", new Color(200, 100, 0)));
    }

    static Icon createCircleIcon(Color color, int size) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(color);
                g2.fillOval(x + 1, y + 1, size - 2, size - 2);
                g2.setColor(color.darker());
                g2.drawOval(x + 1, y + 1, size - 2, size - 2);
                g2.dispose();
            }
            @Override public int getIconWidth()  { return size; }
            @Override public int getIconHeight() { return size; }
        };
    }

    static Icon createMeterIcon(Color color) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(240, 240, 240));
                g2.fillRoundRect(x + 1, y + 1, 14, 14, 3, 3);
                g2.setColor(color);
                g2.drawRoundRect(x + 1, y + 1, 14, 14, 3, 3);
                g2.setColor(color);
                g2.drawArc(x + 3, y + 4, 10, 10, 0, 180);
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawLine(x + 8, y + 12, x + 12, y + 6);
                g2.dispose();
            }
            @Override public int getIconWidth()  { return 16; }
            @Override public int getIconHeight() { return 16; }
        };
    }

    static Icon createBreakerIcon(String state) {
        return new Icon() {
            private final Color COLOR_ON           = new Color(0, 180, 0);
            private final Color COLOR_OFF          = new Color(220, 50, 50);
            private final Color COLOR_INTERMEDIATE = new Color(255, 165, 0);

            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                Color color; Color bgColor; boolean closed;
                if (state.equalsIgnoreCase("on") || state.equals("2")) {
                    color = COLOR_ON; bgColor = new Color(200, 255, 200); closed = true;
                } else if (state.equalsIgnoreCase("off") || state.equals("1")) {
                    color = COLOR_OFF; bgColor = new Color(255, 200, 200); closed = false;
                } else {
                    color = COLOR_INTERMEDIATE; bgColor = new Color(255, 240, 200); closed = false;
                }

                g2.setColor(bgColor);
                g2.fillRoundRect(x, y, 16, 16, 4, 4);
                g2.setColor(color);
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawRoundRect(x, y, 15, 15, 4, 4);
                g2.setStroke(new BasicStroke(2f));
                g2.drawLine(x + 2, y + 10, x + 5, y + 10);
                g2.drawLine(x + 11, y + 10, x + 14, y + 10);
                if (closed) {
                    g2.drawLine(x + 5, y + 10, x + 11, y + 10);
                } else {
                    g2.drawLine(x + 5, y + 10, x + 10, y + 5);
                }
                g2.fillOval(x + 4, y + 8, 4, 4);
                g2.dispose();
            }
            @Override public int getIconWidth()  { return 16; }
            @Override public int getIconHeight() { return 16; }
        };
    }

    static Icon createLargeBreakerIcon(String state) {
        return new Icon() {
            private final Color COLOR_ON           = new Color(0, 180, 0);
            private final Color COLOR_OFF          = new Color(220, 50, 50);
            private final Color COLOR_INTERMEDIATE = new Color(255, 165, 0);

            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                Color color; Color bgColor; boolean closed;
                if (state.equalsIgnoreCase("on") || state.equals("2")) {
                    color = COLOR_ON; bgColor = new Color(200, 255, 200); closed = true;
                } else if (state.equalsIgnoreCase("off") || state.equals("1")) {
                    color = COLOR_OFF; bgColor = new Color(255, 200, 200); closed = false;
                } else {
                    color = COLOR_INTERMEDIATE; bgColor = new Color(255, 240, 200); closed = false;
                }

                g2.setColor(bgColor);
                g2.fillRoundRect(x, y, 22, 22, 6, 6);
                g2.setColor(color);
                g2.setStroke(new BasicStroke(2f));
                g2.drawRoundRect(x, y, 21, 21, 6, 6);
                g2.setStroke(new BasicStroke(3f));
                g2.drawLine(x + 3, y + 14, x + 7, y + 14);
                g2.drawLine(x + 15, y + 14, x + 19, y + 14);
                if (closed) {
                    g2.drawLine(x + 7, y + 14, x + 15, y + 14);
                } else {
                    g2.drawLine(x + 7, y + 14, x + 14, y + 7);
                }
                g2.fillOval(x + 5, y + 12, 5, 5);
                g2.dispose();
            }
            @Override public int getIconWidth()  { return 22; }
            @Override public int getIconHeight() { return 22; }
        };
    }

    static Icon createNodeIcon(String type, Color color) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                if (type.equals("LD")) {
                    g2.setColor(new Color(100, 100, 200));
                    g2.fillRoundRect(x + 1, y + 2, 14, 12, 3, 3);
                    g2.setColor(Color.WHITE);
                    g2.setFont(new Font("Arial", Font.BOLD, 8));
                    g2.drawString("LD", x + 3, y + 11);
                } else if (type.equals("LN")) {
                    g2.setColor(color);
                    int[] xp = {x+3, x+13, x+15, x+13, x+3, x+1};
                    int[] yp = {y+2, y+2, y+8, y+14, y+14, y+8};
                    g2.fillPolygon(xp, yp, 6);
                    g2.setColor(Color.WHITE);
                    g2.setFont(new Font("Arial", Font.PLAIN, 7));
                    g2.drawString("LN", x + 4, y + 10);
                } else if (type.equals("DO")) {
                    g2.setColor(color);
                    g2.fillRect(x + 2, y + 2, 12, 12);
                    g2.setColor(color.darker());
                    g2.drawRect(x + 2, y + 2, 12, 12);
                } else {
                    g2.setColor(color);
                    g2.fillOval(x + 4, y + 4, 8, 8);
                }
                g2.dispose();
            }
            @Override public int getIconWidth()  { return 16; }
            @Override public int getIconHeight() { return 16; }
        };
    }
}
