import com.iednavigator.GoosePublisher;
import org.pcap4j.core.*;
import org.pcap4j.util.LinkLayerAddress;
import org.pcap4j.util.MacAddress;

import java.lang.reflect.Field;
import java.util.*;

/**
 * El publicador GOOSE se recupera si la interfaz cambió de MAC.
 *
 * El caso de campo (2026-10-19): tras una suspensión la Wi-Fi reconectó con una MAC
 * aleatoria nueva. El publicador armaba las tramas con la MAC de antes y el driver las
 * rechazaba todas ("PacketSendPacket failed", una por segundo durante una hora). Detener y
 * volver a publicar no alcanzaba: la interfaz venía de una enumeración vieja.
 *
 * Necesita hardware real: una Wi-Fi que rechace tramas con otra MAC de origen (en cable,
 * la mayoría de las placas las mandan igual y la prueba no ejercitaría nada). Se pasa la IP
 * de la interfaz y una MAC que NO sea la actual, por ejemplo la permanente cuando Windows
 * usa una aleatoria (Get-NetAdapter: PermanentAddress vs MacAddress).
 *
 * Manda unas 15 tramas GOOSE de prueba (bandera de prueba puesta, gocbRef TEST/...).
 *
 * Uso:  java -cp "classes;lib\*;test" TestGooseReapertura <ip de la Wi-Fi> <MAC permanente>
 */
public class TestGooseReapertura {

    private static int fallas = 0;

    static void chk(String titulo, boolean ok, String detalle) {
        if (!ok) fallas++;
        System.out.printf("%-58s %-40s %s%n", titulo, detalle, ok ? "OK" : "<<< FALLA");
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("uso: TestGooseReapertura <ip de la interfaz> <mac que no sea la actual>");
            System.exit(2);
        }
        PcapNetworkInterface nif = porIp(args[0]);
        if (nif == null) { System.out.println("no hay interfaz con " + args[0]); System.exit(2); }
        MacAddress real = MacAddress.getByAddress(nif.getLinkLayerAddresses().get(0).getAddress());
        MacAddress vieja = MacAddress.getByName(args[1]);
        System.out.println("interfaz: " + nif.getDescription() + "   MAC actual " + real + "   MAC vieja " + vieja + "\n");
        if (real.equals(vieja)) { System.out.println("la MAC vieja tiene que ser distinta de la actual"); System.exit(2); }

        // 1) La interfaz cambió de MAC con la publicación andando: se recupera sola.
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        GoosePublisher p = nuevo(log);
        chk("init sobre la interfaz", p.init(nif), "");
        campo(p, "srcMac").set(p, vieja);   // como quedaba tras reconectar la Wi-Fi
        p.setHeartbeatInterval(100);
        p.startPublishing();
        Thread.sleep(1500);
        p.stopPublishing();
        p.close();

        int errores = contar(log, "log.pub.error");
        chk("hubo envíos fallidos con la MAC vieja", errores >= 3, errores + " errores");
        chk("se reabrió una sola vez", contar(log, "log.pub.reopened") == 1, contar(log, "log.pub.reopened") + " reaperturas");
        chk("después volvió a enviar", contar(log, "log.pub.recovered") == 1, contar(log, "log.pub.recovered") + " avisos de recuperación");
        chk("dejó de fallar tras reabrir (3 errores, no más)", errores == 3, errores + " errores");
        chk("quedó con la MAC actual", real.equals(campo(p, "srcMac").get(p)), String.valueOf(campo(p, "srcMac").get(p)));

        // 2) init() con una interfaz de una enumeración vieja: relee la MAC.
        List<String> log2 = Collections.synchronizedList(new ArrayList<>());
        GoosePublisher q = nuevo(log2);
        PcapNetworkInterface rancia = conMac(porIp(args[0]), vieja);
        chk("la interfaz de prueba trae la MAC vieja", vieja.equals(MacAddress.getByAddress(rancia.getLinkLayerAddresses().get(0).getAddress())), "");
        chk("init sobre la interfaz vieja", q.init(rancia), "");
        chk("init tomó la MAC actual", real.equals(campo(q, "srcMac").get(q)), String.valueOf(campo(q, "srcMac").get(q)));
        q.setHeartbeatInterval(100);
        q.startPublishing();
        Thread.sleep(600);
        q.stopPublishing();
        q.close();
        chk("y ningún envío falló", contar(log2, "log.pub.error") == 0, contar(log2, "log.pub.error") + " errores");

        System.out.println(fallas == 0 ? "\nTODO OK" : "\n" + fallas + " FALLAS");
        System.exit(fallas == 0 ? 0 : 1);
    }

    static GoosePublisher nuevo(List<String> log) {
        GoosePublisher p = new GoosePublisher();
        p.setLogListener(log::add);
        p.setGocbRef("TEST/LLN0$GO$reapertura");
        p.setGoId("TEST_REAPERTURA");
        p.setDatSet("TEST/LLN0$DataSet1");
        p.setTestMode(true);
        return p;
    }

    /** Cuántas líneas del log empiezan como el texto de esa clave (hasta su primer parámetro). */
    static int contar(List<String> log, String clave) {
        String plantilla = com.iednavigator.I18n.t(clave, "\u0000", "\u0000", "\u0000");
        String inicio = plantilla.substring(0, plantilla.indexOf('\u0000'));
        int n = 0;
        synchronized (log) { for (String l : log) if (l.startsWith(inicio)) n++; }
        return n;
    }

    static PcapNetworkInterface porIp(String ip) throws PcapNativeException {
        for (PcapNetworkInterface n : Pcaps.findAllDevs()) {
            for (PcapAddress a : n.getAddresses()) {
                if (a.getAddress() != null && a.getAddress().getHostAddress().equals(ip)) return n;
            }
        }
        return null;
    }

    /** La misma interfaz, pero con otra MAC guardada: lo que deja una enumeración vieja. */
    static PcapNetworkInterface conMac(PcapNetworkInterface nif, MacAddress mac) throws Exception {
        Field f = PcapNetworkInterface.class.getDeclaredField("linkLayerAddresses");
        f.setAccessible(true);
        List<LinkLayerAddress> l = new ArrayList<>();
        l.add(mac);
        f.set(nif, l);
        return nif;
    }

    static Field campo(Object o, String nombre) throws Exception {
        Field f = o.getClass().getDeclaredField(nombre);
        f.setAccessible(true);
        return f;
    }
}
