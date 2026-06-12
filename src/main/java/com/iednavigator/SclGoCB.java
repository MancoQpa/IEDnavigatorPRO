package com.iednavigator;

/**
 * Extraído de IEDNavigatorApp.java — Fase 1 de refactorización.
 * Originalmente era una clase interna (private static class).
 * Ver FASE1_REPORTE.md para detalles.
 *
 * Almacena información de un GOOSE Control Block (GoCB) parseado desde un archivo SCL.
 */
public class SclGoCB {
    public String ldInst;       // Logical Device
    public String lnClass;      // LN class (usualmente LLN0)
    public String cbName;       // Control block name
    public String appID;        // Application ID
    public String datSet;       // DataSet name
    public int confRev;         // Configuration revision
    public String macAddress;   // Destination MAC
    public String goID;         // GOOSE ID
    public int vlanId = -1;       // VLAN-ID (0-4095), -1 = no especificado
    public int vlanPriority = -1; // VLAN-PRIORITY (0-7), -1 = no especificado
    public int minTime = -1;      // MinTime en ms (retransmisión rápida), -1 = no especificado
    public int maxTime = -1;      // MaxTime en ms (heartbeat), -1 = no especificado

    @Override
    public String toString() {
        return ldInst + "/" + lnClass + "." + cbName;
    }
}
