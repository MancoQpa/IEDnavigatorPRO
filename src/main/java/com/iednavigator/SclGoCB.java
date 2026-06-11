package com.iednavigator;

/**
 * Extraído de IEDNavigatorApp.java — Fase 1 de refactorización.
 * Originalmente era una clase interna (private static class).
 * Ver FASE1_REPORTE.md para detalles.
 *
 * Almacena información de un GOOSE Control Block (GoCB) parseado desde un archivo SCL.
 */
class SclGoCB {
    String ldInst;       // Logical Device
    String lnClass;      // LN class (usualmente LLN0)
    String cbName;       // Control block name
    String appID;        // Application ID
    String datSet;       // DataSet name
    int confRev;         // Configuration revision
    String macAddress;   // Destination MAC
    String goID;         // GOOSE ID
    int vlanId = -1;       // VLAN-ID (0-4095), -1 = no especificado
    int vlanPriority = -1; // VLAN-PRIORITY (0-7), -1 = no especificado
    int minTime = -1;      // MinTime en ms (retransmisión rápida), -1 = no especificado
    int maxTime = -1;      // MaxTime en ms (heartbeat), -1 = no especificado

    @Override
    public String toString() {
        return ldInst + "/" + lnClass + "." + cbName;
    }
}
