package com.iednavigator;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * La versión que muestra la app sale de classes/version.properties, que escribe
 * build_installer.ps1. Sin el archivo (corriendo desde el repo) se muestra "en desarrollo".
 *
 * Hasta 2026-10-22 el "Acerca de" decía "Version 2.0 - Hybrid Edition", fijo en el código.
 *
 * Está en el paquete com.iednavigator para usar Version.desde(), que es package-private.
 *
 * Uso:  javac -cp "classes;lib\*" -d test test\TestVersion.java
 *       java  -cp "classes;lib\*;test" com.iednavigator.TestVersion [vX.Y.Z]
 *
 * Con un argumento, además verifica que la versión cargada del classpath sea esa (para
 * correrlo contra las clases de un paquete armado).
 */
public class TestVersion {

    private static int fallas = 0;

    static void chk(String titulo, boolean ok, String detalle) {
        if (!ok) fallas++;
        System.out.printf("%-52s %-26s %s%n", titulo, detalle, ok ? "OK" : "<<< FALLA");
    }

    public static void main(String[] args) {
        Properties p = Version.desde(flujo("version=v4.13.7-pre\ncommit=54c85c0\n"));
        chk("lee version", "v4.13.7-pre".equals(p.getProperty("version")), p.getProperty("version"));
        chk("lee commit", "54c85c0".equals(p.getProperty("commit")), p.getProperty("commit"));
        chk("sin archivo: propiedades vacías", Version.desde(null).isEmpty(), "vacío");

        String etiqueta = Version.etiqueta();
        chk("etiqueta: nunca vacía", etiqueta != null && !etiqueta.isEmpty(), String.valueOf(etiqueta));
        chk("Acerca de: ya no dice 2.0 Hybrid",
            !I18n.t("about.version", etiqueta).contains("2.0"), I18n.t("about.version", etiqueta));

        if (args.length > 0) {
            chk("del classpath: la versión del paquete", args[0].equals(Version.numero()), String.valueOf(Version.numero()));
            chk("registro: versión y commit", Version.paraRegistro().startsWith(args[0] + " ("), Version.paraRegistro());
        } else {
            chk("desde el repo: sin versión, en desarrollo", Version.numero() == null
                && etiqueta.equals(I18n.t("about.version.dev")), etiqueta);
        }

        System.out.println(fallas == 0 ? "\nTODO OK" : "\n" + fallas + " FALLAS");
        System.exit(fallas == 0 ? 0 : 1);
    }

    static ByteArrayInputStream flujo(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
