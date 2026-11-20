package com.iednavigator;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Suscripción GOOSE del interruptor virtual (SuscripcionGoose) y lo que necesita del parser:
 * estructuras en el DataSet y timeAllowedToLive.
 *
 * Uso: java -cp "classes;lib\*;test" com.iednavigator.TestSuscripcionGoose
 */
public class TestSuscripcionGoose {

    private static int ok, falla;

    private static void chk(String que, boolean cond) {
        if (cond) { ok++; System.out.println("OK     " + que); }
        else { falla++; System.out.println("FALLA  " + que); }
    }

    private static void chkEq(String que, Object esperado, Object real) {
        chk(que + " (esperado " + esperado + ", real " + real + ")",
                esperado == null ? real == null : esperado.equals(real));
    }

    public static void main(String[] args) throws Exception {
        validacion();
        parser();
        suscripcion();
        System.out.println();
        System.out.println(ok + " pasaron, " + falla + " fallaron");
        if (falla == 0) System.out.println("TODO OK");
        System.exit(falla == 0 ? 0 : 1);
    }

    // ── Validación del formulario ───────────────────────────────────────────

    private static void validacion() {
        SuscripcionGoose.Config c = SuscripcionGoose.Config.validar("0x0001", " IED_PROT1LD0/LLN0$GO$Trip ", "2", "00:30:a7:12:34:56");
        chkEq("APPID con 0x", 1, c.appId);
        chkEq("goCbRef sin espacios", "IED_PROT1LD0/LLN0$GO$Trip", c.gocbRef);
        chkEq("índice", 2, c.indice);
        chkEq("MAC normalizada", "0030a7123456", c.macOrigen);
        chkEq("MAC como se escribe", "00-30-A7-12-34-56", c.textoMac());
        chkEq("APPID sin 0x es hexadecimal", 0x3001, SuscripcionGoose.Config.validar("3001", "A/B", "0", "").appId);
        chkEq("MAC vacía = cualquiera", null, SuscripcionGoose.Config.validar("1", "A/B", "", "").macOrigen);
        chkEq("índice vacío = 0", 0, SuscripcionGoose.Config.validar("1", "A/B", "", "").indice);
        chk("goCbRef sin / se rechaza", lanza(() -> SuscripcionGoose.Config.validar("1", "Trip", "0", "")));
        chk("APPID fuera de rango se rechaza", lanza(() -> SuscripcionGoose.Config.validar("10000", "A/B", "0", "")));
        chk("APPID no hexadecimal se rechaza", lanza(() -> SuscripcionGoose.Config.validar("xyz", "A/B", "0", "")));
        chk("índice negativo se rechaza", lanza(() -> SuscripcionGoose.Config.validar("1", "A/B", "-1", "")));
        chk("MAC mal escrita se rechaza", lanza(() -> SuscripcionGoose.Config.validar("1", "A/B", "0", "00-30-A7")));
        SuscripcionGoose.Config r = SuscripcionGoose.Config.leer(c.serializar());
        chk("serializar y leer devuelve lo mismo", r != null && r.serializar().equals(c.serializar()));
        chkEq("leer texto roto da null", null, SuscripcionGoose.Config.leer("basura"));
    }

    private static boolean lanza(Runnable r) {
        try { r.run(); return false; } catch (IllegalArgumentException e) { return true; }
    }

    // ── Parser: estructura y TAL ────────────────────────────────────────────

    private static void parser() throws Exception {
        // allData: [0] estructura {general=TRUE, q bit-string}, [1] booleano FALSE, [2] entero
        byte[] estructura = tlv(0xA2, concat(tlv(0x83, new byte[]{1}), tlv(0x84, new byte[]{3, 0, 0})));
        byte[] allData = concat(estructura, tlv(0x83, new byte[]{0}), tlv(0x85, new byte[]{5}));
        byte[] pdu = concat(
                tlv(0x80, "IED1LD0/LLN0$GO$Trip".getBytes("US-ASCII")),
                tlv(0x81, new byte[]{0x07, (byte) 0xD0}),          // TAL 2000 ms
                tlv(0x85, new byte[]{7}),                          // stNum
                tlv(0x86, new byte[]{0}),
                tlv(0xAB, allData));
        byte[] goose = tlv(0x61, pdu);
        byte[] cab = {0x00, 0x01, 0, (byte) (8 + goose.length), 0, 0, 0, 0};
        Method m = GooseSubscriber.class.getDeclaredMethod("parseGoosePdu", byte[].class);
        m.setAccessible(true);
        GooseSubscriber.GooseMessage msg = (GooseSubscriber.GooseMessage) m.invoke(new GooseSubscriber(), (Object) concat(cab, goose));
        chkEq("APPID", 1, msg.appId);
        chkEq("timeAllowedToLive leído", 2000, msg.timeAllowedToLive);
        chkEq("stNum", 7, msg.stNum);
        chkEq("3 datos en el DataSet", 3, msg.dataEntries.size());
        chkEq("dato 0 es estructura", "Structure", msg.dataEntries.get(0).type);
        chk("la estructura trae sus 2 miembros", msg.dataEntries.get(0).value instanceof List
                && ((List<?>) msg.dataEntries.get(0).value).size() == 2);
        chkEq("primer booleano de la estructura", Boolean.TRUE, SuscripcionGoose.primerBooleano(msg.dataEntries.get(0).value));
        chkEq("el dato siguiente no se corre", Boolean.FALSE, msg.dataEntries.get(1).value);
        chkEq("entero después", 5, msg.dataEntries.get(2).value);
        chk("la tabla muestra la estructura entre llaves",
                GoosePanel.textoDato("Structure", msg.dataEntries.get(0).value).startsWith("{true"));
    }

    private static byte[] tlv(int tag, byte[] v) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        if (v.length < 128) o.write(v.length);
        else { o.write(0x81); o.write(v.length); }
        o.write(v, 0, v.length);
        return o.toByteArray();
    }

    private static byte[] concat(byte[]... partes) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : partes) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    // ── Suscripción: coincidencia, flanco, estados ─────────────────────────

    private static void suscripcion() {
        long[] ahora = {1_000_000};
        List<String> ordenes = new ArrayList<>();
        SuscripcionGoose s = new SuscripcionGoose((rol, st) -> ordenes.add(rol + "@" + st), () -> ahora[0]);
        SuscripcionGoose.Rol D = SuscripcionGoose.Rol.DISPARO, C = SuscripcionGoose.Rol.CIERRE;

        chkEq("sin configurar", SuscripcionGoose.Estado.SIN_CONFIGURAR, s.estado(D));
        chkEq("resumen sin nada", SuscripcionGoose.Estado.SIN_CONFIGURAR, s.resumen());
        s.configurar(D, SuscripcionGoose.Config.validar("0x0001", "IED1LD0/LLN0$GO$Trip", "0", ""));
        s.configurar(C, SuscripcionGoose.Config.validar("0x0002", "IED1LD0/LLN0$GO$Cls", "1", "00-30-A7-12-34-56"));
        chkEq("configurado, sin datos", SuscripcionGoose.Estado.SIN_DATOS, s.estado(D));

        // Primera trama con TRUE: no maniobra (no se vio FALSE antes)
        s.recibir(msg(1, "IED1LD0/LLN0$GO$Trip", "aa:bb:cc:dd:ee:ff", 1, 1000, true));
        chk("TRUE al configurar no maniobra", ordenes.isEmpty());
        chkEq("recibiendo", SuscripcionGoose.Estado.RECIBIENDO, s.estado(D));
        chk("texto con stNum y valor", s.textoEstado(D).contains("1") && s.textoEstado(D).contains("TRUE"));

        s.recibir(msg(1, "IED1LD0/LLN0$GO$Trip", "aa:bb:cc:dd:ee:ff", 2, 1000, false));
        s.recibir(msg(1, "IED1LD0/LLN0$GO$Trip", "aa:bb:cc:dd:ee:ff", 3, 1000, true));
        chkEq("FALSE→TRUE da la orden de disparo", Arrays.asList("DISPARO@3"), ordenes);
        s.recibir(msg(1, "IED1LD0/LLN0$GO$Trip", "aa:bb:cc:dd:ee:ff", 3, 1000, true));
        chkEq("la retransmisión no repite la orden", 1, ordenes.size());

        // Otro APPID u otro goCbRef no cuentan
        s.recibir(msg(9, "IED1LD0/LLN0$GO$Trip", "aa:bb:cc:dd:ee:ff", 4, 1000, false));
        s.recibir(msg(1, "OTRO/LLN0$GO$Trip", "aa:bb:cc:dd:ee:ff", 4, 1000, false));
        s.recibir(msg(1, "IED1LD0/LLN0$GO$Trip", "aa:bb:cc:dd:ee:ff", 5, 1000, true));
        chkEq("tramas ajenas no rompen el flanco", 1, ordenes.size());

        // TAL vencido
        ahora[0] += 1500;
        chkEq("TAL de 1000 ms vencido: perdido", SuscripcionGoose.Estado.PERDIDO, s.estado(D));
        chk("texto de perdido nombra el TAL", s.textoEstado(D).contains("1000"));

        // Cierre: MAC filtrada, estructura, índice
        s.recibir(msgEstructura(2, "IED1LD0/LLN0$GO$Cls", "11:22:33:44:55:66", 1, false));
        chkEq("MAC distinta: se ignora", SuscripcionGoose.Estado.SIN_DATOS, s.estado(C));
        s.recibir(msgEstructura(2, "IED1LD0/LLN0$GO$Cls", "00:30:a7:12:34:56", 1, false));
        s.recibir(msgEstructura(2, "IED1LD0/LLN0$GO$Cls", "00:30:A7:12:34:56", 2, true));
        chk("estructura: su primer booleano da la orden de cierre", ordenes.contains("CIERRE@2"));
        chkEq("sin TAL en la trama se supone 3000 ms", SuscripcionGoose.Estado.RECIBIENDO, s.estado(C));
        ahora[0] += 3100;
        chkEq("y vence a los 3000 ms", SuscripcionGoose.Estado.PERDIDO, s.estado(C));

        // Errores
        SuscripcionGoose e = new SuscripcionGoose((rol, st) -> { }, () -> ahora[0]);
        e.configurar(D, SuscripcionGoose.Config.validar("1", "A/LLN0$GO$x", "5", ""));
        e.recibir(msg(1, "A/LLN0$GO$x", "", 1, 1000, true));
        chkEq("índice fuera del dataset: error", SuscripcionGoose.Estado.ERROR, e.estado(D));
        chk("el error dice cuántos elementos hay", e.textoEstado(D).contains("5") && e.textoEstado(D).contains("2"));
        e.configurar(D, SuscripcionGoose.Config.validar("1", "A/LLN0$GO$x", "1", ""));
        e.recibir(msg(1, "A/LLN0$GO$x", "", 1, 1000, true));
        chkEq("dato no booleano: error", SuscripcionGoose.Estado.ERROR, e.estado(D));
        chkEq("resumen con un error", SuscripcionGoose.Estado.ERROR, e.resumen());
        e.configurar(D, null);
        chkEq("quitar la configuración", SuscripcionGoose.Estado.SIN_CONFIGURAR, e.estado(D));
    }

    /** Trama con [0] booleano y [1] entero. */
    private static GooseSubscriber.GooseMessage msg(int appId, String ref, String mac, int st, int tal, boolean v) {
        GooseSubscriber.GooseMessage m = new GooseSubscriber.GooseMessage();
        m.appId = appId;
        m.gocbRef = ref;
        m.srcMac = mac;
        m.stNum = st;
        m.timeAllowedToLive = tal;
        m.dataEntries.add(new GooseSubscriber.DataEntry(0, "Boolean", v));
        m.dataEntries.add(new GooseSubscriber.DataEntry(1, "Integer", 3));
        return m;
    }

    /** Trama sin TAL con [0] entero y [1] estructura {booleano, calidad}. */
    private static GooseSubscriber.GooseMessage msgEstructura(int appId, String ref, String mac, int st, boolean v) {
        GooseSubscriber.GooseMessage m = new GooseSubscriber.GooseMessage();
        m.appId = appId;
        m.gocbRef = ref;
        m.srcMac = mac;
        m.stNum = st;
        m.dataEntries.add(new GooseSubscriber.DataEntry(0, "Integer", 1));
        m.dataEntries.add(new GooseSubscriber.DataEntry(1, "Structure", Arrays.asList(v, 0)));
        return m;
    }
}
