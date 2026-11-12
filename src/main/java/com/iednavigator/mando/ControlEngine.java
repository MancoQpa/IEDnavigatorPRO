package com.iednavigator.mando;


import com.beanit.iec61850bean.BasicDataAttribute;
import com.beanit.iec61850bean.BdaBoolean;
import com.beanit.iec61850bean.BdaCheck;
import com.beanit.iec61850bean.BdaDoubleBitPos;
import com.beanit.iec61850bean.BdaInt16;
import com.beanit.iec61850bean.BdaInt32;
import com.beanit.iec61850bean.BdaInt8;
import com.beanit.iec61850bean.BdaInt16U;
import com.beanit.iec61850bean.BdaInt32U;
import com.beanit.iec61850bean.BdaInt8U;
import com.beanit.iec61850bean.BdaOctetString;
import com.beanit.iec61850bean.BdaTimestamp;
import com.beanit.iec61850bean.Fc;
import com.beanit.iec61850bean.ModelNode;
import com.beanit.iec61850bean.ServerModel;
import com.beanit.iec61850bean.ServiceError;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maquina de estados de control: Select-Before-Operate, Operate y Cancel.
 *
 * Hasta ahora el simulador solo sabia RECHAZAR mandos (ControlPolicy). Cuando los
 * aceptaba no pasaba nada: la libreria escribia el valor dentro de la estructura
 * FC=CO y nadie lo propagaba al estado. Es decir, un cliente podia hacer Select y
 * Operate contra el simulador y **el interruptor nunca se movia**, que es
 * justamente lo que un simulador tiene que permitir probar.
 *
 * Aca se implementa lo que hace un equipo real:
 *
 *   SBO / SBOw  -> valida, marca stSeld=true, guarda ctlNum y arranca sboTimeout
 *   Oper        -> valida la seleccion y APLICA ctlVal al stVal, actualiza q y t
 *   Cancel      -> libera la seleccion
 *   vencimiento -> la seleccion caduca sola
 *
 * Se respeta el ctlModel declarado en el SCL, que es lo que decide si hace falta
 * seleccionar antes de operar.
 *
 * Sin dependencias de Android salvo Log, para poder ejercitarla contra archivos
 * CID reales fuera del telefono.
 */
public final class ControlEngine {

    private static final String TAG = "ControlEngine";

    // ctlModel segun IEC 61850-7-3
    public static final int CTL_STATUS_ONLY            = 0;
    public static final int CTL_DIRECT_NORMAL          = 1;
    public static final int CTL_SBO_NORMAL             = 2;
    public static final int CTL_DIRECT_ENHANCED        = 3;
    public static final int CTL_SBO_ENHANCED           = 4;


    private static final long SBO_TIMEOUT_POR_DEFECTO_MS = 30000;

    /**
     * Seleccion viva sobre un objeto controlable.
     *
     * Guarda ctlNum y origin porque IEC 61850-7-2 §20 exige que el Oper repita
     * los mismos valores que trajo el SBOw: son los que correlacionan los dos
     * pasos. Un IED real rechaza el Oper si no coinciden, asi que el simulador
     * tiene que rechazarlo tambien o deja pasar errores que despues aparecen
     * en campo.
     */
    private static final class Seleccion {
        final long vence;
        final int ctlNum;
        final int orCat;
        final String orIdent;
        Seleccion(long vence, int ctlNum, int orCat, String orIdent) {
            this.vence = vence; this.ctlNum = ctlNum;
            this.orCat = orCat; this.orIdent = orIdent;
        }
    }

    /** Selecciones por objeto de control (p.ej. "LD/XCBR1.Pos"). */
    private final Map<String, Seleccion> selecciones = new HashMap<>();

    /** Resultado de procesar una escritura de control. */
    public static final class Resultado {
        /** Error con el que responder, o null si el mando procede. */
        public final ServiceError error;
        /** Atributos que cambiaron, para pasarlos a ServerSap.setValues(). */
        public final List<BasicDataAttribute> cambiados;
        /** Descripcion legible de lo que paso, para el log y la UI. */
        public final String descripcion;
        /** true si la escritura era un control (aunque haya fallado). */
        public final boolean eraControl;
        /** Como termina el mando, si no es en el acto y bien (5.5.0); null = en el acto, positiva. */
        public final Terminacion terminacion;

        Resultado(ServiceError error, List<BasicDataAttribute> cambiados,
                  String descripcion, boolean eraControl) {
            this(error, cambiados, descripcion, eraControl, null);
        }

        Resultado(ServiceError error, List<BasicDataAttribute> cambiados,
                  String descripcion, boolean eraControl, Terminacion terminacion) {
            this.error = error;
            this.cambiados = cambiados != null ? cambiados : new ArrayList<>();
            this.descripcion = descripcion;
            this.eraControl = eraControl;
            this.terminacion = terminacion;
        }

        Resultado con(Terminacion t) {
            return new Resultado(error, cambiados, descripcion, eraControl, t);
        }
        static Resultado noEsControl() { return new Resultado(null, null, null, false); }
        static Resultado rechazo(ServiceError e) { return new Resultado(e, null, e.getMessage(), true); }
        static Resultado ok(List<BasicDataAttribute> c, String d) { return new Resultado(null, c, d, true); }
    }

    /**
     * Procesa un lote de escritura. Si no es un control, devuelve noEsControl().
     */
    public Resultado procesar(ServerModel model, List<BasicDataAttribute> bdas) {
        if (model == null || bdas == null) return Resultado.noEsControl();

        // Que tipo de servicio de control es, y sobre que objeto
        String raiz = null;      // p.ej. "LD/XCBR1.Pos"
        String servicio = null;  // Oper | SBOw | SBO | Cancel
        BasicDataAttribute ctlVal = null;

        for (BasicDataAttribute bda : bdas) {
            if (bda.getFc() != Fc.CO) continue;
            String ref = bda.getReference() != null ? bda.getReference().toString() : "";
            for (String s : new String[]{"Oper", "SBOw", "Cancel"}) {
                int i = ref.indexOf("." + s + ".ctlVal");
                if (i >= 0) { raiz = ref.substring(0, i); servicio = s; ctlVal = bda; }
            }
            if (raiz == null && ref.endsWith(".SBO")) {
                raiz = ref.substring(0, ref.length() - ".SBO".length());
                servicio = "SBO";
            }
        }
        if (raiz == null) return Resultado.noEsControl();

        // Bits Check y Test, ctlNum y origin, que viajan en el mismo lote
        BdaCheck check = null;
        boolean test = false;
        int ctlNum = 0, orCat = -1;
        String orIdent = null;
        for (BasicDataAttribute bda : bdas) {
            String ref = bda.getReference() != null ? bda.getReference().toString() : "";
            if (bda instanceof BdaCheck) check = (BdaCheck) bda;
            else if (bda instanceof BdaBoolean && ref.endsWith(".Test")) test = ((BdaBoolean) bda).getValue();
            else if (ref.endsWith(".ctlNum")) ctlNum = (int) anchoLibre(bda, 0);
            else if (ref.endsWith(".origin.orCat")) orCat = (int) anchoLibre(bda, -1);
            else if (ref.endsWith(".origin.orIdent") && bda instanceof BdaOctetString)
                orIdent = new String(((BdaOctetString) bda).getValue());
        }

        int ctlModel = leerCtlModel(model, raiz);
        String nombre = raiz.contains("/") ? raiz.substring(raiz.indexOf('/') + 1) : raiz;

        Log.i(TAG, servicio + " sobre " + raiz + " ctlModel=" + ctlModel
                + " ctlNum=" + ctlNum + " test=" + test);

        // --- status-only: el objeto no admite mandos ---
        if (ctlModel == CTL_STATUS_ONLY) {
            return Resultado.rechazo(new ServiceError(ServiceError.ACCESS_VIOLATION,
                    nombre + ": ctlModel=status-only, el objeto no acepta mandos"));
        }

        // --- Cancel: libera la seleccion ---
        if ("Cancel".equals(servicio)) {
            selecciones.remove(raiz);
            List<BasicDataAttribute> cambiados = new ArrayList<>();
            ponerStSeld(model, raiz, false, cambiados);
            return Resultado.ok(cambiados, nombre + ": seleccion cancelada");
        }

        // --- Reglas de bloqueo comunes (Beh, bit Test, enclavamiento, sincronismo) ---
        ServiceError bloqueo = ControlPolicy.evaluate(model, bdas);
        if (bloqueo != null) return Resultado.rechazo(bloqueo);

        // Reparto de responsabilidades con la libreria parcheada:
        //
        //   ctlModel=2 (SBO normal)    -> la selecciona la LIBRERIA. El cliente
        //       selecciona LEYENDO el nodo SBO, una lectura que nunca pasa por
        //       este motor, y ServerAssociation ya rechaza el Oper si el objeto
        //       no quedo seleccionado por esa asociacion. Si acá exigieramos
        //       ademas una seleccion propia, rechazariamos mandos validos.
        //   ctlModel=4 (SBO reforzado) -> la seleccionamos NOSOTROS, porque el
        //       select-with-value es una ESCRITURA sobre SBOw y llega hasta acá.
        //
        boolean seleccionPropia = (ctlModel == CTL_SBO_ENHANCED);
        boolean admiteSelect = (ctlModel == CTL_SBO_NORMAL || ctlModel == CTL_SBO_ENHANCED);

        // --- Select ---
        if ("SBO".equals(servicio) || "SBOw".equals(servicio)) {
            if (!admiteSelect) {
                return Resultado.rechazo(new ServiceError(ServiceError.ACCESS_VIOLATION,
                        nombre + ": ctlModel es directo, no admite Select"));
            }
            long timeout = leerSboTimeout(model, raiz);
            selecciones.put(raiz,
                    new Seleccion(System.currentTimeMillis() + timeout, ctlNum, orCat, orIdent));

            List<BasicDataAttribute> cambiados = new ArrayList<>();
            ponerStSeld(model, raiz, true, cambiados);
            return Resultado.ok(cambiados,
                    nombre + ": seleccionado (vence en " + (timeout / 1000) + " s)");
        }

        // --- Operate ---
        if (seleccionPropia) {
            Seleccion sel = selecciones.get(raiz);
            if (sel == null) {
                return Resultado.rechazo(new ServiceError(ServiceError.CONTROL_MUST_BE_SELECTED,
                        nombre + ": hay que seleccionar antes de operar (ctlModel=sbo)"));
            }
            if (System.currentTimeMillis() > sel.vence) {
                selecciones.remove(raiz);
                List<BasicDataAttribute> c = new ArrayList<>();
                ponerStSeld(model, raiz, false, c);
                return Resultado.rechazo(new ServiceError(ServiceError.CONTROL_MUST_BE_SELECTED,
                        nombre + ": la seleccion vencio, hay que volver a seleccionar"));
            }
            // IEC 61850-7-2 §20: el Oper debe repetir el ctlNum y el origin del SBOw.
            // Es lo que correlaciona los dos pasos; si no se verifica, el simulador
            // acepta secuencias que un IED real rechaza.
            if (ctlNum != sel.ctlNum) {
                return Resultado.rechazo(new ServiceError(ServiceError.PARAMETER_VALUE_INCONSISTENT,
                        nombre + ": ctlNum del Oper (" + ctlNum + ") no coincide con el del Select ("
                                + sel.ctlNum + ")"));
            }
            if (sel.orCat >= 0 && orCat >= 0 && orCat != sel.orCat) {
                return Resultado.rechazo(new ServiceError(ServiceError.PARAMETER_VALUE_INCONSISTENT,
                        nombre + ": orCat del Oper (" + orCat + ") no coincide con el del Select ("
                                + sel.orCat + ")"));
            }
            if (sel.orIdent != null && orIdent != null && !orIdent.equals(sel.orIdent)) {
                return Resultado.rechazo(new ServiceError(ServiceError.PARAMETER_VALUE_INCONSISTENT,
                        nombre + ": orIdent del Oper no coincide con el del Select"));
            }
        }

        // --- 5.5.0: como responde el proceso a una orden de abrir o cerrar ---
        if (ctlVal instanceof BdaBoolean && model.findModelNode(raiz + ".stVal", Fc.ST) != null) {
            final boolean cerrar = ((BdaBoolean) ctlVal).getValue();
            int punto = raiz.indexOf('.');
            String lnRef = punto > 0 ? raiz.substring(0, punto) : raiz;

            // Cierre con chequeo de sincronismo y sin condiciones: se acepta, no se mueve, y a la
            // ventana termina en negativo con AddCause 11, como un rele real.
            boolean pideSincronismo = check != null && check.getSynchrocheck();
            if (cerrar && pideSincronismo && (EstadoLocal.sincronismoEnFallaPara(model, raiz)
                    || ControlPolicy.rsynSinPermiso(model, lnRef) != null)) {
                selecciones.remove(raiz);
                List<BasicDataAttribute> c = new ArrayList<>();
                ponerStSeld(model, raiz, false, c);
                long ventana = EstadoLocal.ventanaSincronismoMs;
                return Resultado.ok(c, nombre + ": sin sincronismo, el cierre no se ejecuta"
                        + " (terminacion negativa en " + (ventana / 1000.0) + " s)")
                        .con(Terminacion.negativaEn(ventana, 11));
            }

            EstadoLocal.Respuesta respuesta = EstadoLocal.respuestaPara(model, raiz);
            if (respuesta != EstadoLocal.Respuesta.NORMAL) {
                selecciones.remove(raiz);
                List<BasicDataAttribute> c = new ArrayList<>();
                ponerStSeld(model, raiz, false, c);
                List<String> posiciones = new ArrayList<>();
                posiciones.add(raiz);
                posiciones.addAll(EstadoLocal.posicionesVinculadas(model, raiz));
                switch (respuesta) {
                    case TRABADO:
                        return Resultado.ok(c, nombre + ": aparato trabado, no se movio");
                    case SIN_CABLEAR:
                        for (String p : posiciones) EstadoLocal.aplicarIntermedio(model, p, c);
                        return Resultado.ok(c, nombre + ": aparato sin cablear, queda en intermedio");
                    case LENTO:
                    default:
                        for (String p : posiciones) EstadoLocal.aplicarIntermedio(model, p, c);
                        long recorrido = EstadoLocal.recorridoLentoMs;
                        final BasicDataAttribute orden = ctlVal;
                        final String objeto = raiz;
                        return Resultado.ok(c, nombre + ": en recorrido, llega en "
                                + (recorrido / 1000.0) + " s")
                                .con(Terminacion.positivaEn(recorrido, () -> {
                                    List<BasicDataAttribute> fin = new ArrayList<>();
                                    aplicarAlEstado(model, objeto,
                                            model.findModelNode(objeto + ".stVal", Fc.ST), orden, fin);
                                    return fin;
                                }));
                }
            }
        }

        // Aplicar el mando al estado: esto es lo que faltaba por completo.
        List<BasicDataAttribute> cambiados = new ArrayList<>();
        ModelNode stVal = model.findModelNode(raiz + ".stVal", Fc.ST);
        if (stVal == null) {
            return Resultado.rechazo(new ServiceError(ServiceError.INSTANCE_NOT_AVAILABLE,
                    nombre + ": el objeto controlado no expone stVal"));
        }
        String detalle = aplicarAlEstado(model, raiz, stVal, ctlVal, cambiados);
        if (detalle == null) {
            return Resultado.rechazo(new ServiceError(ServiceError.TYPE_CONFLICT,
                    nombre + ": no se sabe aplicar un ctlVal "
                            + (ctlVal == null ? "ausente" : ctlVal.getClass().getSimpleName())
                            + " sobre un stVal " + stVal.getClass().getSimpleName()));
        }

        selecciones.remove(raiz);
        ponerStSeld(model, raiz, false, cambiados);

        return Resultado.ok(cambiados, nombre + ": " + detalle + (test ? " (modo prueba)" : ""));
    }

    /** Escribe el resultado del mando en el stVal del objeto controlado. */
    private String aplicarAlEstado(ServerModel model, String raiz, ModelNode n,
                                   BasicDataAttribute ctlVal,
                                   List<BasicDataAttribute> cambiados) {
        boolean cerrar = (ctlVal instanceof BdaBoolean) && ((BdaBoolean) ctlVal).getValue();

        // Controles enumerados (ENC, p.ej. Mod) y enteros (INC): el ctlVal no es
        // un booleano de abrir/cerrar sino un valor que se copia tal cual. Sin
        // esto el motor rechazaba todo mando sobre Mod/Beh y sobre cualquier
        // objeto INC, que son los que mas abundan en los archivos reales.
        if (!(ctlVal instanceof BdaBoolean) && ctlVal != null) {
            Long v = comoEntero(ctlVal);
            BasicDataAttribute copia = copiaDe(n);
            if (v != null && ponerEntero(copia, v)) {
                cambiados.add(copia);
                marcarTiempo(model, raiz, cambiados);
                return "valor " + v;
            }
        }

        if (n instanceof BdaDoubleBitPos) {
            // Posicion de doble punto: 1=off (abierto), 2=on (cerrado)
            // La libreria expone el doble punto como enum, no como byte crudo.
            BdaDoubleBitPos p = (BdaDoubleBitPos) copiaDe(n);
            p.setDoubleBitPos(cerrar
                    ? BdaDoubleBitPos.DoubleBitPos.ON
                    : BdaDoubleBitPos.DoubleBitPos.OFF);
            cambiados.add(p);
            marcarTiempo(model, raiz, cambiados);
            return (cerrar ? "cerrado" : "abierto") + moverAparato(model, raiz, cerrar, cambiados);
        }
        if (n instanceof BdaBoolean) {
            BdaBoolean b = (BdaBoolean) copiaDe(n);
            b.setValue(cerrar);
            cambiados.add(b);
            marcarTiempo(model, raiz, cambiados);
            return (cerrar ? "puesto en true" : "puesto en false")
                    + moverAparato(model, raiz, cerrar, cambiados);
        }
        return null;
    }

    /** Actualiza la marca de tiempo del cambio de estado, si el objeto la tiene. */
    private void marcarTiempo(ServerModel model, String raiz, List<BasicDataAttribute> cambiados) {
        ModelNode t = model.findModelNode(raiz + ".t", Fc.ST);
        if (t instanceof BdaTimestamp) {
            BdaTimestamp copia = (BdaTimestamp) copiaDe(t);
            copia.setInstant(java.time.Instant.now());
            cambiados.add(copia);
        }
    }

    /** Marca o desmarca stSeld, si el objeto lo expone. */
    private void ponerStSeld(ServerModel model, String raiz, boolean valor,
                             List<BasicDataAttribute> cambiados) {
        ModelNode s = model.findModelNode(raiz + ".stSeld", Fc.ST);
        if (s instanceof BdaBoolean) {
            BdaBoolean copia = (BdaBoolean) copiaDe(s);
            copia.setValue(valor);
            cambiados.add(copia);
        }
    }

    /**
     * ctlModel del objeto. Si el archivo no lo declara se asume
     * direct-with-normal-security, que es lo mas permisivo sin dejar de operar.
     */
    private int leerCtlModel(ServerModel model, String raiz) {
        Long v = leerEnteroCf(model, raiz + ".ctlModel");
        return (v != null) ? v.intValue() : CTL_DIRECT_NORMAL;
    }

    /**
     * Lee un entero de configuracion sin asumir el ancho ni el signo.
     *
     * Hay que cubrir las variantes SIN signo: verificado que sboTimeout viene como
     * BdaInt32U en los CID de ZIV y de MiCOM. Contemplar solo BdaInt32 hacia que
     * cayera al valor por defecto EN SILENCIO, que es el mismo tipo de falla muda
     * que el instanceof BdaInt8 de Mod/Beh/Health.
     */
    /** Igual que leerEnteroCf, accesible para el resto de la app (p.ej. EstadoLocal). */
    public static Long leerEnteroDeConfig(ServerModel model, String ref) {
        return leerEnteroCf(model, ref);
    }

    private static Long leerEnteroCf(ServerModel model, String ref) {
        ModelNode n = model.findModelNode(ref, Fc.CF);
        if (n instanceof BdaInt8)   return (long) ((BdaInt8) n).getValue();
        if (n instanceof BdaInt8U)  return (long) ((BdaInt8U) n).getValue();
        if (n instanceof BdaInt16)  return (long) ((BdaInt16) n).getValue();
        if (n instanceof BdaInt16U) return (long) ((BdaInt16U) n).getValue();
        if (n instanceof BdaInt32)  return (long) ((BdaInt32) n).getValue();
        if (n instanceof BdaInt32U) return ((BdaInt32U) n).getValue();
        return null;
    }

    /**
     * Copia de trabajo de un atributo del modelo.
     *
     * ServerSap.setValues() NO acepta los BasicDataAttribute del propio modelo:
     * lee bda.mirror, que en esos nodos vale null y revienta con NPE. El mirror
     * lo establece copy(), que deja copy.mirror apuntando al nodo original.
     *
     * Y hay una segunda razon, mas de fondo: setValues() solo emite reporte por
     * RCB si el valor de la copia DIFIERE del que tiene el modelo. Si primero
     * escribiamos el modelo y despues publicabamos, los dos valores coincidian
     * y el reporte no salia nunca. Por eso se copia PRIMERO y se modifica la
     * copia: el modelo lo actualiza setValues() al publicar.
     */
    private static BasicDataAttribute copiaDe(ModelNode n) {
        return (BasicDataAttribute) ((BasicDataAttribute) n).copy();
    }

    /**
     * Mueve el aparato que el mando gobierna, y los demas LN que lo representan.
     *
     * El cliente opera un CSWI, pero la posicion fisica vive en un XCBR o XSWI.
     * Sin esto el simulador respondia "CONTROL OK" y el interruptor no se movia:
     * el estado quedaba solo en el CSWI que recibio la orden, y un cliente que
     * mirara XCBR1.Pos —que es lo normal— no veia nada. Ver
     * EstadoLocal.posicionesVinculadas() para como se deduce el vinculo.
     */
    private String moverAparato(ServerModel model, String raiz, boolean cerrar,
                                List<BasicDataAttribute> cambiados) {
        List<String> vinculadas = EstadoLocal.posicionesVinculadas(model, raiz);
        int movidos = 0;
        for (String ref : vinculadas) {
            int antes = cambiados.size();
            EstadoLocal.aplicarPosicion(model, ref, cerrar, cambiados);
            if (cambiados.size() > antes) movidos++;
        }
        return (movidos == 0) ? "" : " (y " + movidos + " posicion(es) vinculada(s))";
    }

    /** Valor entero de un BDA de cualquier ancho, o null si no es entero. */
    private static Long comoEntero(BasicDataAttribute bda) {
        long v = anchoLibre(bda, Long.MIN_VALUE);
        return v == Long.MIN_VALUE ? null : v;
    }

    /** Escribe un entero en un stVal de cualquier ancho. false si no es entero. */
    private static boolean ponerEntero(ModelNode n, long v) {
        if (n instanceof BdaInt8)   { ((BdaInt8) n).setValue((byte) v);    return true; }
        if (n instanceof BdaInt8U)  { ((BdaInt8U) n).setValue((short) v);  return true; }
        if (n instanceof BdaInt16)  { ((BdaInt16) n).setValue((short) v);  return true; }
        if (n instanceof BdaInt16U) { ((BdaInt16U) n).setValue((int) v);   return true; }
        if (n instanceof BdaInt32)  { ((BdaInt32) n).setValue((int) v);    return true; }
        if (n instanceof BdaInt32U) { ((BdaInt32U) n).setValue(v);         return true; }
        return false;
    }

    /**
     * Igual que leerEnteroCf pero sobre un atributo que ya llego en la escritura.
     *
     * El cast fijo a BdaInt8U para ctlNum era un no-op silencioso con cualquier
     * otro ancho: el ctlNum quedaba en 0 y la correlacion SBOw -> Oper se caia
     * sin que nada lo dijera. Es el mismo arreglo que se hizo en IEDnavigatorPRO.
     */
    private static long anchoLibre(BasicDataAttribute bda, long porDefecto) {
        if (bda instanceof BdaInt8)   return ((BdaInt8) bda).getValue();
        if (bda instanceof BdaInt8U)  return ((BdaInt8U) bda).getValue();
        if (bda instanceof BdaInt16)  return ((BdaInt16) bda).getValue();
        if (bda instanceof BdaInt16U) return ((BdaInt16U) bda).getValue();
        if (bda instanceof BdaInt32)  return ((BdaInt32) bda).getValue();
        if (bda instanceof BdaInt32U) return ((BdaInt32U) bda).getValue();
        return porDefecto;
    }

    /** sboTimeout en ms, con un valor por defecto razonable si no esta declarado. */
    private long leerSboTimeout(ServerModel model, String raiz) {
        Long ms = leerEnteroCf(model, raiz + ".sboTimeout");
        return (ms != null && ms > 0) ? ms : SBO_TIMEOUT_POR_DEFECTO_MS;
    }

    /** Para la UI: dice si un objeto necesita Select antes de Operate. */
    public static boolean requiereSeleccion(int ctlModel) {
        return ctlModel == CTL_SBO_NORMAL || ctlModel == CTL_SBO_ENHANCED;
    }

    /** Nombre legible del ctlModel, para mostrarlo en la ventana de control. */
    public static String nombreCtlModel(int ctlModel) {
        switch (ctlModel) {
            case CTL_STATUS_ONLY:     return "status-only (no admite mandos)";
            case CTL_DIRECT_NORMAL:   return "direct-with-normal-security";
            case CTL_SBO_NORMAL:      return "sbo-with-normal-security";
            case CTL_DIRECT_ENHANCED: return "direct-with-enhanced-security";
            case CTL_SBO_ENHANCED:    return "sbo-with-enhanced-security";
            default:                  return "desconocido (" + ctlModel + ")";
        }
    }

    /** ctlModel de un objeto, para que la UI sepa que secuencia ofrecer. */
    public int ctlModelDe(ServerModel model, String raiz) {
        return leerCtlModel(model, raiz);
    }

    /** true si el objeto esta seleccionado y la seleccion no vencio. */
    public boolean estaSeleccionado(String raiz) {
        Seleccion s = selecciones.get(raiz);
        return s != null && System.currentTimeMillis() <= s.vence;
    }
}
