package com.iednavigator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Qué placa de red elige la pestaña GOOSE sola, a partir de los textos del combo
 * {@code Interface:} ("Descripción - ip1, ip2"). Sin Swing ni pcap, para poder probarla.
 *
 * Antes (hasta 2026-10-23) se elegía la placa que contuviera la IP de la cabecera, comparando
 * como texto. Dos problemas:
 *  - En modo servidor esa IP sale de getLocalIpAddress(), que toma la primera 192.168/10/172
 *    que encuentra; las 100.100.x no cuentan. En una notebook con la Ethernet en
 *    100.100.100.110 (la red del banco) y la Wi-Fi en 192.168.x, GOOSE quedaba en la Wi-Fi y
 *    sus tramas nunca llegaban al cable: el otro extremo no recibía nada (2026-10-23).
 *  - Comparar como texto hacía que "192.168.1.1" coincidiera también con "192.168.1.11".
 *
 * Ahora, en modo servidor, se prefiere una placa cableada con IPv4; la Wi-Fi solo si no hay
 * ninguna. En modo cliente se sigue usando la placa por la que se llega al IED, que es la
 * correcta por construcción. La elección se puede cambiar a mano en el combo.
 */
final class PlacaGoose {

    private PlacaGoose() {}

    /** Wi-Fi o similar, por la descripción que da Npcap. */
    static boolean esInalambrica(String item) {
        String d = descripcion(item).toLowerCase(Locale.ROOT);
        return d.contains("wireless") || d.contains("wi-fi") || d.contains("wifi")
            || d.contains("802.11") || d.contains("wlan");
    }

    /** Adaptadores que no son una placa física de red: no sirven para publicar GOOSE. */
    static boolean esVirtual(String item) {
        String d = descripcion(item).toLowerCase(Locale.ROOT);
        String[] marcas = {"miniport", "virtual", "loopback", "bluetooth", "vmware", "virtualbox",
                           "hyper-v", "vethernet", "tap-", "tap adapter", "vpn", "tunnel", "teredo"};
        for (String m : marcas) if (d.contains(m)) return true;
        return false;
    }

    /** Placa física cableada con al menos una IPv4 útil (no 169.254). */
    static boolean esCableadaConRed(String item) {
        if (item == null || esInalambrica(item) || esVirtual(item)) return false;
        for (String ip : ipsDe(item)) if (!ip.startsWith("169.254.")) return true;
        return false;
    }

    /** Las IPv4 del texto del combo: lo que va después del último " - ", separado por comas. */
    static List<String> ipsDe(String item) {
        List<String> ips = new ArrayList<>();
        if (item == null) return ips;
        int i = item.lastIndexOf(" - ");
        if (i < 0) return ips;
        for (String p : item.substring(i + 3).split(",")) {
            String ip = p.trim();
            if (ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) ips.add(ip);
        }
        return ips;
    }

    static boolean tieneIp(String item, String ip) {
        return ip != null && ipsDe(item).contains(ip.trim());
    }

    /**
     * Índice elegido en {@code items}, o -1 si no hay a cuál.
     *
     * @param ip               la IP de referencia (cabecera del servidor, o la local hacia el IED)
     * @param preferirCableada modo servidor: una placa cableada gana a la Wi-Fi
     */
    static int elegir(List<String> items, String ip, boolean preferirCableada) {
        int porIp = -1;
        for (int i = 0; i < items.size(); i++) {
            if (tieneIp(items.get(i), ip)) { porIp = i; break; }
        }
        if (preferirCableada) {
            if (porIp >= 0 && esCableadaConRed(items.get(porIp))) return porIp;
            for (int i = 0; i < items.size(); i++) {
                if (esCableadaConRed(items.get(i))) return i;
            }
        }
        if (porIp >= 0) return porIp;

        // Misma subred /24, comparando direcciones enteras y no texto
        String[] p = ip == null ? new String[0] : ip.trim().split("\\.");
        if (p.length == 4) {
            String red = p[0] + "." + p[1] + "." + p[2] + ".";
            for (int i = 0; i < items.size(); i++) {
                for (String otra : ipsDe(items.get(i))) {
                    if (otra.startsWith(red)) return i;
                }
            }
        }
        return -1;
    }

    private static String descripcion(String item) {
        if (item == null) return "";
        int i = item.lastIndexOf(" - ");
        return i < 0 ? item : item.substring(0, i);
    }
}
