package com.iedexplorer;

import javax.swing.*;
import javax.swing.tree.*;
import java.awt.Component;
import java.util.Map;

/**
 * Renderer para el árbol Data Model (pestaña Data Model del cliente).
 * Extraído de IEDExplorerApp.java — Fase F1b de refactorización.
 * Aplica iconos según el tipo de nodo (LD/LN/DO/DA/BDA).
 */
class DataModelTreeCellRenderer extends DefaultTreeCellRenderer {

    private final Map<String, Icon> iconCache;

    DataModelTreeCellRenderer(Map<String, Icon> iconCache) {
        this.iconCache = iconCache;
    }

    @Override
    public Component getTreeCellRendererComponent(JTree tree, Object value,
            boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus) {
        super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);

        if (value instanceof javax.swing.tree.DefaultMutableTreeNode) {
            Object userObj = ((javax.swing.tree.DefaultMutableTreeNode) value).getUserObject();
            if (userObj instanceof DataModelNodeInfo) {
                DataModelNodeInfo info = (DataModelNodeInfo) userObj;

                switch (info.type) {
                    case "LD":
                        setIcon(iconCache.get("ld"));
                        break;
                    case "LN":
                        String name = info.node.getName().toUpperCase();
                        if (name.startsWith("XCBR") || name.startsWith("XSWI")) {
                            setIcon(iconCache.get("ln_xcbr"));
                        } else if (name.startsWith("MMXU") || name.startsWith("MMTR")) {
                            setIcon(iconCache.get("ln_mmxu"));
                        } else {
                            setIcon(iconCache.get("ln_default"));
                        }
                        break;
                    case "DO":
                        setIcon(iconCache.get("do"));
                        break;
                    case "DA":
                    case "BDA":
                        setIcon(iconCache.get("da"));
                        break;
                }
            }
        }
        return this;
    }
}
