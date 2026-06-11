package com.iednavigator;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.*;
import java.util.Map;

/**
 * Fase 11: Fábrica de iconos para el árbol de modelo IEC 61850.
 * Extraído de IEDNavigatorApp.java — métodos createXxxIcon + initIcons.
 */
class IconFactory {

    private IconFactory() {}

    /** Puebla el mapa iconCache con todos los iconos estándar de la aplicación. */
    static void fillCache(Map<String, Icon> iconCache) {
        // Iconos para estados de breaker
        iconCache.put("breaker_on",           createBreakerIcon("on"));
        iconCache.put("breaker_off",          createBreakerIcon("off"));
        iconCache.put("breaker_intermediate", createBreakerIcon("intermediate"));

        // Iconos para tipos de LN — grupos IEC 61850-7-4
        iconCache.put("ln_xcbr",    createNodeIcon("LN", new Color(200,  50,  50)));  // X switching: rojo
        iconCache.put("ln_xswi",    createNodeIcon("LN", new Color(200, 100,  50)));  // X switching: naranja
        iconCache.put("ln_mmxu",    createMeterIcon(new Color(  0, 100, 200)));       // M medición: azul
        iconCache.put("ln_mmtr",    createMeterIcon(new Color(  0, 150, 100)));       // M energía: verde-azul
        iconCache.put("ln_cswi",    createNodeIcon("LN", new Color(150, 100, 200)));  // C control: violeta
        iconCache.put("ln_prot",    createShieldIcon(new Color(180,  30,  30)));      // P protección: rojo
        iconCache.put("ln_rela",    createShieldIcon(new Color(130,  30, 170)));      // R prot-relacionada: violeta
        iconCache.put("ln_auto",    createGearIcon(new Color(  0, 150, 170)));        // A automático: cian
        iconCache.put("ln_syst",    createDiamondIcon(new Color( 70,  70,  70)));     // L sistema: gris oscuro
        iconCache.put("ln_genr",    createDiamondIcon(new Color( 90,  90,  90)));     // G genérico: gris
        iconCache.put("ln_supv",    createMeterIcon(new Color( 20, 140, 120)));       // S supervisión: teal
        iconCache.put("ln_trfm",    createMeterIcon(new Color(140,  80,   0)));       // T transformador: marrón
        iconCache.put("ln_intf",    createNodeIcon("LN", new Color( 50,  90, 200)));  // I interfaz: azul claro
        iconCache.put("ln_zpwr",    createNodeIcon("LN", new Color( 80,  80, 150)));  // Z otros equipos: azul-gris
        iconCache.put("ln_default", createNodeIcon("LN", new Color(100, 150, 100))); // sin clasificar

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
                g2.setStroke(new BasicStroke(0.8f));
                g2.drawOval(x + 1, y + 1, size - 2, size - 2);
                // Reflejo superior izquierdo
                g2.setColor(new Color(255, 255, 255, 90));
                g2.fillOval(x + 2, y + 2, (size - 2) / 2, (size - 2) / 2);
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
                // Fondo con gradiente
                g2.setPaint(new GradientPaint(x+1, y+1, new Color(250,250,250), x+15, y+15, new Color(220,220,220)));
                g2.fillRoundRect(x+1, y+1, 14, 14, 4, 4);
                g2.setColor(color);
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawRoundRect(x+1, y+1, 14, 14, 4, 4);
                // Arco del medidor (180°), centro en (x+8, y+10)
                g2.setStroke(new BasicStroke(1.3f));
                g2.drawArc(x+3, y+4, 10, 10, 0, 180);
                // Marcas de escala en 0°, 90°, 180°
                g2.setStroke(new BasicStroke(1f));
                g2.drawLine(x+13, y+9, x+12, y+9);   // 0° (derecha)
                g2.drawLine(x+8,  y+4, x+8,  y+5);   // 90° (arriba)
                g2.drawLine(x+3,  y+9, x+4,  y+9);   // 180° (izquierda)
                // Aguja apuntando ~135° (mitad superior derecha)
                g2.setColor(color.darker());
                g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawLine(x+8, y+9, x+12, y+5);
                // Pivote central
                g2.setColor(color);
                g2.fillOval(x+6, y+7, 4, 4);
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

    /** Ícono escudo — para nodos de protección (grupo P y R). */
    static Icon createShieldIcon(Color color) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                // Escudo con parte superior plana y punta inferior curva
                Path2D.Float shield = new Path2D.Float();
                shield.moveTo(x+2,  y+2);
                shield.lineTo(x+14, y+2);
                shield.lineTo(x+14, y+9);
                shield.quadTo(x+14, y+14, x+8, y+15);
                shield.quadTo(x+2,  y+14, x+2, y+9);
                shield.closePath();
                // Relleno con gradiente
                g2.setPaint(new GradientPaint(x+2, y+2, color.brighter(), x+14, y+15, color.darker()));
                g2.fill(shield);
                g2.setColor(color.darker().darker());
                g2.setStroke(new BasicStroke(1f));
                g2.draw(shield);
                // Símbolo de exclamación interior (protección)
                g2.setColor(new Color(255, 255, 255, 200));
                g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawLine(x+8, y+5, x+8, y+10);
                g2.fillOval(x+7, y+12, 2, 2);
                g2.dispose();
            }
            @Override public int getIconWidth()  { return 16; }
            @Override public int getIconHeight() { return 16; }
        };
    }

    /** Ícono engranaje — para nodos de control automático (grupo A). */
    static Icon createGearIcon(Color color) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                float cx = x + 8f, cy = y + 8f;
                float outerR = 6.5f, innerR = 4.5f;
                int numTeeth = 7;
                // Engranaje con dientes redondeados via Path2D
                Path2D.Float gear = new Path2D.Float();
                for (int i = 0; i < numTeeth * 2; i++) {
                    double angle = i * Math.PI / numTeeth - Math.PI / 2;
                    float r = (i % 2 == 0) ? outerR : innerR;
                    float px = cx + r * (float) Math.cos(angle);
                    float py = cy + r * (float) Math.sin(angle);
                    if (i == 0) gear.moveTo(px, py); else gear.lineTo(px, py);
                }
                gear.closePath();
                g2.setColor(color);
                g2.fill(gear);
                g2.setColor(color.darker());
                g2.setStroke(new BasicStroke(0.8f));
                g2.draw(gear);
                // Agujero central
                g2.setColor(new Color(240, 240, 240));
                g2.fillOval((int)(cx-2.5f), (int)(cy-2.5f), 5, 5);
                g2.setColor(color.darker());
                g2.setStroke(new BasicStroke(0.7f));
                g2.drawOval((int)(cx-2.5f), (int)(cy-2.5f), 5, 5);
                g2.dispose();
            }
            @Override public int getIconWidth()  { return 16; }
            @Override public int getIconHeight() { return 16; }
        };
    }

    /** Ícono diamante — para nodos de sistema y genéricos (grupos L y G). */
    static Icon createDiamondIcon(Color color) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int[] xp = {x+8, x+14, x+8, x+2};
                int[] yp = {y+2, y+8, y+14, y+8};
                // Gradiente diagonal para efecto 3D
                g2.setPaint(new GradientPaint(x+2, y+2, color.brighter(), x+14, y+14, color.darker()));
                g2.fillPolygon(xp, yp, 4);
                g2.setColor(color.darker());
                g2.setStroke(new BasicStroke(0.9f));
                g2.drawPolygon(xp, yp, 4);
                // Línea de brillo superior
                g2.setColor(new Color(255, 255, 255, 110));
                g2.setStroke(new BasicStroke(0.8f));
                g2.drawLine(x+5, y+5, x+10, y+5);
                g2.dispose();
            }
            @Override public int getIconWidth()  { return 16; }
            @Override public int getIconHeight() { return 16; }
        };
    }

    static Icon createNodeIcon(String type, Color color) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                if (type.equals("LD")) {
                    // Dispositivo lógico: rectángulo redondeado con gradiente
                    g2.setPaint(new GradientPaint(x+1, y+2, color.brighter(), x+15, y+14, color));
                    g2.fillRoundRect(x+1, y+2, 14, 12, 4, 4);
                    g2.setColor(color.darker());
                    g2.setStroke(new BasicStroke(0.9f));
                    g2.drawRoundRect(x+1, y+2, 14, 12, 4, 4);
                    g2.setColor(Color.WHITE);
                    g2.setFont(new Font("Arial", Font.BOLD, 7));
                    g2.drawString("LD", x+3, y+11);
                } else if (type.equals("LN")) {
                    // Nodo lógico: hexágono con gradiente
                    int[] xp = {x+3, x+13, x+15, x+13, x+3, x+1};
                    int[] yp = {y+2, y+2, y+8, y+14, y+14, y+8};
                    g2.setPaint(new GradientPaint(x+1, y+2, color.brighter(), x+15, y+14, color.darker()));
                    g2.fillPolygon(xp, yp, 6);
                    g2.setColor(color.darker().darker());
                    g2.setStroke(new BasicStroke(0.8f));
                    g2.drawPolygon(xp, yp, 6);
                    g2.setColor(new Color(255, 255, 255, 210));
                    g2.setFont(new Font("Arial", Font.BOLD, 7));
                    g2.drawString("LN", x+4, y+10);
                } else if (type.equals("DO")) {
                    // Data Object: rectángulo con borde coloreado
                    g2.setColor(new Color(245, 245, 245));
                    g2.fillRoundRect(x+2, y+2, 12, 12, 3, 3);
                    g2.setColor(color);
                    g2.setStroke(new BasicStroke(1.2f));
                    g2.drawRoundRect(x+2, y+2, 12, 12, 3, 3);
                    // Líneas internas que sugieren atributos
                    g2.setStroke(new BasicStroke(0.8f));
                    g2.drawLine(x+4, y+6,  x+12, y+6);
                    g2.drawLine(x+4, y+9,  x+12, y+9);
                    g2.drawLine(x+4, y+12, x+10, y+12);
                } else {
                    g2.setColor(color);
                    g2.fillOval(x+4, y+4, 8, 8);
                    g2.setColor(color.darker());
                    g2.setStroke(new BasicStroke(0.8f));
                    g2.drawOval(x+4, y+4, 8, 8);
                }
                g2.dispose();
            }
            @Override public int getIconWidth()  { return 16; }
            @Override public int getIconHeight() { return 16; }
        };
    }
}
