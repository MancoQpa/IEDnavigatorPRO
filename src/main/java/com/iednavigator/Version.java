package com.iednavigator;

import java.io.InputStream;
import java.util.Properties;

/**
 * Versión de la aplicación, tal como la escribió build_installer.ps1 en
 * {@code classes/version.properties} al armar el paquete.
 *
 * Hasta 2026-10-22 el "Acerca de" decía "Version 2.0 - Hybrid Edition", fijo en el código
 * desde mucho antes de la v4: nadie se acordaba de cambiarlo en cada release. Ahora la versión
 * sale del mismo parámetro con el que se arma el paquete (-Version), sin tocar el código.
 *
 * Corriendo desde el repo (classes\ de compile.ps1, sin empaquetar) no hay archivo: la versión
 * es null y se muestra como "en desarrollo".
 */
public final class Version {

    private static final String RECURSO = "/version.properties";
    private static final Properties DATOS = cargar();

    private Version() {}

    /** "v4.13.7-pre", o null si no es un paquete armado por build_installer.ps1. */
    public static String numero() {
        return texto(DATOS.getProperty("version"));
    }

    /** Commit corto del que se armó el paquete, o null. */
    public static String commit() {
        return texto(DATOS.getProperty("commit"));
    }

    /** Para mostrar: "v4.13.7-pre" o el texto de "en desarrollo" del idioma actual. */
    public static String etiqueta() {
        String v = numero();
        return v != null ? v : I18n.t("about.version.dev");
    }

    /** Para el registro de sesión: "v4.13.7-pre (54c85c0)", "v4.13.7-pre", o "desarrollo". */
    public static String paraRegistro() {
        String v = numero();
        if (v == null) return "desarrollo (sin version.properties)";
        String c = commit();
        return c != null ? v + " (" + c + ")" : v;
    }

    static Properties desde(InputStream in) {
        Properties p = new Properties();
        if (in == null) return p;
        try (InputStream s = in) {
            p.load(new java.io.InputStreamReader(s, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            // Un archivo ilegible es lo mismo que ninguno: se muestra "en desarrollo".
        }
        return p;
    }

    private static Properties cargar() {
        return desde(Version.class.getResourceAsStream(RECURSO));
    }

    private static String texto(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }
}
