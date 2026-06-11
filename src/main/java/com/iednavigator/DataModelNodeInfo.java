package com.iednavigator;

import com.beanit.iec61850bean.*;


/**
 * Objeto de usuario (userObject) para nodos del árbol Data Model.
 * Extraído de IEDNavigatorApp.java — Fase F1b de refactorización.
 */
class DataModelNodeInfo {
    ModelNode node;
    String type; // "LD", "LN", "DO", "DA", "BDA"

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
        if (node instanceof BasicDataAttribute) {
            String val = ((BasicDataAttribute) node).getValueString();
            if (val != null && !val.isEmpty()) sb.append(" = ").append(val);
        }
        return sb.toString();
    }
}
