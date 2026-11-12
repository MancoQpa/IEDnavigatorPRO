package com.iednavigator.mando;

/**
 * Lo único de Android que usaba el motor de mando del IEDsimulatorPRO (android.util.Log).
 *
 * El paquete com.iednavigator.mando es el mismo motor que corre en el simulador de Android
 * (ControlEngine, ControlPolicy, EstadoLocal, Terminacion): esta clase reemplaza a la de
 * Android con la misma firma, para que esos archivos se puedan traer de un proyecto al otro
 * sin tocar su lógica.
 *
 * Escribe por la salida estándar (que la aplicación guarda en iednavigator.log). Por defecto
 * solo los avisos y errores; con -Diednavigator.mando.log=todo también el detalle.
 */
final class Log {

    private static final boolean TODO = "todo".equals(System.getProperty("iednavigator.mando.log"));

    private Log() { }

    static int v(String tag, String msg) { return p("V", tag, msg, null); }
    static int d(String tag, String msg) { return p("D", tag, msg, null); }
    static int i(String tag, String msg) { return p("I", tag, msg, null); }
    static int w(String tag, String msg) { return p("W", tag, msg, null); }
    static int e(String tag, String msg) { return p("E", tag, msg, null); }

    static int v(String tag, String msg, Throwable t) { return p("V", tag, msg, t); }
    static int d(String tag, String msg, Throwable t) { return p("D", tag, msg, t); }
    static int i(String tag, String msg, Throwable t) { return p("I", tag, msg, t); }
    static int w(String tag, String msg, Throwable t) { return p("W", tag, msg, t); }
    static int e(String tag, String msg, Throwable t) { return p("E", tag, msg, t); }

    private static int p(String nivel, String tag, String msg, Throwable t) {
        boolean importa = nivel.equals("W") || nivel.equals("E");
        if (!importa && !TODO) return 0;
        System.out.println("[" + tag + "] " + nivel + " " + msg + (t != null ? " — " + t : ""));
        return 0;
    }
}
