package com.iedexplorer;

import com.beanit.iec61850bean.*;

import javax.swing.*;
import javax.swing.tree.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

/**
 * Fase 8: Extrae la sección POLLING de IEDExplorerApp.
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
        /** Llama updateMonitorValues() en IEDExplorerApp (actualiza tabla Activity Monitor). */
        void updateMonitorValues();
        /** Valor actual del spinner de intervalo (ms). */
        int getPollingInterval();
        /** Executor de background para operaciones asíncronas. */
        ExecutorService backgroundExecutor();
    }

    // ─── Fields ───────────────────────────────────────────────────────────────────────

    private final Context ctx;
    private ScheduledExecutorService pollExecutor;

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
                    ctx.log("Polling error: " + e.getMessage());
                }
            }
        }, 0, interval, TimeUnit.MILLISECONDS);

        ctx.log("Polling iniciado (cada " + interval + "ms)");
    }

    void stop() {
        if (pollExecutor != null) {
            pollExecutor.shutdown();
            pollExecutor = null;
            ctx.log("Polling detenido");
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
                    // Continuar con otros nodos
                }
            }
        }

        final int finalCount = readCount;
        final boolean usingWatchlist = !watchlist.isEmpty();

        SwingUtilities.invokeLater(() -> {
            updateVisibleTreeNodes(ctx.getRootNode());
            ctx.updateMonitorValues();
            if (finalCount > 0) {
                String mode = usingWatchlist ? "watchlist" : "visibles";
                ctx.log("Polling: " + finalCount + " nodos (" + mode + ")");
            }
        });
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
                // Continuar con otros
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

    // ─── Client tree operations (F27: moved from IEDExplorerApp.java) ────────────────

    void readSelectedNode() {
        TreePath path = ctx.getModelTree().getSelectionPath();
        if (path == null) return;
        DefaultMutableTreeNode treeNode = (DefaultMutableTreeNode) path.getLastPathComponent();
        Object userObj = treeNode.getUserObject();
        if (userObj instanceof NodeInfo) {
            NodeInfo info = (NodeInfo) userObj;
            if (info.node instanceof FcModelNode) {
                try {
                    ctx.log("Leyendo: " + info.node.getReference());
                    ctx.getClient().readNodeValues((FcModelNode) info.node);
                    if (info.node instanceof BasicDataAttribute) {
                        BasicDataAttribute bda = (BasicDataAttribute) info.node;
                        info.value = bda.getValueString();
                        ctx.getTreeModel().nodeChanged(treeNode);
                        ctx.log("Valor: " + info.value);
                    } else {
                        ModelTreeBuilder.updateTreeNodeRecursive(treeNode, ctx.getTreeModel());
                        ctx.log("DO actualizado con hijos");
                    }
                } catch (Exception e) {
                    ctx.log("Error leyendo: " + e.getMessage());
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
            ctx.log("Este nodo no soporta FC=BL (blkEna no encontrado)");
            return;
        }
        ctx.backgroundExecutor().submit(() -> {
            try {
                ctx.getClient().setBlocking(blkNode, block);
                String ref = blkNode.getReference().toString();
                SwingUtilities.invokeLater(() -> {
                    ctx.log((block ? "Bloqueado" : "Desbloqueado") + ": " + ref);
                    TreePath path = ctx.getModelTree().getSelectionPath();
                    if (path != null) {
                        ModelTreeBuilder.updateTreeNodeRecursive(
                            (DefaultMutableTreeNode) path.getLastPathComponent(), ctx.getTreeModel());
                    }
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> ctx.log("Error setBlocking: " + ex.getMessage()));
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
