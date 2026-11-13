package com.iednavigator;

import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.prefs.Preferences;

/**
 * Colores y tipografias de la interfaz (rediseño de 2026-11).
 *
 * Un solo lugar para los tonos: antes cada panel traia los suyos (tres azules distintos para
 * lo mismo) y el modo oscuro no tenia por donde empezar. Las fuentes son las del sistema: la
 * sans de la interfaz y una monoespaciada solo para rutas, valores, el registro y los atajos.
 */
public final class Estilo {

    private Estilo() {}

    public static final Color FONDO = new Color(0xEEF0F3);
    public static final Color SUPERFICIE = Color.WHITE;
    public static final Color BORDE = new Color(0xDDE1E7);
    public static final Color BORDE_CAMPO = new Color(0xC9CFD7);
    public static final Color TEXTO = new Color(0x1B1F24);
    public static final Color TEXTO_2 = new Color(0x5B6470);
    public static final Color ACENTO = new Color(0x1F5FBF);
    public static final Color ACENTO_SUAVE = new Color(0xEAF1FB);
    public static final Color EXITO = new Color(0x1E7A46);
    public static final Color AVISO = new Color(0x8A4B00);
    public static final Color ERROR = new Color(0xA11F14);
    public static final Color BARRA_TITULO = new Color(0x1B2A44);
    public static final Color BARRA_TITULO_TEXTO_2 = new Color(0xB9C3D3);
    public static final Color RESALTE = new Color(0xFFF4D6);

    private static final Set<String> FAMILIAS = new HashSet<>(Arrays.asList(
            GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));

    /** Sans de la interfaz: Segoe UI en Windows, la logica Dialog si no esta. */
    public static Font ui(int estilo, float tam) {
        return new Font(primera("Segoe UI", "Inter", Font.DIALOG), estilo, Math.round(tam));
    }

    /** Monoespaciada: Consolas en Windows, la logica Monospaced si no esta. */
    public static Font mono(int estilo, float tam) {
        return new Font(primera("Consolas", "JetBrains Mono", Font.MONOSPACED), estilo, Math.round(tam));
    }

    private static String primera(String... familias) {
        for (String f : familias) if (FAMILIAS.contains(f)) return f;
        return familias[familias.length - 1];
    }

    // ── Tema ────────────────────────────────────────────────────────────────

    private static final Preferences PREFS = Preferences.userRoot().node("com/iednavigator");
    private static final String CLAVE_TEMA = "tema";

    /** true si el usuario eligio el tema oscuro (se aplica al arrancar). */
    public static boolean temaOscuro() {
        return "oscuro".equals(PREFS.get(CLAVE_TEMA, "claro"));
    }

    public static void guardarTemaOscuro(boolean oscuro) {
        PREFS.put(CLAVE_TEMA, oscuro ? "oscuro" : "claro");
    }
}
