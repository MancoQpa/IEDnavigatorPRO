import com.iednavigator.GoosePublisher;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/**
 * Tras un hueco —la PC hibernó, o una ejecución tardó de más— un planificador periódico no
 * debe recuperar las ejecuciones "perdidas" de golpe.
 *
 * El caso de campo (2026-10-18): GoosePublisher usaba scheduleAtFixedRate. Tras 6 días de
 * hibernación el reloj del planificador había avanzado ~517.000 s y salieron ~517.000
 * latidos seguidos, a ~340 tramas/s durante 25 minutos. PollingManager tenía lo mismo, y
 * contra un IED real habría sido una ráfaga de lecturas MMS.
 *
 * El hueco se simula con 3 s de bloqueo y un período de 100 ms: con ritmo fijo salen ~30
 * ejecuciones seguidas al liberar; con demora fija, una.
 *
 * Uso:  java -cp "classes;lib\*;test" TestPlanificadorHueco
 */
public class TestPlanificadorHueco {

    private static int fallas = 0;

    static void chk(String titulo, boolean ok, String detalle) {
        if (!ok) fallas++;
        System.out.printf("%-60s %-22s %s%n", titulo, detalle, ok ? "OK" : "<<< FALLA");
    }

    public static void main(String[] args) throws Exception {
        probarGoosePublisher();
        probarPollingManager();
        System.out.println(fallas == 0 ? "\nTODO OK" : "\n" + fallas + " FALLAS");
        System.exit(fallas == 0 ? 0 : 1);
    }

    /** Sin init(): no hay interfaz de red, pero el latido corre y sqNum avanza igual. */
    static void probarGoosePublisher() throws Exception {
        GoosePublisher pub = new GoosePublisher();
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        pub.setLogListener(log::add);
        pub.setHeartbeatInterval(100);
        pub.startPublishing();
        Thread.sleep(550);
        chk("GOOSE: latido normal antes del hueco", pub.getSqNum() >= 4 && pub.getSqNum() <= 7,
            "sqNum=" + pub.getSqNum());

        // El hueco: se toma el lock de los contadores 3 s; el latido en curso queda esperando.
        Field f = GoosePublisher.class.getDeclaredField("counterLock");
        f.setAccessible(true);
        Object lock = f.get(pub);
        int antes;
        synchronized (lock) {
            antes = pub.getSqNum();
            Thread.sleep(3000);
        }
        Thread.sleep(60);
        int tras = pub.getSqNum() - antes;
        chk("GOOSE: tras 3 s de hueco no recupera latidos", tras <= 2, tras + " latidos en 60 ms");

        Thread.sleep(500);
        int ritmo = pub.getSqNum() - antes - tras;
        chk("GOOSE: después sigue al ritmo configurado", ritmo >= 3 && ritmo <= 7,
            ritmo + " latidos en 500 ms");
        boolean avisado = log.stream().anyMatch(l -> l.contains("3 s") || l.contains("3 s "));
        chk("GOOSE: el hueco queda en el log", avisado, avisado ? "sí" : "no: " + log);
        pub.stopPublishing();
        pub.close();
    }

    /**
     * PollingManager es de paquete: se arma por reflexión con un Context de mentira que cuenta
     * los ciclos (cada ciclo pregunta isConnected()) y hace que el tercero tarde 3 s.
     */
    static void probarPollingManager() throws Exception {
        Class<?> pm = Class.forName("com.iednavigator.PollingManager");
        Class<?> ctxIf = Class.forName("com.iednavigator.PollingManager$Context");
        AtomicInteger ciclos = new AtomicInteger();
        InvocationHandler h = (proxy, m, a) -> {
            switch (m.getName()) {
                case "isConnected":
                    if (ciclos.incrementAndGet() == 3) Thread.sleep(3000);   // el hueco
                    return false;                                           // no lee nada
                case "getPollingInterval": return 100;
                default:
                    Class<?> r = m.getReturnType();
                    // valor por defecto del tipo (false, 0, 0L...), o null si es un objeto
                    return (r.isPrimitive() && r != void.class) ? Array.get(Array.newInstance(r, 1), 0) : null;
            }
        };
        Object ctx = Proxy.newProxyInstance(pm.getClassLoader(), new Class<?>[] { ctxIf }, h);
        Constructor<?> c = pm.getDeclaredConstructor(ctxIf);
        c.setAccessible(true);
        Object manager = c.newInstance(ctx);
        Method start = pm.getDeclaredMethod("start"); start.setAccessible(true);
        Method stop  = pm.getDeclaredMethod("stop");  stop.setAccessible(true);

        start.invoke(manager);
        while (ciclos.get() < 3) Thread.sleep(10);     // el tercer ciclo entra al hueco
        Thread.sleep(2950);                             // casi termina el hueco
        int alSalir = ciclos.get();
        Thread.sleep(150);                              // 50 ms después de liberar + margen
        int tras = ciclos.get() - alSalir;
        chk("Polling: tras 3 s de hueco no recupera ciclos", tras <= 2, tras + " ciclos en 150 ms");
        stop.invoke(manager);
    }
}
