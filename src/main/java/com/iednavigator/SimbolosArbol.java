package com.iednavigator;

import com.beanit.iec61850bean.*;

import javax.swing.*;
import java.awt.*;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Simbolos y colores del arbol del modelo, en un solo lugar: los usa el renderer del arbol
 * (ArbolCeldaRenderer) y los muestra la leyenda (Ayuda › Leyenda de simbolos y colores), asi
 * lo que explica la leyenda es exactamente lo que se ve en el arbol.
 *
 *   - icono por tipo de nodo (LD, DO, DA) y por grupo de la clase de LN (IEC 61850-7-4);
 *   - icono de interruptor segun la posicion (stVal de un Pos: ON / OFF / intermedio);
 *   - color del valor segun el estado, con la convencion de subestaciones: rojo cerrado o
 *     energizado (ON, CLOSED, TRUE; tambien ALARM), verde abierto o desenergizado (OFF, OPEN,
 *     FALSE; tambien OK, la salud normal), naranja advertencia;
 *   - violeta para los nodos de bloqueo (FC=BL), azul para lo que esta en el monitor;
 *   - un color por FC.
 *
 * Los colores son los de la leyenda historica, oscurecidos lo justo para leerse con 4,5:1 o
 * mas sobre blanco (el verde, el naranja y los FC SP y SG no llegaban).
 */
final class SimbolosArbol {

    static final Color VALOR_CERRADO = new Color(0xC80000);      // ON, CLOSED, TRUE, ALARM: cerrado / energizado
    static final Color VALOR_ABIERTO = new Color(0x1E7A46);      // OFF, OPEN, FALSE, OK: abierto / desenergizado
    static final Color VALOR_ADVERTENCIA = new Color(0xA85400);  // INTERMEDIATE, BAD, TEST, BLOCKED
    static final Color BLOQUEO = new Color(0x7850B4);            // FC=BL
    static final Color MONITOREADO = new Color(0x0064C8);         // en el monitor / watchlist

    private static final Map<String, Color> COLOR_FC = new HashMap<>();
    static {
        COLOR_FC.put("ST", new Color(21, 101, 192));
        COLOR_FC.put("MX", new Color(0, 105, 92));
        COLOR_FC.put("CO", new Color(183, 28, 28));
        COLOR_FC.put("CF", new Color(74, 20, 140));
        COLOR_FC.put("DC", new Color(55, 71, 79));
        COLOR_FC.put("SP", new Color(0xBF4300));
        COLOR_FC.put("SG", new Color(0xA65800));
        COLOR_FC.put("SE", new Color(110, 100, 18));
        COLOR_FC.put("BL", new Color(78, 52, 46));
        COLOR_FC.put("EX", new Color(84, 110, 122));
        COLOR_FC.put("OR", new Color(27, 94, 32));
        COLOR_FC.put("RP", new Color(0, 96, 100));
        COLOR_FC.put("BR", new Color(1, 87, 155));
        COLOR_FC.put("GO", new Color(136, 14, 79));
        COLOR_FC.put("SV", new Color(96, 70, 30));
    }

    private SimbolosArbol() { }

    /** Color de la etiqueta de un FC (texto blanco encima). */
    static Color colorFc(String fc) {
        Color c = fc == null ? null : COLOR_FC.get(fc.toUpperCase(Locale.ROOT));
        return c != null ? c : new Color(0x4A525D);
    }

    /** Estado que expresa un valor: 1 cerrado/energizado, -1 abierto/desenergizado, 0 advertencia, null ninguno. */
    static Integer estadoDe(NodeInfo info) {
        if (info == null || info.value == null || info.value.isEmpty()) return null;
        String v = info.value.trim().toLowerCase(Locale.ROOT);
        // "4 [sbo-with-enhanced-security]", "1 [on]": la etiqueta es lo de los corchetes
        String e = v.contains("[") && v.contains("]") ? v.substring(v.indexOf('[') + 1, v.lastIndexOf(']')).trim() : v;
        if (e.equals("on") || e.equals("closed") || e.equals("true") || e.equals("alarm")) return 1;
        if (e.equals("off") || e.equals("open") || e.equals("false") || e.equals("ok")) return -1;
        if (e.contains("intermediate") || e.equals("bad") || e.equals("bad_state") || e.equals("warning")
                || e.equals("blocked") || e.equals("test")) return 0;
        // Posicion de doble bit sin nombre: 2 cerrado, 1 abierto, 0 y 3 intermedio/malo
        if (info.node instanceof BdaDoubleBitPos) {
            if (e.equals("2")) return 1;
            if (e.equals("1")) return -1;
            if (e.equals("0") || e.equals("3")) return 0;
        }
        return null;
    }

    static Color colorEstado(Integer estado) {
        if (estado == null) return null;
        return estado > 0 ? VALOR_CERRADO : estado < 0 ? VALOR_ABIERTO : VALOR_ADVERTENCIA;
    }

    /** Icono de la fila: tipo de nodo, grupo de LN, o interruptor segun la posicion. */
    static Icon icono(NodeInfo info, Map<String, Icon> iconos) {
        String nombre = info.name != null ? info.name : "";
        String pre = info.prefix != null ? info.prefix : "";
        if (pre.equals("LD")) return iconos.get("ld");
        if (pre.equals("LN")) return ModelTreeCellRenderer.lnIcon(nombre.toUpperCase(Locale.ROOT), iconos);
        if (pre.equals("DO")) {
            if (nombre.equalsIgnoreCase("Pos")) return iconoInterruptor(posicionDelDo(info), iconos);
            if (nombre.equalsIgnoreCase("BlkOpn") || nombre.equalsIgnoreCase("BlkCls")) return iconos.get("breaker_intermediate");
            return iconos.get("do");
        }
        if (pre.equals("DA") || pre.equals("SDO")) {
            if (info.node instanceof BdaDoubleBitPos && "stVal".equalsIgnoreCase(nombre)) {
                return iconoInterruptor(estadoDe(info), iconos);
            }
            return iconos.get("da");
        }
        if (pre.equals("GoCB") || info.isGocbContainer) return iconos.get("goose_container");
        if (pre.equals("GCB")) return iconos.get("goose_gcb");
        if (pre.equals("ATTR")) return iconos.get("da");
        return null;
    }

    static Icon iconoInterruptor(Integer estado, Map<String, Icon> iconos) {
        if (estado != null && estado > 0) return iconos.get("breaker_on");
        if (estado != null && estado < 0) return iconos.get("breaker_off");
        return iconos.get("breaker_intermediate");
    }

    /** La posicion de un DO Pos, leida de su stVal (en cualquiera de sus variantes por FC). */
    private static Integer posicionDelDo(NodeInfo info) {
        java.util.List<ModelNode> vs = info.variantes != null ? info.variantes
                : info.node != null ? java.util.Collections.singletonList(info.node) : java.util.Collections.emptyList();
        for (ModelNode v : vs) {
            ModelNode st = v.getChild("stVal");
            if (st instanceof BdaDoubleBitPos) {
                try {
                    switch (((BdaDoubleBitPos) st).getDoubleBitPos()) {
                        case ON: return 1;
                        case OFF: return -1;
                        default: return 0;
                    }
                } catch (Exception e) {
                    return 0;
                }
            }
        }
        return null;
    }
}
