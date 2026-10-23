package com.iednavigator;

import java.util.Arrays;
import java.util.List;

/**
 * Qué placa elige la pestaña GOOSE sola (PlacaGoose.elegir).
 *
 * El caso de campo (2026-10-23): dos notebooks con el mismo SCD, extremos opuestos, en la
 * Ethernet del banco. Una tenía la Ethernet solo en 100.100.100.110 y la Wi-Fi en 192.168.x;
 * la cabecera del servidor tomaba la IP de la Wi-Fi (las 100.100.x no cuentan como privadas)
 * y GOOSE se publicaba por la Wi-Fi: el otro extremo no recibía nada.
 *
 * Los textos son los del combo Interface: tal como los arma loadNetworkInterfaces().
 *
 * Uso:  javac -cp "classes;lib\*" -d test test\TestPlacaGoose.java
 *       java  -cp "classes;lib\*;test" com.iednavigator.TestPlacaGoose
 */
public class TestPlacaGoose {

    private static int fallas = 0;

    static void chk(String titulo, int esperado, int obtenido, List<String> items) {
        boolean ok = esperado == obtenido;
        if (!ok) fallas++;
        String elegido = obtenido >= 0 ? items.get(obtenido) : "ninguna";
        if (elegido.length() > 44) elegido = elegido.substring(0, 44) + "…";
        System.out.printf("%-58s %-46s %s%n", titulo, elegido, ok ? "OK" : "<<< FALLA");
    }

    static void chk(String titulo, boolean ok) {
        if (!ok) fallas++;
        System.out.printf("%-58s %-46s %s%n", titulo, "", ok ? "OK" : "<<< FALLA");
    }

    public static void main(String[] args) {
        String loop = "★ Loopback Interno (pruebas misma maquina)";
        String udp = "★ GOOSE sobre UDP (WiFi/Hotspot)";
        String npcapLoop = "Adapter for loopback traffic capture";
        String wifiDirect = "Microsoft Wi-Fi Direct Virtual Adapter #2 - 169.254.105.48";
        String miniport = "WAN Miniport (IP)";

        // La otra notebook: Ethernet solo en la red del banco, Wi-Fi en 192.168.x
        String ethBanco = "Intel(R) Ethernet Connection I219-V - 100.100.100.110";
        String wifi = "Intel(R) Wi-Fi 6 AX201 160MHz - 192.168.100.25";
        List<String> otra = Arrays.asList(loop, udp, npcapLoop, wifiDirect, ethBanco, miniport, wifi);
        chk("servidor, cabecera = IP de la Wi-Fi: elige la Ethernet", 4,
            PlacaGoose.elegir(otra, "192.168.100.25", true), otra);
        chk("cliente, IED alcanzado por la Wi-Fi: respeta la Wi-Fi", 6,
            PlacaGoose.elegir(otra, "192.168.100.25", false), otra);
        chk("cliente, IED alcanzado por la Ethernet: la Ethernet", 4,
            PlacaGoose.elegir(otra, "100.100.100.110", false), otra);

        // Esta PC: la Ethernet tiene la IP de la cabecera y otras más
        String ethPc = "Intel(R) Ethernet Connection (6) I219-LM - 192.168.1.214, 192.168.1.11, 100.100.100.101";
        String wifiPc = "Qualcomm QCA61x4A 802.11ac Wireless Adapter - 192.168.100.3";
        List<String> pc = Arrays.asList(loop, udp, npcapLoop, wifiDirect, ethPc, miniport, wifiPc);
        chk("servidor, cabecera en la Ethernet: la Ethernet", 4,
            PlacaGoose.elegir(pc, "192.168.1.214", true), pc);

        // Sin placa cableada con red: la Wi-Fi, como antes
        String ethSinRed = "Realtek PCIe GbE Family Controller - 169.254.20.7";
        List<String> soloWifi = Arrays.asList(loop, udp, wifiDirect, ethSinRed, wifi);
        chk("servidor, cable sin red (169.254): queda la Wi-Fi", 4,
            PlacaGoose.elegir(soloWifi, "192.168.100.25", true), soloWifi);

        // La comparación es por dirección entera, no por texto
        List<String> parecidas = Arrays.asList(loop, udp, "Placa A - 192.168.1.11", "Placa B - 192.168.1.1");
        chk("192.168.1.1 no coincide con 192.168.1.11", 3,
            PlacaGoose.elegir(parecidas, "192.168.1.1", false), parecidas);

        // Por subred /24 si la IP exacta no está
        List<String> subred = Arrays.asList(loop, udp, "Placa C - 10.0.5.20");
        chk("sin IP exacta: misma subred /24", 2, PlacaGoose.elegir(subred, "10.0.5.99", false), subred);
        chk("sin IP exacta ni subred: ninguna", -1, PlacaGoose.elegir(subred, "172.16.0.1", false), subred);

        // Clasificación
        chk("Wi-Fi es inalámbrica", PlacaGoose.esInalambrica(wifi) && PlacaGoose.esInalambrica(wifiPc));
        chk("Wi-Fi Direct y Miniport son virtuales", PlacaGoose.esVirtual(wifiDirect) && PlacaGoose.esVirtual(miniport));
        chk("Ethernet del banco es cableada con red", PlacaGoose.esCableadaConRed(ethBanco));
        chk("las entradas ★ del combo no son placas", !PlacaGoose.esCableadaConRed(udp) && !PlacaGoose.esCableadaConRed(loop));

        System.out.println(fallas == 0 ? "\nTODO OK" : "\n" + fallas + " FALLAS");
        System.exit(fallas == 0 ? 0 : 1);
    }
}
