package com.iednavigator.bridge;

import com.beanit.iec61850bean.Array;
import com.beanit.iec61850bean.BasicDataAttribute;
import com.beanit.iec61850bean.ConstructedDataAttribute;
import com.beanit.iec61850bean.FcModelNode;
import com.beanit.iec61850bean.LogicalDevice;
import com.beanit.iec61850bean.LogicalNode;
import com.beanit.iec61850bean.ModelNode;
import com.beanit.iec61850bean.ServerModel;
import com.iednavigator.IEC61850Client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serializa un ServerModel de iec61850bean a un árbol JSON-friendly
 * (mapas anidados). Esqueleto completo: nombre, referencia, tipo de nodo,
 * FC y tipo básico. Los valores actuales solo se incluyen si ya están
 * en memoria (lazy: el frontend los pide vía read/watchlist).
 */
public final class ModelSerializer {

    private final IEC61850Client typeHelper = new IEC61850Client();

    public Map<String, Object> serialize(ServerModel model, String iedName) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("iedName", iedName);
        List<Map<String, Object>> lds = new ArrayList<>();
        if (model.getChildren() != null) {
            for (ModelNode ld : model.getChildren()) {
                lds.add(serializeNode(ld));
            }
        }
        root.put("logicalDevices", lds);
        return root;
    }

    private Map<String, Object> serializeNode(ModelNode node) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("name", node.getName());
        dto.put("ref", node.getReference().toString());
        dto.put("kind", kindOf(node));

        if (node instanceof FcModelNode) {
            FcModelNode fcNode = (FcModelNode) node;
            if (fcNode.getFc() != null) {
                dto.put("fc", fcNode.getFc().toString());
            }
        }

        if (node instanceof BasicDataAttribute) {
            dto.put("type", typeHelper.getValueType(node));
            String v = ((BasicDataAttribute) node).getValueString();
            if (v != null && !v.isEmpty()) {
                dto.put("value", typeHelper.formatValue(node));
            }
            return dto; // hoja
        }

        if (node.getChildren() != null && !node.getChildren().isEmpty()) {
            List<Map<String, Object>> children = new ArrayList<>();
            for (ModelNode child : node.getChildren()) {
                children.add(serializeNode(child));
            }
            dto.put("children", children);
        }
        return dto;
    }

    private String kindOf(ModelNode node) {
        if (node instanceof LogicalDevice) return "LD";
        if (node instanceof LogicalNode) return "LN";
        if (node instanceof BasicDataAttribute) return "DA";
        if (node instanceof Array) return "ARRAY";
        if (node instanceof ConstructedDataAttribute) return "CDA";
        if (node instanceof FcModelNode) return "DO";
        return "NODE";
    }
}
