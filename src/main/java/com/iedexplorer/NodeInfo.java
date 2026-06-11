package com.iedexplorer;

import com.beanit.iec61850bean.ModelNode;

/**
 * Extraído de IEDExplorerApp.java — Fase 1 de refactorización.
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
