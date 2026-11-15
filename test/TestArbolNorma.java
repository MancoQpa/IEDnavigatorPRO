package com.iednavigator;

import com.beanit.iec61850bean.*;

import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Árbol del modelo con los términos de la norma (etapa 2 del rediseño, 2026-11-15):
 *
 *   1. Marcas como en el SCL: DO (de un LN), SDO (DO dentro de un DO), DA (atributo de un
 *      DO: stVal, Oper) y BDA (componente de un DA: Oper.ctlVal). Antes Oper salía "SDO" y
 *      un SDO de verdad "DO".
 *   2. Un SDO aparece una sola vez aunque esté en varias FC (phsA de un WYE).
 *   3. El CDC que declara el SCL, también el de un SDO (DescripcionesScl.cdc).
 *   4. Valores: "1 [on]" se lee "1 · on"; un 0 en Beh es "no configurado" en Servidor y
 *      "fuera del enumerado" en Cliente.
 *
 * Uso: java -cp "classes;lib\*;test" com.iednavigator.TestArbolNorma
 */
public class TestArbolNorma {
    static int ok = 0, mal = 0;
    static void chk(String que, boolean c) {
        System.out.println((c ? "OK    " : "FALLA ") + que);
        if (c) ok++; else mal++;
    }

    static final String SCL = "<?xml version='1.0' encoding='UTF-8'?>"
        + "<SCL xmlns='http://www.iec.ch/61850/2003/SCL' version='2007' revision='B'>"
        + "<Header id='T'/>"
        + "<IED name='MED'><AccessPoint name='AP1'><Server><Authentication/>"
        + "<LDevice inst='LD0'>"
        + " <LN0 lnClass='LLN0' inst='' lnType='LLN0_T'/>"
        + " <LN lnClass='MMXU' inst='1' lnType='MMXU_T'/>"
        + "</LDevice></Server></AccessPoint></IED>"
        + "<DataTypeTemplates>"
        + " <LNodeType id='LLN0_T' lnClass='LLN0'><DO name='Beh' type='ENS_T'/></LNodeType>"
        + " <LNodeType id='MMXU_T' lnClass='MMXU'><DO name='Beh' type='ENS_T'/><DO name='PhV' type='WYE_T'/></LNodeType>"
        + " <DOType id='ENS_T' cdc='ENS'><DA name='stVal' fc='ST' bType='Enum' type='Beh_E'/>"
        + "   <DA name='q' fc='ST' bType='Quality'/><DA name='t' fc='ST' bType='Timestamp'/></DOType>"
        + " <DOType id='WYE_T' cdc='WYE'><SDO name='phsA' type='CMV_T'/><SDO name='phsB' type='CMV_T'/></DOType>"
        + " <DOType id='CMV_T' cdc='CMV'><DA name='cVal' fc='MX' bType='Struct' type='Vec_T'/>"
        + "   <DA name='q' fc='MX' bType='Quality'/><DA name='t' fc='MX' bType='Timestamp'/>"
        + "   <DA name='units' fc='CF' bType='Struct' type='Unit_T'/></DOType>"
        + " <DAType id='Vec_T'><BDA name='mag' bType='Struct' type='AV_T'/></DAType>"
        + " <DAType id='AV_T'><BDA name='f' bType='FLOAT32'/></DAType>"
        + " <DAType id='Unit_T'><BDA name='SIUnit' bType='Enum' type='SIUnit_E'/></DAType>"
        + " <EnumType id='Beh_E'><EnumVal ord='1'>on</EnumVal><EnumVal ord='2'>blocked</EnumVal>"
        + "   <EnumVal ord='3'>test</EnumVal><EnumVal ord='4'>test/blocked</EnumVal><EnumVal ord='5'>off</EnumVal></EnumType>"
        + " <EnumType id='SIUnit_E'><EnumVal ord='29'>V</EnumVal></EnumType>"
        + "</DataTypeTemplates></SCL>";

    public static void main(String[] a) throws Exception {
        File f = File.createTempFile("arbolnorma", ".cid");
        f.deleteOnExit();
        Files.write(f.toPath(), SCL.getBytes(StandardCharsets.UTF_8));
        ServerModel m = SclParser.parse(f.getAbsolutePath()).get(0);

        DefaultMutableTreeNode raiz = new DefaultMutableTreeNode("x");
        Map<String, DefaultMutableTreeNode> nodeMap = new HashMap<>();
        ModelTreeBuilder.agruparDo = true;
        ModelTreeBuilder.buildTree(m, raiz, new DefaultTreeModel(raiz), nodeMap,
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new javax.swing.JTree(), null, null);

        DefaultMutableTreeNode phv = buscar(raiz, "MMXU1", "PhV");
        chk("PhV es DO", "DO".equals(info(phv).prefix));
        List<String> hijos = new ArrayList<>();
        for (int i = 0; i < phv.getChildCount(); i++) hijos.add(info((DefaultMutableTreeNode) phv.getChildAt(i)).name);
        chk("phsA y phsB una sola vez cada uno (MX y CF agrupados): " + hijos,
                Collections.frequency(hijos, "phsA") == 1 && Collections.frequency(hijos, "phsB") == 1);
        DefaultMutableTreeNode phsA = hijo(phv, "phsA");
        chk("phsA es SDO", "SDO".equals(info(phsA).prefix));
        DefaultMutableTreeNode cVal = hijo(phsA, "cVal");
        chk("cVal (atributo del SDO) es DA", cVal != null && "DA".equals(info(cVal).prefix));
        chk("units está junto a cVal (de otra FC, mismo SDO)", hijo(phsA, "units") != null);
        DefaultMutableTreeNode mag = hijo(cVal, "mag");
        chk("cVal.mag es BDA", mag != null && "BDA".equals(info(mag).prefix));
        chk("cVal.mag.f es BDA", hijo(mag, "f") != null && "BDA".equals(info(hijo(mag, "f")).prefix));

        DefaultMutableTreeNode beh = buscar(raiz, "LLN0", "Beh");
        chk("Beh es DO", "DO".equals(info(beh).prefix));
        DefaultMutableTreeNode st = hijo(beh, "stVal");
        chk("Beh.stVal es DA", "DA".equals(info(st).prefix));

        // CDC del SCL, también el de un SDO
        DescripcionesScl ds = DescripcionesScl.de(f);
        chk("SCL: PhV es WYE", "WYE".equals(ds.cdc("MEDLD0/MMXU1.PhV")));
        chk("SCL: PhV.phsA es CMV", "CMV".equals(ds.cdc("MEDLD0/MMXU1.PhV.phsA")));
        chk("SCL: LLN0.Beh es ENS", "ENS".equals(ds.cdc("MEDLD0/LLN0.Beh")));
        chk("deducido: PhV es WYE", "WYE".equals(TiposIec61850.cdcDe((FcDataObject) info(phv).node)));
        chk("deducido: phsA es CMV", "CMV".equals(TiposIec61850.cdcDe((FcDataObject) info(phsA).node)));

        // Valores
        ArbolCeldaRenderer r = new ArbolCeldaRenderer(new HashSet<>(), () -> null, new HashMap<>());
        NodeInfo ni = info(st);
        ni.value = "1 [on]";
        chk("1 [on] -> " + r.textoValor(ni), "1 · on".equals(r.textoValor(ni)));
        ni.value = "0";
        r.modoServidor = () -> true;
        String srv = r.textoValor(ni);
        chk("Beh 0 en Servidor -> " + srv, srv.equals("0 · (" + I18n.t("tree.enum.unset") + ")"));
        r.modoServidor = () -> false;
        String cli = r.textoValor(ni);
        chk("Beh 0 en Cliente -> " + cli, cli.equals("0 · (" + I18n.t("tree.enum.out") + ")"));
        ni.value = "3";
        chk("Beh 3 sin etiqueta -> " + r.textoValor(ni), "3 · test".equals(r.textoValor(ni)));

        // Con el agrupado apagado, el árbol de antes (red de seguridad)
        ModelTreeBuilder.agruparDo = false;
        DefaultMutableTreeNode raiz2 = new DefaultMutableTreeNode("x");
        ModelTreeBuilder.buildTree(m, raiz2, new DefaultTreeModel(raiz2), new HashMap<>(),
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new javax.swing.JTree(), null, null);
        int pv = 0;
        DefaultMutableTreeNode mmxu = buscar(raiz2, "MMXU1", null);
        for (int i = 0; i < mmxu.getChildCount(); i++) if ("PhV".equals(info((DefaultMutableTreeNode) mmxu.getChildAt(i)).name)) pv++;
        chk("sin agrupar: PhV una vez por FC (" + pv + ")", pv == 2);
        ModelTreeBuilder.agruparDo = true;

        System.out.println(ok + " pasaron, " + mal + " fallaron");
        if (mal == 0) System.out.println("TODO OK");
        System.exit(mal == 0 ? 0 : 1);
    }

    static NodeInfo info(DefaultMutableTreeNode n) { return (NodeInfo) n.getUserObject(); }

    static DefaultMutableTreeNode hijo(DefaultMutableTreeNode p, String nombre) {
        if (p == null) return null;
        for (int i = 0; i < p.getChildCount(); i++) {
            DefaultMutableTreeNode h = (DefaultMutableTreeNode) p.getChildAt(i);
            if (h.getUserObject() instanceof NodeInfo && nombre.equals(info(h).name)) return h;
        }
        return null;
    }

    /** El LN con ese nombre y, si se pide, su hijo. */
    static DefaultMutableTreeNode buscar(DefaultMutableTreeNode raiz, String ln, String hijo) {
        Enumeration<?> e = raiz.depthFirstEnumeration();
        while (e.hasMoreElements()) {
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) e.nextElement();
            if (n.getUserObject() instanceof NodeInfo && ln.equals(info(n).name) && "LN".equals(info(n).prefix)) {
                return hijo == null ? n : hijo(n, hijo);
            }
        }
        return null;
    }
}
