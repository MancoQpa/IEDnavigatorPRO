package com.iednavigator;

import com.beanit.iec61850bean.*;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.util.*;

/**
 * Árbol y panel Propiedades con armónicos expuestos como arreglo (Array de iec61850bean), como
 * los entrega el ION 7400 por MMS: MHAI1.HA.phsAHar(0..n), cada elemento {cVal.mag.f, q, t}.
 *
 * Un Array convierte el nombre de un hijo a índice: pedirle "q" o "mag" por nombre lanza
 * NumberFormatException (así se colgó el monitor por paneles el 2026-11-20). Esta prueba
 * construye el árbol con el código de la app y, para cada nodo, dibuja su fila, arma su
 * Propiedades y su ícono, y verifica que nada lance excepción.
 *
 * Uso: java -cp "classes;lib\*;test" com.iednavigator.TestArbolArmonicos
 */
public class TestArbolArmonicos {

    private static int ok, falla;

    private static void chk(String que, boolean cond) {
        if (cond) { ok++; System.out.println("OK     " + que); }
        else { falla++; System.out.println("FALLA  " + que); }
    }

    public static void main(String[] args) throws Exception {
        ServerModel modelo = modelo();
        DefaultMutableTreeNode raiz = new DefaultMutableTreeNode("IED");
        DefaultTreeModel tm = new DefaultTreeModel(raiz);
        Map<String, DefaultMutableTreeNode> mapa = new HashMap<>();
        JTree arbol = new JTree(tm);
        List<String> log = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> ModelTreeBuilder.buildTree(modelo, raiz, tm, mapa,
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), arbol, log::add, (n, r) -> r));

        List<DefaultMutableTreeNode> nodos = new ArrayList<>();
        Enumeration<?> e = raiz.depthFirstEnumeration();
        while (e.hasMoreElements()) nodos.add((DefaultMutableTreeNode) e.nextElement());
        boolean hayArreglo = false, hayElemento = false;
        for (DefaultMutableTreeNode n : nodos) {
            if (n.getUserObject() instanceof NodeInfo) {
                ModelNode m = ((NodeInfo) n.getUserObject()).node;
                if (m instanceof Array) hayArreglo = true;
                if (m != null && m.getParent() instanceof Array) hayElemento = true;
            }
        }
        chk("el árbol tiene " + nodos.size() + " nodos, con el arreglo y sus elementos", hayArreglo && hayElemento);

        ArbolCeldaRenderer r = new ArbolCeldaRenderer(new HashSet<>(), () -> null, new HashMap<>());
        Map<String, Icon> iconos = new HashMap<>();
        List<String> errores = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            for (DefaultMutableTreeNode n : nodos) {
                if (!(n.getUserObject() instanceof NodeInfo)) continue;
                NodeInfo info = (NodeInfo) n.getUserObject();
                String quien = info.node == null ? String.valueOf(info) : info.node.getReference().toString();
                for (boolean sel : new boolean[]{false, true}) {
                    try {
                        r.getTreeCellRendererComponent(arbol, n, sel, true, n.isLeaf(), 0, sel);
                    } catch (RuntimeException ex) {
                        errores.add("fila " + quien + ": " + ex);
                    }
                }
                try { PanelPropiedades.filas(info, r); } catch (RuntimeException ex) { errores.add("propiedades " + quien + ": " + ex); }
                try { SimbolosArbol.icono(info, iconos); } catch (RuntimeException ex) { errores.add("ícono " + quien + ": " + ex); }
                // textoValor como lo llaman el renderer y Propiedades: solo con valor
                try { if (info.value != null && !info.value.isEmpty()) r.textoValor(info); r.cdcDe(info); } catch (RuntimeException ex) { errores.add("valor/CDC " + quien + ": " + ex); }
            }
        });
        for (String s : errores) System.out.println("       " + s);
        chk("filas del árbol, Propiedades, íconos y CDC sin excepciones", errores.isEmpty());

        // Propiedades del elemento 0 (no disponible: 3,4E38, calidad invalid)
        DefaultMutableTreeNode el0 = null;
        for (DefaultMutableTreeNode n : nodos) {
            if (n.getUserObject() instanceof NodeInfo && ((NodeInfo) n.getUserObject()).node != null
                    && ((NodeInfo) n.getUserObject()).node.getReference().toString().endsWith("phsAHar(0).cVal.mag.f")) el0 = n;
        }
        chk("está el valor del elemento 0 en el árbol", el0 != null);
        if (el0 != null) {
            List<PanelPropiedades.Fila> f = PanelPropiedades.filas((NodeInfo) el0.getUserObject(), r);
            chk("Propiedades del valor del elemento 0 tiene filas (" + f.size() + ")", !f.isEmpty());
        }

        System.out.println();
        System.out.println(ok + " pasaron, " + falla + " fallaron");
        if (falla == 0) System.out.println("TODO OK");
        System.exit(falla == 0 ? 0 : 1);
    }

    /** ION/MHAI1 con HA (MX): phsAHar como Array de 3 elementos {cVal.mag.f, q, t}. */
    private static ServerModel modelo() {
        List<FcModelNode> elems = new ArrayList<>();
        float[] v = {3.4028235E38f, 1.0f, 0.003f};
        for (int i = 0; i < 3; i++) {
            String b = "IONLD0/MHAI1.HA.phsAHar(" + i + ")";
            BdaFloat32 f = new BdaFloat32(new ObjectReference(b + ".cVal.mag.f"), Fc.MX, "", false, false);
            f.setFloat(v[i]);
            ConstructedDataAttribute mag = new ConstructedDataAttribute(new ObjectReference(b + ".cVal.mag"), Fc.MX, lista(f));
            ConstructedDataAttribute cVal = new ConstructedDataAttribute(new ObjectReference(b + ".cVal"), Fc.MX, lista(mag));
            BdaQuality q = new BdaQuality(new ObjectReference(b + ".q"), Fc.MX, "", false);
            if (i == 0) q.setValidity(BdaQuality.Validity.INVALID);
            BdaTimestamp t = new BdaTimestamp(new ObjectReference(b + ".t"), Fc.MX, "", false, false);
            elems.add(new ConstructedDataAttribute(new ObjectReference(b), Fc.MX, lista(cVal, q, t)));
        }
        Array arr = new Array(new ObjectReference("IONLD0/MHAI1.HA.phsAHar"), Fc.MX, elems);
        FcDataObject ha = new FcDataObject(new ObjectReference("IONLD0/MHAI1.HA"), Fc.MX, lista(arr));
        LogicalNode ln = new LogicalNode(new ObjectReference("IONLD0/MHAI1"), new ArrayList<>(Collections.singletonList(ha)));
        LogicalDevice ld = new LogicalDevice(new ObjectReference("IONLD0"), new ArrayList<>(Collections.singletonList(ln)));
        return new ServerModel(new ArrayList<>(Collections.singletonList(ld)), new ArrayList<>());
    }

    private static List<FcModelNode> lista(FcModelNode... n) {
        return new ArrayList<>(Arrays.asList(n));
    }
}
