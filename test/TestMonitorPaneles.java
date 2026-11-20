package com.iednavigator;

import com.beanit.iec61850bean.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Vista de paneles del monitor (MonitorPaneles) y la elección de la señal de un DO
 * (MonitorManager.principal), sobre test/test_bay_control.cid servido.
 *
 * Uso: java -cp "classes;lib\*;test" com.iednavigator.TestMonitorPaneles
 */
public class TestMonitorPaneles {

    private static int ok, falla;

    private static void chk(String que, boolean cond) {
        if (cond) { ok++; System.out.println("OK     " + que); }
        else { falla++; System.out.println("FALLA  " + que); }
    }

    public static void main(String[] args) throws Exception {
        IEC61850Server srv = new IEC61850Server();
        chk("carga test_bay_control.cid", srv.loadSclFile("test/test_bay_control.cid"));
        ServerModel m = srv.getServerModel();

        MonitorItem pos = item(m, "BAYCTRLCTRL/Q0XCBR1.Pos", Fc.ST);
        MonitorItem mod = item(m, "BAYCTRLCTRL/LLN0.Mod", Fc.ST);
        MonitorItem loc = item(m, "BAYCTRLCTRL/Q0XCBR1.Loc", Fc.ST);
        MonitorItem ena = item(m, "BAYCTRLCTRL/Q0CILO1.EnaOpn", Fc.ST);
        chk("existen las señales del CID", pos != null && mod != null && loc != null && ena != null);

        chk("Pos (DPC) es un interruptor", MonitorPaneles.tipoBase(pos) == MonitorPaneles.Tipo.INTERRUPTOR);
        chk("Mod (ENC) es una etiqueta", MonitorPaneles.tipoBase(mod) == MonitorPaneles.Tipo.ETIQUETA);
        chk("Loc (SPS) es un LED", MonitorPaneles.tipoBase(loc) == MonitorPaneles.Tipo.LED);
        chk("EnaOpn (SPS) es un LED", MonitorPaneles.tipoBase(ena) == MonitorPaneles.Tipo.LED);

        ModelNode stVal = m.findModelNode("BAYCTRLCTRL/Q0XCBR1.Pos.stVal", Fc.ST);
        MonitorItem suelto = new MonitorItem("BAYCTRLCTRL/Q0XCBR1.Pos.stVal", "x", "ST", "", (FcModelNode) stVal);
        chk("un stVal suelto (Dbpos) también es un interruptor", MonitorPaneles.tipoBase(suelto) == MonitorPaneles.Tipo.INTERRUPTOR);
        ModelNode q = m.findModelNode("BAYCTRLCTRL/Q0XCBR1.Pos.q", Fc.ST);
        MonitorItem qSuelto = new MonitorItem("BAYCTRLCTRL/Q0XCBR1.Pos.q", "x", "ST", "", (FcModelNode) q);

        // Visibles: un atributo de un DO que ya está no tiene tarjeta propia
        List<MonitorItem> v = MonitorPaneles.visibles(new ArrayList<>(Arrays.asList(pos, suelto, qSuelto, mod, mod)));
        chk("los atributos de un DO presente no se repiten (" + v.size() + " tarjetas)", v.size() == 2);
        List<MonitorItem> v2 = MonitorPaneles.visibles(new ArrayList<>(Arrays.asList(suelto, mod)));
        chk("un atributo sin su DO sí tiene tarjeta", v2.size() == 2);

        // La señal de un DO es su variante ST
        FcDataObject posDo = (FcDataObject) m.findModelNode("BAYCTRLCTRL/Q0CSWI1.Pos", Fc.ST);
        FcDataObject p = MonitorManager.principal(posDo);
        chk("principal de un DO controlable: la variante ST", p != null && p.getFc() == Fc.ST);

        // Unidades tal como las expone el equipo (con su multiplicador)
        MonitorPaneles mp = new MonitorPaneles(java.util.Collections::emptyList, (n, r) -> r);
        MonitorPaneles.Unidad volt = new MonitorPaneles.Unidad(0, "V"), kw = new MonitorPaneles.Unidad(3, "W");
        chk("tensión como la publica el equipo", Arrays.equals(mp.mostrar(13343.8, volt), new String[]{"13343.8", "V"}));
        chk("potencia publicada en kW", Arrays.equals(mp.mostrar(5650.2, kw), new String[]{"5650.2", "kW"}));
        chk("corriente en A", Arrays.equals(mp.mostrar(450.37, new MonitorPaneles.Unidad(0, "A")), new String[]{"450.37", "A"}));
        chk("unidad por nombre: PPV en V, VAr en VAr", MonitorPaneles.unidadPorNombre("PPV").equals("V")
                && MonitorPaneles.unidadPorNombre("VAr").equals("VAr"));

        chk("formato entero", MonitorPaneles.formato(16).equals("16"));
        chk("formato con decimales", MonitorPaneles.formato(50.0234).equals("50.02"));
        chk("título sin el LD", MonitorPaneles.titulo("BAYCTRLCTRL/Q0XCBR1.Pos").equals("Q0XCBR1.Pos"));
        chk("calidad buena: sin rótulo", MonitorPaneles.calidad(pos.node) == null);
        ((BdaQuality) m.findModelNode("BAYCTRLCTRL/Q0XCBR1.Pos.q", Fc.ST)).setValidity(BdaQuality.Validity.INVALID);
        String[] cq = MonitorPaneles.calidad(pos.node);
        chk("calidad inválida: rótulo invalid en rojo", cq != null && cq[0].equals("invalid") && cq[1].equals("e"));

        System.out.println();
        System.out.println(ok + " pasaron, " + falla + " fallaron");
        if (falla == 0) System.out.println("TODO OK");
        System.exit(falla == 0 ? 0 : 1);
    }

    private static MonitorItem item(ServerModel m, String ref, Fc fc) {
        ModelNode n = m.findModelNode(ref, fc);
        return n instanceof FcModelNode ? new MonitorItem(ref, ref, fc.toString(), "", (FcModelNode) n) : null;
    }
}
