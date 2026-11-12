import com.iednavigator.BobinasGoose;
import com.iednavigator.GooseSubscriber;
import com.iednavigator.IEC61850Server;
import com.iednavigator.mando.EstadoLocal;

import java.util.ArrayList;
import java.util.List;

/**
 * Bobinas del Interruptor virtual cableadas a GOOSE recibido (test/test_bay_control.cid).
 * Las tramas se arman a mano: no hace falta red ni Npcap.
 *
 *   1. una señal que ya llega en true no maniobra;
 *   2. el flanco false → true abre por la bobina de apertura, una sola vez aunque se retransmita;
 *   3. el flanco en la bobina de cierre cierra;
 *   4. otro GoCB, otro índice o un dato no booleano no hacen nada;
 *   5. con resorte descargado el cierre por GOOSE se rechaza y se avisa al observador;
 *   6. quitar el enlace lo desconecta.
 */
public class TestBobinasGoose {
    static int ok = 0, fallas = 0;
    static final String XCBR = "BAYCTRLCTRL/Q0XCBR1.Pos";
    static final String DISPARO = "PROTPROT/LLN0$GO$gcbDisparo";
    static IEC61850Server srv;
    static int stNum = 1;
    static final List<String> eventos = new ArrayList<>();

    public static void main(String[] a) throws Exception {
        srv = new IEC61850Server();
        ver(srv.loadSclFile("test/test_bay_control.cid"), "carga test_bay_control.cid");
        ver(srv.start(10483), "servidor iniciado");
        srv.agregarObservadorMando((origen, texto, aceptado) ->
                eventos.add(origen + "|" + (aceptado ? "+" : "-") + "|" + texto));
        srv.ordenarAparato(XCBR, true, "Panel");
        ver("CERRADO".equals(estado()), "arranca cerrado");

        BobinasGoose b = new BobinasGoose(srv);
        BobinasGoose.Enlace disparo = b.cablear(DISPARO, 1, BobinasGoose.Bobina.APERTURA, XCBR);
        b.cablear(DISPARO, 2, BobinasGoose.Bobina.CIERRE, XCBR);

        // 1. Ya en true al cablear
        b.recibir(trama(DISPARO, false, true, false));
        espera();
        ver("CERRADO".equals(estado()), "señal ya en true: no maniobra");

        // 2. Flanco de apertura
        b.recibir(trama(DISPARO, false, false, false));
        b.recibir(trama(DISPARO, false, true, false));
        espera();
        ver("ABIERTO".equals(estado()), "flanco en la bobina de apertura: abre");
        ver(eventos.stream().anyMatch(e -> e.startsWith("GOOSE|+|")), "el registro anota la orden por GOOSE");
        srv.ordenarAparato(XCBR, true, "Panel");
        b.recibir(trama(DISPARO, false, true, false)); // retransmisión
        espera();
        ver("CERRADO".equals(estado()), "retransmisión del true: no repite la apertura");

        // 3. Flanco de cierre
        srv.ordenarAparato(XCBR, false, "Panel");
        b.recibir(trama(DISPARO, false, false, false));
        b.recibir(trama(DISPARO, false, false, true));
        espera();
        ver("CERRADO".equals(estado()), "flanco en la bobina de cierre: cierra");

        // 4. Lo que no corresponde
        b.recibir(trama("OTRO/LLN0$GO$gcb", false, false, false));
        b.recibir(trama("OTRO/LLN0$GO$gcb", false, true, false));
        espera();
        ver("CERRADO".equals(estado()), "otro GoCB: nada");
        GooseSubscriber.GooseMessage m = trama(DISPARO, false, false, false);
        m.dataEntries.set(1, new GooseSubscriber.DataEntry(1, "Integer", 1));
        b.recibir(m);
        espera();
        ver("CERRADO".equals(estado()), "dato no booleano: nada");

        // 5. Resorte descargado
        b.recibir(trama(DISPARO, false, true, false));   // abre (el anterior false era de la bobina de cierre)
        espera();
        b.recibir(trama(DISPARO, false, false, false));
        espera();
        ver("ABIERTO".equals(estado()), "abierto antes de probar el resorte");
        srv.ponerResorteDescargado(XCBR, true);
        eventos.clear();
        b.recibir(trama(DISPARO, false, false, true));
        espera();
        ver("ABIERTO".equals(estado()), "resorte descargado: el cierre por GOOSE no cierra");
        ver(eventos.stream().anyMatch(e -> e.startsWith("GOOSE|-|")), "resorte descargado: el registro anota el rechazo");
        srv.ponerResorteDescargado(XCBR, false);

        // 6. Quitar el enlace
        srv.ordenarAparato(XCBR, true, "Panel");
        b.quitar(disparo);
        b.recibir(trama(DISPARO, false, false, false));
        b.recibir(trama(DISPARO, false, true, false));
        espera();
        ver("CERRADO".equals(estado()), "enlace quitado: no abre");
        ver(b.enlaces().size() == 1, "queda un enlace");

        srv.stop();
        System.out.println(ok + " pasaron, " + fallas + " fallaron");
        System.out.println(fallas == 0 ? "TODO OK" : "HAY FALLAS");
        System.exit(fallas == 0 ? 0 : 1);
    }

    static GooseSubscriber.GooseMessage trama(String gocb, boolean... valores) {
        GooseSubscriber.GooseMessage m = new GooseSubscriber.GooseMessage();
        m.gocbRef = gocb;
        m.stNum = stNum++;
        for (int i = 0; i < valores.length; i++) m.dataEntries.add(new GooseSubscriber.DataEntry(i, "Boolean", valores[i]));
        return m;
    }

    static String estado() {
        for (EstadoLocal.Objeto o : srv.listarAparatos()) if (o.ref.equals(XCBR)) return o.estado;
        return "?";
    }

    static void espera() throws InterruptedException { Thread.sleep(80); }

    static void ver(boolean c, String msg) {
        if (c) ok++; else fallas++;
        System.out.println((c ? "OK    " : "FALLA ") + msg);
    }
}
