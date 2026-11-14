package com.iednavigator;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Bobinas de apertura y de cierre del Interruptor virtual cableadas a señales GOOSE de otro IED
 * (un disparo de protección, una orden de cierre de un automatismo).
 *
 * Cada enlace mira un dato booleano de un GoCB y actúa en el flanco de subida: el valor tiene que
 * haberse visto en false antes de pasar a true. Así la retransmisión de una trama no repite la
 * orden, y una señal que ya estaba en true cuando se cableó no maniobra el aparato de golpe.
 * La orden va por {@link IEC61850Server#ordenarAparato}, que respeta las fallas del instructor.
 */
public final class BobinasGoose {

    public enum Bobina { APERTURA, CIERRE }

    public static final class Enlace {
        /** gocbRef de la trama, o su goID si no trae gocbRef. */
        public final String gocb;
        /** Posición del dato en el DataSet, desde 0. */
        public final int indice;
        public final Bobina bobina;
        /** Referencia de la posición del aparato, como la da listarAparatos. */
        public final String aparato;
        /** Último valor visto; null mientras no llegó ninguna trama. */
        volatile Boolean ultimo;

        Enlace(String gocb, int indice, Bobina bobina, String aparato) {
            this.gocb = gocb;
            this.indice = indice;
            this.bobina = bobina;
            this.aparato = aparato;
        }
    }

    private final IEC61850Server srv;
    private final List<Enlace> enlaces = new CopyOnWriteArrayList<>();

    public BobinasGoose(IEC61850Server srv) {
        this.srv = srv;
    }

    public Enlace cablear(String gocb, int indice, Bobina bobina, String aparato) {
        Enlace e = new Enlace(gocb, indice, bobina, aparato);
        enlaces.add(e);
        return e;
    }

    public void quitar(Enlace e) {
        enlaces.remove(e);
    }

    public List<Enlace> enlaces() {
        return new ArrayList<>(enlaces);
    }

    /** Identificador del GoCB de una trama: el mismo que se guarda en {@link Enlace#gocb}. */
    public static String idDe(GooseSubscriber.GooseMessage m) {
        if (m.gocbRef != null && !m.gocbRef.isEmpty()) return m.gocbRef;
        return m.goId != null ? m.goId : "";
    }

    /** Valor booleano de un dato de la trama, o null si no es booleano. */
    public static Boolean booleano(GooseSubscriber.DataEntry d) {
        return d != null && d.value instanceof Boolean ? (Boolean) d.value : null;
    }

    /** Procesa una trama recibida. Llamado desde el hilo de captura. */
    public void recibir(GooseSubscriber.GooseMessage m) {
        if (enlaces.isEmpty()) return;
        String id = idDe(m);
        for (Enlace e : enlaces) {
            if (!e.gocb.equals(id) || e.indice < 0 || e.indice >= m.dataEntries.size()) continue;
            Boolean v = booleano(m.dataEntries.get(e.indice));
            if (v == null) continue;
            Boolean antes = e.ultimo;
            e.ultimo = v;
            if (!v || !Boolean.FALSE.equals(antes)) continue;

            boolean cerrar = e.bobina == Bobina.CIERRE;
            String origen = "GOOSE";
            srv.avisarMando(origen, I18n.t(cerrar ? "vint.evt.goose.cierre" : "vint.evt.goose.apertura",
                    e.aparato, e.gocb, String.valueOf(e.indice), String.valueOf(m.stNum)), true);
            srv.ordenarAparato(e.aparato, cerrar, origen);
        }
    }
}
