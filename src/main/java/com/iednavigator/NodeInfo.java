package com.iednavigator;

import com.beanit.iec61850bean.ModelNode;

/**
 * Extraído de IEDNavigatorApp.java — Fase 1 de refactorización.
 * Originalmente era una clase interna (private static class).
 * Ver FASE1_REPORTE.md para detalles.
 *
 * Objeto de usuario (userObject) para nodos del árbol de modelo IEC 61850.
 * Contiene metadatos de presentación: prefijo, FC, valor actual, tipo,
 * referencia al ModelNode subyacente y referencia opcional a un GoCB.
 */
class NodeInfo {
    String name;
    String prefix;
    String fc;
    String value;
    String type;
    ModelNode node;
    SclGoCB gocb;        // Para nodos GoCB
    boolean isGocbContainer; // True si es el contenedor "GOOSE"
    /**
     * Para un DO: sus variantes por FC (Pos [ST], Pos [CO], Pos [CF]...). El arbol muestra el
     * DO una sola vez (rediseño de 2026-11); node apunta a la variante principal (ST si hay) y
     * aca estan todas, para quien necesite una en particular (el mando busca la de CO).
     */
    java.util.List<ModelNode> variantes;
    /** Cuando cambio el valor por ultima vez (0 = nunca): la fila se resalta y se desvanece. */
    volatile long resaltarDesde;
    /** El ultimo cambio de cualquier nodo: ArbolUI repinta mientras haya resaltes vivos. */
    static volatile long ultimoResalte;

    /** Pone el valor; si cambio respecto del anterior, la fila se resalta (como en el monitor). */
    void ponerValor(String nuevo) {
        String viejo = value;
        value = nuevo;
        if (viejo != null && !viejo.equals(nuevo)) {
            long ahora = System.currentTimeMillis();
            resaltarDesde = ahora;
            ultimoResalte = ahora;
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(prefix).append("] ").append(name);
        if (fc != null && !fc.isEmpty()) {
            sb.append(" [").append(fc).append("]");
        }
        if (value != null && !value.isEmpty()) {
            sb.append(" = ").append(value);
        }
        return sb.toString();
    }
}
