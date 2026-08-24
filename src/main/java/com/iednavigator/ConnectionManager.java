package com.iednavigator;

import com.beanit.iec61850bean.FileInformation;
import com.beanit.iec61850bean.ServerModel;

import javax.swing.*;
import javax.swing.filechooser.FileFilter;
import java.awt.*;
import java.io.File;
import java.util.List;
import java.util.Locale;
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
            ctx.log(I18n.t("log.cm.clientdisc.tomodesrv"));
        }
        ctx.switchUiToServerMode();
        ctx.clearModel();
        ctx.updateStatus(false, I18n.t("status.mode.server"));
    }

    void switchToClientMode() {
        // Si el servidor esta corriendo, detenerlo primero
        if (ctx.isServerRunning() && ctx.getServer() != null) {
            ctx.getServer().stop();
            ctx.setServerRunning(false);
            ctx.setBtnStartStopText("Iniciar Simulacion");
            ctx.log(I18n.t("log.cm.srvstop.tomodecli"));
        }
        ctx.switchUiToClientMode();
        ctx.clearModel();
        ctx.updateStatus(false, I18n.t("status.mode.client"));
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

        ctx.log(I18n.t("log.cm.searchingscl"));

        ctx.backgroundExecutor().submit(() -> {
            try {
                // Buscar archivos SCL en el IED
                List<String> sclFiles = ctx.getClient().findSclFiles();

                if (sclFiles.isEmpty()) {
                    // Intentar listar directorio raiz para ver que hay
                    ctx.log(I18n.t("log.cm.noscl.listingroot"));
                    try {
                        List<FileInformation> rootFiles = ctx.getClient().listFiles("");
                        if (rootFiles != null && !rootFiles.isEmpty()) {
                            ctx.log(I18n.t("log.cm.filesinied"));
                            for (FileInformation fi : rootFiles) {
                                ctx.log(I18n.t("log.cm.fileentry", fi.getFilename(), fi.getFileSize()));
                            }
                        }
                    } catch (Exception e) {
                        ctx.log(I18n.t("log.cm.listfileserror", e.getMessage()));
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
                        ctx.log(I18n.t("log.cm.downloadcancelled"));
                        return;
                    }
                }

                ctx.log(I18n.t("log.cm.downloading", selectedFile));

                // Descargar el archivo
                downloadedCidData = ctx.getClient().downloadFile(selectedFile);
                downloadedCidFilename = selectedFile;

                // Extraer solo el nombre del archivo
                int lastSlash = selectedFile.lastIndexOf('/');
                String filename = lastSlash >= 0 ? selectedFile.substring(lastSlash + 1) : selectedFile;

                ctx.log(I18n.t("log.cm.ciddownloaded", filename, downloadedCidData.length));

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
                    ctx.log(I18n.t("log.cm.cidparseerror", parseEx.getMessage()));
                }

            } catch (Exception e) {
                ctx.log(I18n.t("log.cm.cidgeterror", e.getMessage()));
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

        ctx.log(I18n.t("log.cm.searchingcid.forgocb"));

        ctx.backgroundExecutor().submit(() -> {
            try {
                // Buscar archivos SCL en el IED
                List<String> sclFiles = ctx.getClient().findSclFiles();

                if (sclFiles.isEmpty()) {
                    ctx.log(I18n.t("log.cm.nocid.nogocb"));
                    ctx.log(I18n.t("log.cm.notemanualscl"));
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

                ctx.log(I18n.t("log.cm.autodownloadingcid", selectedFile));

                // Descargar el archivo
                downloadedCidData = ctx.getClient().downloadFile(selectedFile);
                downloadedCidFilename = selectedFile;

                // Extraer nombre
                int lastSlash = selectedFile.lastIndexOf('/');
                String filename = lastSlash >= 0 ? selectedFile.substring(lastSlash + 1) : selectedFile;

                ctx.log(I18n.t("log.cm.ciddownloaded", filename, downloadedCidData.length));

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
                    ctx.log(I18n.t("log.cm.gocbsautoloaded"));
                });

            } catch (Exception e) {
                ctx.log(I18n.t("log.cm.autocidfailed", e.getMessage()));
                ctx.log(I18n.t("log.cm.formanualscl"));
            }
        });
    }

    /**
     * Guarda el CID en disco. Hay dos orígenes posibles:
     *
     *  1. El archivo que el IED entrega por el servicio de archivos MMS
     *     ({@code obtenerCidDelIed()}). Es el de máxima fidelidad, pero muchos equipos no
     *     guardan el CID como archivo y entonces no hay nada que descargar.
     *  2. Una reconstrucción generada desde el modelo de datos que el IED expuso al conectarse
     *     (ver {@link SclExporter}). Está disponible siempre que haya un modelo recuperado,
     *     que es el caso normal tras una conexión exitosa.
     *
     * Si están disponibles los dos, se le pregunta al usuario cuál quiere.
     */
    void guardarCid() {
        boolean hayDescargado = downloadedCidData != null && downloadedCidData.length > 0;
        ServerModel modelo = (ctx.getClient() != null) ? ctx.getClient().getServerModel() : null;
        boolean hayModelo = modelo != null && modelo.getChildren() != null
                            && !modelo.getChildren().isEmpty();

        if (!hayDescargado && !hayModelo) {
            JOptionPane.showMessageDialog(ctx.parentWindow(),
                I18n.t("cid.save.nosource"),
                I18n.t("cid.save.nosource.title"), JOptionPane.WARNING_MESSAGE);
            return;
        }

        boolean generar;
        if (hayDescargado && hayModelo) {
            String[] opts = { I18n.t("cid.save.src.file"), I18n.t("cid.save.src.model") };
            int sel = JOptionPane.showOptionDialog(ctx.parentWindow(),
                I18n.t("cid.save.src.msg"), I18n.t("cid.save.src.title"),
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, opts, opts[0]);
            if (sel < 0) return;                 // cerró el diálogo sin elegir
            generar = (sel == 1);
        } else {
            generar = !hayDescargado;
        }

        if (generar) guardarCidGenerado(modelo);
        else         guardarCidDescargado();
    }

    /** Escribe en disco el archivo tal cual lo entregó el IED por el servicio de archivos. */
    private void guardarCidDescargado() {
        String sugerido = downloadedCidFilename;
        if (sugerido != null) {
            int lastSlash = sugerido.lastIndexOf('/');
            if (lastSlash >= 0) sugerido = sugerido.substring(lastSlash + 1);
        } else {
            sugerido = "ied_config.cid";
        }
        File file = pedirDestino(sugerido);
        if (file == null) return;
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
            fos.write(downloadedCidData);
            ctx.log(I18n.t("log.cm.cidsaved", file.getAbsolutePath()));
            JOptionPane.showMessageDialog(ctx.parentWindow(),
                I18n.t("cid.save.ok", file.getAbsolutePath()),
                I18n.t("cid.save.ok.title"), JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception e) {
            ctx.log(I18n.t("log.cm.cidsaveerror", e.getMessage()));
            JOptionPane.showMessageDialog(ctx.parentWindow(),
                I18n.t("cid.save.error", e.getMessage()),
                "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Reconstruye el SCL desde el modelo recuperado por MMS y lo guarda. */
    private void guardarCidGenerado(ServerModel modelo) {
        // El nombre del IED no siempre se puede deducir: en MMS los LD se llaman
        // iedName+ldInst, y con un solo LD no hay prefijo común del que separarlos. Por eso se
        // ofrece editable, con la mejor sugerencia disponible.
        String sugerido = SclExporter.suggestIedName(modelo);
        if (sugerido == null || sugerido.isBlank()) {
            sugerido = (ctx.getClient() != null) ? ctx.getClient().getIedName() : "";
        }
        if (sugerido == null || sugerido.isBlank()) sugerido = "IED";

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 6, 4, 6);
        g.anchor = GridBagConstraints.WEST;
        g.gridx = 0; g.gridy = 0; g.gridwidth = 2;
        panel.add(new JLabel(I18n.t("cid.gen.warn")), g);
        g.gridwidth = 1; g.gridy = 1;
        panel.add(new JLabel(I18n.t("cid.gen.iedname")), g);
        g.gridx = 1;
        JTextField tfName = new JTextField(sugerido, 22);
        panel.add(tfName, g);

        if (JOptionPane.showConfirmDialog(ctx.parentWindow(), panel, I18n.t("cid.gen.title"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        String escrito = tfName.getText().trim();
        final String iedName = escrito.isEmpty() ? sugerido : escrito;

        File file = pedirDestino(iedName + ".cid");
        if (file == null) return;

        // La lectura de valores y la exportacion van a un hilo de fondo: son miles de
        // viajes MMS y esto corre en el hilo de la interfaz, que se congelaria.
        ctx.backgroundExecutor().submit(() -> generarYGuardarCid(modelo, iedName, file));
    }

    /** Lee los valores del IED, reconstruye el SCL y lo escribe. Fuera del hilo de UI. */
    private void generarYGuardarCid(ServerModel modelo, String iedName, File file) {
        ctx.log(I18n.t("log.cid.generating"));
        try {
            // Sin esto el archivo sale con la estructura correcta y TODOS los valores en
            // cero: retrieveModel() trae la estructura, no los valores. Ver
            // IEC61850Client.leerValoresParaExportar().
            IEC61850Client.LecturaModelo lectura = null;
            if (ctx.getClient() != null) {
                lectura = ctx.getClient().leerValoresParaExportar(
                        (hechos, total) -> ctx.log(I18n.t("log.cid.readprogress", hechos, total)));
                ctx.log(I18n.t("log.cid.readdone", lectura.leidos, lectura.fallidos));
                // Los DataSets no vienen con el modelo cuando retrieveModel() falla y se
                // cae a la construccion manual. Se recuperan aparte, tolerando el fallo
                // individual, o el CID sale con los ReportControl apuntando a la nada.
                IEC61850Client.LecturaDataSets ds = ctx.getClient().recuperarDataSetsTolerante();
                if (ds.recuperados > 0 || ds.omitidos > 0) {
                    ctx.log(I18n.t("log.cid.dsleidos", ds.recuperados, ds.omitidos));
                }
            }

            String host = (ctx.getClient() != null) ? ctx.getClient().getHost() : null;
            // Fabricante / tipo / configRev: del nameplate que ya se leyó al conectar
            String mfr = null, tipo = null, cfg = null;
            String[] np = ctx.getLoadedIedNameplate();
            if (np != null) {
                if (np.length > 1) mfr  = np[1];
                if (np.length > 2) tipo = np[2];
                if (np.length > 3) cfg  = np[3];
            }

            SclExporter.Result r = SclExporter.export(modelo, host, iedName, mfr, tipo, cfg);
            try (java.io.Writer w = new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(file), java.nio.charset.StandardCharsets.UTF_8)) {
                w.write(r.xml);
            }

            ctx.log(I18n.t("log.cid.generated", file.getAbsolutePath(),
                    r.logicalDevices, r.logicalNodes, r.dataObjects));
            if (!r.uncertainCdc.isEmpty()) {
                ctx.log(I18n.t("log.cid.uncertain", r.uncertainCdc.size()));
                for (String u : r.uncertainCdc) ctx.log("    - " + u);
            }
            if (!r.datSetsColgados.isEmpty()) {
                ctx.log(I18n.t("log.cid.dscolgados", r.datSetsColgados.size()));
                for (String d : r.datSetsColgados) ctx.log("    - " + d);
            }

            StringBuilder msg = new StringBuilder();
            msg.append(I18n.t("cid.gen.ok", file.getAbsolutePath())).append("\n\n");
            msg.append(I18n.t("cid.gen.stats", r.logicalDevices, r.logicalNodes,
                              r.dataObjects, r.dataSets, r.reportControls)).append("\n");
            msg.append(I18n.t("cid.gen.templates", r.lnodeTypes, r.doTypes, r.daTypes)).append("\n");
            if (lectura != null) {
                msg.append(I18n.t("cid.gen.values", lectura.leidos, lectura.total,
                                  lectura.fallidos)).append("\n");
            }
            msg.append("\n");
            if (!r.uncertainCdc.isEmpty()) {
                msg.append(I18n.t("cid.gen.uncertain", r.uncertainCdc.size())).append("\n\n");
            }
            if (!r.datSetsColgados.isEmpty()) {
                msg.append(I18n.t("cid.gen.dscolgados", r.datSetsColgados.size())).append("\n\n");
            }
            msg.append(I18n.t("cid.gen.limits"));
            final String resumen = msg.toString();
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                ctx.parentWindow(), resumen,
                I18n.t("cid.save.ok.title"), JOptionPane.INFORMATION_MESSAGE));

        } catch (Exception e) {
            ctx.log(I18n.t("log.cid.error", e.getMessage()));
            e.printStackTrace();
            final String detalleError = String.valueOf(e.getMessage());
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                ctx.parentWindow(), I18n.t("cid.save.error", detalleError),
                "Error", JOptionPane.ERROR_MESSAGE));
        }
    }

    /** Diálogo de guardado con el filtro SCL, la extensión asegurada y aviso de sobrescritura. */
    private File pedirDestino(String nombreSugerido) {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle(I18n.t("cid.save.dialog.title"));
        fc.setSelectedFile(new File(nombreSugerido));
        fc.setFileFilter(new FileFilter() {
            public boolean accept(File f) {
                if (f.isDirectory()) return true;
                String n = f.getName().toLowerCase();
                return n.endsWith(".cid") || n.endsWith(".icd") || n.endsWith(".scl");
            }
            public String getDescription() { return "SCL Files (*.cid, *.icd, *.scl)"; }
        });
        if (fc.showSaveDialog(ctx.parentWindow()) != JFileChooser.APPROVE_OPTION) return null;
        File file = fc.getSelectedFile();
        String n = file.getName().toLowerCase();
        if (!n.endsWith(".cid") && !n.endsWith(".icd") && !n.endsWith(".scl")) {
            file = new File(file.getAbsolutePath() + ".cid");
        }
        if (file.exists()) {
            int ow = JOptionPane.showConfirmDialog(ctx.parentWindow(),
                I18n.t("cid.save.overwrite", file.getName()),
                I18n.t("cid.save.overwrite.title"), JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
            if (ow != JOptionPane.YES_OPTION) return null;
        }
        return file;
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
            ctx.updateStatus(false, I18n.t("status.analyzingscl"));
            ctx.setStatusIndicatorConnecting();

            ctx.backgroundExecutor().submit(() -> {
                try {
                    ctx.log(I18n.t("log.cm.analyzing", file.getName(), (file.length()/1024)));

                    // Primero obtener lista de IEDs disponibles
                    List<String> availableIEDs = ctx.getServer().getAvailableIEDs(file.getAbsolutePath());
                    int iedCount = availableIEDs.size();
                    ctx.log(I18n.t("log.cm.iedsfound", iedCount));

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
                                ctx.updateStatus(false, I18n.t("status.loadcancelled"));
                                ctx.setLblFileName("");
                            });
                            return;
                        }
                        selectedIED = selection[0];
                        ctx.log(I18n.t("log.cm.iedselected", availableIEDs.get(selectedIED), selectedIED));
                    }

                    // Cargar el IED seleccionado
                    SwingUtilities.invokeLater(() -> ctx.updateStatus(false, I18n.t("status.loadingied")));
                    long startTime = System.currentTimeMillis();

                    boolean success = ctx.getServer().loadSclFileWithIED(file.getAbsolutePath(), selectedIED);

                    // Parsear GoCBs del SCL (filtrado por IED seleccionado)
                    ctx.parseGoCBsFromScl(file, selectedIED);

                    long elapsed = System.currentTimeMillis() - startTime;
                    // String.valueOf y no el long: MessageFormat le pondría separador de miles.
                    ctx.log(I18n.t("log.cm.parsingdone", String.valueOf(elapsed), success));
                    ctx.log(I18n.t("log.cm.gocbsfoundscl", ctx.getSclGoCBs().size()));

                    final int finalSelectedIED = selectedIED;
                    SwingUtilities.invokeLater(() -> {
                        try {
                            if (success) {
                                ctx.setBtnStartStopEnabled(true);
                                String iedName = availableIEDs.size() > finalSelectedIED ?
                                    availableIEDs.get(finalSelectedIED) : "IED";
                                ctx.updateStatus(false, I18n.t("status.sclloaded", iedName));
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
                                    ctx.log(I18n.t("log.cm.nameplate", mfr, type, cfgV.trim()));
                                    // Inyectar nameplate en los nodos FC=DC del modelo servido
                                    // para que clientes que lean via MMS obtengan los datos reales
                                    ctx.getServer().injectNameplate(np[0], np[1], np[3]);
                                }
                                ctx.log(I18n.t("log.cm.buildingtree"));
                                ctx.displayServerModel();
                                ctx.log(I18n.t("log.cm.sclloadedok"));
                                // Actualizar GoCBs automaticamente
                                ctx.refreshGooseControlBlocks();
                            } else {
                                ctx.updateStatus(false, I18n.t("status.sclerror"));
                                ctx.log(I18n.t("log.cm.sclloadfailed"));
                            }
                        } catch (Exception e) {
                            ctx.log(I18n.t("log.cm.uierror", e.getMessage()));
                            e.printStackTrace();
                        }
                    });
                } catch (Exception e) {
                    ctx.log(I18n.t("log.cm.bgerror", e.getMessage()));
                    e.printStackTrace();
                    SwingUtilities.invokeLater(() -> {
                        ctx.updateStatus(false, I18n.t("err.title") + ": " + e.getMessage());
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
            ctx.updateStatus(false, I18n.t("status.simstopped"));
            ctx.updateConnectionInfo("", 0);
            ctx.log(I18n.t("log.cm.simstopped"));
        } else {
            try {
                int port = Integer.parseInt(ctx.getTfServerPort().trim());
                ctx.updateStatus(false, I18n.t("status.simstarting"));
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
                            ctx.updateStatus(true, I18n.t("status.simactive"));
                            ctx.updateConnectionInfo(localIp + " (servidor)", finalPort);
                            ctx.log(I18n.t("log.cm.simactive"));
                            ctx.log(I18n.t("log.cm.ipport", localIp, finalPort));
                            ctx.log(I18n.t("log.cm.connectclientto", localIp, finalPort));

                            // Auto-seleccionar interfaz de red para GOOSE (igual que en modo cliente)
                            ctx.autoSelectGooseInterface(localIp);
                        } else {
                            ctx.updateStatus(false, I18n.t("status.simerror"));
                            ctx.updateConnectionInfo("", 0);
                            ctx.log(I18n.t("log.cm.srvstartfailed"));
                            ctx.log(I18n.t("log.cm.checkport", finalPort));
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

    /**
     * Duración legible y sin sorpresas de locale.
     *
     * {@link I18n#t(String, Object...)} pasa por {@code MessageFormat}, que formatea los números
     * con el locale de la interfaz. Con es-PY un {@code {0}} de 22251 salía "22.251ms": el punto
     * es separador de miles, pero se lee como decimal, así que una conexión de 22 segundos
     * quedaba registrada de un modo que se lee como 22 milisegundos. Pasó de verdad, y sobre un
     * registro que después se usa como evidencia.
     *
     * Por eso el número se arma acá, con {@link Locale#ROOT}, y el patrón traducido recibe el
     * texto ya hecho —incluida la unidad—. Arriba del segundo se muestran las dos escalas: los
     * segundos para leer de un vistazo, y los milisegundos porque son los que se comparan entre
     * sesiones.
     */
    static String formatDuration(long ms) {
        if (ms < 1000) return ms + " ms";
        return String.format(Locale.ROOT, "%.2f s (%d ms)", ms / 1000.0, ms);
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
        ctx.updateStatus(false, I18n.t("status.connecting", host, port));
        ctx.setStatusIndicatorConnecting();
        ctx.log(I18n.t("log.cm.connectingto", host, port));

        ctx.backgroundExecutor().submit(() -> {
            try {
                ctx.log(I18n.t("log.cm.initiatingconn"));
                long startTime = System.currentTimeMillis();

                ctx.getClient().setConnectionTimeoutMs(ctx.getConnectionTimeoutMs());
                ctx.getClient().connect(host, port);

                long elapsed = System.currentTimeMillis() - startTime;
                ctx.log(I18n.t("log.cm.connestablished", formatDuration(elapsed)));

                // Detectar interfaz local usada para la conexion
                String localIp = detectLocalInterface(host);
                ctx.log(I18n.t("log.cm.localifacedetected", localIp));

                final String finalHost = host;
                final int finalPort = port;
                final String finalLocalIp = localIp;
                SwingUtilities.invokeLater(() -> {
                    try {
                        ctx.setConnected(true);
                        currentHost = finalHost;
                        currentPort = finalPort;
                        connectedLocalIp = finalLocalIp;
                        ctx.setBtnConnectText(I18n.t("btn.disconnect"));
                        ctx.setBtnConnectEnabled(true);
                        ctx.setCbPollingEnabled(true);
                        ctx.setSpinnerIntervalEnabled(true);
                        ctx.updateStatus(true, I18n.t("status.connected"));
                        ctx.updateConnectionInfo(finalHost, finalPort);
                        ctx.log(I18n.t("log.cm.buildingmodeltree"));
                        ctx.displayClientModel();
                        ctx.log(I18n.t("log.cm.connectedmodelreceived"));

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
                                ctx.log(I18n.t("log.cm.iedplate", finalInfo.trim()));
                            });
                        });

                        // Auto-seleccionar interfaz de red para GOOSE
                        ctx.autoSelectGooseInterface(finalLocalIp);

                        // Auto-descargar CID para obtener GoCBs (en background)
                        autoDownloadCid();
                    } catch (Exception e) {
                        ctx.log(I18n.t("log.cm.uierrorafterconn", e.getMessage()));
                        e.printStackTrace();
                    }
                });

            } catch (Exception e) {
                String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                if (errMsg.startsWith("SCL_FALLBACK:")) {
                    // El IED rechazó retrieveModel() por DataSet inexistente, pero la asociación MMS
                    // sigue activa. Pedimos al usuario un archivo SCL local para usarlo como modelo.
                    ctx.log(I18n.t("log.cm.incompletemodel"));
                    ctx.log(I18n.t("log.cm.loadinglocalscl"));

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
                            ctx.updateStatus(false, I18n.t("status.conncancelled"));
                            ctx.log(I18n.t("log.cm.conncancelleduser"));
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
                            ctx.updateStatus(false, I18n.t("status.conncancelled"));
                            ctx.log(I18n.t("log.cm.sclselcancelled"));
                            return;
                        }

                        File sclFile = fc.getSelectedFile();
                        ctx.log(I18n.t("log.cm.parsingscl", sclFile.getName()));
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
                                            ctx.updateStatus(false, I18n.t("status.conncancelled"));
                                        });
                                        return;
                                    }
                                    iedIdx = sel[0];
                                }

                                ServerModel model = tmpServer.getMergedModel(iedIdx);
                                if (model == null) throw new Exception("No se pudo obtener el modelo del IED " + iedIdx);

                                boolean ok = ctx.getClient().attachExternalModel(model);
                                if (!ok) throw new Exception("attachExternalModel falló");

                                ctx.log(I18n.t("log.cm.sclmodelinjected", iedNames.get(iedIdx), sclFile.getName()));

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
                                        ctx.setBtnConnectText(I18n.t("btn.disconnect"));
                                        ctx.setBtnConnectEnabled(true);
                                        ctx.setCbPollingEnabled(true);
                                        ctx.setSpinnerIntervalEnabled(true);
                                        ctx.updateStatus(true, I18n.t("status.connected.sclmodel"));
                                        ctx.updateConnectionInfo(fHost, fPort);
                                        ctx.log(I18n.t("log.cm.buildingtreefromscl"));
                                        ctx.displayClientModel();
                                        ctx.log(I18n.t("log.cm.connectedsclmodel"));
                                        ctx.autoSelectGooseInterface(finalLocalIp);
                                        ctx.refreshGooseControlBlocks();
                                    } catch (Exception uiEx) {
                                        ctx.log(I18n.t("log.cm.uierrorfallback", uiEx.getMessage()));
                                    }
                                });
                            } catch (Exception ex) {
                                ctx.getClient().cancelPendingAssociation();
                                ctx.log(I18n.t("log.cm.fallbackerror", ex.getMessage()));
                                ex.printStackTrace();
                                SwingUtilities.invokeLater(() -> {
                                    ctx.setBtnConnectEnabled(true);
                                    ctx.updateStatus(false, I18n.t("err.title") + ": " + ex.getMessage());
                                });
                            }
                        });
                    });

                } else {
                    ctx.log(I18n.t("log.cm.connerror", e.getClass().getSimpleName(), errMsg));
                    e.printStackTrace();
                    SwingUtilities.invokeLater(() -> {
                        ctx.setBtnConnectEnabled(true);
                        ctx.updateStatus(false, I18n.t("err.title") + ": " + errMsg);
                    });
                }
            }
        });
    }

    private void disconnect() {
        ctx.stopPolling();
        ctx.getClient().disconnect();
        handleDisconnect();
        ctx.log(I18n.t("log.cm.disconnected"));
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
        ctx.updateStatus(false, I18n.t("status.disconnected"));
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
            ctx.log(I18n.t("log.cm.localifaceerror", e.getMessage()));
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
