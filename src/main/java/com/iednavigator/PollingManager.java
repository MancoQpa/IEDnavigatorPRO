package com.iednavigator;

import com.beanit.iec61850bean.*;

import javax.swing.*;
import javax.swing.tree.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

/**
 * Fase 8: Extrae la sección POLLING de IEDNavigatorApp.
 * Gestiona el ciclo de polling periódico y la actualización de nodos en el árbol.
 */
class PollingManager {

    // ─── Context interface ────────────────────────────────────────────────────────────

    interface Context {
        void log(String msg);
        boolean isConnected();
        IEC61850Client getClient();
        Set<String> getWatchlist();
        DefaultMutableTreeNode getRootNode();
        JTree getModelTree();
        DefaultTreeModel getTreeModel();
        /** Formatea un valor BDA respetando enums SCL (delega a GoosePanel). */
        String formatEnumValue(ModelNode node, String rawValue);
        /** Llama updateMonitorValues() en IEDNavigatorApp (actualiza tabla Activity Monitor). */
        void updateMonitorValues();
        /** Valor actual del spinner de intervalo (ms). */
        int getPollingInterval();
        /** Executor de background para operaciones asíncronas. */
        ExecutorService backgroundExecutor();
    }

    // ─── Fields ───────────────────────────────────────────────────────────────────────

    private final Context ctx;
    private ScheduledExecutorService pollExecutor;

    /** Fallo de transporte detectado en el ciclo en curso; null si el ciclo fue limpio. */
    private Exception linkFailure;
    /** Evita repetir el aviso de "sin nodos que leer" en cada vuelta del ciclo. */
    private boolean idleReported;
    /** Evita repetir el aviso de lectura fallida en cada vuelta del ciclo. */
    private boolean readFailReported;

    // ─── Constructor ─────────────────────────────────────────────────────────────────

    PollingManager(Context ctx) {
        this.ctx = ctx;
    }

    // ─── Public API ───────────────────────────────────────────────────────────────────

    void toggle(boolean start) {
        if (start) {
            start();
        } else {
            stop();
        }
    }

    void start() {
        if (pollExecutor != null) {
            pollExecutor.shutdown();
        }

        int interval = ctx.getPollingInterval();
        pollExecutor = Executors.newSingleThreadScheduledExecutor();

        pollExecutor.scheduleAtFixedRate(() -> {
            if (ctx.isConnected()) {
                try {
                    refreshAllValues();
                } catch (Exception e) {
                    ctx.log(I18n.t("log.polling.error", e.getMessage()));
                }
            }
        }, 0, interval, TimeUnit.MILLISECONDS);

        // String.valueOf y no el int: MessageFormat mostraba "2.000ms" para 2000 ms.
        ctx.log(I18n.t("log.polling.started", String.valueOf(interval)));
    }

    void stop() {
        if (pollExecutor != null) {
            pollExecutor.shutdown();
            pollExecutor = null;
            ctx.log(I18n.t("log.polling.stopped"));
        }
    }

    // ─── Private implementation ───────────────────────────────────────────────────────

    private void refreshAllValues() {
        IEC61850Client client = ctx.getClient();
        if (client == null) return;
        ServerModel model = client.getServerModel();
        if (model == null) return;

        int readCount = 0;
        Set<String> watchlist = ctx.getWatchlist();
        linkFailure = null;

        if (!watchlist.isEmpty()) {
            readCount = pollWatchlistItems(client, model, watchlist);
        } else {
            List<FcModelNode> nodesToRead = new ArrayList<>();
            collectVisibleNodes(ctx.getRootNode(), nodesToRead);

            for (FcModelNode node : nodesToRead) {
                try {
                    client.readNodeValues(node);
                    readCount++;
                } catch (Exception e) {
                    if (isTransportFailure(e)) { linkFailure = e; break; }
                    // Un nodo que el equipo rechaza no interrumpe el ciclo.
                }
            }
        }

        // ── Una lectura falló de un modo que sugiere que no hay enlace ──
        // Antes se descartaban todas las excepciones por igual, así que una conexión muerta
        // no producía ninguna señal: el ciclo seguía corriendo en silencio. Pero un nodo
        // puede fallar por lento o pesado sin que el enlace tenga nada de malo, así que no
        // se da por perdida la conexión: se confirma con el latido, que es una lectura
        // mínima. Si esa también falla, el problema no era el nodo.
        if (linkFailure != null) {
            final String detalle = String.valueOf(linkFailure.getMessage());
            if (!client.heartbeat()) {
                SwingUtilities.invokeLater(() -> ctx.log(I18n.t("log.polling.linkfail", detalle)));
                return;   // heartbeat() ya notificó el cierre
            }
            if (!readFailReported) {
                readFailReported = true;
                SwingUtilities.invokeLater(() -> ctx.log(I18n.t("log.polling.readfail", detalle)));
            }
        } else {
            readFailReported = false;
        }

        // ── El ciclo no tuvo nada que leer ──
        // Pasa con la watchlist vacía y el árbol colapsado: no hay atributos visibles. Sin
        // lecturas no hay tráfico, y sin tráfico una caída del IED es indetectable. Se avisa
        // una sola vez —el ciclo se repite cada pocos segundos— y se comprueba el enlace.
        if (readCount == 0) {
            if (!idleReported) {
                idleReported = true;
                SwingUtilities.invokeLater(() -> ctx.log(I18n.t("log.polling.idle")));
            }
            if (!client.heartbeat()) return;   // heartbeat() ya avisó del cierre
        } else {
            idleReported = false;
        }

        final int finalCount = readCount;
        final boolean usingWatchlist = !watchlist.isEmpty();

        SwingUtilities.invokeLater(() -> {
            updateVisibleTreeNodes(ctx.getRootNode());
            ctx.updateMonitorValues();
            if (finalCount > 0) {
                String mode = usingWatchlist ? "watchlist" : I18n.t("log.polling.mode.visible");
                ctx.log(I18n.t("log.polling.count", finalCount, mode));
            }
        });
    }

    /**
     * ¿Este fallo hace sospechar que no hay enlace, o es cosa de ese nodo?
     *
     * readNodeValues() envuelve el ServiceError en IOException, así que el tipo por sí solo
     * no alcanza. Y el ServiceError tampoco: la librería reporta el timeout de respuesta
     * como ServiceError, o sea que un "error de servicio" puede significar tanto que el
     * equipo rechazó la lectura —enlace vivo— como que no contestó nadie. Sólo los códigos
     * de {@link IEC61850Client#esFalloDeEnlace} indican lo segundo.
     *
     * Es sospecha, no veredicto: quien decide es el latido.
     */
    private static boolean isTransportFailure(Exception e) {
        if (!(e instanceof java.io.IOException)) return false;
        Throwable causa = e.getCause();
        if (causa instanceof ServiceError) {
            return IEC61850Client.esFalloDeEnlace((ServiceError) causa);
        }
        return true;   // IOException sin ServiceError debajo: falló el transporte
    }

    private int pollWatchlistItems(IEC61850Client client, ServerModel model, Set<String> watchlist) {
        int count = 0;
        for (String fullRef : watchlist) {
            try {
                int idx = fullRef.lastIndexOf("$");
                if (idx < 0) continue;

                String ref = fullRef.substring(0, idx);
                String fcStr = fullRef.substring(idx + 1);
                Fc fc = Fc.valueOf(fcStr);

                ModelNode node = model.findModelNode(ref, fc);
                if (node instanceof FcModelNode) {
                    client.readNodeValues((FcModelNode) node);
                    count++;
                }
            } catch (Exception e) {
                if (isTransportFailure(e)) { linkFailure = e; break; }
                // Un nodo que el equipo rechaza no interrumpe el ciclo.
            }
        }
        return count;
    }

    private void collectVisibleNodes(DefaultMutableTreeNode treeNode, List<FcModelNode> result) {
        JTree modelTree = ctx.getModelTree();
        DefaultMutableTreeNode rootNode = ctx.getRootNode();
        TreePath path = new TreePath(treeNode.getPath());

        if (treeNode != rootNode && !modelTree.isVisible(path)) {
            return;
        }

        Object userObj = treeNode.getUserObject();
        if (userObj instanceof NodeInfo) {
            NodeInfo info = (NodeInfo) userObj;
            if (info.node instanceof FcModelNode) {
                FcModelNode fcNode = (FcModelNode) info.node;
                Fc fc = fcNode.getFc();
                if (fc == Fc.ST || fc == Fc.MX) {
                    result.add(fcNode);
                }
            }
        }

        if (modelTree.isExpanded(path) || treeNode == rootNode) {
            for (int i = 0; i < treeNode.getChildCount(); i++) {
                DefaultMutableTreeNode child = (DefaultMutableTreeNode) treeNode.getChildAt(i);
                collectVisibleNodes(child, result);
            }
        }
    }

    // ─── Client tree operations (F27: moved from IEDNavigatorApp.java) ────────────────

    void readSelectedNode() {
        TreePath path = ctx.getModelTree().getSelectionPath();
        if (path == null) return;
        DefaultMutableTreeNode treeNode = (DefaultMutableTreeNode) path.getLastPathComponent();
        Object userObj = treeNode.getUserObject();
        if (userObj instanceof NodeInfo) {
            NodeInfo info = (NodeInfo) userObj;
            if (info.node instanceof FcModelNode) {
                try {
                    ctx.log(I18n.t("log.reading", info.node.getReference()));
                    ctx.getClient().readNodeValues((FcModelNode) info.node);
                    if (info.node instanceof BasicDataAttribute) {
                        BasicDataAttribute bda = (BasicDataAttribute) info.node;
                        info.value = bda.getValueString();
                        ctx.getTreeModel().nodeChanged(treeNode);
                        ctx.log(I18n.t("log.value", info.value));
                    } else {
                        ModelTreeBuilder.updateTreeNodeRecursive(treeNode, ctx.getTreeModel());
                        ctx.log(I18n.t("log.do.updated"));
                    }
                } catch (Exception e) {
                    ctx.log(I18n.t("log.read.error", e.getMessage()));
                }
            }
        }
    }

    FcModelNode getSelectedBlkEnaNode() {
        TreePath path = ctx.getModelTree().getSelectionPath();
        if (path == null) return null;
        Object userObj = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
        if (!(userObj instanceof NodeInfo)) return null;
        NodeInfo info = (NodeInfo) userObj;
        if (info.node == null) return null;
        if ("blkEna".equalsIgnoreCase(info.node.getName()) && "BL".equals(info.fc)) {
            return info.node instanceof FcModelNode ? (FcModelNode) info.node : null;
        }
        if ("DO".equals(info.prefix)) {
            return ctx.getClient().findBlkEnaNode(info.node.getReference().toString());
        }
        return null;
    }

    void toggleBlocking(boolean block) {
        FcModelNode blkNode = getSelectedBlkEnaNode();
        if (blkNode == null) {
            ctx.log(I18n.t("log.blk.notsupported"));
            return;
        }
        ctx.backgroundExecutor().submit(() -> {
            try {
                ctx.getClient().setBlocking(blkNode, block);
                String ref = blkNode.getReference().toString();
                SwingUtilities.invokeLater(() -> {
                    ctx.log((block ? I18n.t("log.blocked") : I18n.t("log.unblocked")) + ": " + ref);
                    TreePath path = ctx.getModelTree().getSelectionPath();
                    if (path != null) {
                        ModelTreeBuilder.updateTreeNodeRecursive(
                            (DefaultMutableTreeNode) path.getLastPathComponent(), ctx.getTreeModel());
                    }
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> ctx.log(I18n.t("log.setblocking.error", ex.getMessage())));
            }
        });
    }

    void updateVisibleTreeNodes(DefaultMutableTreeNode treeNode) {
        Object userObj = treeNode.getUserObject();
        if (userObj instanceof NodeInfo) {
            NodeInfo info = (NodeInfo) userObj;
            if (info.node instanceof BasicDataAttribute) {
                BasicDataAttribute bda = (BasicDataAttribute) info.node;
                String newValue = ctx.formatEnumValue(info.node, bda.getValueString());
                if (newValue == null) newValue = "";
                if (!newValue.equals(info.value == null ? "" : info.value)) {
                    info.value = newValue;
                    ctx.getTreeModel().nodeChanged(treeNode);
                }
            }
        }

        for (int i = 0; i < treeNode.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) treeNode.getChildAt(i);
            updateVisibleTreeNodes(child);
        }
    }
}
