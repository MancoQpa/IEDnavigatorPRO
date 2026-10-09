package com.iednavigator;

import com.beanit.iec61850bean.*;

/**
 * Texto del valor de un atributo básico, correcto para todos los tipos.
 *
 * {@link BasicDataAttribute#getValueString()} de iec61850bean no está implementado para
 * todos los anchos (medido sobre la librería 1.9.0, 2026-10-08):
 *
 * <ul>
 *   <li>INT8U, INT16, INT16U e INT64 devuelven {@code null}: el valor no se ve, aunque se
 *       haya leído del equipo;</li>
 *   <li>FLOAT64 devuelve los bytes crudos ({@code "[11, 0, 0, ...]"}), que se mostraban
 *       como si fueran el valor.</li>
 * </ul>
 *
 * Los enumerados ya tenían un parche propio en {@code GoosePanel.formatEnumValue}; los
 * enteros comunes de esos anchos —{@code numHar}, {@code NumOfSG}, contadores— no. Todo
 * lugar que muestre, compare o serialice un valor debería pasar por acá.
 */
public final class ValorBda {

    private ValorBda() { }

    /**
     * El valor como texto, o {@code null} si el tipo no tiene representación de texto
     * (la misma convención que {@code getValueString()} para los tipos que sí funcionan).
     */
    public static String texto(BasicDataAttribute bda) {
        if (bda == null) return null;
        try {
            if (bda instanceof BdaInt8U)  return String.valueOf(((BdaInt8U) bda).getValue());
            if (bda instanceof BdaInt16)  return String.valueOf(((BdaInt16) bda).getValue());
            if (bda instanceof BdaInt16U) return String.valueOf(((BdaInt16U) bda).getValue());
            if (bda instanceof BdaInt64)  return String.valueOf(((BdaInt64) bda).getValue());
            if (bda instanceof BdaFloat64) {
                Double d = ((BdaFloat64) bda).getDouble();
                return d == null ? null : String.valueOf(d);
            }
            return bda.getValueString();
        } catch (Exception e) {
            return null;
        }
    }
}
