package com.iednavigator;

import com.beanit.iec61850bean.BdaDoubleBitPos;
import com.iednavigator.mando.EstadoLocal;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * La ventana compacta del interruptor virtual, sobre
 * test/test_bay_control.cid servido (no se muestra en pantalla: pack() ya da el tamaño real).
 *
 *  1. la ventana principal es angosta y el panel del instructor entra en la pantalla;
 *  2. mando local: se rechaza "ya en posición", cierra en LOCAL, en REMOTO los botones se
 *     bloquean;
 *  3. GOOSE: el disparo abre aun en REMOTO; el cierre se bloquea en LOCAL y cierra en REMOTO;
 *  4. posición forzada por el instructor: rechaza el mando y al soltarla vuelve la anterior.
 *
 * Uso: java -cp "classes;lib\*;test" com.iednavigator.TestVentanaInterruptor
 */
public class TestVentanaInterruptor {

    private static int ok, falla;

    private static void chk(String que, boolean cond) {
        if (cond) { ok++; System.out.println("OK     " + que); }
        else { falla++; System.out.println("FALLA  " + que); }
    }

    private static IEC61850Server srv;
    private static String ref;
    private static InterruptorVirtualDialog v;

    public static void main(String[] args) throws Exception {
        srv = new IEC61850Server();
        chk("carga test_bay_control.cid", srv.loadSclFile("test/test_bay_control.cid"));
        chk("servidor iniciado", srv.start(10483));
        for (EstadoLocal.Objeto o : srv.listarAparatos()) if (o.esAparato && o.ref.endsWith("XCBR1.Pos")) ref = o.ref;
        chk("hay un XCBR", ref != null);
        srv.ponerTiempoAperturaMs(50);
        srv.ponerTiempoCierreMs(80);
        srv.maniobrarLocal(ref, false);
        Thread.sleep(300);

        SwingUtilities.invokeAndWait(() -> v = new InterruptorVirtualDialog(null, srv, null, ref));

        // 1. Tamaño
        Dimension d = v.getSize();
        System.out.println("       ventana principal " + d.width + " x " + d.height);
        chk("ventana principal angosta (<= 400 px)", d.width <= 400);
        chk("ventana principal baja (<= 640 px)", d.height <= 640);
        JDialog[] instr = new JDialog[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                Class<?> c = Class.forName("com.iednavigator.InterruptorVirtualDialog$PanelInstructor");
                Constructor<?> k = c.getDeclaredConstructor(InterruptorVirtualDialog.class);
                k.setAccessible(true);
                instr[0] = (JDialog) k.newInstance(v);
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        Rectangle util = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        System.out.println("       panel del instructor " + instr[0].getWidth() + " x " + instr[0].getHeight()
                + " (pantalla útil " + util.width + " x " + util.height + ")");
        chk("panel del instructor angosto (<= 420 px)", instr[0].getWidth() <= 420);
        chk("panel del instructor entra en la pantalla", instr[0].getHeight() <= util.height);
        if (!java.util.prefs.Preferences.userRoot().node("iednavigator/vint").getBoolean("instructor.mas", false)) {
            chk("con lo extra plegado entra sin barra de desplazamiento", instr[0].getHeight() < util.height - 20);
        }
        SwingUtilities.invokeAndWait(() -> instr[0].dispose());

        // 2. Mando local
        Boolean tieneLlave = srv.enLocal(ref);
        if (tieneLlave != null) srv.ponerLlave(ref, true);
        llamar("mandoLocal", false);
        chk("abrir estando abierto: rechazado (ya en posición)", ultimo().contains(I18n.t("vint.rech.enposicion")));
        llamar("mandoLocal", true);
        Thread.sleep(400);
        chk("CERRAR en LOCAL cierra", "CERRADO".equals(estado()));
        if (tieneLlave != null) {
            srv.ponerLlave(ref, false);
            llamar("refrescar");
            JButton cerrar = (JButton) campo("btnCerrar");
            chk("en REMOTO los botones locales quedan bloqueados", !cerrar.isEnabled());
        } else {
            System.out.println("       (el CID no tiene Loc: se saltea la prueba del selector)");
        }

        // 3. GOOSE
        SuscripcionGoose s = (SuscripcionGoose) campo("suscripcion");
        s.configurar(SuscripcionGoose.Rol.DISPARO, SuscripcionGoose.Config.validar("0x0010", "PROT/LLN0$GO$Trip", "0", ""));
        s.configurar(SuscripcionGoose.Rol.CIERRE, SuscripcionGoose.Config.validar("0x0011", "AUTO/LLN0$GO$Cls", "0", ""));
        s.recibir(trama(0x10, "PROT/LLN0$GO$Trip", 1, false));
        s.recibir(trama(0x10, "PROT/LLN0$GO$Trip", 2, true));
        Thread.sleep(400);
        vaciarEdt();
        chk("disparo por GOOSE abre" + (tieneLlave != null ? " aun en REMOTO" : ""), "ABIERTO".equals(estado()));
        chk("el registro anota el disparo recibido", contiene(I18n.t("vint.evt.goose.rx.disparo", "2")));
        if (tieneLlave != null) {
            srv.ponerLlave(ref, true);
            s.recibir(trama(0x11, "AUTO/LLN0$GO$Cls", 1, false));
            s.recibir(trama(0x11, "AUTO/LLN0$GO$Cls", 2, true));
            Thread.sleep(300);
            vaciarEdt();
            chk("cierre por GOOSE en LOCAL: no cierra", "ABIERTO".equals(estado()));
            chk("y el registro dice bloqueado en LOCAL", ultimo().contains(I18n.t("vint.rech.local")));
            srv.ponerLlave(ref, false);
        }
        s.recibir(trama(0x11, "AUTO/LLN0$GO$Cls", 3, false));
        s.recibir(trama(0x11, "AUTO/LLN0$GO$Cls", 4, true));
        Thread.sleep(400);
        vaciarEdt();
        chk("cierre por GOOSE en REMOTO cierra", "CERRADO".equals(estado()));

        // 4. Forzado
        if (tieneLlave != null) srv.ponerLlave(ref, true);
        llamarForzar(BdaDoubleBitPos.DoubleBitPos.INTERMEDIATE_STATE);
        chk("forzado a intermedio", "INTERMEDIO".equals(estado()));
        llamar("mandoLocal", false);
        chk("mando con posición forzada: rechazado", ultimo().contains(I18n.t("vint.rech.forzada")));
        llamarForzar(null);
        chk("al soltar el forzado vuelve CERRADO", "CERRADO".equals(estado()));

        SwingUtilities.invokeAndWait(() -> v.dispose());
        srv.stop();
        System.out.println();
        System.out.println(ok + " pasaron, " + falla + " fallaron");
        if (falla == 0) System.out.println("TODO OK");
        System.exit(falla == 0 ? 0 : 1);
    }

    private static String estado() {
        for (EstadoLocal.Objeto o : srv.listarAparatos()) if (o.ref.equals(ref)) return o.estado;
        return null;
    }

    private static GooseSubscriber.GooseMessage trama(int appId, String gocbRef, int st, boolean valor) {
        GooseSubscriber.GooseMessage m = new GooseSubscriber.GooseMessage();
        m.appId = appId;
        m.gocbRef = gocbRef;
        m.stNum = st;
        m.timeAllowedToLive = 2000;
        m.dataEntries.add(new GooseSubscriber.DataEntry(0, "Boolean", valor));
        return m;
    }

    private static Object campo(String nombre) throws Exception {
        Field f = InterruptorVirtualDialog.class.getDeclaredField(nombre);
        f.setAccessible(true);
        return f.get(v);
    }

    @SuppressWarnings("unchecked")
    private static String ultimo() throws Exception {
        vaciarEdt();
        List<String> ev = (List<String>) campo("eventos");
        return ev.isEmpty() ? "" : ev.get(ev.size() - 1);
    }

    @SuppressWarnings("unchecked")
    private static boolean contiene(String texto) throws Exception {
        vaciarEdt();
        for (String e : (List<String>) campo("eventos")) if (e.contains(texto)) return true;
        return false;
    }

    private static void vaciarEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
    }

    private static void llamar(String metodo, Object... args) throws Exception {
        Class<?>[] tipos = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) tipos[i] = args[i] instanceof Boolean ? boolean.class : args[i].getClass();
        Method m = InterruptorVirtualDialog.class.getDeclaredMethod(metodo, tipos);
        m.setAccessible(true);
        SwingUtilities.invokeAndWait(() -> {
            try { m.invoke(v, args); } catch (Exception e) { throw new RuntimeException(e); }
        });
        Thread.sleep(150);
    }

    private static void llamarForzar(BdaDoubleBitPos.DoubleBitPos p) throws Exception {
        Method m = InterruptorVirtualDialog.class.getDeclaredMethod("forzar", BdaDoubleBitPos.DoubleBitPos.class);
        m.setAccessible(true);
        SwingUtilities.invokeAndWait(() -> {
            try { m.invoke(v, p); } catch (Exception e) { throw new RuntimeException(e); }
        });
        Thread.sleep(150);
    }
}
