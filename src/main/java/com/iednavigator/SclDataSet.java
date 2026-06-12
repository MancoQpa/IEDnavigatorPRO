package com.iednavigator;

import java.util.ArrayList;
import java.util.List;

/**
 * Extraído de IEDNavigatorApp.java — Fase 1 de refactorización.
 * Originalmente era una clase interna (private static class).
 * Ver FASE1_REPORTE.md para detalles.
 *
 * Almacena información de un DataSet parseado desde un archivo SCL.
 */
public class SclDataSet {
    public String ldInst;
    public String lnClass;
    public String name;
    public String desc;
    public List<String> members = new ArrayList<>();
}
