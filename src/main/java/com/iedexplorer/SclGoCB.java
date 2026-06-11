package com.iedexplorer;

/**
 * Extraído de IEDExplorerApp.java — Fase 1 de refactorización.
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

    @Override
    public String toString() {
        return ldInst + "/" + lnClass + "." + cbName;
    }
}
