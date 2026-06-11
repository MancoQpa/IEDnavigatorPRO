package com.iedexplorer;

/**
 * Extraído de IEDExplorerApp.java — Fase 1 de refactorización.
 * Originalmente era una clase interna (private static class).
 * Ver FASE1_REPORTE.md para detalles.
 *
 * Almacena información de un Report Control Block (RCB) parseado desde un archivo SCL.
 */
class SclReport {
    String ldInst;
    String lnClass;
    String name;
    String rptID;
    String datSet;
    boolean buffered;
    int confRev;
}
