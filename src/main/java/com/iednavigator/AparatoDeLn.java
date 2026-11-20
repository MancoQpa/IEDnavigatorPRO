package com.iednavigator;

import com.beanit.iec61850bean.ModelNode;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Qué aparato de maniobra controla (CSWI) o enclava (CILO) un nodo lógico: interruptor o
 * seccionador.
 *
 * <p>La clase de LN no lo dice: CSWI es el controlador de cualquier aparato y CILO su
 * enclavamiento (IEC 61850-7-4). El árbol mostraba "Controlador de Interruptor" en todos los
 * CSWI, también en los de seccionador (DSCSWI1..9 de un relé de alimentador, 2026-11-16). Se
 * deduce así, de lo más a lo menos seguro:
 *
 * <ol>
 *   <li>el XCBR o XSWI del mismo LD con el mismo prefijo e instancia (DSCSWI1 ↔ DSXSWI1), con el
 *       mismo prefijo, o con un prefijo que empieza igual (CBNOCHKCSWI1 ↔ CBXCBR1);
 *   <li>el código del prefijo (IEC 81346 y usos comunes): CB, QA, Q0 interruptor; DS, ES, QB,
 *       QC, QZ, Q1, Q2, Q8, Q9 seccionador (incluye los de puesta a tierra);
 *   <li>si el LD tiene aparatos de un solo tipo, ese.
 * </ol>
 *
 * Si nada lo decide, devuelve null y se muestra el nombre genérico de la clase.
 */
final class AparatoDeLn {

    enum Tipo { INTERRUPTOR, SECCIONADOR }

    private AparatoDeLn() { }

    private static final Pattern LN = Pattern.compile("^(.*?)([A-Z]{4})(\\d*)$");

    /** {prefijo, clase, instancia} de un nombre de LN ("DSCSWI1" → DS, CSWI, 1), o null. */
    static String[] partes(String nombreLn) {
        if (nombreLn == null) return null;
        Matcher m = LN.matcher(nombreLn);
        if (!m.matches()) return null;
        return new String[] {m.group(1), m.group(2), m.group(3)};
    }

    /** El aparato que controla o enclava este LN (CSWI/CILO), o null si no aplica o no se sabe. */
    static Tipo de(ModelNode ln) {
        if (ln == null) return null;
        String[] p = partes(ln.getName());
        if (p == null || !("CSWI".equals(p[1]) || "CILO".equals(p[1]))) return null;
        String pre = p[0].toUpperCase(Locale.ROOT);
        ModelNode ld = ln.getParent();

        // 1. Por el aparato del mismo LD. Máscara por nivel: 1 interruptor, 2 seccionador
        int exacto = 0, mismoPrefijo = 0, parecido = 0, enLd = 0;
        if (ld != null && ld.getChildren() != null) {
            for (ModelNode otro : ld.getChildren()) {
                String[] q = partes(otro.getName());
                if (q == null) continue;
                int t = "XCBR".equals(q[1]) ? 1 : "XSWI".equals(q[1]) ? 2 : 0;
                if (t == 0) continue;
                enLd |= t;
                String qp = q[0].toUpperCase(Locale.ROOT);
                if (qp.equals(pre) && q[2].equals(p[2])) exacto |= t;
                else if (qp.equals(pre)) mismoPrefijo |= t;
                else if (qp.length() >= 2 && pre.length() >= 2 && (pre.startsWith(qp) || qp.startsWith(pre))) {
                    parecido |= t;
                }
            }
        }
        for (int m : new int[] {exacto, mismoPrefijo, parecido}) {
            if (m == 1) return Tipo.INTERRUPTOR;
            if (m == 2) return Tipo.SECCIONADOR;
        }

        // 2. Por el código del prefijo
        Tipo porCodigo = porPrefijo(pre);
        if (porCodigo != null) return porCodigo;

        // 3. El LD tiene aparatos de un solo tipo
        if (enLd == 1) return Tipo.INTERRUPTOR;
        if (enLd == 2) return Tipo.SECCIONADOR;
        return null;
    }

    static Tipo porPrefijo(String pre) {
        if (pre == null || pre.isEmpty()) return null;
        String p = pre.toUpperCase(Locale.ROOT);
        if (p.startsWith("CB") || p.startsWith("QA") || p.equals("Q0")) return Tipo.INTERRUPTOR;
        if (p.startsWith("DS") || p.startsWith("ES") || p.startsWith("QB") || p.startsWith("QC")
                || p.startsWith("QZ") || p.matches("Q[1289]\\d*")) {
            return Tipo.SECCIONADOR;
        }
        return null;
    }

    /** "Controlador de seccionador", "Enclavamiento de interruptor"... o null si no aplica. */
    static String descripcion(ModelNode ln) {
        Tipo t = de(ln);
        if (t == null) return null;
        String clase = partes(ln.getName())[1];
        String clave = ("CSWI".equals(clase) ? "ln.cswi." : "ln.cilo.")
                + (t == Tipo.INTERRUPTOR ? "cb" : "ds");
        return I18n.t(clave);
    }
}
