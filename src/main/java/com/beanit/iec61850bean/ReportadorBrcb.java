package com.beanit.iec61850bean;

import com.beanit.asn1bean.ber.types.BerBitString;
import com.beanit.iec61850bean.internal.mms.asn1.AccessResult;
import com.beanit.iec61850bean.internal.mms.asn1.Data;
import com.beanit.iec61850bean.internal.mms.asn1.Identifier;
import com.beanit.iec61850bean.internal.mms.asn1.InformationReport;
import com.beanit.iec61850bean.internal.mms.asn1.MMSpdu;
import com.beanit.iec61850bean.internal.mms.asn1.ObjectName;
import com.beanit.iec61850bean.internal.mms.asn1.UnconfirmedPDU;
import com.beanit.iec61850bean.internal.mms.asn1.UnconfirmedService;
import com.beanit.iec61850bean.internal.mms.asn1.VariableAccessSpecification;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Reportes con buffer (BRCB, IEC 61850-7-2 §17.2 y 8-1 §17) para el simulador.
 *
 * <p>Clase propia (no sombrea nada). En iec61850bean 1.9.0 el Brcb es solo un contenedor de
 * datos: el servidor no lo habilitaba ni mandaba reportes, y las escrituras caían en la ruta
 * genérica (se aceptaban sin efecto). Esto hace de motor de un BRCB:
 *
 * <ul>
 *   <li>se engancha en las listas de la biblioteca ({@code chgRcbs}/{@code dupdRcbs}), por eso
 *       extiende Urcb: ServerSap.setValues llama a {@link #report} con cada cambio;
 *   <li>cada reporte es una entrada del buffer con su EntryID (8 bytes) y TimeOfEntry; se manda
 *       si el bloque está habilitado y se guarda igual (hasta {@link #CAPACIDAD} entradas; al
 *       desbordar se descarta la más vieja y el próximo reporte lleva BufOvfl=true);
 *   <li>si la asociación se cae, RptEna pasa a false y el buffer sigue llenándose; al volver a
 *       habilitar se mandan las entradas que siguen a la última enviada, o a la EntryID que
 *       escribió el cliente (todo el buffer si escribió ceros);
 *   <li>BufTm junta en una entrada los cambios que llegan dentro del tiempo; GI, integridad
 *       (IntgPd) y PurgeBuf como en la norma.
 * </ul>
 *
 * <p>ServerAssociation llama a {@link #escribir} para toda escritura con FC=BR y a
 * {@link #asociacionCerrada} al limpiar la conexión.
 */
final class ReportadorBrcb extends Urcb {

    static final int CAPACIDAD = 1000;

    private static final ObjectReference REF = new ObjectReference("x.y");

    /** Errores de acceso MMS (DataAccessError). */
    static final int OK = -1, DENEGADO = 3, VALOR_INVALIDO = 11;

    private static final ScheduledExecutorService RELOJ = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "brcb-reloj");
        t.setDaemon(true);
        return t;
    });

    private static final class Entrada {
        final byte[] id;
        final long tiempo;
        final boolean[] incluidos;
        final Data[] valores;
        final BdaReasonForInclusion[] motivos;
        boolean enviada;

        Entrada(byte[] id, long tiempo, int n) {
            this.id = id;
            this.tiempo = tiempo;
            this.incluidos = new boolean[n];
            this.valores = new Data[n];
            this.motivos = new BdaReasonForInclusion[n];
        }
    }

    final Brcb brcb;
    private final ArrayDeque<Entrada> buffer = new ArrayDeque<>();
    private ServerAssociation dueno;
    private boolean habilitado;
    private boolean registrado;
    private boolean desbordado;
    private byte[] ultimoEnviado = new byte[8];   // ceros: desde el principio del buffer
    private long proximoId;
    private int sqNum;
    private final Map<BasicDataAttribute, Integer> miembroDe = new IdentityHashMap<>();
    private final Map<Integer, BdaReasonForInclusion> pendientes = new LinkedHashMap<>();
    private ScheduledFuture<?> descarga;
    private ScheduledFuture<?> integridad;

    private ReportadorBrcb(Brcb brcb) {
        super(brcb.getReference(), new ArrayList<>());
        this.brcb = brcb;
        // Distinto en cada arranque, para que un cliente no confunda un buffer viejo con uno nuevo
        this.proximoId = (System.currentTimeMillis() / 1000L) << 20;
    }

    /** El motor de un BRCB del modelo, creado la primera vez que se lo usa. */
    static ReportadorBrcb de(Brcb b, ServerModel modelo) {
        synchronized (modelo.reportadoresBrcb) {
            return modelo.reportadoresBrcb.computeIfAbsent(b, ReportadorBrcb::new);
        }
    }

    static void asociacionCerrada(ServerAssociation a, ServerModel modelo) {
        List<ReportadorBrcb> todos;
        synchronized (modelo.reportadoresBrcb) {
            todos = new ArrayList<>(modelo.reportadoresBrcb.values());
        }
        for (ReportadorBrcb r : todos) r.cerrada(a);
    }

    // ---------------------------------------------------------------- escrituras del cliente

    /**
     * Escritura de un atributo del BRCB. Devuelve {@link #OK} o el código de DataAccessError.
     * {@code nuevo} es la copia con el valor que mandó el cliente; {@code ds} el DataSet ya
     * resuelto cuando se escribe DatSet (null si no existe).
     */
    synchronized int escribir(ServerAssociation a, String campo, FcModelNode nuevo, DataSet ds) {
        boolean libre = !habilitado && (dueno == null || dueno == a);
        switch (campo) {
            case "RptEna": {
                boolean on = ((BdaBoolean) nuevo).getValue();
                if (on) {
                    if (habilitado) return dueno == a ? OK : DENEGADO;
                    if (dueno != null && dueno != a) return DENEGADO;
                    if (brcb.dataSet == null) return DENEGADO;
                    habilitar(a);
                } else {
                    if (!habilitado) return OK;
                    if (dueno != a) return DENEGADO;
                    deshabilitar();
                }
                return OK;
            }
            case "GI": {
                if (!((BdaBoolean) nuevo).getValue()) return OK;
                if (!habilitado || dueno != a || !brcb.getTrgOps().isGeneralInterrogation()) return DENEGADO;
                descargarPendientes();
                entradaCompleta(false);
                return OK;
            }
            case "PurgeBuf": {
                if (!libre) return DENEGADO;
                if (((BdaBoolean) nuevo).getValue()) purgar();
                return OK;
            }
            case "EntryID": {
                if (!libre) return DENEGADO;
                byte[] id = ((BdaOctetString) nuevo).getValue();
                if (id == null || id.length != 8) return VALOR_INVALIDO;
                if (!esCero(id) && buscar(id) == null) return VALOR_INVALIDO;
                ultimoEnviado = id.clone();
                brcb.getEntryId().setValue(id.clone());
                return OK;
            }
            case "DatSet": {
                if (!libre) return DENEGADO;
                String ref = ((BdaVisibleString) nuevo).getStringValue();
                if (!ref.isEmpty() && ds == null) return DENEGADO;
                desregistrar();
                brcb.dataSet = ref.isEmpty() ? null : ds;
                brcb.getDatSet().setValueFrom((BasicDataAttribute) nuevo);
                purgar();
                return OK;
            }
            case "TrgOps": {
                if (!libre) return DENEGADO;
                desregistrar();
                brcb.getTrgOps().setValueFrom((BasicDataAttribute) nuevo);
                return OK;
            }
            case "OptFlds": {
                if (!libre) return DENEGADO;
                BdaOptFlds o = (BdaOptFlds) nuevo;
                o.setSegmentation(false);
                brcb.getOptFlds().setValueFrom(o);
                return OK;
            }
            case "RptID":
            case "BufTm":
            case "IntgPd": {
                if (!libre) return DENEGADO;
                ((BasicDataAttribute) brcb.getChild(campo)).setValueFrom((BasicDataAttribute) nuevo);
                return OK;
            }
            case "ResvTms": {
                if (brcb.getResvTms() == null) return DENEGADO;
                if (dueno != null && dueno != a) return DENEGADO;
                short v = ((BdaInt16) nuevo).getValue();
                dueno = v == 0 && !habilitado ? null : a;
                brcb.getResvTms().setValue(v);
                return OK;
            }
            default:
                // ConfRev, SqNum, TimeOfEntry, Owner: solo lectura
                return DENEGADO;
        }
    }

    private void habilitar(ServerAssociation a) {
        dueno = a;
        habilitado = true;
        enabled = true;
        brcb.getRptEna().setValue(true);
        registrar();
        // Resincronización: lo que quedó en el buffer después de la última entrada enviada
        List<Entrada> aMandar = new ArrayList<>();
        boolean desdeCero = esCero(ultimoEnviado) || buscar(ultimoEnviado) == null;
        boolean pasado = desdeCero;
        for (Entrada e : buffer) {
            if (pasado) aMandar.add(e);
            else if (Arrays.equals(e.id, ultimoEnviado)) pasado = true;
        }
        for (Entrada e : aMandar) mandar(e);
        long pd = brcb.getIntgPd().getValue();
        if (brcb.getTrgOps().isIntegrity() && pd >= 10) {
            integridad = RELOJ.scheduleWithFixedDelay(this::integridad, pd, pd, TimeUnit.MILLISECONDS);
        }
    }

    /** Deja de mandar; el buffer y el registro siguen (la norma pide seguir guardando). */
    private void deshabilitar() {
        habilitado = false;
        enabled = false;
        brcb.getRptEna().setValue(false);
        if (integridad != null) {
            integridad.cancel(false);
            integridad = null;
        }
    }

    private synchronized void cerrada(ServerAssociation a) {
        if (dueno != a) return;
        if (habilitado) deshabilitar();
        dueno = null;
    }

    private void purgar() {
        buffer.clear();
        pendientes.clear();
        desbordado = false;
        ultimoEnviado = new byte[8];
        brcb.getEntryId().setValue(new byte[8]);
    }

    // ---------------------------------------------------------------- cambios del modelo

    private void registrar() {
        if (registrado || brcb.dataSet == null) return;
        miembroDe.clear();
        List<FcModelNode> miembros = brcb.dataSet.getMembers();
        BdaTriggerConditions t = brcb.getTrgOps();
        for (int i = 0; i < miembros.size(); i++) {
            for (BasicDataAttribute b : miembros.get(i).getBasicDataAttributes()) {
                miembroDe.put(b, i);
                if ((b.dchg && t.isDataChange()) || (b.qchg && t.isQualityChange())) {
                    synchronized (b.chgRcbs) {
                        b.chgRcbs.add(this);
                    }
                } else if (b.dupd && t.isDataUpdate()) {
                    synchronized (b.dupdRcbs) {
                        b.dupdRcbs.add(this);
                    }
                }
            }
        }
        registrado = true;
    }

    private void desregistrar() {
        if (!registrado) return;
        for (BasicDataAttribute b : miembroDe.keySet()) {
            synchronized (b.chgRcbs) {
                b.chgRcbs.remove(this);
            }
            synchronized (b.dupdRcbs) {
                b.dupdRcbs.remove(this);
            }
        }
        miembroDe.clear();
        registrado = false;
    }

    @Override
    public BdaTriggerConditions getTrgOps() {
        return brcb == null ? super.getTrgOps() : brcb.getTrgOps();
    }

    /** Lo llama ServerSap.setValues por cada atributo que cambió. */
    @Override
    synchronized void report(BasicDataAttribute bda, boolean dchg, boolean qchg, boolean dupd) {
        Integer i = miembroDe.get(bda);
        if (i == null) return;
        if (pendientes.containsKey(i)) descargarPendientes();   // segundo cambio del mismo dato
        BdaReasonForInclusion m = pendientes.computeIfAbsent(i, k -> new BdaReasonForInclusion(REF));
        if (dchg) m.setDataChange(true);
        if (qchg) m.setQualityChange(true);
        if (dupd) m.setDataUpdate(true);
        long bufTm = brcb.getBufTm().getValue();
        if (bufTm <= 0) {
            descargarPendientes();
        } else if (descarga == null) {
            descarga = RELOJ.schedule(() -> {
                synchronized (ReportadorBrcb.this) {
                    descargarPendientes();
                }
            }, bufTm, TimeUnit.MILLISECONDS);
        }
    }

    private void descargarPendientes() {
        if (descarga != null) {
            descarga.cancel(false);
            descarga = null;
        }
        if (pendientes.isEmpty() || brcb.dataSet == null) return;
        List<FcModelNode> miembros = brcb.dataSet.getMembers();
        Entrada e = nueva(miembros.size());
        for (Map.Entry<Integer, BdaReasonForInclusion> p : pendientes.entrySet()) {
            int k = p.getKey();
            e.incluidos[k] = true;
            e.valores[k] = miembros.get(k).getMmsDataObj();
            e.motivos[k] = p.getValue();
        }
        pendientes.clear();
        guardarYMandar(e);
    }

    private synchronized void integridad() {
        if (!habilitado) return;
        descargarPendientes();
        entradaCompleta(true);
    }

    /** GI o integridad: todos los miembros. */
    private void entradaCompleta(boolean esIntegridad) {
        if (brcb.dataSet == null) return;
        List<FcModelNode> miembros = brcb.dataSet.getMembers();
        Entrada e = nueva(miembros.size());
        for (int k = 0; k < miembros.size(); k++) {
            e.incluidos[k] = true;
            e.valores[k] = miembros.get(k).getMmsDataObj();
            BdaReasonForInclusion m = new BdaReasonForInclusion(REF);
            if (esIntegridad) m.setIntegrity(true);
            else m.setGeneralInterrogation(true);
            e.motivos[k] = m;
        }
        guardarYMandar(e);
    }

    private Entrada nueva(int n) {
        long v = proximoId++;
        byte[] id = new byte[8];
        for (int k = 7; k >= 0; k--) {
            id[k] = (byte) v;
            v >>>= 8;
        }
        return new Entrada(id, System.currentTimeMillis(), n);
    }

    private void guardarYMandar(Entrada e) {
        buffer.addLast(e);
        while (buffer.size() > CAPACIDAD) {
            Entrada vieja = buffer.removeFirst();
            // Se pierde una entrada que el cliente todavía no recibió
            if (!vieja.enviada) desbordado = true;
        }
        brcb.getTimeOfEntry().setTimestamp(e.tiempo);
        if (habilitado) mandar(e);
    }

    // ---------------------------------------------------------------- el PDU

    private void mandar(Entrada e) {
        ServerAssociation a = dueno;
        if (a == null) return;
        BdaOptFlds opt = brcb.getOptFlds();
        List<AccessResult> l = new ArrayList<>();
        l.add(ok(brcb.getRptId().getMmsDataObj()));
        BdaOptFlds optEnviado = opt.copy();
        optEnviado.setSegmentation(false);
        l.add(ok(optEnviado.getMmsDataObj()));
        if (opt.isSequenceNumber()) {
            BdaInt8U sq = new BdaInt8U(REF, Fc.BR, "", false, false);
            sq.setValue((short) (sqNum & 0xFF));
            l.add(ok(sq.getMmsDataObj()));
        }
        if (opt.isReportTimestamp()) {
            BdaEntryTime t = new BdaEntryTime(REF, Fc.BR, "", false, false);
            t.setTimestamp(e.tiempo);
            l.add(ok(t.getMmsDataObj()));
        }
        if (opt.isDataSetName()) l.add(ok(brcb.getDatSet().getMmsDataObj()));
        if (opt.isBufferOverflow()) {
            BdaBoolean b = new BdaBoolean(REF, Fc.BR, "", false, false);
            b.setValue(desbordado);
            l.add(ok(b.getMmsDataObj()));
        }
        if (opt.isEntryId()) {
            BdaOctetString id = new BdaOctetString(REF, Fc.BR, "", 8, false, false);
            id.setValue(e.id.clone());
            l.add(ok(id.getMmsDataObj()));
        }
        if (opt.isConfigRevision()) l.add(ok(brcb.getConfRev().getMmsDataObj()));

        int n = e.incluidos.length;
        byte[] bits = new byte[(n + 7) / 8];
        for (int k = 0; k < n; k++) if (e.incluidos[k]) bits[k / 8] |= (byte) (0x80 >> (k % 8));
        Data inc = new Data();
        inc.setBitString(new BerBitString(bits, n));
        l.add(ok(inc));

        List<FcModelNode> miembros = brcb.dataSet != null ? brcb.dataSet.getMembers() : null;
        if (opt.isDataReference() && miembros != null && miembros.size() == n) {
            for (int k = 0; k < n; k++) {
                if (!e.incluidos[k]) continue;
                BdaVisibleString r = new BdaVisibleString(REF, Fc.BR, "", 255, false, false);
                r.setValue(ReferenciasEnReporte.nombreMms(miembros.get(k)));
                l.add(ok(r.getMmsDataObj()));
            }
        }
        for (int k = 0; k < n; k++) if (e.incluidos[k]) l.add(ok(e.valores[k]));
        if (opt.isReasonForInclusion()) {
            for (int k = 0; k < n; k++) if (e.incluidos[k]) l.add(ok(e.motivos[k].getMmsDataObj()));
        }

        InformationReport ir = new InformationReport();
        VariableAccessSpecification vas = new VariableAccessSpecification();
        ObjectName on = new ObjectName();
        on.setVmdSpecific(new Identifier("RPT".getBytes(StandardCharsets.UTF_8)));
        vas.setVariableListName(on);
        ir.setVariableAccessSpecification(vas);
        InformationReport.ListOfAccessResult lar = new InformationReport.ListOfAccessResult();
        lar.getAccessResult().addAll(l);
        ir.setListOfAccessResult(lar);
        UnconfirmedService us = new UnconfirmedService();
        us.setInformationReport(ir);
        UnconfirmedPDU up = new UnconfirmedPDU();
        up.setService(us);
        MMSpdu pdu = new MMSpdu();
        pdu.setUnconfirmedPDU(up);

        if (a.sendAnMmsPdu(pdu)) {
            sqNum++;
            brcb.getSqNum().setValue((short) (sqNum & 0xFF));
            e.enviada = true;
            ultimoEnviado = e.id.clone();
            brcb.getEntryId().setValue(e.id.clone());
            desbordado = false;
        }
    }

    private static AccessResult ok(Data d) {
        AccessResult r = new AccessResult();
        r.setSuccess(d);
        return r;
    }

    private Entrada buscar(byte[] id) {
        for (Iterator<Entrada> it = buffer.iterator(); it.hasNext(); ) {
            Entrada e = it.next();
            if (Arrays.equals(e.id, id)) return e;
        }
        return null;
    }

    private static boolean esCero(byte[] id) {
        for (byte b : id) if (b != 0) return false;
        return true;
    }

    /** Para pruebas. */
    synchronized int entradasEnBuffer() {
        return buffer.size();
    }
}
