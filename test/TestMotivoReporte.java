package com.beanit.iec61850bean;

import com.beanit.asn1bean.ber.types.BerBitString;
import com.beanit.iec61850bean.internal.mms.asn1.Data;

/**
 * Motivo de inclusión de los reportes (ReasonCode): el servidor lo manda con 6 bits, como el
 * simulador de OMICRON IEDScout; con 7 bits IEDScout cortaba la asociación al primer reporte
 * (2026-11-15). El cliente sigue aceptando 6 y 7 bits, porque hay IED que mandan 7.
 *
 * Uso: java -cp "classes;lib\*;test" com.beanit.iec61850bean.TestMotivoReporte
 */
public class TestMotivoReporte {
    static int ok = 0, mal = 0;
    static void chk(String que, boolean c) {
        System.out.println((c ? "OK    " : "FALLA ") + que);
        if (c) ok++; else mal++;
    }

    static BerBitString codificar(BdaReasonForInclusion r) {
        return r.getMmsDataObj().getBitString();
    }

    public static void main(String[] a) throws Exception {
        ObjectReference ref = new ObjectReference("LD/LLN0.rcb.ReasonCode");

        BdaReasonForInclusion gi = new BdaReasonForInclusion(ref);
        gi.setGeneralInterrogation(true);
        BerBitString b = codificar(gi);
        chk("GI: 6 bits, 0x04 (como IEDScout: 84 02 02 04)", b.numBits == 6 && (b.value[0] & 0xFF) == 0x04);

        BdaReasonForInclusion in = new BdaReasonForInclusion(ref);
        in.setIntegrity(true);
        b = codificar(in);
        chk("integridad: 6 bits, 0x08", b.numBits == 6 && (b.value[0] & 0xFF) == 0x08);

        BdaReasonForInclusion dc = new BdaReasonForInclusion(ref);
        dc.setDataChange(true);
        dc.setQualityChange(true);
        b = codificar(dc);
        chk("cambio de dato y de calidad: 6 bits, 0x60", b.numBits == 6 && (b.value[0] & 0xFF) == 0x60);

        BdaReasonForInclusion at = new BdaReasonForInclusion(ref);
        at.setApplicationTrigger(true);
        b = codificar(at);
        chk("application-trigger: 7 bits, 0x02", b.numBits == 7 && (b.value[0] & 0xFF) == 0x02);

        // Ida y vuelta por BER
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        com.beanit.asn1bean.ber.ReverseByteArrayOutputStream os = new com.beanit.asn1bean.ber.ReverseByteArrayOutputStream(64);
        gi.getMmsDataObj().encode(os);
        String hex = "";
        for (byte x : os.getArray()) hex += String.format("%02x", x);
        chk("BER del GI: " + hex, hex.equals("84020204"));

        // El cliente acepta 6 y 7 bits
        for (int bits : new int[]{6, 7}) {
            Data d = new Data();
            d.setBitString(new BerBitString(new byte[]{0x04}, bits));
            BdaReasonForInclusion r = new BdaReasonForInclusion(ref);
            try {
                r.setValueFromMmsDataObj(d);
                chk("el cliente acepta " + bits + " bits: " + r, r.isGeneralInterrogation());
            } catch (ServiceError e) {
                chk("el cliente acepta " + bits + " bits (" + e.getMessage() + ")", false);
            }
        }

        // copy() conserva el valor y el codificado
        BdaReasonForInclusion c = gi.copy();
        chk("copy conserva el valor: " + c, c.isGeneralInterrogation() && codificar(c).numBits == 6);

        System.out.println(ok + " pasaron, " + mal + " fallaron");
        if (mal == 0) System.out.println("TODO OK");
        System.exit(mal == 0 ? 0 : 1);
    }
}
