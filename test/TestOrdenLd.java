package com.iednavigator;

import com.beanit.iec61850bean.*;

import java.io.File;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Orden de los LD: el de descubrimiento (pedido de usuarios, 2026-11-16). Un intento previo los
 * ordenaba alfabéticamente en los dos modos; los usuarios quieren el orden en que se encuentran.
 *
 *   1. El árbol de Servidor muestra los LD en el orden del archivo SCL.
 *   2. El simulador los entrega en ese mismo orden: un cliente conectado a él ve el mismo árbol.
 *
 * En Cliente, el orden es el que entrega el IED (un relé real suele entregarlos ordenados).
 *
 * Uso: java -cp "classes;lib\*;test" com.iednavigator.TestOrdenLd
 */
public class TestOrdenLd {
    static int ok = 0, mal = 0;
    static void chk(String que, boolean c) {
        System.out.println((c ? "OK    " : "FALLA ") + que);
        if (c) ok++; else mal++;
    }

    static String ld(String inst) {
        return "<LDevice inst='" + inst + "'><LN0 lnClass='LLN0' inst='' lnType='LLN0_T'/></LDevice>";
    }

    static final String SCL = "<?xml version='1.0' encoding='UTF-8'?>"
        + "<SCL xmlns='http://www.iec.ch/61850/2003/SCL' version='2007' revision='B'><Header id='T'/>"
        + "<IED name='ORD'><AccessPoint name='AP1'><Server><Authentication/>"
        + ld("LD0") + ld("PROT") + ld("MONT") + ld("CTRL") + ld("MEAS")
        + "</Server></AccessPoint></IED>"
        + "<DataTypeTemplates><LNodeType id='LLN0_T' lnClass='LLN0'><DO name='Beh' type='INS_T'/></LNodeType>"
        + "<DOType id='INS_T' cdc='INS'><DA name='stVal' fc='ST' bType='INT32'/></DOType></DataTypeTemplates></SCL>";

    static final List<String> ESPERADO = Arrays.asList("ORDLD0", "ORDPROT", "ORDMONT", "ORDCTRL", "ORDMEAS");

    static List<String> nombres(Iterable<ModelNode> lds) {
        List<String> r = new ArrayList<>();
        for (ModelNode n : lds) r.add(n.getName());
        return r;
    }

    public static void main(String[] a) throws Exception {
        File f = File.createTempFile("ordenld", ".cid");
        f.deleteOnExit();
        Files.write(f.toPath(), SCL.getBytes(StandardCharsets.UTF_8));
        IEC61850Server srv = new IEC61850Server();
        chk("carga el SCL", srv.loadSclFile(f.getAbsolutePath()));
        ServerModel m = srv.getServerModel();
        System.out.println("       orden del archivo: " + nombres(m.getChildren()));
        List<String> arbol = nombres(m.getChildren());
        chk("1. árbol de Servidor en el orden del archivo: " + arbol, arbol.equals(ESPERADO));

        chk("servidor iniciado", srv.start(23190));
        ClientAssociation as = new ClientSap().associate(InetAddress.getByName("127.0.0.1"), 23190, null,
                new ClientEventListener() {
                    public void newReport(Report r) { }
                    public void associationClosed(java.io.IOException e) { }
                });
        List<String> cliente = nombres(as.retrieveModel().getChildren());
        chk("2. el cliente los recibe en ese mismo orden: " + cliente, cliente.equals(ESPERADO));
        as.close();
        srv.stop();
        System.out.println(ok + " pasaron, " + mal + " fallaron");
        if (mal == 0) System.out.println("TODO OK");
        System.exit(mal == 0 ? 0 : 1);
    }
}
