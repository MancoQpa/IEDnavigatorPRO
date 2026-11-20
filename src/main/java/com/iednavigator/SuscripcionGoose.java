package com.iednavigator;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Suscripción GOOSE del interruptor virtual: bobina de disparo (abrir) y bobina de cierre, cada
 * una cableada a un dato booleano de un GoCB de otro IED. Se configura a mano, como en la
 * herramienta de referencia del curso: APPID, goCbRef, índice del dato y, si se quiere, la MAC de
 * origen. No hace falta haber visto tramas antes.
 *
 * Cada señal tiene un estado (sin configurar, sin datos, recibiendo, perdida por TAL vencido,
 * error) para el LED de la ventana. La orden sale en el flanco de subida: el dato tiene que
 * haberse visto en FALSE antes de pasar a TRUE, así una retransmisión no repite la orden y una
 * señal que ya estaba en TRUE al configurar no maniobra de golpe. Si el dato es una estructura
 * (un DO entero, p. ej. PTRC1.Tr) se usa su primer booleano (general).
 *
 * No depende de Swing: {@link #recibir} se llama desde el hilo de captura.
 */
public final class SuscripcionGoose {

    public enum Rol { DISPARO, CIERRE }

    public enum Estado { SIN_CONFIGURAR, SIN_DATOS, RECIBIENDO, PERDIDO, ERROR }

    /** TAL que se supone cuando la trama no lo trae (GOOSE sobre UDP, loopback): tres latidos de 1 s. */
    static final int TAL_POR_DEFECTO_MS = 3000;

    /** Qué hacer cuando llega la orden: la ventana del interruptor decide si se puede maniobrar. */
    public interface Accion {
        void ordenar(Rol rol, int stNum);
    }

    /** Una señal configurada. Inmutable: se valida al crearla. */
    public static final class Config {
        public final int appId;
        public final String gocbRef;
        public final int indice;
        /** MAC de origen en minúsculas y sin separadores, o null para aceptar cualquiera. */
        public final String macOrigen;

        Config(int appId, String gocbRef, int indice, String macOrigen) {
            this.appId = appId;
            this.gocbRef = gocbRef;
            this.indice = indice;
            this.macOrigen = macOrigen;
        }

        /**
         * Valida lo que escribió el usuario. El APPID va en hexadecimal, con o sin 0x, como lo
         * muestran la tabla de la pestaña GOOSE y el SCL.
         *
         * @throws IllegalArgumentException con un texto para mostrar
         */
        public static Config validar(String appId, String gocbRef, String indice, String mac) {
            String ref = gocbRef == null ? "" : gocbRef.trim();
            if (ref.isEmpty() || !ref.contains("/")) throw new IllegalArgumentException(I18n.t("vint.sub.err.gocbref"));
            int idx;
            try {
                idx = Integer.parseInt(indice == null || indice.trim().isEmpty() ? "0" : indice.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(I18n.t("vint.sub.err.indice"));
            }
            if (idx < 0) throw new IllegalArgumentException(I18n.t("vint.sub.err.indice"));
            return new Config(appIdDe(appId), ref, idx, macDe(mac));
        }

        /** APPID en hexadecimal, como se muestra: {@code 0x0001}. */
        public String textoAppId() { return String.format("0x%04X", appId); }

        /** MAC como la escribe el usuario ({@code 00-30-A7-12-34-56}), o "" si no hay. */
        public String textoMac() {
            if (macOrigen == null) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i += 2) {
                if (sb.length() > 0) sb.append('-');
                sb.append(macOrigen, i, i + 2);
            }
            return sb.toString().toUpperCase(Locale.ROOT);
        }

        /** Para guardar en las preferencias. */
        public String serializar() {
            return textoAppId() + "|" + gocbRef + "|" + indice + "|" + textoMac();
        }

        /** Lo inverso de {@link #serializar}; null si el texto no sirve. */
        public static Config leer(String s) {
            if (s == null || s.isEmpty()) return null;
            String[] p = s.split("\\|", -1);
            if (p.length < 4) return null;
            try {
                return validar(p[0], p[1], p[2], p[3]);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    static int appIdDe(String texto) {
        String t = texto == null ? "" : texto.trim().toLowerCase(Locale.ROOT);
        if (t.startsWith("0x")) t = t.substring(2);
        if (t.isEmpty()) throw new IllegalArgumentException(I18n.t("vint.sub.err.appid"));
        int v;
        try {
            v = Integer.parseInt(t, 16);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(I18n.t("vint.sub.err.appid"));
        }
        if (v < 0 || v > 0xFFFF) throw new IllegalArgumentException(I18n.t("vint.sub.err.appid"));
        return v;
    }

    /** MAC normalizada (12 hex en minúsculas) o null si está vacía. */
    static String macDe(String texto) {
        String t = texto == null ? "" : texto.trim();
        if (t.isEmpty()) return null;
        String limpio = t.replace(":", "").replace("-", "").replace(".", "").toLowerCase(Locale.ROOT);
        if (!limpio.matches("[0-9a-f]{12}")) throw new IllegalArgumentException(I18n.t("vint.sub.err.mac"));
        return limpio;
    }

    /** Valor booleano de un dato: el propio, o el primer booleano de una estructura. */
    static Boolean primerBooleano(Object v) {
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof List) {
            for (Object x : (List<?>) v) {
                Boolean b = primerBooleano(x);
                if (b != null) return b;
            }
        }
        return null;
    }

    private static final class Senal {
        final Config cfg;
        volatile long ultimoRxMs;
        volatile int talMs;
        volatile int stNum;
        volatile Boolean valor;
        volatile boolean test;
        volatile String error;
        /** Último valor visto, para el flanco; null hasta la primera trama válida. */
        Boolean previo;

        Senal(Config cfg) { this.cfg = cfg; }
    }

    private final Map<Rol, Senal> senales = new EnumMap<>(Rol.class);
    private final Accion accion;
    /** Reloj en ms; reemplazable en las pruebas. */
    private final java.util.function.LongSupplier reloj;

    public SuscripcionGoose(Accion accion) {
        this(accion, System::currentTimeMillis);
    }

    SuscripcionGoose(Accion accion, java.util.function.LongSupplier reloj) {
        this.accion = accion;
        this.reloj = reloj;
    }

    /** Configura una señal; con null la deja sin configurar. */
    public synchronized void configurar(Rol rol, Config cfg) {
        if (cfg == null) senales.remove(rol);
        else senales.put(rol, new Senal(cfg));
    }

    public synchronized Config config(Rol rol) {
        Senal s = senales.get(rol);
        return s == null ? null : s.cfg;
    }

    public synchronized boolean hayAlguna() {
        return !senales.isEmpty();
    }

    /** Procesa una trama recibida (hilo de captura). */
    public void recibir(GooseSubscriber.GooseMessage m) {
        if (m == null) return;
        for (Rol rol : Rol.values()) {
            Senal s;
            synchronized (this) { s = senales.get(rol); }
            if (s == null || !coincide(s.cfg, m)) continue;
            procesar(rol, s, m);
        }
    }

    private static boolean coincide(Config c, GooseSubscriber.GooseMessage m) {
        if (m.appId != c.appId) return false;
        if (m.gocbRef == null || !c.gocbRef.equals(m.gocbRef.trim())) return false;
        if (c.macOrigen == null) return true;
        String mac = m.srcMac == null ? "" : m.srcMac.replace(":", "").replace("-", "").toLowerCase(Locale.ROOT);
        return c.macOrigen.equals(mac);
    }

    private void procesar(Rol rol, Senal s, GooseSubscriber.GooseMessage m) {
        s.ultimoRxMs = reloj.getAsLong();
        s.talMs = m.timeAllowedToLive;
        s.stNum = m.stNum;
        s.test = m.test;
        if (s.cfg.indice >= m.dataEntries.size()) {
            s.error = I18n.t("vint.sub.err.fuera", String.valueOf(s.cfg.indice), String.valueOf(m.dataEntries.size()));
            s.valor = null;
            return;
        }
        Boolean v = primerBooleano(m.dataEntries.get(s.cfg.indice).value);
        if (v == null) {
            s.error = I18n.t("vint.sub.err.nobool", String.valueOf(s.cfg.indice));
            s.valor = null;
            return;
        }
        s.error = null;
        s.valor = v;
        Boolean antes = s.previo;
        s.previo = v;
        if (v && Boolean.FALSE.equals(antes) && accion != null) accion.ordenar(rol, m.stNum);
    }

    public Estado estado(Rol rol) {
        Senal s;
        synchronized (this) { s = senales.get(rol); }
        if (s == null) return Estado.SIN_CONFIGURAR;
        if (s.error != null) return Estado.ERROR;
        if (s.ultimoRxMs == 0) return Estado.SIN_DATOS;
        int tal = s.talMs > 0 ? s.talMs : TAL_POR_DEFECTO_MS;
        if (reloj.getAsLong() - s.ultimoRxMs > tal) return Estado.PERDIDO;
        return Estado.RECIBIENDO;
    }

    /** Texto del estado para el LED y su tooltip. */
    public String textoEstado(Rol rol) {
        Senal s;
        synchronized (this) { s = senales.get(rol); }
        switch (estado(rol)) {
            case SIN_CONFIGURAR: return I18n.t("vint.sub.st.none");
            case SIN_DATOS:      return I18n.t("vint.sub.st.waiting");
            case ERROR:          return s.error;
            case PERDIDO:        return I18n.t("vint.sub.st.lost", String.valueOf(s.talMs > 0 ? s.talMs : TAL_POR_DEFECTO_MS));
            default:
                return I18n.t("vint.sub.st.ok", String.valueOf(s.stNum), Boolean.TRUE.equals(s.valor) ? "TRUE" : "FALSE")
                        + (s.test ? " · TEST" : "");
        }
    }

    /** Estado agregado para el LED de la ventana principal. */
    public Estado resumen() {
        boolean alguna = false, todasOk = true, problema = false;
        for (Rol r : Rol.values()) {
            Estado e = estado(r);
            if (e == Estado.SIN_CONFIGURAR) continue;
            alguna = true;
            if (e != Estado.RECIBIENDO) todasOk = false;
            if (e == Estado.PERDIDO || e == Estado.ERROR) problema = true;
        }
        if (!alguna) return Estado.SIN_CONFIGURAR;
        if (todasOk) return Estado.RECIBIENDO;
        return problema ? Estado.ERROR : Estado.SIN_DATOS;
    }
}
