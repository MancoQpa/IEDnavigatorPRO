import com.iednavigator.WrapLayout;

import javax.swing.*;
import java.awt.*;

/**
 * Banco de WrapLayout: la fila de controles de GOOSE tiene que pedir mas altura cuando los
 * botones saltan de linea, y ningun boton puede quedar fuera del area visible del panel.
 *
 * El defecto que motivo esto: con FlowLayout, en una pantalla de 1366x768 el boton
 * "CAPTURAR GOOSE" quedaba en una segunda linea que el panel no mostraba.
 *
 * Corre sin pantalla (java.awt.headless): solo mide y acomoda, no dibuja.
 */
public class TestWrapLayout {

    static int fallas = 0;

    static void check(String que, boolean ok) {
        System.out.println((ok ? "OK    " : "FALLA ") + que);
        if (!ok) fallas++;
    }

    /** Arma una fila como la de GOOSE: combo ancho + boton chico + boton de 160 + etiqueta. */
    static JPanel fila(LayoutManager lm) {
        JPanel p = new JPanel(lm);
        Component[] cs = {
            caja(70, 22), caja(300, 26), caja(30, 26), caja(15, 1), caja(160, 28), caja(120, 20)
        };
        for (Component c : cs) p.add(c);
        return p;
    }

    static JComponent caja(int w, int h) {
        JPanel c = new JPanel();
        Dimension d = new Dimension(w, h);
        c.setPreferredSize(d);
        c.setMinimumSize(d);
        return c;
    }

    /** Acomoda la fila al ancho dado, con la altura que la propia fila pide. */
    static JPanel acomodar(LayoutManager lm, int ancho) {
        JPanel p = fila(lm);
        p.setSize(ancho, 10);                       // primero el ancho, como hace el padre
        int alto = p.getPreferredSize().height;
        p.setSize(ancho, alto);
        p.doLayout();
        return p;
    }

    static boolean todoVisible(JPanel p) {
        for (Component c : p.getComponents()) {
            Rectangle r = c.getBounds();
            if (r.y + r.height > p.getHeight() || r.x + r.width > p.getWidth()) return false;
        }
        return true;
    }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        final int ANCHO_HOLGADO = 900, ANCHO_ANGOSTO = 480;

        // Una sola linea: mismo alto que FlowLayout, no cambia nada donde ya entraba.
        JPanel flowAncho = acomodar(new FlowLayout(FlowLayout.LEFT, 8, 3), ANCHO_HOLGADO);
        JPanel wrapAncho = acomodar(new WrapLayout(FlowLayout.LEFT, 8, 3), ANCHO_HOLGADO);
        check("ancho holgado: mismo alto que FlowLayout (" + wrapAncho.getHeight() + " = "
                + flowAncho.getHeight() + ")", wrapAncho.getHeight() == flowAncho.getHeight());
        check("ancho holgado: todo visible", todoVisible(wrapAncho));

        // El defecto, reproducido con FlowLayout.
        JPanel flow = acomodar(new FlowLayout(FlowLayout.LEFT, 8, 3), ANCHO_ANGOSTO);
        check("ancho angosto, FlowLayout: reproduce el defecto (algo queda fuera)", !todoVisible(flow));

        // Con WrapLayout: mas alto y todo dentro.
        JPanel wrap = acomodar(new WrapLayout(FlowLayout.LEFT, 8, 3), ANCHO_ANGOSTO);
        check("ancho angosto, WrapLayout: pide mas alto (" + wrap.getHeight() + " > "
                + flow.getHeight() + ")", wrap.getHeight() > flow.getHeight());
        check("ancho angosto, WrapLayout: todo visible", todoVisible(wrap));

        // Muy angosto: una linea por componente, todo visible igual.
        JPanel muy = acomodar(new WrapLayout(FlowLayout.LEFT, 8, 3), 320);
        check("muy angosto (320 px): todo visible", todoVisible(muy));

        // El minimo no debe impedir achicar el panel mas alla de la suma de los anchos.
        int min = new WrapLayout(FlowLayout.LEFT, 8, 3).minimumLayoutSize(acomodar(new WrapLayout(FlowLayout.LEFT, 8, 3), ANCHO_ANGOSTO)).width;
        check("minimo de ancho menor que la fila completa (" + min + " < 700)", min < 700);

        System.out.println();
        System.out.println(fallas == 0 ? "TODO OK" : fallas + " FALLA(S)");
        if (fallas > 0) System.exit(1);
    }
}
