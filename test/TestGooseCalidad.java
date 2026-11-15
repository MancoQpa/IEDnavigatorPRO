package com.iednavigator;

import com.beanit.iec61850bean.*;

/**
 * Calidad y posicion en GOOSE (bit-strings), ida y vuelta.
 *
 * Antes el publicador mandaba la calidad en un byte (8 bits, se perdian test y
 * operatorBlocked), el suscriptor mostraba el byte de relleno en vez del valor, y la calidad
 * del modelo salia siempre good. Se detecto con el sniffer de una herramienta comercial.
 *
 * Uso: java -cp "classes;lib\*;test" com.iednavigator.TestGooseCalidad
 */
public class TestGooseCalidad {
    static int ok = 0, mal = 0;
    static void chk(String que, boolean c) {
        System.out.println((c ? "OK    " : "FALLA ") + que);
        if (c) ok++; else mal++;
    }

    public static void main(String[] a) throws Exception {
        // Codificacion: 13 bits = relleno 3 + dos bytes; el bit 0 de la norma es el mas alto
        byte[] inv = GoosePublisher.codificarCalidad(0x0002);
        chk("invalid: relleno 3, 2 bytes, 0x40 0x00", inv.length == 3 && inv[0] == 3 && (inv[1] & 0xFF) == 0x40 && inv[2] == 0);
        byte[] tst = GoosePublisher.codificarCalidad(0x0800);
        chk("test (bit 11): segundo byte 0x10", (tst[1] & 0xFF) == 0 && (tst[2] & 0xFF) == 0x10);
        byte[] blq = GoosePublisher.codificarCalidad(0x1000);
        chk("operatorBlocked (bit 12): segundo byte 0x08", (blq[2] & 0xFF) == 0x08);

        // Ida y vuelta
        int[] valores = {0x0000, 0x0002, 0x0003, 0x0800, 0x0802, 0x1000, 0x0400 | 0x0003};
        String[] textos = {"good", "invalid", "questionable", "good · test", "invalid · test", "good · operatorBlocked",
                "questionable · substituted"};
        for (int i = 0; i < valores.length; i++) {
            byte[] c = GoosePublisher.codificarCalidad(valores[i]);
            Object[] d = GooseSubscriber.decodificarBitString(c, 0, c.length);
            chk(String.format("0x%04X -> %s (%s)", valores[i], d[1], d[0]), "Quality".equals(d[0]) && textos[i].equals(d[1]));
        }

        // Posicion de doble bit: 2 bits (relleno 6)
        String[] pos = {"0", "1 (OFF)", "2 (ON)", "3"};
        for (int v = 0; v < 4; v++) {
            byte[] c = {0x06, (byte) (v << 6)};
            Object[] d = GooseSubscriber.decodificarBitString(c, 0, 2);
            chk("Dbpos " + pos[v] + " -> " + d[0] + " " + d[1], "Dbpos".equals(d[0]) && Integer.valueOf(v).equals(d[1]));
        }
        chk("Dbpos se muestra con su nombre: " + GoosePanel.textoDato("Dbpos", 2), GoosePanel.textoDato("Dbpos", 2).startsWith("ON"));

        // Otro bit-string: valor real, no el relleno
        Object[] otro = GooseSubscriber.decodificarBitString(new byte[]{0x00, (byte) 0xA5}, 0, 2);
        chk("bit-string de 8 bits: " + otro[1], "BitString".equals(otro[0]) && ((String) otro[1]).startsWith("0xA5"));

        // Desde el modelo: la calidad real del q
        BdaQuality q = new BdaQuality(new ObjectReference("X/Y.Pos.q"), Fc.ST, null, false);
        q.setValidity(BdaQuality.Validity.INVALID);
        byte[] v = q.getValue().clone();
        v[1] |= 0x10;   // test (bit 11)
        q.setValue(v);
        Object pub = SclReferenceUtils.convertBdaToPublisherValue(q, GoosePublisher.DataValue.Type.BITSTRING);
        chk("q del modelo invalid + test -> " + pub, Integer.valueOf(0x0802).equals(pub));

        System.out.println(ok + " pasaron, " + mal + " fallaron");
        if (mal == 0) System.out.println("TODO OK");
        System.exit(mal == 0 ? 0 : 1);
    }
}
