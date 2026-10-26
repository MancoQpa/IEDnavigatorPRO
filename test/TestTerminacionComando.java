package com.iednavigator;

import com.beanit.asn1bean.ber.ReverseByteArrayOutputStream;
import com.beanit.asn1bean.ber.types.BerInteger;
import com.beanit.asn1bean.ber.types.BerOctetString;
import com.beanit.asn1bean.ber.types.string.BerVisibleString;
import com.beanit.iec61850bean.ClientAssociation;
import com.beanit.iec61850bean.internal.mms.asn1.*;

import java.io.ByteArrayInputStream;

/**
 * LastApplError y CommandTermination (IEC 61850-8-1): la app los decodifica y el buzón los
 * entrega al control que los espera. Los reportes se arman como los manda un IED, se codifican
 * en BER y se decodifican, igual que los recibe la asociación.
 *
 * Hasta 2026-10-26 iec61850bean los descartaba; ahora el ClientAssociation sombreado los pasa.
 *
 * Uso:  javac -encoding UTF-8 -cp "classes;lib\*;test" -d test test\TestTerminacionComando.java
 *       java  -cp "classes;lib\*;test" com.iednavigator.TestTerminacionComando
 */
public class TestTerminacionComando {

    private static int fallas = 0;

    static void chk(String titulo, boolean ok, String detalle) {
        if (!ok) fallas++;
        System.out.printf("%-62s %-34s %s%n", titulo, detalle, ok ? "OK" : "<<< FALLA");
    }

    static Identifier id(String s) { return new Identifier(s.getBytes()); }

    static ObjectName vmd(String s) { ObjectName n = new ObjectName(); n.setVmdSpecific(id(s)); return n; }

    static ObjectName dom(String ld, String item) {
        ObjectName.DomainSpecific d = new ObjectName.DomainSpecific();
        d.setDomainID(id(ld)); d.setItemID(id(item));
        ObjectName n = new ObjectName(); n.setDomainSpecific(d); return n;
    }

    static Data entero(int v) { Data d = new Data(); d.setInteger(new BerInteger(v)); return d; }
    static Data sinSigno(int v) { Data d = new Data(); d.setUnsigned(new BerInteger(v)); return d; }

    static Data estructura(Data... hijos) {
        Data.Structure s = new Data.Structure();
        for (Data h : hijos) s.getData().add(h);
        Data d = new Data(); d.setStructure(s); return d;
    }

    /** LastApplError: CntrlObj, Error, Origin{orCat, orIdent}, ctlNum, AddCause. */
    static Data lastApplError(String cntrlObj, int ctlNum, int addCause) {
        Data obj = new Data(); obj.setVisibleString(new BerVisibleString(cntrlObj.getBytes()));
        Data orIdent = new Data(); orIdent.setOctetString(new BerOctetString("IEDNav".getBytes()));
        return estructura(obj, entero(0), estructura(entero(2), orIdent), sinSigno(ctlNum), entero(addCause));
    }

    /** Un Oper simplificado: ctlVal, origin, ctlNum. Su contenido no se usa. */
    static Data oper(int ctlNum) {
        Data v = new Data(); v.setBool(new com.beanit.iec61850bean.internal.BerBoolean(true));
        return estructura(v, estructura(entero(2)), sinSigno(ctlNum));
    }

    /** Arma el reporte, lo codifica en BER y lo decodifica, como lo recibe la asociación. */
    static InformationReport reporte(Object... pares) throws Exception {
        VariableDefs vars = new VariableDefs();
        InformationReport.ListOfAccessResult res = new InformationReport.ListOfAccessResult();
        for (int i = 0; i < pares.length; i += 2) {
            VariableSpecification vs = new VariableSpecification(); vs.setName((ObjectName) pares[i]);
            VariableDefs.SEQUENCE seq = new VariableDefs.SEQUENCE(); seq.setVariableSpecification(vs);
            vars.getSEQUENCE().add(seq);
            AccessResult ar = new AccessResult(); ar.setSuccess((Data) pares[i + 1]);
            res.getAccessResult().add(ar);
        }
        VariableAccessSpecification vas = new VariableAccessSpecification();
        vas.setListOfVariable(vars);
        InformationReport ir = new InformationReport();
        ir.setVariableAccessSpecification(vas);
        ir.setListOfAccessResult(res);
        ReverseByteArrayOutputStream os = new ReverseByteArrayOutputStream(1000, true);
        ir.encode(os);
        InformationReport dec = new InformationReport();
        dec.decode(new ByteArrayInputStream(os.getArray()));
        return dec;
    }

    public static void main(String[] args) throws Exception {
        final String OPER = "IED001CTRL/CBXCBR1.Pos.Oper";

        // 1. CommandTermination positivo: la variable Oper sola
        TerminacionComando.Mensaje m = TerminacionComando.decodificar(
            reporte(dom("IED001CTRL", "CBXCBR1$CO$Pos$Oper"), oper(7)), 1000);
        chk("positivo: es terminación", m != null && m.esTerminacion(), m != null ? m.oper : "null");
        chk("positivo: es positiva, sin LastApplError", m != null && m.esPositiva() && !m.traeLastApplError, "");
        chk("positivo: referencia del Oper como la de la app", m != null && OPER.equals(m.oper), "");

        // 2. CommandTermination negativo: LastApplError + Oper
        m = TerminacionComando.decodificar(reporte(
            vmd("LastApplError"), lastApplError("IED001CTRL/CBXCBR1$CO$Pos$Oper", 7, 10),
            dom("IED001CTRL", "CBXCBR1$CO$Pos$Oper"), oper(7)), 1000);
        chk("negativo: es terminación negativa", m != null && m.esTerminacion() && !m.esPositiva(), "");
        chk("negativo: AddCause 10", m != null && m.addCause == 10, m != null ? "" + m.addCause : "");
        chk("negativo: ctlNum 7, orCat 2, Error 0",
            m != null && m.ctlNum == 7 && m.orCat == 2 && m.error == 0,
            m != null ? m.ctlNum + "/" + m.orCat + "/" + m.error : "");
        chk("negativo: CntrlObj", m != null && "IED001CTRL/CBXCBR1$CO$Pos$Oper".equals(m.cntrlObj), "");
        chk("negativo: texto con AddCause",
            m != null && m.lastApplErrorTexto().contains("AddCause=10"), "");

        // 3. LastApplError suelto: el rechazo de un SBOw/Oper
        m = TerminacionComando.decodificar(reporte(
            vmd("LastApplError"), lastApplError("IED001CTRL/DSCSWI1$CO$Pos$SBOw", 3, 5)), 1000);
        chk("rechazo: LastApplError sin Oper", m != null && m.traeLastApplError && !m.esTerminacion(), "");
        chk("rechazo: AddCause 5", m != null && m.addCause == 5, "");

        // 4. Otro reporte con lista de variables: no es de control
        m = TerminacionComando.decodificar(reporte(dom("IED001CTRL", "CBXCBR1$ST$Pos$stVal"), entero(1)), 1000);
        chk("otro reporte: se ignora", m == null, "");

        // 5. Referencias
        chk("referencia con prefijo de LN", "LD0/Q0CSWI1.Pos.Oper".equals(
            TerminacionComando.referenciaApp("LD0", "Q0CSWI1$CO$Pos$Oper")),
            TerminacionComando.referenciaApp("LD0", "Q0CSWI1$CO$Pos$Oper"));

        // 6. Buzón
        TerminacionComando.Buzon b = new TerminacionComando.Buzon();
        long t = System.currentTimeMillis();
        b.recibir(reporte(vmd("LastApplError"), lastApplError("X", 1, 9)));
        chk("buzón: rechazo desde antes de llegar", b.rechazoDesde(t - 10) != null, "");
        chk("buzón: un rechazo viejo no cuenta", b.rechazoDesde(System.currentTimeMillis() + 10) == null, "");

        final TerminacionComando.Buzon b2 = new TerminacionComando.Buzon();
        final long desde = System.currentTimeMillis();
        new Thread(() -> {
            try {
                Thread.sleep(300);
                b2.recibir(reporte(dom("IED001CTRL", "DSCSWI1$CO$Pos$Oper"), oper(1)));   // otro aparato
                b2.recibir(reporte(dom("IED001CTRL", "CBXCBR1$CO$Pos$Oper"), oper(1)));
            } catch (Exception e) { e.printStackTrace(); }
        }).start();
        long t0 = System.currentTimeMillis();
        TerminacionComando.Mensaje esp = b2.esperarTerminacion(OPER, desde, 3000);
        long dt = System.currentTimeMillis() - t0;
        chk("buzón: espera la terminación de su Oper", esp != null && OPER.equals(esp.oper), dt + " ms");
        chk("buzón: vuelve apenas llega, no al vencer", dt < 1500, dt + " ms");
        chk("buzón: una terminación anterior al envío no cuenta",
            b2.esperarTerminacion(OPER, System.currentTimeMillis() + 10, 200) == null, "");
        t0 = System.currentTimeMillis();
        chk("buzón: sin terminación vence y da null",
            b2.esperarTerminacion("LD/OTRO1.Pos.Oper", desde, 400) == null,
            (System.currentTimeMillis() - t0) + " ms");

        // 7. La asociación que corre es la sombreada, con el listener
        boolean tiene = false;
        try {
            ClientAssociation.class.getMethod("setInformationReportListener",
                ClientAssociation.InformationReportListener.class);
            tiene = true;
        } catch (NoSuchMethodException e) { /* falla abajo */ }
        String origen = String.valueOf(ClientAssociation.class.getProtectionDomain().getCodeSource().getLocation());
        chk("ClientAssociation sombreado (no el del jar)", tiene && !origen.endsWith(".jar"), origen);

        System.out.println(fallas == 0 ? "TODO OK" : fallas + " FALLAS");
        System.exit(fallas == 0 ? 0 : 1);
    }
}
