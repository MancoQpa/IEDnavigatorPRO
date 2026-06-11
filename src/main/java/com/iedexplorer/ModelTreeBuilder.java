package com.iedexplorer;

import com.beanit.iec61850bean.*;
import javax.swing.JTree;
import javax.swing.tree.*;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Construye y actualiza el árbol de modelo IEC 61850 (JTree / DefaultMutableTreeNode).
 * Extraído de IEDExplorerApp.java — Fase 3 de refactorización.
 *
 * Métodos originales movidos aquí:
 *   buildTree, buildTreeRecursive, createTreeNode, getNodePrefix,
 *   countNodes, countNodesRecursive, addDataSetsToLdNode, addReportsToLdNode,
 *   addReportNode, findInsertIndexAfterLLN0, addGoCBsToLdNode, addGocbProperty,
 *   addDataSetToGoCB, updateNodeValue, updateTreeValues, updateTreeValuesRecursive,
 *   clearModel.
 */
class ModelTreeBuilder {

    private ModelTreeBuilder() {}

    // -----------------------------------------------------------------------
    // Métodos públicos
    // -----------------------------------------------------------------------

    /**
     * Construye el árbol completo desde un ServerModel.
     *
     * @param model        ServerModel a visualizar
     * @param rootNode     Nodo raíz del JTree (será vaciado y rellenado)
     * @param treeModel    DefaultTreeModel para notificar cambios
     * @param nodeMap      Map ref→nodo para actualización rápida (será limpiado)
     * @param sclDataSets  DataSets parseados del SCL
     * @param sclReports   Reports parseados del SCL
     * @param sclGoCBs     GoCBs parseados del SCL
     * @param logger       Consumer<String> para logging
     * @param enumFormatter BiFunction (ModelNode, rawValue) → formattedValue
     */
    static void buildTree(
            ServerModel model,
            DefaultMutableTreeNode rootNode,
            DefaultTreeModel treeModel,
            Map<String, DefaultMutableTreeNode> nodeMap,
            List<SclDataSet> sclDataSets,
            List<SclReport>  sclReports,
            List<SclGoCB>    sclGoCBs,
            javax.swing.JTree treeComponent,
            Consumer<String> logger,
            BiFunction<ModelNode, String, String> enumFormatter) {
        try {
            if (logger != null) logger.accept("buildTree: iniciando...");
            rootNode.removeAllChildren();
            nodeMap.clear();

            int nodeCount = countNodes(model);
            if (logger != null) logger.accept("buildTree: " + nodeCount + " nodos totales");
            rootNode.setUserObject("Modelo (" + nodeCount + " nodos)");

            int ldIdx = 0;
            for (ModelNode ld : model.getChildren()) {
                ldIdx++;
                DefaultMutableTreeNode ldNode = createTreeNode(ld, "LD", enumFormatter);
                rootNode.add(ldNode);
                buildTreeRecursive(ldNode, ld, nodeMap, enumFormatter);

                String ldName = ld.getName();
                if (ldName.contains("/")) {
                    ldName = ldName.substring(ldName.indexOf("/") + 1);
                }

                addDataSetsToLdNode(ldNode, ldName, sclDataSets);
                addReportsToLdNode(ldNode, ldName, sclReports);
                addGoCBsToLdNode(ldNode, ld.getName(), sclDataSets, sclGoCBs);
            }
            if (logger != null) logger.accept("buildTree: " + ldIdx + " LDs agregados");

            treeModel.reload();
            if (logger != null) logger.accept("buildTree: arbol recargado");

            treeComponent.expandRow(0);
            if (logger != null) logger.accept("buildTree: completado");
        } catch (Exception e) {
            if (logger != null) logger.accept("ERROR en buildTree: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Actualiza los valores de BDA en el árbol sin reconstruirlo.
     */
    static void updateTreeValues(
            ServerModel model,
            Map<String, DefaultMutableTreeNode> nodeMap,
            DefaultTreeModel treeModel) {
        for (ModelNode ld : model.getChildren()) {
            updateTreeValuesRecursive(ld, nodeMap, treeModel);
        }
    }

    /**
     * Actualiza el valor de un nodo individual por referencia.
     */
    static void updateNodeValue(
            String reference,
            String value,
            Map<String, DefaultMutableTreeNode> nodeMap,
            DefaultTreeModel treeModel) {
        DefaultMutableTreeNode treeNode = nodeMap.get(reference);
        if (treeNode != null) {
            Object userObj = treeNode.getUserObject();
            if (userObj instanceof NodeInfo) {
                ((NodeInfo) userObj).value = value;
                treeModel.nodeChanged(treeNode);
            }
        }
    }

    /**
     * Actualiza un único nodo por referencia, consultando su BDA directamente.
     * Más rápido que updateTreeValues() para cambios individuales (no congela el EDT).
     * @param formatFn BiFunction(ModelNode, rawString) -> formattedString
     */
    static void updateSingleNodeInTree(
            String reference,
            Map<String, DefaultMutableTreeNode> nodeMap,
            DefaultTreeModel treeModel,
            BiFunction<ModelNode, String, String> formatFn) {

        DefaultMutableTreeNode treeNode = nodeMap.get(reference);
        if (treeNode != null && treeNode.getUserObject() instanceof NodeInfo) {
            NodeInfo info = (NodeInfo) treeNode.getUserObject();
            if (info.node instanceof BasicDataAttribute) {
                info.value = formatFn.apply(info.node, ((BasicDataAttribute) info.node).getValueString());
                treeModel.nodeChanged(treeNode);
                return;
            }
        }
        // Fallback: buscar por prefijo de referencia
        for (Map.Entry<String, DefaultMutableTreeNode> entry : nodeMap.entrySet()) {
            if (entry.getKey().startsWith(reference)) {
                DefaultMutableTreeNode node = entry.getValue();
                if (node.getUserObject() instanceof NodeInfo) {
                    NodeInfo info = (NodeInfo) node.getUserObject();
                    if (info.node instanceof BasicDataAttribute) {
                        info.value = formatFn.apply(info.node, ((BasicDataAttribute) info.node).getValueString());
                        treeModel.nodeChanged(node);
                    }
                }
            }
        }
    }

    /**
     * Limpia el árbol (raíz + nodeMap).
     */
    static void clearModel(
            DefaultMutableTreeNode rootNode,
            Map<String, DefaultMutableTreeNode> nodeMap,
            DefaultTreeModel treeModel) {
        rootNode.removeAllChildren();
        rootNode.setUserObject("Modelo");
        nodeMap.clear();
        treeModel.reload();
    }

    // -----------------------------------------------------------------------
    // Métodos privados — construcción recursiva
    // -----------------------------------------------------------------------

    private static void buildTreeRecursive(
            DefaultMutableTreeNode parent,
            ModelNode modelNode,
            Map<String, DefaultMutableTreeNode> nodeMap,
            BiFunction<ModelNode, String, String> enumFormatter) {
        Collection<ModelNode> children = modelNode.getChildren();
        if (children == null) return;

        for (ModelNode child : children) {
            String prefix = getNodePrefix(child);
            DefaultMutableTreeNode childNode = createTreeNode(child, prefix, enumFormatter);
            parent.add(childNode);

            if (child instanceof FcModelNode) {
                String ref = child.getReference().toString();
                nodeMap.put(ref, childNode);
            }

            if (!(child instanceof BasicDataAttribute)) {
                buildTreeRecursive(childNode, child, nodeMap, enumFormatter);
            }
        }
    }

    private static DefaultMutableTreeNode createTreeNode(
            ModelNode node,
            String prefix,
            BiFunction<ModelNode, String, String> enumFormatter) {
        NodeInfo info = new NodeInfo();
        info.name   = node.getName();
        info.prefix = prefix;
        info.node   = node;

        if (node instanceof FcModelNode) {
            info.fc = ((FcModelNode) node).getFc().toString();
        }

        if (node instanceof BasicDataAttribute) {
            BasicDataAttribute bda = (BasicDataAttribute) node;
            String raw = bda.getValueString();
            info.value = (enumFormatter != null) ? enumFormatter.apply(node, raw) : raw;
            info.type  = bda.getClass().getSimpleName().replace("Bda", "");
        }

        return new DefaultMutableTreeNode(info);
    }

    private static String getNodePrefix(ModelNode node) {
        if (node instanceof LogicalDevice)          return "LD";
        if (node instanceof LogicalNode)            return "LN";
        if (node instanceof FcDataObject)           return "DO";
        if (node instanceof ConstructedDataAttribute) return "SDO";
        if (node instanceof BasicDataAttribute)     return "DA";
        return "";
    }

    static int countNodes(ServerModel model) {
        int count = 0;
        for (ModelNode ld : model.getChildren()) {
            count += countNodesRecursive(ld);
        }
        return count;
    }

    private static int countNodesRecursive(ModelNode node) {
        int count = 1;
        Collection<ModelNode> children = node.getChildren();
        if (children != null) {
            for (ModelNode child : children) {
                count += countNodesRecursive(child);
            }
        }
        return count;
    }

    // -----------------------------------------------------------------------
    // Métodos privados — DataSets, Reports, GoCBs
    // -----------------------------------------------------------------------

    private static void addDataSetsToLdNode(
            DefaultMutableTreeNode ldNode,
            String ldName,
            List<SclDataSet> sclDataSets) {
        List<SclDataSet> ldDataSets = new ArrayList<>();
        for (SclDataSet ds : sclDataSets) {
            if (ds.ldInst != null && ds.ldInst.equals(ldName)) {
                ldDataSets.add(ds);
            }
        }
        if (ldDataSets.isEmpty()) return;

        NodeInfo containerInfo = new NodeInfo();
        containerInfo.name   = "DataSets (" + ldDataSets.size() + ")";
        containerInfo.prefix = "DS";
        DefaultMutableTreeNode dsContainer = new DefaultMutableTreeNode(containerInfo);

        for (SclDataSet ds : ldDataSets) {
            NodeInfo dsInfo = new NodeInfo();
            dsInfo.name   = ds.name;
            dsInfo.prefix = "DSET";
            dsInfo.value  = ds.desc != null ? ds.desc : "";
            DefaultMutableTreeNode dsNode = new DefaultMutableTreeNode(dsInfo);

            int idx = 0;
            for (String member : ds.members) {
                NodeInfo memberInfo = new NodeInfo();
                memberInfo.name   = "[" + idx + "] " + member;
                memberInfo.prefix = "FCDA";
                dsNode.add(new DefaultMutableTreeNode(memberInfo));
                idx++;
            }
            dsContainer.add(dsNode);
        }

        int insertIndex = findInsertIndexAfterLLN0(ldNode);
        ldNode.insert(dsContainer, insertIndex);
    }

    private static void addReportsToLdNode(
            DefaultMutableTreeNode ldNode,
            String ldName,
            List<SclReport> sclReports) {
        List<SclReport> ldReports = new ArrayList<>();
        for (SclReport rpt : sclReports) {
            if (rpt.ldInst != null && rpt.ldInst.equals(ldName)) {
                ldReports.add(rpt);
            }
        }
        if (ldReports.isEmpty()) return;

        List<SclReport> brcbs = new ArrayList<>();
        List<SclReport> urcbs = new ArrayList<>();
        for (SclReport rpt : ldReports) {
            if (rpt.buffered) brcbs.add(rpt);
            else              urcbs.add(rpt);
        }

        if (!brcbs.isEmpty()) {
            NodeInfo brcbInfo = new NodeInfo();
            brcbInfo.name   = "BRCB (" + brcbs.size() + ")";
            brcbInfo.prefix = "RPT";
            DefaultMutableTreeNode brcbContainer = new DefaultMutableTreeNode(brcbInfo);
            for (SclReport rpt : brcbs) addReportNode(brcbContainer, rpt);
            int insertIndex = findInsertIndexAfterLLN0(ldNode) + 1;
            ldNode.insert(brcbContainer, Math.min(insertIndex, ldNode.getChildCount()));
        }

        if (!urcbs.isEmpty()) {
            NodeInfo urcbInfo = new NodeInfo();
            urcbInfo.name   = "URCB (" + urcbs.size() + ")";
            urcbInfo.prefix = "RPT";
            DefaultMutableTreeNode urcbContainer = new DefaultMutableTreeNode(urcbInfo);
            for (SclReport rpt : urcbs) addReportNode(urcbContainer, rpt);
            int insertIndex = findInsertIndexAfterLLN0(ldNode) + 2;
            ldNode.insert(urcbContainer, Math.min(insertIndex, ldNode.getChildCount()));
        }
    }

    private static void addReportNode(DefaultMutableTreeNode container, SclReport rpt) {
        NodeInfo rptInfo = new NodeInfo();
        rptInfo.name   = rpt.name;
        rptInfo.prefix = rpt.buffered ? "BRCB" : "URCB";
        DefaultMutableTreeNode rptNode = new DefaultMutableTreeNode(rptInfo);
        addGocbProperty(rptNode, "rptID",   rpt.rptID);
        addGocbProperty(rptNode, "datSet",  rpt.datSet);
        addGocbProperty(rptNode, "confRev", String.valueOf(rpt.confRev));
        container.add(rptNode);
    }

    private static int findInsertIndexAfterLLN0(DefaultMutableTreeNode ldNode) {
        for (int i = 0; i < ldNode.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) ldNode.getChildAt(i);
            Object obj = child.getUserObject();
            if (obj instanceof NodeInfo) {
                NodeInfo info = (NodeInfo) obj;
                if ("LLN0".equals(info.name)) return i + 1;
            }
        }
        return 0;
    }

    private static void addGoCBsToLdNode(
            DefaultMutableTreeNode ldNode,
            String ldName,
            List<SclDataSet> sclDataSets,
            List<SclGoCB>    sclGoCBs) {
        if (sclGoCBs.isEmpty()) return;

        String shortLdName = ldName;
        if (ldName.contains("/")) {
            shortLdName = ldName.substring(ldName.indexOf("/") + 1);
        }

        List<SclGoCB> ldGocbs = new ArrayList<>();
        for (SclGoCB gcb : sclGoCBs) {
            if (gcb.ldInst != null && (gcb.ldInst.equals(ldName) || gcb.ldInst.equals(shortLdName))) {
                ldGocbs.add(gcb);
            }
        }
        if (ldGocbs.isEmpty()) return;

        NodeInfo containerInfo = new NodeInfo();
        containerInfo.name          = "GOOSE (" + ldGocbs.size() + ")";
        containerInfo.prefix        = "GoCB";
        containerInfo.isGocbContainer = true;
        DefaultMutableTreeNode gooseContainer = new DefaultMutableTreeNode(containerInfo);

        for (SclGoCB gcb : ldGocbs) {
            NodeInfo gcbInfo = new NodeInfo();
            gcbInfo.name   = gcb.cbName;
            gcbInfo.prefix = "GCB";
            gcbInfo.gocb   = gcb;
            gcbInfo.value  = gcb.goID != null ? gcb.goID : "";
            DefaultMutableTreeNode gcbNode = new DefaultMutableTreeNode(gcbInfo);

            addGocbProperty(gcbNode, "goID",    gcb.goID);
            addGocbProperty(gcbNode, "confRev", String.valueOf(gcb.confRev));
            addGocbProperty(gcbNode, "appID",   gcb.appID);
            if (gcb.macAddress != null) addGocbProperty(gcbNode, "MAC", gcb.macAddress);

            addDataSetToGoCB(gcbNode, gcb.datSet, shortLdName, sclDataSets);
            gooseContainer.add(gcbNode);
        }

        // Insertar el contenedor GOOSE después de LLN0 (si existe)
        int insertIndex = 0;
        for (int i = 0; i < ldNode.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) ldNode.getChildAt(i);
            Object obj = child.getUserObject();
            if (obj instanceof NodeInfo) {
                NodeInfo info = (NodeInfo) obj;
                if ("LLN0".equals(info.name)) { insertIndex = i + 1; break; }
            }
        }
        ldNode.insert(gooseContainer, Math.min(insertIndex, ldNode.getChildCount()));
    }

    private static void addGocbProperty(DefaultMutableTreeNode parent, String name, String value) {
        if (value == null || value.isEmpty()) return;
        NodeInfo propInfo = new NodeInfo();
        propInfo.name   = name;
        propInfo.prefix = "ATTR";
        propInfo.value  = value;
        parent.add(new DefaultMutableTreeNode(propInfo));
    }

    private static void addDataSetToGoCB(
            DefaultMutableTreeNode gcbNode,
            String datSetName,
            String ldInst,
            List<SclDataSet> sclDataSets) {
        if (datSetName == null || datSetName.isEmpty()) return;

        SclDataSet foundDs = null;
        for (SclDataSet ds : sclDataSets) {
            if (ds.name != null && ds.name.equals(datSetName)) {
                if (ds.ldInst == null || ds.ldInst.equals(ldInst)) {
                    foundDs = ds;
                    break;
                }
            }
        }

        NodeInfo dsInfo = new NodeInfo();
        dsInfo.name   = "datSet: " + datSetName;
        dsInfo.prefix = "DSET";
        DefaultMutableTreeNode dsNode = new DefaultMutableTreeNode(dsInfo);

        if (foundDs != null && !foundDs.members.isEmpty()) {
            int idx = 0;
            for (String member : foundDs.members) {
                NodeInfo memberInfo = new NodeInfo();
                memberInfo.name   = "[" + idx + "] " + member;
                memberInfo.prefix = "FCDA";
                dsNode.add(new DefaultMutableTreeNode(memberInfo));
                idx++;
            }
            dsInfo.value = foundDs.members.size() + " members";
        } else {
            dsInfo.value = "(no members found)";
        }

        gcbNode.add(dsNode);
    }

    // -----------------------------------------------------------------------
    // Métodos privados — actualización incremental
    // -----------------------------------------------------------------------

    private static void updateTreeValuesRecursive(
            ModelNode node,
            Map<String, DefaultMutableTreeNode> nodeMap,
            DefaultTreeModel treeModel) {
        if (node instanceof BasicDataAttribute) {
            BasicDataAttribute bda = (BasicDataAttribute) node;
            String ref = bda.getReference().toString();
            updateNodeValue(ref, bda.getValueString(), nodeMap, treeModel);
        }

        Collection<ModelNode> children = node.getChildren();
        if (children != null) {
            for (ModelNode child : children) {
                updateTreeValuesRecursive(child, nodeMap, treeModel);
            }
        }
    }

    // ─── Recursive tree update (F23: moved from IEDExplorerApp.java) ─────────

    /** Updates NodeInfo.value for all BasicDataAttribute nodes in the subtree. */
    static void updateTreeNodeRecursive(DefaultMutableTreeNode treeNode, DefaultTreeModel treeModel) {
        Object userObj = treeNode.getUserObject();
        if (userObj instanceof NodeInfo) {
            NodeInfo info = (NodeInfo) userObj;
            if (info.node instanceof BasicDataAttribute) {
                BasicDataAttribute bda = (BasicDataAttribute) info.node;
                info.value = bda.getValueString();
                treeModel.nodeChanged(treeNode);
            }
        }
        for (int i = 0; i < treeNode.getChildCount(); i++) {
            updateTreeNodeRecursive((DefaultMutableTreeNode) treeNode.getChildAt(i), treeModel);
        }
    }

    // ─── FCDA tree navigation (F22: moved from IEDExplorerApp.java) ──────────

    /**
     * Navigates the model tree to the node matching an FCDA reference.
     * FCDA format: "[idx] ldInst/prefixLNClassInst.doName.daName [FC]"
     */
    static void navigateToFcdaInModel(JTree tree, String fcdaName,
            Consumer<String> onSuccess, Consumer<String> onNotFound) {
        DefaultMutableTreeNode target = findNodeByFcda(tree, fcdaName);
        if (target != null) {
            TreePath treePath = new TreePath(target.getPath());
            tree.expandPath(treePath);
            tree.setSelectionPath(treePath);
            tree.scrollPathToVisible(treePath);
            if (onSuccess != null) onSuccess.accept("Navegando a: " + fcdaName);
        } else {
            if (onNotFound != null) onNotFound.accept("No se encontro el nodo en el modelo: " + fcdaName);
        }
    }

    /** Finds a tree node matching an FCDA reference string. Returns null if not found. */
    static DefaultMutableTreeNode findNodeByFcda(JTree tree, String fcdaName) {
        String clean = fcdaName;
        if (clean.startsWith("[")) {
            int cb = clean.indexOf(']');
            if (cb > 0) clean = clean.substring(cb + 1).trim();
        }
        int fcBracket = clean.lastIndexOf('[');
        if (fcBracket > 0) clean = clean.substring(0, fcBracket).trim();

        String[] slashParts = clean.split("/", 2);
        String ldInst    = slashParts.length > 1 ? slashParts[0] : "";
        String remainder = slashParts.length > 1 ? slashParts[1] : slashParts[0];
        String[] dotParts = remainder.split("\\.");

        DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
        return searchTreeForFcda(root, ldInst, dotParts, 0);
    }

    private static DefaultMutableTreeNode searchTreeForFcda(
            DefaultMutableTreeNode node, String ldInst, String[] parts, int depth) {
        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
            Object obj = child.getUserObject();
            if (obj instanceof NodeInfo) {
                NodeInfo info = (NodeInfo) obj;
                String nodeName = info.name != null ? info.name : "";
                if (depth == 0 && nodeName.contains(ldInst)) {
                    DefaultMutableTreeNode r = searchTreeForFcda(child, ldInst, parts, 1);
                    if (r != null) return r;
                }
                if (depth == 1 && parts.length > 0) {
                    String lnRef = parts[0];
                    if (nodeName.equalsIgnoreCase(lnRef) || nodeName.toUpperCase().contains(lnRef.toUpperCase())) {
                        if (parts.length == 1) return child;
                        DefaultMutableTreeNode r = searchTreeForFcda(child, ldInst, parts, 2);
                        if (r != null) return r;
                    }
                }
                if (depth == 2 && parts.length > 1) {
                    if (nodeName.equalsIgnoreCase(parts[1])) {
                        if (parts.length == 2) return child;
                        DefaultMutableTreeNode r = searchTreeForFcda(child, ldInst, parts, 3);
                        if (r != null) return r;
                    }
                }
                if (depth == 3 && parts.length > 2 && nodeName.equalsIgnoreCase(parts[2])) {
                    return child;
                }
            }
            if (depth == 0) {
                DefaultMutableTreeNode r = searchTreeForFcda(child, ldInst, parts, 0);
                if (r != null) return r;
            }
        }
        return null;
    }
}
