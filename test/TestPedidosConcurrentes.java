import com.beanit.iec61850bean.*;

import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Dos hilos que piden a la vez por la misma asociación MMS no se cruzan las respuestas.
 *
 * El caso de campo (2026-10-29, contra el simulador Android): en el mismo segundo salieron la
 * lectura del ctlModel (diálogo de mando), la lectura mínima del polling y un setBlocking. La
 * lectura del ctlModel falló con "TYPE_CONFLICT(10) expected type: integer", el setBlocking con
 * ServiceError 11, y diez segundos después el latido dio TIMEOUT y se cerró la asociación.
 *
 * ClientAssociation de iec61850bean 1.9.0 no serializa los pedidos: comparte el buffer de salida,
 * la cola de respuestas y un único "pedido esperado". Con dos pedidos en vuelo, un hilo puede
 * tomar la respuesta del otro (y decodificar un BOOLEAN donde esperaba un entero) y el otro se
 * queda sin respuesta.
 *
 * Uso:  javac -encoding UTF-8 -cp "classes;lib\*;test" -d test test\TestPedidosConcurrentes.java
 *       java  -cp "classes;lib\*;test" TestPedidosConcurrentes
 */
public class TestPedidosConcurrentes {

    private static int fallas = 0;

    static void chk(String titulo, boolean ok, String detalle) {
        if (!ok) fallas++;
        System.out.printf("%-58s %-30s %s%n", titulo, detalle, ok ? "OK" : "<<< FALLA");
    }

    public static void main(String[] args) throws Exception {
        int puerto = 10312;
        List<ServerModel> models = SclParser.parse("test/test_bay_control.cid");
        ServerSap sap = new ServerSap(puerto, 0, null, models.get(0), null);
        sap.startListening(new ServerEventListener() {
            @Override public List<ServiceError> write(List<BasicDataAttribute> b) { return null; }
            @Override public void serverStoppedListening(ServerSap s) { }
        });

        ClientSap cs = new ClientSap();
        cs.setResponseTimeout(3000);
        ClientAssociation as = cs.associate(InetAddress.getByName("127.0.0.1"), puerto, null, null);
        ServerModel m = as.retrieveModel();

        // Atributos de tipos distintos: si una respuesta se cruza, decodificarla en el otro tipo
        // falla con TYPE_CONFLICT, igual que en el campo.
        List<FcModelNode> nodos = new ArrayList<>();
        Map<String, String> esperado = new HashMap<>();
        Set<String> clases = new HashSet<>();
        for (BasicDataAttribute b : m.getBasicDataAttributes()) {
            if (b.getFc() == Fc.CO || b.getFc() == Fc.SE || b.getFc() == Fc.SG) continue;
            if (clases.add(b.getClass().getSimpleName())) nodos.add(b);
            if (nodos.size() >= 6) break;
        }
        for (FcModelNode n : nodos) {
            as.getDataValues(n);
            esperado.put(n.getReference() + "$" + n.getFc(), ((BasicDataAttribute) n).getValueString());
        }
        System.out.println("  " + nodos.size() + " atributos de tipos distintos: " + clases);

        int hilos = 6, vueltas = 300;
        AtomicInteger ok = new AtomicInteger(), errores = new AtomicInteger(), malos = new AtomicInteger();
        Map<String, Integer> motivos = new ConcurrentHashMap<>();
        ExecutorService ex = Executors.newFixedThreadPool(hilos);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<?>> fs = new ArrayList<>();
        for (int h = 0; h < hilos; h++) {
            final FcModelNode n = nodos.get(h % nodos.size());
            fs.add(ex.submit(() -> {
                largada.await();
                for (int i = 0; i < vueltas; i++) {
                    try {
                        as.getDataValues(n);
                        String v = ((BasicDataAttribute) n).getValueString();
                        String e = esperado.get(n.getReference() + "$" + n.getFc());
                        if (Objects.equals(v, e)) ok.incrementAndGet(); else malos.incrementAndGet();
                    } catch (Exception e) {
                        errores.incrementAndGet();
                        String k = e.getClass().getSimpleName() + ": " + e.getMessage();
                        motivos.merge(k.length() > 70 ? k.substring(0, 70) : k, 1, Integer::sum);
                    }
                }
                return null;
            }));
        }
        long t0 = System.currentTimeMillis();
        largada.countDown();
        for (Future<?> f : fs) f.get(120, TimeUnit.SECONDS);
        long dt = System.currentTimeMillis() - t0;
        ex.shutdown();
        motivos.forEach((k, v) -> System.out.println("    " + v + " x " + k));

        int total = hilos * vueltas;
        chk("lecturas concurrentes sin error", errores.get() == 0, errores.get() + " errores de " + total);
        chk("ningún hilo recibió el valor de otro", malos.get() == 0, malos.get() + " valores cruzados");
        chk("todas las lecturas correctas", ok.get() == total, ok.get() + "/" + total + " en " + dt + " ms");

        // Después de la ráfaga la asociación sigue sana
        boolean sana;
        try { as.getDataValues(nodos.get(0)); sana = true; } catch (Exception e) { sana = false; }
        chk("la asociación sigue usable después", sana, sana ? "sí" : "no");

        as.close();
        sap.stop();
        System.out.println(fallas == 0 ? "TODO OK" : fallas + " FALLAS");
        System.exit(fallas == 0 ? 0 : 1);
    }
}
