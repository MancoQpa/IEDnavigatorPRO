package com.iednavigator;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.TreeModelEvent;
import javax.swing.event.TreeModelListener;
import javax.swing.tree.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Busqueda en el arbol del modelo (rediseño de 2026-11): filtra por nombre o por ruta
 * ("PDOP1.Beh.stVal").
 *
 * Un CID grande tiene mas de cien mil nodos. Para no trabar la ventana:
 *   - se espera a que se deje de escribir (250 ms) antes de buscar;
 *   - la busqueda y el armado del resultado corren en segundo plano (el executor de la
 *     ventana); en el hilo de Swing solo se pone el modelo y se expande. Una busqueda vieja
 *     que termina despues de una nueva se descarta (generacion);
 *   - se busca sobre un indice de rutas armado una vez por modelo (se rehace solo cuando
 *     cambia la estructura del arbol);
 *   - el resultado se muestra en un modelo aparte con copias livianas de los nodos que
 *     coinciden y sus ancestros; el arbol real no se toca. Las copias comparten el NodeInfo,
 *     asi que los menus, el arrastre al monitor y los valores siguen funcionando igual.
 */
final class BuscadorArbol {

    private static final int MAX_COINCIDENCIAS = 400;
    private static final int MAX_NODOS_COPIADOS = 6000;
    private static final int MAX_EXPANDIDOS = 60;

    private final JTree arbol;
    private final DefaultTreeModel original;
    private final JTextField campo;
    private final Consumer<String> aviso;
    private final javax.swing.Timer espera;
    private final Executor fondo;
    private final AtomicInteger generacion = new AtomicInteger();
    private volatile List<Object[]> indice;
    /** Copia mostrada -> nodo real. Se reemplaza entero (en el hilo de Swing) con cada resultado. */
    private Map<TreeNode, DefaultMutableTreeNode> copiaAOriginal = new HashMap<>();
    private boolean filtrando;

    /**
     * @param fondo donde corre la busqueda (el backgroundExecutor de la ventana)
     * @param aviso recibe "N coincidencias" (o "" al limpiar)
     */
    BuscadorArbol(JTree arbol, DefaultTreeModel original, JTextField campo, Executor fondo, Consumer<String> aviso) {
        this.arbol = arbol;
        this.original = original;
        this.campo = campo;
        this.fondo = fondo;
        this.aviso = aviso;
        espera = new javax.swing.Timer(250, e -> aplicar(campo.getText()));
        espera.setRepeats(false);
        campo.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { espera.restart(); }
            @Override public void removeUpdate(DocumentEvent e) { espera.restart(); }
            @Override public void changedUpdate(DocumentEvent e) { espera.restart(); }
        });
        original.addTreeModelListener(new TreeModelListener() {
            @Override public void treeNodesChanged(TreeModelEvent e) { }
            @Override public void treeNodesInserted(TreeModelEvent e) { invalidar(); }
            @Override public void treeNodesRemoved(TreeModelEvent e) { invalidar(); }
            @Override public void treeStructureChanged(TreeModelEvent e) { invalidar(); }
        });
    }

    boolean filtrando() { return filtrando; }

    /** Vuelve al arbol completo y borra el texto buscado. */
    void limpiar() {
        if (!campo.getText().isEmpty()) campo.setText("");
        espera.stop();
        aplicar("");
    }

    /** El nodo del arbol real que corresponde a uno mostrado (el mismo si no hay filtro). */
    DefaultMutableTreeNode original(Object nodo) {
        DefaultMutableTreeNode o = copiaAOriginal.get(nodo);
        return o != null ? o : (DefaultMutableTreeNode) nodo;
    }

    private void invalidar() {
        indice = null;
        if (filtrando) SwingUtilities.invokeLater(() -> aplicar(campo.getText()));
    }

    private void aplicar(String texto) {
        String q = texto == null ? "" : texto.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            generacion.incrementAndGet();   // descarta una busqueda en curso
            if (filtrando) {
                DefaultMutableTreeNode sel = seleccionOriginal();
                filtrando = false;
                copiaAOriginal = new HashMap<>();
                arbol.setModel(original);
                arbol.expandPath(new TreePath(original.getRoot()));
                if (sel != null) {
                    TreePath p = new TreePath(sel.getPath());
                    arbol.setSelectionPath(p);
                    arbol.scrollPathToVisible(p);
                }
            }
            aviso.accept("");
            return;
        }
        int gen = generacion.incrementAndGet();
        fondo.execute(() -> {
            Resultado r;
            try {
                r = buscar(q);
            } catch (RuntimeException e) {
                return;   // el arbol cambio mientras se buscaba: invalidar() vuelve a buscar
            }
            SwingUtilities.invokeLater(() -> mostrar(r, gen));
        });
    }

    /** Lo armado en segundo plano: el modelo de copias y que expandir. */
    private static final class Resultado {
        DefaultMutableTreeNode raiz;
        final Map<TreeNode, DefaultMutableTreeNode> copiaAOriginal = new HashMap<>();
        final List<DefaultMutableTreeNode> aExpandir = new ArrayList<>();
        int total;
    }

    /** En segundo plano: coincidencias y copias livianas (no toca el JTree). */
    private Resultado buscar(String q) {
        List<Object[]> ind = indice;
        if (ind == null) { ind = armarIndice(); indice = ind; }
        Resultado res = new Resultado();
        List<DefaultMutableTreeNode> hallados = new ArrayList<>();
        for (Object[] e : ind) {
            if (((String) e[0]).contains(q)) {
                res.total++;
                if (hallados.size() < MAX_COINCIDENCIAS) hallados.add((DefaultMutableTreeNode) e[1]);
            }
        }
        DefaultMutableTreeNode raizOriginal = (DefaultMutableTreeNode) original.getRoot();
        Map<DefaultMutableTreeNode, DefaultMutableTreeNode> copias = new HashMap<>();
        res.raiz = copiar(raizOriginal, copias, res.copiaAOriginal);
        int[] copiados = {0};
        Set<DefaultMutableTreeNode> conjunto = new HashSet<>(hallados);
        for (DefaultMutableTreeNode h : hallados) {
            DefaultMutableTreeNode c = asegurarCamino(h, copias, res.copiaAOriginal);
            // Se abre hasta la coincidencia de mas arriba de cada rama: las anidadas (todo un
            // Oper, por ejemplo) quedan a un clic, sin llenar la lista.
            boolean anidada = false;
            for (TreeNode t = h.getParent(); t != null; t = t.getParent()) {
                if (conjunto.contains(t)) { anidada = true; break; }
            }
            if (!anidada && res.aExpandir.size() < MAX_EXPANDIDOS) res.aExpandir.add((DefaultMutableTreeNode) c.getParent());
            // Lo que cuelga de lo hallado tambien se ve (buscar "PDOP1.Beh" muestra sus atributos)
            copiarDescendientes(h, c, copias, res.copiaAOriginal, copiados);
        }
        return res;
    }

    /** En el hilo de Swing: muestra el resultado si sigue siendo la busqueda vigente. */
    private void mostrar(Resultado r, int gen) {
        if (gen != generacion.get() || campo.getText().trim().isEmpty()) return;
        filtrando = true;
        copiaAOriginal = r.copiaAOriginal;
        arbol.setModel(new DefaultTreeModel(r.raiz));
        for (DefaultMutableTreeNode n : r.aExpandir) {
            if (n != null) arbol.expandPath(new TreePath(n.getPath()));
        }
        arbol.expandPath(new TreePath(r.raiz.getPath()));
        aviso.accept(r.total > MAX_COINCIDENCIAS
                ? I18n.t("tree.search.many", String.valueOf(r.total), String.valueOf(MAX_COINCIDENCIAS))
                : I18n.t("tree.search.count", String.valueOf(r.total)));
    }

    private DefaultMutableTreeNode seleccionOriginal() {
        TreePath p = arbol.getSelectionPath();
        return p == null ? null : original(p.getLastPathComponent());
    }

    private static DefaultMutableTreeNode copiar(DefaultMutableTreeNode o, Map<DefaultMutableTreeNode, DefaultMutableTreeNode> copias,
                                                 Map<TreeNode, DefaultMutableTreeNode> aOriginal) {
        DefaultMutableTreeNode c = new DefaultMutableTreeNode(o.getUserObject());
        copias.put(o, c);
        aOriginal.put(c, o);
        return c;
    }

    /** Copia el camino desde la raiz hasta o (solo lo que falte) y devuelve la copia de o. */
    private static DefaultMutableTreeNode asegurarCamino(DefaultMutableTreeNode o,
                                                         Map<DefaultMutableTreeNode, DefaultMutableTreeNode> copias,
                                                         Map<TreeNode, DefaultMutableTreeNode> aOriginal) {
        DefaultMutableTreeNode c = copias.get(o);
        if (c != null) return c;
        DefaultMutableTreeNode padre = asegurarCamino((DefaultMutableTreeNode) o.getParent(), copias, aOriginal);
        c = copiar(o, copias, aOriginal);
        padre.add(c);
        return c;
    }

    private static void copiarDescendientes(DefaultMutableTreeNode o, DefaultMutableTreeNode c,
                                            Map<DefaultMutableTreeNode, DefaultMutableTreeNode> copias,
                                            Map<TreeNode, DefaultMutableTreeNode> aOriginal, int[] copiados) {
        for (int i = 0; i < o.getChildCount() && copiados[0] < MAX_NODOS_COPIADOS; i++) {
            DefaultMutableTreeNode hijo = (DefaultMutableTreeNode) o.getChildAt(i);
            if (copias.containsKey(hijo)) continue;
            DefaultMutableTreeNode ch = copiar(hijo, copias, aOriginal);
            c.add(ch);
            copiados[0]++;
            copiarDescendientes(hijo, ch, copias, aOriginal, copiados);
        }
    }

    /** Ruta en minusculas de cada nodo: "ld/ln.do.da" para los del modelo, nombres para el resto. */
    private List<Object[]> armarIndice() {
        List<Object[]> l = new ArrayList<>();
        DefaultMutableTreeNode raiz = (DefaultMutableTreeNode) original.getRoot();
        Enumeration<TreeNode> en = raiz.preorderEnumeration();
        while (en.hasMoreElements()) {
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) en.nextElement();
            if (n == raiz) continue;
            Object uo = n.getUserObject();
            String ruta;
            if (uo instanceof NodeInfo && ((NodeInfo) uo).node != null) {
                ruta = ((NodeInfo) uo).node.getReference().toString();
            } else {
                StringBuilder sb = new StringBuilder();
                for (TreeNode t : n.getPath()) {
                    if (t == raiz) continue;
                    Object u = ((DefaultMutableTreeNode) t).getUserObject();
                    if (sb.length() > 0) sb.append('/');
                    sb.append(u instanceof NodeInfo ? ((NodeInfo) u).name : String.valueOf(u));
                }
                ruta = sb.toString();
            }
            l.add(new Object[]{ruta.toLowerCase(Locale.ROOT), n});
        }
        return l;
    }
}
