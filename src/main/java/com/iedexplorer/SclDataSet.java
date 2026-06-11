package com.iedexplorer;

import java.util.ArrayList;
import java.util.List;

/**
 * Extraído de IEDExplorerApp.java — Fase 1 de refactorización.
 * Originalmente era una clase interna (private static class).
 * Ver FASE1_REPORTE.md para detalles.
 *
 * Almacena información de un DataSet parseado desde un archivo SCL.
 */
class SclDataSet {
    String ldInst;
    String lnClass;
    String name;
    String desc;
    List<String> members = new ArrayList<>();
}
