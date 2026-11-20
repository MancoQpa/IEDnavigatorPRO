package com.beanit.iec61850bean;

import com.beanit.asn1bean.ber.types.string.BerVisibleString;
import com.beanit.iec61850bean.internal.mms.asn1.Data;
import com.beanit.iec61850bean.internal.mms.asn1.Identifier;
import com.beanit.iec61850bean.internal.mms.asn1.ObjectName;
import com.beanit.iec61850bean.internal.mms.asn1.VariableDefs;
import com.beanit.iec61850bean.internal.mms.asn1.VariableSpecification;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Lectura de todo un FC de un LN ("LLN0$ST"), como la hace OMICRON IEDScout al descubrir un IED.
 * Antes ServerModel.getNodeFromVariableDef tiraba "invalid mms item id" (unos 1.700 errores por
 * descubrimiento contra un CID real); ahora devuelve una estructura con los DO de esa FC, en el
 * orden de LogicalNode.getChildren(fc), que es el mismo que usa el tipo (GetVariableAccessAttributes).
 *
 * Uso: java -cp "classes;lib\*;test" com.beanit.iec61850bean.TestLecturaFcDeLn
 */
public class TestLecturaFcDeLn {
    static int ok = 0, mal = 0;
    static void chk(String que, boolean c) {
        System.out.println((c ? "OK    " : "FALLA ") + que);
        if (c) ok++; else mal++;
    }

    static VariableDefs.SEQUENCE pedido(String dominio, String item) {
        ObjectName.DomainSpecific ds = new ObjectName.DomainSpecific();
        ds.setDomainID(new Identifier(dominio.getBytes(StandardCharsets.UTF_8)));
        ds.setItemID(new Identifier(item.getBytes(StandardCharsets.UTF_8)));
        ObjectName on = new ObjectName();
        on.setDomainSpecific(ds);
        VariableSpecification vs = new VariableSpecification();
        vs.setName(on);
        VariableDefs.SEQUENCE s = new VariableDefs.SEQUENCE();
        s.setVariableSpecification(vs);
        return s;
    }

    public static void main(String[] a) throws Exception {
        ServerModel m = SclParser.parse("test/test_bay_control.cid").get(0);
        LogicalDevice ld = (LogicalDevice) m.getChildren().iterator().next();
        LogicalNode lln0 = (LogicalNode) ld.getChild("LLN0");
        String dominio = ld.getName();

        for (Fc fc : new Fc[]{Fc.ST, Fc.CF, Fc.DC}) {
            List<FcDataObject> esperados = lln0.getChildren(fc);
            FcModelNode n = m.getNodeFromVariableDef(pedido(dominio, "LLN0$" + fc));
            if (esperados == null || esperados.isEmpty()) {
                chk("LLN0$" + fc + " sin DO -> null (object-non-existent)", n == null);
                continue;
            }
            chk("LLN0$" + fc + " devuelve un nodo", n != null);
            if (n == null) continue;
            Data d = n.getMmsDataObj();
            List<Data> partes = d.getStructure() == null ? null : d.getStructure().getData();
            chk("LLN0$" + fc + ": estructura con " + (partes == null ? 0 : partes.size()) + " DO (esperados " + esperados.size() + ")",
                    partes != null && partes.size() == esperados.size());
            boolean iguales = partes != null;
            for (int i = 0; iguales && i < partes.size(); i++) {
                iguales = partes.get(i).toString().equals(esperados.get(i).getMmsDataObj().toString());
            }
            chk("LLN0$" + fc + ": cada DO igual a su lectura individual, en el orden del tipo", iguales);
            chk("LLN0$" + fc + ": el modelo no cambió (padre del DO intacto)", esperados.get(0).getParent() == lln0);
            try {
                n.setValueFromMmsDataObj(d);
                chk("LLN0$" + fc + ": escribir el FC entero se rechaza", false);
            } catch (ServiceError e) {
                chk("LLN0$" + fc + ": escribir el FC entero se rechaza (" + e.getErrorCode() + ")", true);
            }
        }

        // Una lectura normal sigue igual
        FcModelNode beh = m.getNodeFromVariableDef(pedido(dominio, "LLN0$ST$Beh$stVal"));
        chk("LLN0$ST$Beh$stVal sigue funcionando", beh instanceof BasicDataAttribute);
        // FC desconocida: el Fc sombreado la vuelve UNKNOWN (no tumba el LN) y no hay DO con
        // esa FC, así que el pedido contesta object-non-existent en vez de fallar entero.
        FcModelNode zz = m.getNodeFromVariableDef(pedido(dominio, "LLN0$ZZ"));
        chk("LLN0$ZZ: FC desconocida -> object-non-existent (null)", zz == null);

        System.out.println(ok + " pasaron, " + mal + " fallaron");
        if (mal == 0) System.out.println("TODO OK");
        System.exit(mal == 0 ? 0 : 1);
    }
}
