package com.iednavigator.bridge;

import java.util.UUID;

/**
 * Entry point headless del bridge REST/WS para el frontend Tauri.
 *
 * Uso: java -jar bridge.jar --port 0 --token <uuid> [--watchdog 30]
 *
 * Imprime "BRIDGE_READY port=<n>" por stdout cuando el servidor HTTP
 * está escuchando (handshake con el sidecar de Tauri).
 */
public final class BridgeMain {

    public static void main(String[] args) {
        int port = 0;
        String token = null;
        int watchdogSeconds = 0; // 0 = deshabilitado (útil para pruebas con curl)

        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--port":     port = Integer.parseInt(args[i + 1]); break;
                case "--token":    token = args[i + 1]; break;
                case "--watchdog": watchdogSeconds = Integer.parseInt(args[i + 1]); break;
                default: break;
            }
        }

        if (token == null || token.isEmpty()) {
            token = UUID.randomUUID().toString();
            System.out.println("BRIDGE_TOKEN " + token);
        }

        BridgeServer server = new BridgeServer(token, watchdogSeconds);
        int actualPort = server.start(port);

        // Handshake con el proceso padre (Tauri sidecar)
        System.out.println("BRIDGE_READY port=" + actualPort);
        System.out.flush();
    }

    private BridgeMain() {}
}
