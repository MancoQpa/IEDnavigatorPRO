package com.iednavigator;

import com.beanit.iec61850bean.FileInformation;
import com.beanit.iec61850bean.ServerModel;

import javax.swing.*;
import javax.swing.filechooser.FileFilter;
import java.awt.*;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Fase 7: Extrae la sección CONNECTION MANAGEMENT de IEDNavigatorApp.
 * Gestiona el ciclo de vida de conexiones cliente/servidor y la carga de archivos SCL.
 */
class ConnectionManager {

    // ─── Context interface ────────────────────────────────────────────────────────────

    interface Context {
        void log(String msg);
        void updateStatus(boolean active, String msg);
        Component parentWindow();
        ExecutorService backgroundExecutor();

        // State getters/setters
        IEC61850Client getClient();
        void setClient(IEC61850Client c);
        IEC61850Server getServer();
        void setServer(IEC61850Server s);
        boolean isConnected();
        void setConnected(boolean v);
        boolean isServerRunning();
        void setServerRunning(boolean v);

        // SCL state
        File getLoadedSclFile();
        void setLoadedSclFile(File f);
        String getLoadedIedName();
        void setLoadedIedName(String n);
        String[] getLoadedIedNameplate();
        void setLoadedIedNameplate(String[] np);
        List<SclGoCB> getSclGoCBs();

        // UI callbacks (implemented by IEDNavigatorApp)
        void switchUiToServerMode();
        void switchUiToClientMode();

        // Post-connect/disconnect callbacks
        void onConnected(String host, int port, String localIp);
        void onDisconnected();
        void onServerStarted(String localIp, int port);
        void onServerStopped();

        // Model display
        void displayServerModel();
        void displayClientModel();
        void refreshGooseControlBlocks();
        void autoSelectGooseInterface(String localIp);

        // SCL parsing (delegates to IEDNavigatorApp.parseGoCBsFromScl)
        void parseGoCBsFromScl(File f);
        void parseGoCBsFromScl(File f, int iedIndex);

        // IED selection dialog
        int showIEDSelectionDialog(List<String> iedNames, String fileName);

        // Polling stop
        void stopPolling();

        // UI field access (needed inside connection logic)
        String getTfHost();
        String getTfClientPort();
        int getConnectionTimeoutMs();
        String getTfServerPort();
        void setLblFileName(String text);
        void setStatusIndicatorConnecting();
        void setBtnConnectEnabled(boolean v);
        void setBtnConnectText(String text);
        void setBtnStartStopText(String text);
        void setBtnStartStopEnabled(boolean v);
        void setCbPollingEnabled(boolean v);
        void setCbPollingSelected(boolean v);
        void setSpinnerIntervalEnabled(boolean v);
        void setLblIedInfo(String text);
        void updateConnectionInfo(String host, int port);
        void clearModel();

        // Read nameplate from client (background)
        Map<String, String> readDeviceNameplate();
    }

    // ─── Fields ───────────────────────────────────────────────────────────────────────

    private final Context ctx;

    // Connection state fields
    private byte[] downloadedCidData = null;
    private String downloadedCidFilename = null;
    private String currentHost = "";
    private int currentPort = 0;
    private String connectedLocalIp = "";

    // ─── Constructor ─────────────────────────────────────────────────────────────────

    ConnectionManager(Context ctx) {
        this.ctx = ctx;
    }

    // ─── Getters for fields migrated out of IEDNavigatorApp ───────────────────────────

    byte[] getDownloadedCidData() { return downloadedCidData; }
    String getDownloadedCidFilename() { return downloadedCidFilename; }
    String getCurrentHost() { return currentHost; }
    int getCurrentPort() { return currentPort; }
    String getConnectedLocalIp() { return connectedLocalIp; }

    // ─── Public API (called from IEDNavigatorApp) ──────────────────────────────────────

    void switchToServerMode() {
        // Si estamos conectados como cliente, desconectar primero
        if (ctx.isConnected() && ctx.getClient() != null) {
            disconnect();
            ctx.log("Cliente desconectado al cambiar a modo Servidor");
        }
        ctx.switchUiToServerMode();
        ctx.clearModel();
        ctx.updateStatus(false, "Modo Servidor");
    }

    void switchToClientMode() {
        // Si el servidor esta corriendo, detenerlo primero
        if (ctx.isServerRunning() && ctx.getServer() != null) {
            ctx.getServer().stop();
            ctx.setServerRunning(false);
            ctx.setBtnStartStopText("Iniciar Simulacion");
            ctx.log("Servidor detenido al cambiar a modo Cliente");
        }
        ctx.switchUiToClientMode();
        ctx.clearModel();
        ctx.updateStatus(false, "Modo Cliente");
    }

    /**
     * Obtiene el archivo CID del IED conectado
     */
    void obtenerCidDelIed() {
        if (!ctx.isConnected() || ctx.getClient() == null) {
            JOptionPane.showMessageDialog(ctx.parentWindow(), "Primero conecte a un IED",
                "No conectado", JOptionPane.WARNING_MESSAGE);
            return;
        }

        ctx.log("Buscando archivos SCL/CID en el IED...");

        ctx.backgroundExecutor().submit(() -> {
            try {
                // Buscar archivos SCL en el IED
                List<String> sclFiles = ctx.getClient().findSclFiles();

                if (sclFiles.isEmpty()) {
                    // Intentar listar directorio raiz para ver que hay
                    ctx.log("No se encontraron archivos SCL. Listando directorio raiz...");
                    try {
                        List<FileInformation> rootFiles = ctx.getClient().listFiles("");
                        if (rootFiles != null && !rootFiles.isEmpty()) {
                            ctx.log("Archivos en el IED:");
                            for (FileInformation fi : rootFiles) {
                                ctx.log("  - " + fi.getFilename() + " (" + fi.getFileSize() + " bytes)");
                            }
                        }
                    } catch (Exception e) {
                        ctx.log("No se pudo listar archivos: " + e.getMessage());
                    }

                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(ctx.parentWindow(),
                            "No se encontraron archivos CID/ICD/SCL en el IED.\nEl IED puede no soportar el servicio de archivos.",
                            "Archivos no encontrados", JOptionPane.INFORMATION_MESSAGE);
                    });
                    return;
                }

                // Si hay varios archivos, permitir seleccionar
                String selectedFile;
                if (sclFiles.size() == 1) {
                    selectedFile = sclFiles.get(0);
                } else {
                    // Mostrar dialogo para seleccionar
                    final String[] files = sclFiles.toArray(new String[0]);
                    final String[] selected = new String[1];

                    SwingUtilities.invokeAndWait(() -> {
                        selected[0] = (String) JOptionPane.showInputDialog(ctx.parentWindow(),
                            "Seleccione el archivo a descargar:",
                            "Archivos SCL encontrados",
                            JOptionPane.QUESTION_MESSAGE,
                            null, files, files[0]);
                    });

                    selectedFile = selected[0];
                    if (selectedFile == null) {
                        ctx.log("Descarga cancelada");
                        return;
                    }
                }

                ctx.log("Descargando: " + selectedFile);

                // Descargar el archivo
                downloadedCidData = ctx.getClient().downloadFile(selectedFile);
                downloadedCidFilename = selectedFile;

                // Extraer solo el nombre del archivo
                int lastSlash = selectedFile.lastIndexOf('/');
                String filename = lastSlash >= 0 ? selectedFile.substring(lastSlash + 1) : selectedFile;

                ctx.log("CID descargado: " + filename + " (" + downloadedCidData.length + " bytes)");

                // Parsear GoCBs del CID descargado
                try {
                    // Guardar temporalmente para parsear
                    File tempFile = File.createTempFile("ied_cid_", ".cid");
                    tempFile.deleteOnExit();
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tempFile)) {
                        fos.write(downloadedCidData);
                    }

                    ctx.parseGoCBsFromScl(tempFile);
                    ctx.setLoadedSclFile(tempFile);

                    SwingUtilities.invokeLater(() -> {
                        ctx.refreshGooseControlBlocks();
                        JOptionPane.showMessageDialog(ctx.parentWindow(),
                            "CID descargado exitosamente:\n" + filename + "\n\nGoCBs encontrados: " +
                            ctx.getSclGoCBs().size() +
                            "\n\nUse 'Guardar CID' para guardarlo en disco.",
                            "CID Descargado", JOptionPane.INFORMATION_MESSAGE);
                    });

                } catch (Exception parseEx) {
                    ctx.log("Error parseando CID: " + parseEx.getMessage());
                }

            } catch (Exception e) {
                ctx.log("Error obteniendo CID: " + e.getMessage());
                e.printStackTrace();
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(ctx.parentWindow(),
                        "Error obteniendo CID:\n" + e.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
                });
            }
        });
    }

    /**
     * Auto-descarga el CID del IED silenciosamente (sin dialogos)
     * para obtener GoCBs, Reports, etc.
     */
    private void autoDownloadCid() {
        if (!ctx.isConnected() || ctx.getClient() == null) return;

        ctx.log("Buscando CID en el IED para obtener GoCBs...");

        ctx.backgroundExecutor().submit(() -> {
            try {
                // Buscar archivos SCL en el IED
                List<String> sclFiles = ctx.getClient().findSclFiles();

                if (sclFiles.isEmpty()) {
                    ctx.log("No se encontro CID en el IED - GoCBs no disponibles via SCL");
                    ctx.log("Nota: Para ver GoCBs, cargue manualmente un archivo SCL (Archivo -> Cargar SCL/CID)");
                    return;
                }

                // Preferir archivos .cid sobre .icd
                String selectedFile = null;
                for (String f : sclFiles) {
                    if (f.toLowerCase().endsWith(".cid")) {
                        selectedFile = f;
                        break;
                    }
                }
                if (selectedFile == null) {
                    selectedFile = sclFiles.get(0);
                }

                ctx.log("Descargando CID automaticamente: " + selectedFile);

                // Descargar el archivo
                downloadedCidData = ctx.getClient().downloadFile(selectedFile);
                downloadedCidFilename = selectedFile;

                // Extraer nombre
                int lastSlash = selectedFile.lastIndexOf('/');
                String filename = lastSlash >= 0 ? selectedFile.substring(lastSlash + 1) : selectedFile;

                ctx.log("CID descargado: " + filename + " (" + downloadedCidData.length + " bytes)");

                // Parsear GoCBs del CID descargado
                File tempFile = File.createTempFile("ied_cid_auto_", ".cid");
                tempFile.deleteOnExit();
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tempFile)) {
                    fos.write(downloadedCidData);
                }

                ctx.parseGoCBsFromScl(tempFile);
                ctx.setLoadedSclFile(tempFile);

                // Actualizar UI
                SwingUtilities.invokeLater(() -> {
                    // Refrescar panel de GoCBs
                    ctx.refreshGooseControlBlocks();
                    ctx.log("GoCBs cargados automaticamente del CID");
                });

            } catch (Exception e) {
                ctx.log("Auto-descarga CID falló: " + e.getMessage());
                ctx.log("Para ver GoCBs, cargue un archivo SCL manualmente");
            }
        });
    }

    /**
     * Guarda el CID descargado en disco
     */
    void guardarCid() {
        if (downloadedCidData == null || downloadedCidData.length == 0) {
            JOptionPane.showMessageDialog(ctx.parentWindow(),
                "No hay CID descargado.\nPrimero use 'Obtener CID' para descargar el archivo del IED.",
                "Sin CID", JOptionPane.WARNING_MESSAGE);
            return;
        }

        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Guardar CID");

        // Nombre sugerido
        String suggestedName = downloadedCidFilename;
        if (suggestedName != null) {
            int lastSlash = suggestedName.lastIndexOf('/');
            if (lastSlash >= 0) {
                suggestedName = suggestedName.substring(lastSlash + 1);
            }
        } else {
            suggestedName = "ied_config.cid";
        }
        fc.setSelectedFile(new File(suggestedName));

        fc.setFileFilter(new FileFilter() {
            public boolean accept(File f) {
                if (f.isDirectory()) return true;
                String name = f.getName().toLowerCase();
                return name.endsWith(".cid") || name.endsWith(".icd") || name.endsWith(".scl");
            }
            public String getDescription() {
                return "SCL Files (*.cid, *.icd, *.scl)";
            }
        });

        if (fc.showSaveDialog(ctx.parentWindow()) == JFileChooser.APPROVE_OPTION) {
            File file = fc.getSelectedFile();

            // Asegurar extension
            if (!file.getName().toLowerCase().endsWith(".cid") &&
                !file.getName().toLowerCase().endsWith(".icd") &&
                !file.getName().toLowerCase().endsWith(".scl")) {
                file = new File(file.getAbsolutePath() + ".cid");
            }

            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
                fos.write(downloadedCidData);
                ctx.log("CID guardado en: " + file.getAbsolutePath());
                JOptionPane.showMessageDialog(ctx.parentWindow(),
                    "CID guardado exitosamente:\n" + file.getAbsolutePath(),
                    "Guardado", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception e) {
                ctx.log("Error guardando CID: " + e.getMessage());
                JOptionPane.showMessageDialog(ctx.parentWindow(),
                    "Error guardando archivo:\n" + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    void selectSclFile() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileFilter() {
            public boolean accept(File f) {
                if (f.isDirectory()) return true;
                String name = f.getName().toLowerCase();
                return name.endsWith(".icd") || name.endsWith(".cid") ||
                       name.endsWith(".scd") || name.endsWith(".scl");
            }
            public String getDescription() {
                return "SCL Files (*.icd, *.cid, *.scd, *.scl)";
            }
        });

        if (fc.showOpenDialog(ctx.parentWindow()) == JFileChooser.APPROVE_OPTION) {
            File file = fc.getSelectedFile();
            ctx.setLoadedSclFile(file);
            ctx.setLblFileName(file.getName());
            ctx.updateStatus(false, "Analizando SCL...");
            ctx.setStatusIndicatorConnecting();

            ctx.backgroundExecutor().submit(() -> {
                try {
                    ctx.log("Analizando: " + file.getName() + " (" + (file.length()/1024) + " KB)");

                    // Primero obtener lista de IEDs disponibles
                    List<String> availableIEDs = ctx.getServer().getAvailableIEDs(file.getAbsolutePath());
                    int iedCount = availableIEDs.size();
                    ctx.log("IEDs encontrados: " + iedCount);

                    int selectedIED = 0; // Por defecto el primero

                    // Si hay múltiples IEDs, mostrar diálogo de selección
                    if (iedCount > 1) {
                        final int[] selection = {-1};
                        SwingUtilities.invokeAndWait(() -> {
                            selection[0] = ctx.showIEDSelectionDialog(availableIEDs, file.getName());
                        });

                        if (selection[0] < 0) {
                            // Usuario canceló
                            SwingUtilities.invokeLater(() -> {
                                ctx.updateStatus(false, "Carga cancelada");
                                ctx.setLblFileName("");
                            });
                            return;
                        }
                        selectedIED = selection[0];
                        ctx.log("IED seleccionado: " + availableIEDs.get(selectedIED) +
                            " (índice " + selectedIED + ")");
                    }

                    // Cargar el IED seleccionado
                    SwingUtilities.invokeLater(() -> ctx.updateStatus(false, "Cargando IED..."));
                    long startTime = System.currentTimeMillis();

                    boolean success = ctx.getServer().loadSclFileWithIED(file.getAbsolutePath(), selectedIED);

                    // Parsear GoCBs del SCL (filtrado por IED seleccionado)
                    ctx.parseGoCBsFromScl(file, selectedIED);

                    long elapsed = System.currentTimeMillis() - startTime;
                    ctx.log("Parsing completado en " + elapsed + "ms, success=" + success);
                    ctx.log("GoCBs encontrados en SCL: " + ctx.getSclGoCBs().size());

                    final int finalSelectedIED = selectedIED;
                    SwingUtilities.invokeLater(() -> {
                        try {
                            if (success) {
                                ctx.setBtnStartStopEnabled(true);
                                String iedName = availableIEDs.size() > finalSelectedIED ?
                                    availableIEDs.get(finalSelectedIED) : "IED";
                                ctx.updateStatus(false, "SCL cargado - " + iedName);
                                ctx.setLblFileName(file.getName() + " [" + iedName + "]");
                                // Mostrar nameplate del IED en status bar
                                String[] np = ctx.getLoadedIedNameplate();
                                if (np != null) {
                                    String mfr  = np[0].isEmpty() ? "?" : np[0];
                                    String type = np[1].isEmpty() ? "?" : np[1];
                                    String cfgV = np[3].isEmpty() ? "" : "  cfg:" + np[3];
                                    String plate = String.format("  IED: %s  |  Fabricante: %s  |  Tipo: %s%s",
                                        iedName, mfr, type, cfgV);
                                    ctx.setLblIedInfo(plate);
                                    ctx.log("Nameplate: fabricante=" + mfr + " tipo=" + type + cfgV.trim());
                                    // Inyectar nameplate en los nodos FC=DC del modelo servido
                                    // para que clientes que lean via MMS obtengan los datos reales
                                    ctx.getServer().injectNameplate(np[0], np[1], np[3]);
                                }
                                ctx.log("Construyendo arbol...");
                                ctx.displayServerModel();
                                ctx.log("SCL cargado correctamente");
                                // Actualizar GoCBs automaticamente
                                ctx.refreshGooseControlBlocks();
                            } else {
                                ctx.updateStatus(false, "Error cargando SCL");
                                ctx.log("ERROR: No se pudo cargar el SCL");
                            }
                        } catch (Exception e) {
                            ctx.log("ERROR en UI: " + e.getMessage());
                            e.printStackTrace();
                        }
                    });
                } catch (Exception e) {
                    ctx.log("ERROR en background: " + e.getMessage());
                    e.printStackTrace();
                    SwingUtilities.invokeLater(() -> {
                        ctx.updateStatus(false, "Error: " + e.getMessage());
                    });
                }
            });
        }
    }

    void toggleServer() {
        if (ctx.isServerRunning()) {
            ctx.getServer().stop();
            ctx.setServerRunning(false);
            ctx.setBtnStartStopText("Iniciar Simulacion");
            ctx.updateStatus(false, "Simulacion detenida");
            ctx.updateConnectionInfo("", 0);
            ctx.log("Simulacion IED detenida");
        } else {
            try {
                int port = Integer.parseInt(ctx.getTfServerPort().trim());
                ctx.updateStatus(false, "Iniciando simulacion IED...");
                ctx.setStatusIndicatorConnecting();

                final int finalPort = port;
                ctx.backgroundExecutor().submit(() -> {
                    boolean success = ctx.getServer().start(finalPort);

                    SwingUtilities.invokeLater(() -> {
                        if (success) {
                            ctx.setServerRunning(true);
                            currentPort = finalPort;
                            String localIp = getLocalIpAddress();
                            currentHost = localIp;
                            ctx.setBtnStartStopText("Detener Simulacion");
                            ctx.updateStatus(true, "IED Simulado ACTIVO");
                            ctx.updateConnectionInfo(localIp + " (servidor)", finalPort);
                            ctx.log("SIMULACION IED ACTIVA");
                            ctx.log("IP: " + localIp + " | Puerto: " + finalPort);
                            ctx.log("Conecta cliente a: " + localIp + ":" + finalPort);

                            // Auto-seleccionar interfaz de red para GOOSE (igual que en modo cliente)
                            ctx.autoSelectGooseInterface(localIp);
                        } else {
                            ctx.updateStatus(false, "Error iniciando simulacion");
                            ctx.updateConnectionInfo("", 0);
                            ctx.log("ERROR: No se pudo iniciar el servidor");
                            ctx.log("Verifica que el puerto " + finalPort + " no este en uso");
                        }
                    });
                });

            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(ctx.parentWindow(), "Puerto invalido");
            }
        }
    }

    void toggleConnection() {
        if (ctx.isConnected()) {
            disconnect();
        } else {
            connect();
        }
    }

    private void connect() {
        String host = ctx.getTfHost().trim();
        if (host.isEmpty()) {
            JOptionPane.showMessageDialog(ctx.parentWindow(), "Ingrese el host");
            return;
        }

        int port;
        try {
            port = Integer.parseInt(ctx.getTfClientPort().trim());
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(ctx.parentWindow(), "Puerto invalido");
            return;
        }

        ctx.setBtnConnectEnabled(false);
        ctx.updateStatus(false, "Conectando a " + host + ":" + port + "...");
        ctx.setStatusIndicatorConnecting();
        ctx.log("Conectando a " + host + ":" + port + "...");

        ctx.backgroundExecutor().submit(() -> {
            try {
                ctx.log("Iniciando conexion...");
                long startTime = System.currentTimeMillis();

                ctx.getClient().setConnectionTimeoutMs(ctx.getConnectionTimeoutMs());
                ctx.getClient().connect(host, port);

                long elapsed = System.currentTimeMillis() - startTime;
                ctx.log("Conexion establecida en " + elapsed + "ms");

                // Detectar interfaz local usada para la conexion
                String localIp = detectLocalInterface(host);
                ctx.log("Interfaz local detectada: " + localIp);

                final String finalHost = host;
                final int finalPort = port;
                final String finalLocalIp = localIp;
                SwingUtilities.invokeLater(() -> {
                    try {
                        ctx.setConnected(true);
                        currentHost = finalHost;
                        currentPort = finalPort;
                        connectedLocalIp = finalLocalIp;
                        ctx.setBtnConnectText("Desconectar");
                        ctx.setBtnConnectEnabled(true);
                        ctx.setCbPollingEnabled(true);
                        ctx.setSpinnerIntervalEnabled(true);
                        ctx.updateStatus(true, "Conectado");
                        ctx.updateConnectionInfo(finalHost, finalPort);
                        ctx.log("Construyendo arbol del modelo...");
                        ctx.displayClientModel();
                        ctx.log("Conectado! Modelo recibido.");

                        // Leer placa de identificación del IED (FC=DC) en background
                        ctx.backgroundExecutor().submit(() -> {
                            Map<String, String> plate = ctx.getClient().readDeviceNameplate();
                            // IED name: usar vendor si es un nombre de fabricante,
                            // sino extraer del prefijo común de LDs del modelo
                            String iedName = ctx.getClient().getIedName();
                            if (iedName.isEmpty()) iedName = finalHost;
                            // Fabricante: vendor del LLN0.NamPlt
                            String mfr = plate.getOrDefault("vendor", "");
                            // Tipo: campo d (descripción) o swRev como fallback
                            String tipo = plate.getOrDefault("d", "");
                            if (tipo.isEmpty()) tipo = plate.getOrDefault("swRev", "");
                            // Config: configRev
                            String cfgV = plate.getOrDefault("configRev", "");
                            // Formato idéntico al modo Servidor para que updateIedDisplay parsee igual
                            StringBuilder sb = new StringBuilder("  IED: ").append(iedName);
                            if (!mfr.isEmpty())  sb.append("  |  Fabricante: ").append(mfr);
                            if (!tipo.isEmpty()) sb.append("  |  Tipo: ").append(tipo);
                            if (!cfgV.isEmpty()) sb.append("  cfg:").append(cfgV);
                            final String finalInfo = sb.toString();
                            SwingUtilities.invokeLater(() -> {
                                ctx.setLblIedInfo(finalInfo);
                                ctx.log("Placa IED →" + finalInfo.trim());
                            });
                        });

                        // Auto-seleccionar interfaz de red para GOOSE
                        ctx.autoSelectGooseInterface(finalLocalIp);

                        // Auto-descargar CID para obtener GoCBs (en background)
                        autoDownloadCid();
                    } catch (Exception e) {
                        ctx.log("ERROR en UI despues de conexion: " + e.getMessage());
                        e.printStackTrace();
                    }
                });

            } catch (Exception e) {
                String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                if (errMsg.startsWith("SCL_FALLBACK:")) {
                    // El IED rechazó retrieveModel() por DataSet inexistente, pero la asociación MMS
                    // sigue activa. Pedimos al usuario un archivo SCL local para usarlo como modelo.
                    ctx.log("AVISO: El IED no pudo entregar el modelo completo (DataSet inválido).");
                    ctx.log("Cargando modelo desde archivo SCL local para continuar...");

                    final String fHost = host;
                    final int fPort = port;

                    SwingUtilities.invokeLater(() -> {
                        int choice = JOptionPane.showConfirmDialog(ctx.parentWindow(),
                            "<html><b>El IED rechazó la descarga del modelo</b><br><br>" +
                            "Causa: " + errMsg.substring("SCL_FALLBACK:".length()).trim() + "<br><br>" +
                            "¿Desea cargar un archivo SCL/CID local para continuar?<br>" +
                            "<small>(La conexión MMS sigue activa — solo se usará el SCL para navegación)</small></html>",
                            "Fallback SCL", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

                        if (choice != JOptionPane.YES_OPTION) {
                            ctx.getClient().cancelPendingAssociation();
                            ctx.setBtnConnectEnabled(true);
                            ctx.updateStatus(false, "Conexión cancelada");
                            ctx.log("Conexión cancelada por el usuario.");
                            return;
                        }

                        JFileChooser fc = new JFileChooser();
                        fc.setDialogTitle("Seleccionar SCL/CID del IED");
                        fc.setFileFilter(new javax.swing.filechooser.FileFilter() {
                            public boolean accept(File f) {
                                if (f.isDirectory()) return true;
                                String n = f.getName().toLowerCase();
                                return n.endsWith(".icd") || n.endsWith(".cid") ||
                                       n.endsWith(".scd") || n.endsWith(".scl");
                            }
                            public String getDescription() { return "SCL Files (*.icd, *.cid, *.scd, *.scl)"; }
                        });

                        if (fc.showOpenDialog(ctx.parentWindow()) != JFileChooser.APPROVE_OPTION) {
                            ctx.getClient().cancelPendingAssociation();
                            ctx.setBtnConnectEnabled(true);
                            ctx.updateStatus(false, "Conexión cancelada");
                            ctx.log("Selección de SCL cancelada.");
                            return;
                        }

                        File sclFile = fc.getSelectedFile();
                        ctx.log("Parseando SCL: " + sclFile.getName());
                        ctx.backgroundExecutor().submit(() -> {
                            try {
                                // Usar IEC61850Server para parsear y fusionar AccessPoints
                                IEC61850Server tmpServer = new IEC61850Server();
                                java.util.List<String> iedNames = tmpServer.getAvailableIEDs(sclFile.getAbsolutePath());

                                if (iedNames.isEmpty()) {
                                    throw new Exception("No se encontraron IEDs en el archivo SCL");
                                }

                                int iedIdx = 0;
                                if (iedNames.size() > 1) {
                                    final int[] sel = {-1};
                                    SwingUtilities.invokeAndWait(() -> {
                                        sel[0] = ctx.showIEDSelectionDialog(iedNames, sclFile.getName());
                                    });
                                    if (sel[0] < 0) {
                                        ctx.getClient().cancelPendingAssociation();
                                        SwingUtilities.invokeLater(() -> {
                                            ctx.setBtnConnectEnabled(true);
                                            ctx.updateStatus(false, "Conexión cancelada");
                                        });
                                        return;
                                    }
                                    iedIdx = sel[0];
                                }

                                ServerModel model = tmpServer.getMergedModel(iedIdx);
                                if (model == null) throw new Exception("No se pudo obtener el modelo del IED " + iedIdx);

                                boolean ok = ctx.getClient().attachExternalModel(model);
                                if (!ok) throw new Exception("attachExternalModel falló");

                                ctx.log("Modelo SCL inyectado: " + iedNames.get(iedIdx) + " (" + sclFile.getName() + ")");

                                // Parsear GoCBs del SCL seleccionado
                                final int fIdx = iedIdx;
                                ctx.parseGoCBsFromScl(sclFile, fIdx);
                                ctx.setLoadedSclFile(sclFile);

                                String localIp = detectLocalInterface(fHost);
                                final String finalLocalIp = localIp;

                                SwingUtilities.invokeLater(() -> {
                                    try {
                                        ctx.setConnected(true);
                                        currentHost = fHost;
                                        currentPort = fPort;
                                        connectedLocalIp = finalLocalIp;
                                        ctx.setBtnConnectText("Desconectar");
                                        ctx.setBtnConnectEnabled(true);
                                        ctx.setCbPollingEnabled(true);
                                        ctx.setSpinnerIntervalEnabled(true);
                                        ctx.updateStatus(true, "Conectado (modelo SCL)");
                                        ctx.updateConnectionInfo(fHost, fPort);
                                        ctx.log("Construyendo árbol del modelo desde SCL...");
                                        ctx.displayClientModel();
                                        ctx.log("Conectado con modelo SCL. Valores individuales disponibles vía MMS.");
                                        ctx.autoSelectGooseInterface(finalLocalIp);
                                        ctx.refreshGooseControlBlocks();
                                    } catch (Exception uiEx) {
                                        ctx.log("ERROR en UI (SCL fallback): " + uiEx.getMessage());
                                    }
                                });
                            } catch (Exception ex) {
                                ctx.getClient().cancelPendingAssociation();
                                ctx.log("ERROR en fallback SCL: " + ex.getMessage());
                                ex.printStackTrace();
                                SwingUtilities.invokeLater(() -> {
                                    ctx.setBtnConnectEnabled(true);
                                    ctx.updateStatus(false, "Error: " + ex.getMessage());
                                });
                            }
                        });
                    });

                } else {
                    ctx.log("ERROR de conexion: " + e.getClass().getSimpleName() + " - " + errMsg);
                    e.printStackTrace();
                    SwingUtilities.invokeLater(() -> {
                        ctx.setBtnConnectEnabled(true);
                        ctx.updateStatus(false, "Error: " + errMsg);
                    });
                }
            }
        });
    }

    private void disconnect() {
        ctx.stopPolling();
        ctx.getClient().disconnect();
        handleDisconnect();
        ctx.log("Desconectado");
    }

    void handleDisconnect() {  // F26: package-private so IEDNavigatorApp can delegate
        ctx.setConnected(false);
        currentHost = "";
        currentPort = 0;
        connectedLocalIp = "";
        ctx.setBtnConnectText("Conectar");
        ctx.setBtnConnectEnabled(true);
        ctx.setCbPollingEnabled(false);
        ctx.setCbPollingSelected(false);
        ctx.setSpinnerIntervalEnabled(false);
        ctx.updateStatus(false, "Desconectado");
        ctx.updateConnectionInfo("", 0);
        ctx.setLblIedInfo(" ");
        ctx.clearModel();
    }

    /**
     * Detecta la interfaz local usada para conectar a un host remoto.
     * Crea una conexion temporal para determinar cual IP local se usaria.
     */
    private String detectLocalInterface(String remoteHost) {
        try {
            // Crear socket temporal para detectar la ruta
            java.net.DatagramSocket socket = new java.net.DatagramSocket();
            socket.connect(java.net.InetAddress.getByName(remoteHost), 102);
            String localIp = socket.getLocalAddress().getHostAddress();
            socket.close();
            return localIp;
        } catch (Exception e) {
            ctx.log("No se pudo detectar interfaz local: " + e.getMessage());
            return "";
        }
    }

    // Obtener IP local del sistema (sin priorización - retorna la primera IP privada)
    private String getLocalIpAddress() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces =
                java.net.NetworkInterface.getNetworkInterfaces();

            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;

                java.util.Enumeration<java.net.InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress addr = addresses.nextElement();
                    if (addr instanceof java.net.Inet4Address) {
                        String ip = addr.getHostAddress();
                        // Solo considerar IPs de red privada (no link-local)
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") ||
                            (ip.startsWith("172.") && !ip.startsWith("169.254."))) {
                            return ip;
                        }
                    }
                }
            }
            // Fallback
            return java.net.InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }
}
