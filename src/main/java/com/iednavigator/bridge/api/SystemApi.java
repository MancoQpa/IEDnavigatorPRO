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

    public void shutdown(Context ctx) {
        ctx.json(Map.of("status", "shutting down"));
        shutdownAction.run();
    }
}
