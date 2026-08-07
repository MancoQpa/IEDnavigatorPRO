package com.iednavigator;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;

/**
 * Registro de sesión en disco.
 *
 * <h3>Por qué existe</h3>
 * El panel de Log de la interfaz es un {@code JTextArea} en memoria: al cerrar la ventana su
 * contenido se pierde. Eso se hizo evidente tras una sesión de pruebas contra un IED real, en
 * la que la única constancia de la secuencia de maniobras quedó en capturas de pantalla del
 * operador. Para trabajo de campo el registro tiene valor propio, así que cada sesión escribe
 * además a un archivo.
 *
 * <h3>Criterios</h3>
 * <ul>
 *   <li><b>Un archivo por sesión</b>, con fecha y hora en el nombre. Es más útil que un único
 *       archivo rotativo: cada sesión queda como una pieza identificable que se puede adjuntar
 *       a un informe.</li>
 *   <li><b>Volcado inmediato</b> tras cada línea. Un cierre abrupto o un corte de energía no
 *       debe llevarse la parte final, que suele ser justo la que interesa.</li>
 *   <li><b>Fecha completa</b> en cada línea, no sólo la hora: una sesión de puesta en servicio
 *       puede cruzar la medianoche.</li>
 *   <li><b>Poda automática</b>: se conservan las últimas {@value #KEEP_SESSIONS} sesiones.</li>
 *   <li><b>Nunca interrumpe la aplicación</b>: si el archivo no se puede abrir o escribir, se
 *       desactiva en silencio y la app sigue funcionando igual.</li>
 * </ul>
 */
final class SessionLog implements java.io.Closeable {

    /** Cantidad de sesiones que se conservan en disco. */
    private static final int KEEP_SESSIONS = 30;

    private final File file;
    private Writer out;
    private final SimpleDateFormat stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    private boolean broken = false;

    private SessionLog(File file, Writer out) {
        this.file = file;
        this.out = out;
    }

    /**
     * Abre el registro de la sesión. Nunca lanza: si no se puede escribir en ningún destino
     * devuelve null y la aplicación funciona sin registro en disco.
     */
    static SessionLog start() {
        File dir = resolveDir();
        if (dir == null) return null;
        try {
            String name = "session-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".log";
            File f = new File(dir, name);
            Writer w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8);
            SessionLog log = new SessionLog(f, w);
            log.writeHeader();
            prune(dir);
            return log;
        } catch (Exception e) {
            System.err.println("[LOG] No se pudo abrir el registro de sesión: " + e.getMessage());
            return null;
        }
    }

    /**
     * Carpeta de registros. Se prefiere {@code logs/} junto a la aplicación, que es lo que
     * espera quien trabaja con la copia portable; si no se puede escribir ahí —por ejemplo si
     * quedó instalada bajo Program Files— se cae al perfil del usuario.
     */
    private static File resolveDir() {
        for (File base : new File[]{ new File(System.getProperty("user.dir", "."), "logs"),
                                     new File(System.getProperty("user.home", "."),
                                              ".iednavigator" + File.separator + "logs") }) {
            try {
                if (!base.exists() && !base.mkdirs()) continue;
                if (base.isDirectory() && base.canWrite()) return base;
            } catch (Exception ignore) {}
        }
        return null;
    }

    private void writeHeader() {
        String nl = System.lineSeparator();
        StringBuilder sb = new StringBuilder();
        sb.append("# IEDNavigator PRO — registro de sesión").append(nl);
        sb.append("# inicio    : ").append(stamp.format(new Date())).append(nl);
        sb.append("# carpeta   : ").append(System.getProperty("user.dir", "?")).append(nl);
        sb.append("# java      : ").append(System.getProperty("java.version", "?"))
          .append(" (").append(System.getProperty("os.name", "?")).append(")").append(nl);
        sb.append("# idioma    : ").append(I18n.currentTag()).append(nl);
        sb.append("#").append(nl);
        rawWrite(sb.toString());
    }

    /** Agrega una línea al registro. Sincronizado: {@code log()} se llama desde varios hilos. */
    synchronized void write(String message) {
        if (broken || out == null) return;
        rawWrite(stamp.format(new Date()) + "  " + message + System.lineSeparator());
    }

    private void rawWrite(String text) {
        try {
            out.write(text);
            out.flush();               // el final del registro es el que más importa
        } catch (IOException e) {
            broken = true;             // se desactiva, no se interrumpe a la aplicación
            System.err.println("[LOG] Registro de sesión desactivado: " + e.getMessage());
        }
    }

    /** Archivo de esta sesión. */
    File file() { return file; }

    /** Carpeta donde viven los registros. */
    File directory() { return file.getParentFile(); }

    @Override
    public synchronized void close() {
        if (out == null) return;
        try {
            rawWrite("# fin       : " + stamp.format(new Date()) + System.lineSeparator());
            out.close();
        } catch (Exception ignore) {
        } finally {
            out = null;
        }
    }

    /** Conserva las últimas KEEP_SESSIONS sesiones y borra las más viejas. */
    private static void prune(File dir) {
        try {
            File[] files = dir.listFiles((d, n) -> n.startsWith("session-") && n.endsWith(".log"));
            if (files == null || files.length <= KEEP_SESSIONS) return;
            Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
            for (int i = KEEP_SESSIONS; i < files.length; i++) {
                if (!files[i].delete()) files[i].deleteOnExit();
            }
        } catch (Exception ignore) {}
    }
}
