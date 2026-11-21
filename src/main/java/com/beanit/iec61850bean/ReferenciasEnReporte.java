package com.beanit.iec61850bean;

import com.beanit.asn1bean.ber.types.string.BerVisibleString;
import com.beanit.iec61850bean.internal.mms.asn1.AccessResult;
import com.beanit.iec61850bean.internal.mms.asn1.Data;
import com.beanit.iec61850bean.internal.mms.asn1.InformationReport;
import com.beanit.iec61850bean.internal.mms.asn1.MMSpdu;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Agrega a un reporte las referencias de los datos (OptFlds data-reference, IEC 61850-8-1).
 *
 * <p>Clase propia (no sombrea nada). El Urcb de iec61850bean 1.9.0 copia el OptFlds del bloque al
 * reporte pero nunca escribe las referencias: si el cliente (o el SCL) pedía data-reference, el
 * reporte lo anunciaba sin traerlas y el cliente leía los valores corridos. ServerAssociation
 * llama a esto antes de mandar cada PDU; si es un reporte (lista "RPT") con data-reference y sin
 * las referencias, las inserta después del bit-string de inclusión, una por miembro incluido,
 * con el nombre MMS del miembro ("LD/LN$FC$DO$DA"), como el simulador de la herramienta comercial.
 */
final class ReferenciasEnReporte {

    private ReferenciasEnReporte() { }

    static void agregar(MMSpdu pdu, ServerModel model) {
        try {
            if (pdu == null || model == null || pdu.getUnconfirmedPDU() == null) return;
            InformationReport ir = pdu.getUnconfirmedPDU().getService().getInformationReport();
            if (ir == null || ir.getVariableAccessSpecification().getVariableListName() == null) return;
            Object vmd = ir.getVariableAccessSpecification().getVariableListName().getVmdSpecific();
            if (vmd == null || !"RPT".equals(vmd.toString())) return;
            List<AccessResult> l = ir.getListOfAccessResult().getAccessResult();
            if (l.size() < 3) return;

            BdaOptFlds opt = new BdaOptFlds(new ObjectReference("x.OptFlds"), null);
            opt.setValueFromMmsDataObj(l.get(1).getSuccess());
            if (!opt.isDataReference()) return;

            String rptId = new String(l.get(0).getSuccess().getVisibleString().value, StandardCharsets.UTF_8);
            int i = 2;
            if (opt.isSequenceNumber()) i++;
            if (opt.isReportTimestamp()) i++;
            String datSet = null;
            if (opt.isDataSetName()) {
                datSet = new String(l.get(i).getSuccess().getVisibleString().value, StandardCharsets.UTF_8);
                i++;
            }
            if (opt.isBufferOverflow()) i++;
            if (opt.isEntryId()) i++;
            if (opt.isConfigRevision()) i++;
            if (opt.isSegmentation()) i += 2;
            int inclusion = i;
            boolean[] incluidos = l.get(inclusion).getSuccess().getBitString().getValueAsBooleans();
            int n = 0;
            for (boolean b : incluidos) if (b) n++;
            int esperado = inclusion + 1 + n + (opt.isReasonForInclusion() ? n : 0);
            if (l.size() != esperado) return;   // ya trae referencias, o no es lo que se espera

            DataSet ds = dataSetDe(model, rptId, datSet);
            if (ds == null) return;
            List<FcModelNode> miembros = ds.getMembers();
            List<AccessResult> refs = new ArrayList<>();
            for (int k = 0; k < incluidos.length && k < miembros.size(); k++) {
                if (!incluidos[k]) continue;
                Data d = new Data();
                d.setVisibleString(new BerVisibleString(nombreMms(miembros.get(k)).getBytes(StandardCharsets.UTF_8)));
                AccessResult ar = new AccessResult();
                ar.setSuccess(d);
                refs.add(ar);
            }
            if (refs.size() != n) return;
            l.addAll(inclusion + 1, refs);
        } catch (Exception e) {
            // Un reporte sin referencias es mejor que ningún reporte
        }
    }

    /** "LD/LN.DO.DA" con FC ST -> "LD/LN$ST$DO$DA". */
    static String nombreMms(FcModelNode n) {
        String ref = n.getReference().toString();
        int barra = ref.indexOf('/');
        int punto = ref.indexOf('.', barra + 1);
        if (barra < 0 || punto < 0) return ref;
        return ref.substring(0, punto) + "$" + n.getFc() + "$" + ref.substring(punto + 1).replace('.', '$');
    }

    private static DataSet dataSetDe(ServerModel model, String rptId, String datSet) {
        if (datSet != null) {
            DataSet d = model.getDataSet(datSet.replace('$', '.'));
            if (d != null) return d;
        }
        for (Urcb u : model.getUrcbs()) {
            if (u.enabled && u.getRptId() != null && rptId.equals(u.getRptId().getStringValue())) {
                String ref = u.getDatSet().getStringValue();
                DataSet d = model.getDataSet(ref.replace('$', '.'));
                if (d != null) return d;
            }
        }
        return null;
    }
}
