package com.iednavigator;

import com.beanit.iec61850bean.internal.mms.asn1.AccessResult;
import com.beanit.iec61850bean.internal.mms.asn1.Data;
import com.beanit.iec61850bean.internal.mms.asn1.InformationReport;
import com.beanit.iec61850bean.internal.mms.asn1.ObjectName;
import com.beanit.iec61850bean.internal.mms.asn1.VariableDefs;

import java.util.ArrayList;
import java.util.List;

/**
 * LastApplError y CommandTermination: los InformationReport que el IED manda por su cuenta
 * durante un control (IEC 61850-8-1 §20 y IEC 61850-7-2 §20).
 *
 * Con seguridad reforzada (ctlModel 3 y 4) el IED responde el Write del Oper apenas acepta la
 * orden, y cuando la maniobra termina manda el CommandTermination:
 * <ul>
 *   <li>positivo: un reporte con la variable Oper sola;</li>
 *   <li>negativo: un reporte con LastApplError + Oper; el LastApplError trae el AddCause.</li>
 * </ul>
 * Además, cuando rechaza un SBOw/Oper/Cancel, manda un LastApplError suelto antes de la
 * respuesta negativa del Write.
 *
 * Hasta 2026-10-26 iec61850bean descartaba los tres (ClientAssociation sombreado). La app
 * deducía el resultado leyendo stVal; un IED que "acepta y después falla" (el NARI) quedaba
 * como "sin confirmación" y sin motivo.
 */
public final class TerminacionComando {

    private TerminacionComando() {}

    /** Lo que trae un reporte, ya decodificado. */
    public static final class Mensaje {
        /** Referencia del Oper al estilo de la app ("LD/LN.DO.Oper"); null si no vino. */
        public final String oper;
        public final boolean traeLastApplError;
        /** Campos del LastApplError (válidos si traeLastApplError). */
        public final String cntrlObj;
        public final int error, orCat, ctlNum, addCause;
        public final long recibidoMs;

        Mensaje(String oper, boolean traeLastApplError, String cntrlObj, int error, int orCat,
                int ctlNum, int addCause, long recibidoMs) {
            this.oper = oper; this.traeLastApplError = traeLastApplError; this.cntrlObj = cntrlObj;
            this.error = error; this.orCat = orCat; this.ctlNum = ctlNum; this.addCause = addCause;
            this.recibidoMs = recibidoMs;
        }

        /** CommandTermination: trae el Oper. */
        public boolean esTerminacion() { return oper != null; }
        public boolean esPositiva() { return oper != null && !traeLastApplError; }

        /** El LastApplError como texto, con los mismos nombres que la lectura del nodo. */
        public String lastApplErrorTexto() {
            if (!traeLastApplError) return null;
            return "CntrlObj=" + cntrlObj + " Error=" + error + " orCat=" + orCat
                + " ctlNum=" + ctlNum + " AddCause=" + addCause;
        }
    }

    /**
     * Decodifica un InformationReport con lista de variables. Devuelve null si no es ni
     * LastApplError ni un Oper (otro tipo de reporte no solicitado).
     */
    public static Mensaje decodificar(InformationReport ir, long ahoraMs) {
        if (ir == null || ir.getVariableAccessSpecification() == null
                || ir.getVariableAccessSpecification().getListOfVariable() == null) return null;
        List<VariableDefs.SEQUENCE> vars =
            ir.getVariableAccessSpecification().getListOfVariable().getSEQUENCE();
        List<AccessResult> res = ir.getListOfAccessResult() != null
            ? ir.getListOfAccessResult().getAccessResult() : new ArrayList<>();

        String oper = null;
        boolean lae = false;
        String cntrlObj = null;
        int error = -1, orCat = -1, ctlNum = -1, addCause = -1;
        for (int i = 0; i < vars.size(); i++) {
            ObjectName n = vars.get(i).getVariableSpecification() != null
                ? vars.get(i).getVariableSpecification().getName() : null;
            if (n == null) continue;
            Data d = i < res.size() ? res.get(i).getSuccess() : null;
            if (n.getVmdSpecific() != null && "LastApplError".equals(n.getVmdSpecific().toString())) {
                lae = true;
                List<Data> c = (d != null && d.getStructure() != null)
                    ? d.getStructure().getData() : new ArrayList<>();
                // CntrlObj, Error, Origin{orCat, orIdent}, ctlNum, AddCause (8-1, tabla 98)
                if (c.size() > 0 && c.get(0).getVisibleString() != null)
                    cntrlObj = c.get(0).getVisibleString().toString();
                if (c.size() > 1) error = entero(c.get(1));
                if (c.size() > 2 && c.get(2).getStructure() != null
                        && !c.get(2).getStructure().getData().isEmpty())
                    orCat = entero(c.get(2).getStructure().getData().get(0));
                if (c.size() > 3) ctlNum = entero(c.get(3));
                if (c.size() > 4) addCause = entero(c.get(4));
            } else if (n.getDomainSpecific() != null) {
                String item = n.getDomainSpecific().getItemID().toString();
                if (item.endsWith("$Oper")) {
                    oper = referenciaApp(n.getDomainSpecific().getDomainID().toString(), item);
                }
            }
        }
        if (oper == null && !lae) return null;
        return new Mensaje(oper, lae, cntrlObj, error, orCat, ctlNum, addCause, ahoraMs);
    }

    /** "LD" + "LN$CO$DO$Oper" → "LD/LN.DO.Oper" (sin el FC, como las referencias de la app). */
    static String referenciaApp(String ld, String item) {
        String[] p = item.split("\\$");
        StringBuilder sb = new StringBuilder(ld).append('/').append(p[0]);
        for (int i = 2; i < p.length; i++) sb.append('.').append(p[i]);
        return sb.toString();
    }

    private static int entero(Data d) {
        if (d == null) return -1;
        if (d.getInteger() != null) return d.getInteger().value.intValue();
        if (d.getUnsigned() != null) return d.getUnsigned().value.intValue();
        return -1;
    }

    /**
     * Lo que fue llegando durante los controles. Lo alimenta el hilo receptor de la asociación
     * (no bloquea) y lo consultan los hilos que operan.
     */
    public static final class Buzon {
        private final List<Mensaje> mensajes = new ArrayList<>();

        public synchronized void recibir(InformationReport ir) {
            Mensaje m = decodificar(ir, System.currentTimeMillis());
            if (m == null) return;
            if (mensajes.size() > 50) mensajes.remove(0);
            mensajes.add(m);
            notifyAll();
        }

        /** Para pruebas: agregar un mensaje ya decodificado. */
        synchronized void agregar(Mensaje m) { mensajes.add(m); notifyAll(); }

        /**
         * El último LastApplError suelto (sin Oper) llegado desde {@code desdeMs}, o null. Es el
         * motivo del rechazo de un SBOw/Oper/Cancel; llega antes de la respuesta negativa.
         */
        public synchronized Mensaje rechazoDesde(long desdeMs) {
            for (int i = mensajes.size() - 1; i >= 0; i--) {
                Mensaje m = mensajes.get(i);
                if (m.recibidoMs < desdeMs) break;
                if (m.traeLastApplError && !m.esTerminacion()) return m;
            }
            return null;
        }

        /**
         * Espera el CommandTermination del Oper {@code oper} llegado desde {@code desdeMs}.
         * Devuelve null si no llegó en {@code timeoutMs}.
         */
        public synchronized Mensaje esperarTerminacion(String oper, long desdeMs, long timeoutMs)
                throws InterruptedException {
            long fin = System.currentTimeMillis() + timeoutMs;
            while (true) {
                for (Mensaje m : mensajes) {
                    if (m.recibidoMs >= desdeMs && m.esTerminacion() && oper.equals(m.oper)) return m;
                }
                long falta = fin - System.currentTimeMillis();
                if (falta <= 0) return null;
                wait(falta);
            }
        }
    }
}
