package com.iednavigator;

import com.beanit.iec61850bean.*;


/**
 * Objeto de usuario (userObject) para nodos del árbol Data Model.
 * Extraído de IEDNavigatorApp.java — Fase F1b de refactorización.
 *
 * La etiqueta usa los términos de la norma: "[DO] Beh [ST] · ENS" (con el CDC deducido) y
 * "[DA] stVal [ST] = on (1)" (con el nombre del valor si el enumerado es uno estándar).
 */
class DataModelNodeInfo {
    ModelNode node;
    String type; // "LD", "LN", "DO", "SDO", "DA", "BDA"
    private String cdc;          // calculado una vez: toString se llama en cada repintado
    private boolean cdcCalculado;

    DataModelNodeInfo(ModelNode node, String type) {
        this.node = node;
        this.type = type;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(type).append("] ").append(node.getName());
        if (node instanceof FcModelNode) {
            sb.append(" [").append(((FcModelNode) node).getFc()).append("]");
        }
        if (node instanceof FcDataObject) {
            if (!cdcCalculado) {
                cdc = TiposIec61850.cdcDe((FcDataObject) node);
                cdcCalculado = true;
            }
            if (cdc != null) sb.append(" · ").append(cdc);
        }
        if (node instanceof BasicDataAttribute) {
            String val = valorMostrado((BasicDataAttribute) node, false);
            if (val != null && !val.isEmpty()) sb.append(" = ").append(val);
        }
        return sb.toString();
    }

    /**
     * Valor para mostrar: el de {@link ValorBda#texto}, y si el atributo es un enumerado
     * estándar (Beh, Mod, Health, ctlModel…), "on (1)". Un valor que no existe en el
     * enumerado se marca solo si {@code explicar} (en la tabla de propiedades).
     */
    static String valorMostrado(BasicDataAttribute bda, boolean explicar) {
        String texto = ValorBda.texto(bda);
        Long n = entero(bda);
        if (n == null) return texto;
        String ref = bda.getReference().toString();
        String nombre = CatalogoIec61850.nombreEnumerado(ref, bda.getFc(), n);
        if (nombre != null) return nombre;
        String clase = CatalogoIec61850.claseEnumerado(ref, bda.getFc());
        if (clase != null && explicar) return texto + " (" + I18n.t("dm.enum.out", clase) + ")";
        return texto;
    }

    private static Long entero(BasicDataAttribute bda) {
        if (bda instanceof BdaInt8)  return (long) ((BdaInt8) bda).getValue();
        if (bda instanceof BdaInt16) return (long) ((BdaInt16) bda).getValue();
        if (bda instanceof BdaInt32) return (long) ((BdaInt32) bda).getValue();
        if (bda instanceof BdaInt8U) return (long) ((BdaInt8U) bda).getValue();
        return null;
    }
}
