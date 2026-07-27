package com.iednavigator;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.prefs.Preferences;

/**
 * Capa de internacionalización (i18n) — el "intérprete" de la interfaz.
 *
 * La lógica de la aplicación queda igual (en su idioma nativo); solo los textos visibles se
 * buscan por clave en una tabla de traducción (archivos i18n/messages*.properties, UTF-8).
 * Agregar un idioma = agregar un archivo messages_XX.properties; el código no cambia.
 *
 * Idiomas incluidos: español (base), inglés (en), chino simplificado (zh), portugués (pt).
 * Las claves no traducidas en en/zh/pt caen automáticamente al español (base).
 */
public final class I18n {

    private static final String BUNDLE = "i18n.messages";
    private static final Preferences PREFS = Preferences.userRoot().node("com/iednavigator");

    private static volatile ResourceBundle bundle;
    private static volatile Locale current;

    static {
        // Idioma persistido. Default: Español (el menú Idioma permite cambiar a
        // 中文/English/Português, y la elección se guarda entre sesiones).
        setLocale(Locale.forLanguageTag(PREFS.get("ui.locale", "es")));
    }

    public static synchronized void setLocale(Locale locale) {
        current = locale;
        // No-fallback: va del idioma pedido directo a la base (evita caer al locale del sistema).
        bundle = ResourceBundle.getBundle(BUNDLE, locale,
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
    }

    /** Cambia el idioma y lo guarda para próximas sesiones. */
    public static void setLocaleAndSave(String languageTag) {
        PREFS.put("ui.locale", languageTag);
        setLocale(Locale.forLanguageTag(languageTag));
    }

    public static String currentTag() {
        return current == null ? "es" : current.toLanguageTag();
    }

    /** Texto por clave; si falta en todos los idiomas, devuelve la clave (marcador de faltante). */
    public static String t(String key) {
        try {
            return bundle.getString(key);
        } catch (MissingResourceException e) {
            return key;
        }
    }

    /** Texto con parámetros: t("control.ok", ref, valor). */
    public static String t(String key, Object... args) {
        return MessageFormat.format(t(key), args);
    }

    private I18n() {}
}
