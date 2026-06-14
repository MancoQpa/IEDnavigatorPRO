package com.iednavigator.bridge.api;

import io.javalin.http.Context;
import org.pcap4j.core.PcapNetworkInterface;
import org.pcap4j.core.Pcaps;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Endpoints /api/v1/system/* : capacidades del host y shutdown. */
public final class SystemApi {

    private final Runnable shutdownAction;

    public SystemApi(Runnable shutdownAction) {
        this.shutdownAction = shutdownAction;
    }

    public void info(Context ctx) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("app", "ied-navigator-bridge");
        info.put("version", "1.0.0");
        info.put("javaVersion", System.getProperty("java.version"));
        info.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));

        // Capacidad Npcap/pcap4j
        boolean npcap = false;
        List<Map<String, Object>> interfaces = new ArrayList<>();
        try {
            List<PcapNetworkInterface> devs = Pcaps.findAllDevs();
            npcap = true;
            if (devs != null) {
                for (PcapNetworkInterface dev : devs) {
                    Map<String, Object> nif = new LinkedHashMap<>();
                    nif.put("name", dev.getName());
                    nif.put("description", dev.getDescription());
                    interfaces.add(nif);
                }
            }
        } catch (Throwable t) {
            // Npcap no instalado o pcap4j sin acceso nativo
        }
        info.put("npcapAvailable", npcap);
        info.put("interfaces", interfaces);

        // Capacidad libiec61850.dll (JNA)
        String dllPath = System.getProperty("jna.library.path", "lib");
        boolean dll = new File(dllPath, "iec61850.dll").exists()
                || new File("lib", "iec61850.dll").exists();
        info.put("nativeDllAvailable", dll);

        ctx.json(info);
    }

    /** GET /system/portcheck?port=N — comprueba si el puerto TCP está libre para bind. */
    public void portCheck(Context ctx) {
        int port;
        try {
            port = Integer.parseInt(ctx.queryParam("port"));
        } catch (Exception e) {
            throw new io.javalin.http.BadRequestResponse("Parámetro 'port' inválido");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("port", port);
        try (java.net.ServerSocket s = new java.net.ServerSocket()) {
            s.setReuseAddress(false);
            s.bind(new java.net.InetSocketAddress(port));
            out.put("free", true);
        } catch (Exception e) {
            out.put("free", false);
            out.put("error", e.getMessage());
        }
        ctx.json(out);
    }

    /**
     * POST /system/portrelease {port} — termina el proceso que escucha en el puerto
     * (réplica del botón «Liberar Puerto» de la GUI clásica; solo Windows).
     */
    public void portRelease(Context ctx) {
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        int port;
        try {
            port = ((Number) body.get("port")).intValue();
        } catch (Exception e) {
            throw new io.javalin.http.BadRequestResponse("Parámetro 'port' inválido");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("port", port);
        try {
            long pid = findListeningPid(port);
            if (pid <= 0) {
                out.put("released", false);
                out.put("message", "Ningún proceso escucha en el puerto " + port);
            } else if (pid == ProcessHandle.current().pid()) {
                out.put("released", false);
                out.put("pid", pid);
                out.put("message", "El puerto lo usa el propio backend; no se termina");
            } else {
                Process kill = new ProcessBuilder("taskkill", "/F", "/PID", String.valueOf(pid))
                        .redirectErrorStream(true).start();
                kill.waitFor();
                out.put("released", kill.exitValue() == 0);
                out.put("pid", pid);
                if (kill.exitValue() != 0) {
                    out.put("message", "taskkill devolvió código " + kill.exitValue()
                            + " (puede requerir permisos de administrador)");
                }
            }
        } catch (Exception e) {
            out.put("released", false);
            out.put("message", e.getMessage());
        }
        ctx.json(out);
    }

    /** PID del proceso en LISTENING sobre el puerto TCP dado (netstat -ano), o -1. */
    private static long findListeningPid(int port) throws java.io.IOException {
        Process p = new ProcessBuilder("netstat", "-ano", "-p", "TCP")
                .redirectErrorStream(true).start();
        String suffix = ":" + port;
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] tok = line.trim().split("\\s+");
                // Proto LocalAddress ForeignAddress State PID
                if (tok.length >= 5 && tok[0].equalsIgnoreCase("TCP")
                        && tok[1].endsWith(suffix) && tok[3].toUpperCase().contains("LISTEN")) {
                    try {
                        return Long.parseLong(tok[4]);
                    } catch (NumberFormatException ignore) {
                        // seguir buscando
                    }
                }
            }
        } finally {
            p.destroy();
        }
        return -1;
    }

    public void shutdown(Context ctx) {
        ctx.json(Map.of("status", "shutting down"));
        shutdownAction.run();
    }
}
