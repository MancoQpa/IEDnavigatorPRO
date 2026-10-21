import javax.swing.SwingUtilities;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * La cabecera del servidor se corrige sola si la IP que muestra deja de ser de esta PC.
 *
 * El caso de campo (2026-10-19): la PC volvió de una suspensión, el DHCP le dio otra IP a
 * la Wi-Fi y la cabecera siguió diciendo la vieja. Un cliente que la copió dio timeout,
 * aunque el servidor escuchaba en todas las interfaces y seguía alcanzable por la nueva.
 *
 * Usa ConnectionManager.revisarIpLocal() con un contexto de mentira (Proxy): no hace falta
 * cambiar la IP de la PC. Una IP que no es de esta PC hace el papel de la vieja.
 *
 * Uso:  java -cp "classes;lib\*;test" TestCabeceraIp
 */
public class TestCabeceraIp {

    private static int fallas = 0;

    static void chk(String titulo, boolean ok, String detalle) {
        if (!ok) fallas++;
        System.out.printf("%-58s %-36s %s%n", titulo, detalle, ok ? "OK" : "<<< FALLA");
    }

    // Lo que el contexto de mentira registra
    static final List<String> cabeceras = Collections.synchronizedList(new ArrayList<>());
    static final List<String> log = Collections.synchronizedList(new ArrayList<>());
    static volatile boolean simulando = true;
    static final ExecutorService fondo = Executors.newSingleThreadExecutor();

    public static void main(String[] args) throws Exception {
        Class<?> cm = Class.forName("com.iednavigator.ConnectionManager");
        Class<?> ctxIf = Class.forName("com.iednavigator.ConnectionManager$Context");
        InvocationHandler h = (proxy, m, a) -> {
            switch (m.getName()) {
                case "isServerRunning":       return simulando;
                case "backgroundExecutor":    return fondo;
                case "updateConnectionInfo":  cabeceras.add(a[0] + ":" + a[1]); return null;
                case "log":                   log.add(String.valueOf(a[0])); return null;
                default:
                    Class<?> r = m.getReturnType();
                    return (r.isPrimitive() && r != void.class) ? Array.get(Array.newInstance(r, 1), 0) : null;
            }
        };
        Object ctx = Proxy.newProxyInstance(cm.getClassLoader(), new Class<?>[] { ctxIf }, h);
        Constructor<?> c = cm.getDeclaredConstructor(ctxIf);
        c.setAccessible(true);
        Object manager = c.newInstance(ctx);
        Method revisar = cm.getDeclaredMethod("revisarIpLocal");
        revisar.setAccessible(true);
        Method esDeEstaPc = cm.getDeclaredMethod("esIpDeEstaPc", String.class);
        esDeEstaPc.setAccessible(true);
        Method ipLocal = cm.getDeclaredMethod("getLocalIpAddress");
        ipLocal.setAccessible(true);
        Field host = cm.getDeclaredField("currentHost");
        host.setAccessible(true);
        Field puerto = cm.getDeclaredField("currentPort");
        puerto.setAccessible(true);

        // 203.0.113.0/24 es TEST-NET-3 (RFC 5737): no puede ser de esta PC
        String vieja = "203.0.113.77";
        String actual = (String) ipLocal.invoke(manager);
        System.out.println("IP local de esta PC: " + actual + "\n");

        chk("esIpDeEstaPc: 127.0.0.1", (boolean) esDeEstaPc.invoke(null, "127.0.0.1"), "");
        chk("esIpDeEstaPc: la IP local", (boolean) esDeEstaPc.invoke(null, actual), actual);
        chk("esIpDeEstaPc: una de TEST-NET-3, no", !(boolean) esDeEstaPc.invoke(null, vieja), vieja);

        // 1) La IP mostrada ya no es de esta PC: se reemplaza
        host.set(manager, vieja);
        puerto.set(manager, 10102);
        correr(revisar, manager);
        chk("cabecera actualizada a la IP actual", cabeceras.equals(List.of(actual + " (servidor):10102")), cabeceras.toString());
        chk("currentHost quedó en la IP actual", actual.equals(host.get(manager)), String.valueOf(host.get(manager)));
        String esperado = com.iednavigator.I18n.t("log.cm.ipchanged", vieja, actual, "10102");
        chk("una línea en el log con vieja, nueva y puerto", log.equals(List.of(esperado)), log.size() + " línea(s)");

        // 2) La IP mostrada sigue siendo de esta PC: no se toca
        cabeceras.clear(); log.clear();
        correr(revisar, manager);
        chk("con la IP vigente no cambia nada", cabeceras.isEmpty() && log.isEmpty(), cabeceras + " " + log);

        // 2b) Se mostraba 127.0.0.1 (no había red) y la red volvió: se pasa a la IP de red.
        //     El caso de campo del 2026-10-20: la loopback siempre "es de esta PC" y la
        //     cabecera quedaba trabada en 127.0.0.1.
        Method privada = cm.getDeclaredMethod("esIpPrivada", String.class);
        privada.setAccessible(true);
        chk("esIpPrivada: 192.168.1.5, 10.0.0.1, 172.16.0.1", (boolean) privada.invoke(null, "192.168.1.5")
            && (boolean) privada.invoke(null, "10.0.0.1") && (boolean) privada.invoke(null, "172.16.0.1"), "");
        chk("esIpPrivada: 127.0.0.1, 172.32.0.1, 169.254.1.1, no", !(boolean) privada.invoke(null, "127.0.0.1")
            && !(boolean) privada.invoke(null, "172.32.0.1") && !(boolean) privada.invoke(null, "169.254.1.1"), "");
        if ((boolean) privada.invoke(null, actual)) {
            host.set(manager, "127.0.0.1");
            correr(revisar, manager);
            chk("de 127.0.0.1 a la IP de red cuando vuelve", cabeceras.equals(List.of(actual + " (servidor):10102")), cabeceras.toString());
            String vuelta = com.iednavigator.I18n.t("log.cm.ipavailable", "127.0.0.1", actual, "10102");
            chk("  con el aviso de que volvió la red", log.equals(List.of(vuelta)), log.size() + " línea(s)");
            cabeceras.clear(); log.clear();
        } else {
            System.out.println("(sin IP privada en esta PC: se salta el caso 127.0.0.1 -> red)");
        }

        // 3) Simulación detenida: no se toca aunque la IP sea vieja
        host.set(manager, vieja);
        simulando = false;
        correr(revisar, manager);
        chk("sin simulación no cambia nada", cabeceras.isEmpty() && log.isEmpty(), cabeceras + " " + log);
        chk("  y currentHost queda como estaba", vieja.equals(host.get(manager)), String.valueOf(host.get(manager)));

        fondo.shutdown();
        System.out.println(fallas == 0 ? "\nTODO OK" : "\n" + fallas + " FALLAS");
        System.exit(fallas == 0 ? 0 : 1);
    }

    /** Llama a revisarIpLocal() en el EDT y espera a que terminen el fondo y el EDT. */
    static void correr(Method revisar, Object manager) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try { revisar.invoke(manager); } catch (Exception e) { throw new RuntimeException(e); }
        });
        fondo.submit(() -> { }).get(5, TimeUnit.SECONDS);   // lo que se encoló en el fondo
        SwingUtilities.invokeAndWait(() -> { });             // y lo que ese fondo devolvió al EDT
    }
}
