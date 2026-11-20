package com.iednavigator;

import com.iednavigator.mando.EstadoLocal;

import javax.swing.*;
import java.awt.Color;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * GOOSE del interruptor virtual de punta a punta con la pestaña GOOSE real (GoosePanel) en
 * "Loopback interno": sin red ni Npcap.
 *
 * Sobre test/test_bay_control.cid se agregan dos GoCB sintéticos: gcbPos (lleva Q0XCBR1.Pos) y
 * gcbTrip (lleva Q0CILO1.EnaOpn, un booleano que hace de disparo de otro IED).
 *  1. LED GOOSE Pub: rojo con la posición sin publicar, verde al publicar desde la ventana;
 *  2. la suscripción de disparo arranca la captura (loopback) y pasa a "recibiendo";
 *  3. el dato del GoCB de disparo pasa de FALSE a TRUE: el interruptor abre;
 *  4. la marca de simulado se cambia desde la ventana igual que en la pestaña.
 *
 * Uso: java -cp "classes;lib\*;test" com.iednavigator.TestGooseInterruptor
 */
public class TestGooseInterruptor {

    private static int ok, falla;

    private static void chk(String que, boolean cond) {
        if (cond) { ok++; System.out.println("OK     " + que); }
        else { falla++; System.out.println("FALLA  " + que); }
    }

    private static IEC61850Server srv;
    private static String ref;
    private static GoosePanel gp;
    private static InterruptorVirtualDialog v;

    public static void main(String[] args) throws Exception {
        srv = new IEC61850Server();
        chk("carga test_bay_control.cid", srv.loadSclFile("test/test_bay_control.cid"));
        chk("servidor iniciado", srv.start(10486));
        for (EstadoLocal.Objeto o : srv.listarAparatos()) if (o.esAparato && o.ref.endsWith("XCBR1.Pos")) ref = o.ref;
        srv.ponerTiempoAperturaMs(30);
        srv.ponerTiempoCierreMs(30);
        if (srv.enLocal(ref) != null) srv.ponerLlave(ref, false);
        srv.maniobrarLocal(ref, true);
        Thread.sleep(300);
        chk("arranca CERRADO", "CERRADO".equals(estado()));

        List<SclGoCB> gocbs = new ArrayList<>();
        List<SclDataSet> dss = new ArrayList<>();
        gocbs.add(gocb("gcbPos", "dsPos", "0101"));
        gocbs.add(gocb("gcbTrip", "dsTrip", "0102"));
        dss.add(ds("dsPos", "CTRL/Q0XCBR1.Pos.stVal [ST]"));
        dss.add(ds("dsTrip", "CTRL/Q0CILO1.EnaOpn.stVal [ST]"));
        GoosePanel.Context ctx = (GoosePanel.Context) Proxy.newProxyInstance(
                GoosePanel.Context.class.getClassLoader(), new Class<?>[]{GoosePanel.Context.class},
                (p, m, a) -> {
                    switch (m.getName()) {
                        case "getServer": return srv;
                        case "isServerMode": case "isServerRunning": return true;
                        case "getLoadedIedName": return "BAYCTRL";
                        case "getSclGoCBs": return gocbs;
                        case "getSclDataSets": return dss;
                        case "backgroundExecutor": return Executors.newCachedThreadPool();
                        case "formatReference": return a[0];
                        case "log": return null;
                        default:
                            Class<?> r = m.getReturnType();
                            if (r == boolean.class) return false;
                            if (r == int.class) return 0;
                            return null;
                    }
                });
        SwingUtilities.invokeAndWait(() -> {
            gp = new GoosePanel(ctx);
            gp.createPanel();
            try {
                @SuppressWarnings("unchecked")
                JComboBox<String> combo = (JComboBox<String>) campo(GoosePanel.class, gp, "gooseInterfaceCombo");
                for (int i = 0; i < combo.getItemCount(); i++) {
                    if (combo.getItemAt(i).contains("Loopback")) combo.setSelectedIndex(i);
                }
            } catch (Exception e) { throw new RuntimeException(e); }
            v = new InterruptorVirtualDialog(null, srv, gp, ref);
        });
        chk("la pestaña GOOSE quedó en Loopback", gp.placaElegida() != null && gp.placaElegida().contains("Loopback"));
        chk("el GoCB de la posición es el único que lleva el aparato",
                gp.indicesGocbQueLlevan("BAYCTRLCTRL", java.util.Arrays.asList("Q0XCBR1.Pos")).equals(java.util.Arrays.asList(0)));

        // 1. LED GOOSE Pub
        refrescar();
        chk("LED Pub rojo: la posición va en un GoCB sin publicar", VintComponentes.ROJO.equals(led("ledPub")));
        SwingUtilities.invokeAndWait(() -> gp.publicarGoCB(0, true));
        refrescar();
        chk("publicado desde la ventana: LED Pub verde", VintComponentes.VERDE_LED.equals(led("ledPub")) && gp.publicando(0));
        chk("arranca marcado como simulado (como la pestaña)", gp.publicaSimulado());
        SwingUtilities.invokeAndWait(() -> gp.ponerPublicaSimulado(false));
        chk("desmarcar simulado desde la ventana", !gp.publicaSimulado());

        // 2. Suscripción de disparo
        SuscripcionGoose s = (SuscripcionGoose) campo(InterruptorVirtualDialog.class, v, "suscripcion");
        s.configurar(SuscripcionGoose.Rol.DISPARO,
                SuscripcionGoose.Config.validar("0x0102", GoosePanel.gocbRefDe(gocbs.get(1)), "0", ""));
        SwingUtilities.invokeAndWait(() -> gp.asegurarCaptura());
        chk("la captura queda corriendo (loopback)", gp.capturando());
        SwingUtilities.invokeAndWait(() -> gp.publicarGoCB(1, true));
        Thread.sleep(1500);
        chk("la suscripción recibe el GoCB de disparo: " + s.textoEstado(SuscripcionGoose.Rol.DISPARO),
                s.estado(SuscripcionGoose.Rol.DISPARO) == SuscripcionGoose.Estado.RECIBIENDO);
        refrescar();
        chk("LED Sub verde", VintComponentes.VERDE_LED.equals(led("ledSub")));

        // 3. FALSE → TRUE en el dato de disparo
        @SuppressWarnings("unchecked")
        Map<Integer, GoosePublisher> pubs = (Map<Integer, GoosePublisher>) campo(GoosePanel.class, gp, "activePublishers");
        GoosePublisher trip = pubs.get(1);
        trip.setDataValue(0, false);
        trip.publishStateChange();
        Thread.sleep(300);
        chk("con el dato en FALSE sigue cerrado", "CERRADO".equals(estado()));
        trip.setDataValue(0, true);
        trip.publishStateChange();
        Thread.sleep(500);
        chk("FALSE → TRUE por GOOSE: el interruptor abre", "ABIERTO".equals(estado()));

        SwingUtilities.invokeAndWait(() -> {
            v.dispose();
            gp.publicarGoCB(0, false);
            gp.publicarGoCB(1, false);
        });
        chk("detener desde la ventana", !gp.publicando(0) && !gp.publicando(1));
        srv.stop();
        System.out.println();
        System.out.println(ok + " pasaron, " + falla + " fallaron");
        if (falla == 0) System.out.println("TODO OK");
        System.exit(falla == 0 ? 0 : 1);
    }

    private static SclGoCB gocb(String nombre, String ds, String appId) {
        SclGoCB g = new SclGoCB();
        g.ldInst = "CTRL";
        g.lnClass = "LLN0";
        g.cbName = nombre;
        g.datSet = ds;
        g.appID = appId;
        g.goID = nombre;
        g.confRev = 1;
        g.macAddress = "01-0C-CD-01-00-01";
        return g;
    }

    private static SclDataSet ds(String nombre, String miembro) {
        SclDataSet d = new SclDataSet();
        d.ldInst = "CTRL";
        d.lnClass = "LLN0";
        d.name = nombre;
        d.members.add(miembro);
        return d;
    }

    private static String estado() {
        for (EstadoLocal.Objeto o : srv.listarAparatos()) if (o.ref.equals(ref)) return o.estado;
        return null;
    }

    private static Object campo(Class<?> c, Object o, String nombre) throws Exception {
        Field f = c.getDeclaredField(nombre);
        f.setAccessible(true);
        return f.get(o);
    }

    private static Color led(String nombre) throws Exception {
        return ((VintComponentes.Led) campo(InterruptorVirtualDialog.class, v, nombre)).color();
    }

    private static void refrescar() throws Exception {
        Method m = InterruptorVirtualDialog.class.getDeclaredMethod("refrescar");
        m.setAccessible(true);
        SwingUtilities.invokeAndWait(() -> {
            try { m.invoke(v); } catch (Exception e) { throw new RuntimeException(e); }
        });
    }
}
