package com.iednavigator.mando;

import com.beanit.iec61850bean.BasicDataAttribute;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Como termina un mando aceptado (5.5.0).
 *
 * Hasta la 5.4.0 un Oper aceptado cambiaba la posicion en el acto y la terminacion positiva salia
 * pegada a la respuesta. Un rele real puede tardar: un seccionador recorre durante segundos, y un
 * cierre con chequeo de sincronismo espera una ventana antes de rendirse (terminacion negativa,
 * AddCause 11). Esta clase lleva esa decision del motor de control (ControlEngine) al servidor
 * (ServerAssociation parcheada), que es el unico que puede mandar el CommandTermination.
 *
 * El canal es el hilo: la biblioteca llama al ServerEventListener.write() dentro del mismo hilo que
 * atiende el pedido, asi que el listener deja aca el plan con anunciar() y la asociacion lo toma con
 * tomar() apenas el write() vuelve.
 *
 * Sin dependencias de Android, para que el banco la compile.
 */
public final class Terminacion {

    /** Milisegundos desde la respuesta al Oper hasta la terminacion. 0 = en el acto. */
    public final long retardoMs;
    public final boolean positiva;
    /** AddCause de una terminacion negativa (IEC 61850-7-2). */
    public final int addCause;
    /** Cambios a aplicar al terminar (la posicion final de un aparato lento), o null. */
    public final Supplier<List<BasicDataAttribute>> alTerminar;

    private Terminacion(long retardoMs, boolean positiva, int addCause,
                        Supplier<List<BasicDataAttribute>> alTerminar) {
        this.retardoMs = retardoMs;
        this.positiva = positiva;
        this.addCause = addCause;
        this.alTerminar = alTerminar;
    }

    public static Terminacion positivaEn(long ms, Supplier<List<BasicDataAttribute>> alTerminar) {
        return new Terminacion(ms, true, 0, alTerminar);
    }

    public static Terminacion negativaEn(long ms, int addCause) {
        return new Terminacion(ms, false, addCause, null);
    }

    // ------------------------------------------------------------- canal por hilo

    private static final ThreadLocal<Terminacion> ACTUAL = new ThreadLocal<>();

    /** Lo llama el listener dentro de write(), con el plan del mando que acaba de aceptar. */
    public static void anunciar(Terminacion t) {
        ACTUAL.set(t);
    }

    /** Lo llama la asociacion despues del write(). Devuelve el plan (o null) y limpia el canal. */
    public static Terminacion tomar() {
        Terminacion t = ACTUAL.get();
        ACTUAL.remove();
        return t;
    }

    // ------------------------------------------------------------- reloj y publicacion

    private static final ScheduledExecutorService RELOJ = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread h = new Thread(r, "terminacion-diferida");
        h.setDaemon(true);
        return h;
    });

    /**
     * Quien escribe en el modelo y emite los reportes de los cambios finales. Lo registra el
     * servicio (o el banco) al arrancar; sin el, los cambios no se publican.
     */
    public static volatile Consumer<List<BasicDataAttribute>> publicador;

    /** Programa el final del mando: aplica los cambios y despues corre {@code despues}. */
    public void programar(Runnable despues) {
        RELOJ.schedule(() -> {
            try {
                if (alTerminar != null) {
                    List<BasicDataAttribute> c = alTerminar.get();
                    Consumer<List<BasicDataAttribute>> p = publicador;
                    if (c != null && !c.isEmpty() && p != null) p.accept(new ArrayList<>(c));
                }
            } finally {
                if (despues != null) despues.run();
            }
        }, retardoMs, TimeUnit.MILLISECONDS);
    }
}
