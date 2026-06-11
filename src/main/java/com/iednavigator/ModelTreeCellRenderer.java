package com.iednavigator;

import com.beanit.iec61850bean.*;
import javax.swing.*;
import javax.swing.tree.*;
import java.awt.*;
import java.util.Map;
import java.util.Set;

/**
 * Renderer personalizado para el árbol de modelo IEC 61850 (pestaña principal).
 * Extraído de IEDNavigatorApp.java — Fase F1b de refactorización.
 *
 * Aplica colores y iconos según:
 *  - Tipo de nodo (LD/LN/DO/DA) y clase LN (XCBR/MMXU/etc.)
 *  - Valor de estado (on/off/intermediate)
 *  - Presencia en la watchlist (marcado con asterisco + color azul)
 *  - FC=BL (bloqueo) → color violeta
 */
class ModelTreeCellRenderer extends DefaultTreeCellRenderer {

    private final Map<String, Icon> iconCache;
    private final Set<String>       watchlist;

    private static final Color WATCHLIST_COLOR   = new Color(0,   100, 200);
    private static final Color COLOR_ON          = new Color(0,   150, 0);
    private static final Color COLOR_OFF         = new Color(200, 0,   0);
    private static final Color COLOR_INTERMEDIATE= new Color(255, 140, 0);
    private static final Color COLOR_BL          = new Color(120, 80,  180);


    ModelTreeCellRenderer(Map<String, Icon> iconCache, Set<String> watchlist) {
        this.iconCache = iconCache;
        this.watchlist = watchlist;
    }

    @Override
    public Component getTreeCellRendererComponent(JTree tree, Object value,
            boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus) {

        super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);

        if (value instanceof DefaultMutableTreeNode) {
            Object userObj = ((DefaultMutableTreeNode) value).getUserObject();
            if (userObj instanceof NodeInfo) {
                NodeInfo info = (NodeInfo) userObj;

                // Verificar si está en watchlist
                boolean inWatchlist = false;
                if (info.node instanceof FcModelNode) {
                    String ref = info.node.getReference().toString();
                    Fc fc = ((FcModelNode) info.node).getFc();
                    String fullRef = ref + "$" + fc.toString();
                    inWatchlist = watchlist.contains(fullRef);
                }

                // === ASIGNAR ICONOS ===
                Icon icon = getIconForNode(info);
                if (icon != null) setIcon(icon);

                // === COLOREAR SEGÚN ESTADO ===
                if (info.value != null && !info.value.isEmpty()) {
                    String v = info.value.toLowerCase();
                    String label = v.contains("[") && v.contains("]")
                        ? v.substring(v.indexOf('[') + 1, v.lastIndexOf(']')).trim() : v;

                    boolean isOn  = label.equals("on") || label.equals("ok") || label.equals("closed")
                        || label.equals("true") || (!v.contains("[") && (v.equals("on") || v.equals("2")));
                    boolean isOff = label.equals("off") || label.equals("alarm") || label.equals("open")
                        || label.equals("false") || (!v.contains("[") && (v.equals("off") || v.equals("1")));
                    boolean isWarn = label.equals("warning") || label.contains("intermediate")
                        || label.equals("bad") || label.equals("bad_state") || label.equals("blocked")
                        || label.equals("test") || (!v.contains("[") && (v.equals("0") || v.equals("3")));

                    if (isOn) {
                        setForeground(COLOR_ON);
                        setFont(getFont().deriveFont(Font.BOLD));
                    } else if (isOff) {
                        setForeground(COLOR_OFF);
                        setFont(getFont().deriveFont(Font.BOLD));
                    } else if (isWarn) {
                        setForeground(COLOR_INTERMEDIATE);
                        setFont(getFont().deriveFont(Font.BOLD));
                    } else if (inWatchlist) {
                        setForeground(WATCHLIST_COLOR);
                    }
                } else if (inWatchlist) {
                    setForeground(WATCHLIST_COLOR);
                }

                // FC=BL: nodo de bloqueo → color violeta + prefijo candado
                if ("BL".equals(info.fc)) {
                    setForeground(COLOR_BL);
                    if (!getText().startsWith("🔒")) setText("🔒 " + getText());
                }

                // Agregar asterisco si está en watchlist
                if (inWatchlist && !getText().startsWith("*")) {
                    setText("* " + getText());
                }
            }
        }
        return this;
    }

    private Icon getIconForNode(NodeInfo info) {
        String name   = info.name   != null ? info.name.toUpperCase() : "";
        String prefix = info.prefix != null ? info.prefix : "";

        if (prefix.equals("LD")) return iconCache.get("ld");

        if (prefix.equals("LN")) {
            return lnIcon(name, iconCache);
        }

        if (prefix.equals("DO")) {
            if (name.equals("POS") || name.equals("BLKOPN") || name.equals("BLKCLS"))
                return iconCache.get("breaker_intermediate");
            return iconCache.get("do");
        }

        if (prefix.equals("DA") || prefix.equals("SDO")) {
            if (name.equalsIgnoreCase("stVal") && info.value != null) {
                String v = info.value.toLowerCase();
                String label = v.contains("[") && v.contains("]")
                    ? v.substring(v.indexOf('[') + 1, v.lastIndexOf(']')).trim() : v;
                boolean iconOn  = label.equals("on") || label.equals("ok") || label.equals("closed")
                    || (!v.contains("[") && v.equals("2"));
                boolean iconOff = label.equals("off") || label.equals("alarm") || label.equals("open")
                    || (!v.contains("[") && v.equals("1"));
                if (iconOn)  return iconCache.get("breaker_on");
                if (iconOff) return iconCache.get("breaker_off");
                return iconCache.get("breaker_intermediate");
            }
            return iconCache.get("da");
        }

        if (prefix.equals("GoCB") || info.isGocbContainer) return iconCache.get("goose_container");
        if (prefix.equals("GCB"))  return iconCache.get("goose_gcb");
        if (prefix.equals("ATTR")) return iconCache.get("da");

        return null;
    }

    /** Selecciona el ícono de LN correcto según la clase inferida (grupo IEC 61850-7-4). */
    static Icon lnIcon(String name, Map<String, Icon> iconCache) {
        String cls = Iec61850Dictionary.inferLnClass(name);
        if (cls == null || cls.isEmpty()) return iconCache.get("ln_default");
        // Casos específicos de la misma letra
        if (cls.equals("XCBR"))                               return iconCache.get("ln_xcbr");
        if (cls.equals("XSWI") || cls.equals("CSWI"))        return iconCache.get("ln_xswi");
        if (cls.equals("MMTR") || cls.equals("MSTA"))        return iconCache.get("ln_mmtr");
        if (cls.equals("CILO"))                               return iconCache.get("ln_cswi");
        // Grupo por letra inicial
        switch (cls.charAt(0)) {
            case 'X': return iconCache.get("ln_xswi");   // otros X (XSWI variantes)
            case 'C': return iconCache.get("ln_cswi");   // otros C (CPOW, CPDM…)
            case 'M': return iconCache.get("ln_mmxu");   // todos los demás M (medición)
            case 'P': return iconCache.get("ln_prot");   // protección
            case 'R': return iconCache.get("ln_rela");   // prot-relacionada (RREC, RPSB…)
            case 'A': return iconCache.get("ln_auto");   // control automático (ATCC…)
            case 'L': return iconCache.get("ln_syst");   // sistema (LLN0, LPHD)
            case 'G': return iconCache.get("ln_genr");   // genérico (GAPC, GGIO)
            case 'S': return iconCache.get("ln_supv");   // supervisión (STMP, SARC…)
            case 'T': return iconCache.get("ln_trfm");   // transformador instrumento
            case 'I': return iconCache.get("ln_intf");   // interfaz (IHMI, ITCI…)
            case 'Z': return iconCache.get("ln_zpwr");   // otros equipos de potencia
            default:  return iconCache.get("ln_default");
        }
    }
}
