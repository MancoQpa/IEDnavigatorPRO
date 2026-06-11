package com.iednavigator;

import com.beanit.iec61850bean.FcModelNode;

/**
 * Extraído de IEDNavigatorApp.java — Fase 1 de refactorización.
 * Originalmente era una clase interna (private static class).
 * Ver FASE1_REPORTE.md para detalles.
 *
 * Representa un elemento del Activity Monitor: un nodo IEC 61850 que el usuario
 * ha agregado a la lista de monitoreo (watchlist).
 */
class MonitorItem {
    String reference;
    String name;
    String fc;
    String value;
    String oldValue;  // Para detectar cambios
    String type;
    FcModelNode node;
    long lastChangeTime;

    MonitorItem(String reference, String name, String fc, String type, FcModelNode node) {
        this.reference = reference;
        this.name = name;
        this.fc = fc;
        this.type = type;
        this.node = node;
        this.value = "";
        this.oldValue = "";
        this.lastChangeTime = 0;
    }
}
