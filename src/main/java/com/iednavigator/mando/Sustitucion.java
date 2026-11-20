package com.iednavigator.mando;

import com.beanit.iec61850bean.BasicDataAttribute;
import com.beanit.iec61850bean.BdaBoolean;
import com.beanit.iec61850bean.BdaQuality;
import com.beanit.iec61850bean.BdaTimestamp;
import com.beanit.iec61850bean.Fc;
import com.beanit.iec61850bean.ModelNode;
import com.beanit.iec61850bean.ServerModel;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sustitución de valores (IEC 61850-7-3, atributos FC=SV): un cliente escribe subVal / subMag /
 * subCVal, subQ y subID, y con subEna=true el servidor publica esos valores en lugar de los de
 * proceso, con la calidad subQ marcada "substituted" (source). Con subEna=false vuelve el valor
 * de proceso, incluido lo que el proceso cambió mientras tanto.
 *
 * <pre>
 *   subVal  -> stVal (FC ST)      SPS, SPC, DPS, DPC, INS, INC, ENS, ENC...
 *   subMag  -> mag   (FC MX)      MV
 *   subCVal -> cVal  (FC MX)      CMV
 *   subQ    -> q     (FC del dato sustituido)
 * </pre>
 *
 * Mismo código en IEDNavigator (com.iednavigator.mando) y en el IEDsimulatorPRO
 * (com.ied.simulator): mantener las dos copias iguales.
 */
public final class Sustitucion {

    private Sustitucion() { }

    /** Atributo de sustitución -> atributo de proceso que reemplaza, y su FC. */
    private static final String[][] PARES = {
        {"subVal", "stVal", "ST"},
        {"subMag", "mag", "MX"},
        {"subCVal", "cVal", "MX"},
    };

    /** Valores de proceso guardados de cada DO sustituido: "ref|FC" del atributo -> copia. */
    private static final Map<String, Map<String, BasicDataAttribute>> GUARDADOS = new ConcurrentHashMap<>();

    /** Al cargar otro modelo o reiniciar el servidor. */
    public static void limpiar() {
        GUARDADOS.clear();
    }

    /** ¿Este DO tiene la sustitución activa? */
    public static boolean activa(String doRef) {
        return GUARDADOS.containsKey(doRef);
    }

    /** "LD/LN.DO" de un atributo de sustitución ("LD/LN.DO.subMag.f" -> "LD/LN.DO"), o null. */
    static String doDe(String ref) {
        String[] partes = ref.split("\\.");
        for (int i = 1; i < partes.length; i++) {
            if (partes[i].startsWith("sub") && partes[i].length() > 3) {
                return String.join(".", java.util.Arrays.copyOfRange(partes, 0, i));
            }
        }
        return null;
    }

    /**
     * Lo que hay que publicar por una escritura de un cliente: si toca atributos FC=SV de un DO,
     * calcula el nuevo estado de la sustitución (con los valores escritos, que el modelo todavía
     * no tiene) y devuelve copias listas para ServerSap.setValues. Vacía si no corresponde.
     */
    public static List<BasicDataAttribute> porEscritura(ServerModel model, List<BasicDataAttribute> escritos) {
        List<BasicDataAttribute> salida = new ArrayList<>();
        if (model == null || escritos == null) return salida;
        Map<String, Map<String, BasicDataAttribute>> porDo = new LinkedHashMap<>();
        for (BasicDataAttribute b : escritos) {
            if (b.getFc() != Fc.SV || b.getReference() == null) continue;
            String ref = b.getReference().toString();
            String d = doDe(ref);
            if (d == null) continue;
            porDo.computeIfAbsent(d, k -> new LinkedHashMap<>()).put(ref, b);
        }
        for (Map.Entry<String, Map<String, BasicDataAttribute>> e : porDo.entrySet()) {
            salida.addAll(aplicar(model, e.getKey(), e.getValue()));
        }
        return salida;
    }

    /** Valor efectivo de un atributo SV: el escrito si vino en esta escritura, si no el del modelo. */
    private static BasicDataAttribute sv(ServerModel model, String ref, Map<String, BasicDataAttribute> escritos) {
        BasicDataAttribute b = escritos.get(ref);
        if (b != null) return b;
        ModelNode n = model.findModelNode(ref, Fc.SV);
        return n instanceof BasicDataAttribute ? (BasicDataAttribute) n : null;
    }

    private static List<BasicDataAttribute> aplicar(ServerModel model, String doRef,
                                                    Map<String, BasicDataAttribute> escritos) {
        List<BasicDataAttribute> c = new ArrayList<>();
        BasicDataAttribute ena = sv(model, doRef + ".subEna", escritos);
        boolean activar = ena instanceof BdaBoolean && ((BdaBoolean) ena).getValue();

        if (!activar) {
            Map<String, BasicDataAttribute> g = GUARDADOS.remove(doRef);
            if (g == null) return c;   // no estaba sustituido: solo se guardan los SV escritos
            for (BasicDataAttribute guardado : g.values()) {
                ModelNode n = model.findModelNode(guardado.getReference().toString(), guardado.getFc());
                if (!(n instanceof BasicDataAttribute)) continue;
                BasicDataAttribute copia = (BasicDataAttribute) ((BasicDataAttribute) n).copy();
                copia.setValueFrom(guardado);
                c.add(copia);
            }
            estampar(model, doRef, c);
            return c;
        }

        // Activa (o ya activa y cambió un valor): qué par usa este DO
        String fcDato = null;
        Map<String, BasicDataAttribute> guardar = new LinkedHashMap<>();
        for (String[] par : PARES) {
            ModelNode sub = model.findModelNode(doRef + "." + par[0], Fc.SV);
            Fc fc = Fc.fromString(par[2]);
            ModelNode dato = model.findModelNode(doRef + "." + par[1], fc);
            if (sub == null || dato == null) continue;
            fcDato = par[2];
            for (BasicDataAttribute dest : dato.getBasicDataAttributes()) {
                String relativo = dest.getReference().toString().substring((doRef + "." + par[1]).length());
                BasicDataAttribute origen = sv(model, doRef + "." + par[0] + relativo, escritos);
                if (origen == null) continue;
                guardar.put(clave(dest), (BasicDataAttribute) dest.copy());
                BasicDataAttribute copia = (BasicDataAttribute) dest.copy();
                try {
                    copia.setValueFrom(origen);
                } catch (Exception tipoDistinto) {
                    continue;
                }
                c.add(copia);
            }
        }
        if (fcDato == null) return c;   // DO sin dato sustituible conocido

        ModelNode q = model.findModelNode(doRef + ".q", Fc.fromString(fcDato));
        BasicDataAttribute subQ = sv(model, doRef + ".subQ", escritos);
        if (q instanceof BdaQuality) {
            guardar.put(clave((BasicDataAttribute) q), (BasicDataAttribute) ((BdaQuality) q).copy());
            BdaQuality copia = ((BdaQuality) q).copy();
            if (subQ instanceof BdaQuality) copia.setValueFrom(subQ);
            copia.setSubstituted(true);
            c.add(copia);
        }
        // Lo guardado es el valor de proceso: si ya estaba sustituido, se conserva el original
        GUARDADOS.putIfAbsent(doRef, guardar);
        estampar(model, doRef, c);
        return c;
    }

    /**
     * Cambios que produce el proceso (mando, panel, edición en el árbol) mientras un DO está
     * sustituido: los del dato sustituido y su q no se publican; se guardan como nuevo valor de
     * proceso, que vuelve al quitar la sustitución. Devuelve la lista sin esos atributos.
     */
    public static List<BasicDataAttribute> filtrarProceso(List<BasicDataAttribute> cambiados) {
        if (GUARDADOS.isEmpty() || cambiados == null) return cambiados;
        List<BasicDataAttribute> salen = new ArrayList<>();
        for (BasicDataAttribute b : cambiados) {
            Map<String, BasicDataAttribute> g = b.getReference() == null ? null : guardadosDe(b.getReference().toString());
            BasicDataAttribute previo = g == null ? null : g.get(clave(b));
            if (previo != null) {
                previo.setValueFrom(b);   // nuevo valor de proceso, oculto mientras dure
            } else {
                salen.add(b);
            }
        }
        return salen;
    }

    private static Map<String, BasicDataAttribute> guardadosDe(String ref) {
        for (Map.Entry<String, Map<String, BasicDataAttribute>> e : GUARDADOS.entrySet()) {
            if (ref.startsWith(e.getKey() + ".")) return e.getValue();
        }
        return null;
    }

    private static String clave(BasicDataAttribute b) {
        return b.getReference() + "|" + b.getFc();
    }

    private static void estampar(ServerModel model, String doRef, List<BasicDataAttribute> c) {
        for (String fc : new String[] {"ST", "MX"}) {
            ModelNode t = model.findModelNode(doRef + ".t", Fc.fromString(fc));
            if (t instanceof BdaTimestamp) {
                BdaTimestamp bt = ((BdaTimestamp) t).copy();
                bt.setInstant(Instant.now());
                c.add(bt);
                return;
            }
        }
    }
}
