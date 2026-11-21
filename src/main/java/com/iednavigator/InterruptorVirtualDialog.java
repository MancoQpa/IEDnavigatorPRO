package com.iednavigator;

import com.beanit.iec61850bean.BdaDoubleBitPos;
import com.iednavigator.mando.EstadoLocal;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * Interruptor virtual: un aparato de maniobra del modelo servido, manejado como a pie de equipo,
 * con el aspecto y el comportamiento de un panel de prueba de interruptor.
 *
 * La ventana principal es angosta (360 px) y tiene solo lo de a pie de equipo: estado de
 * comunicación (MMS, GOOSE Pub, GOOSE Sub), símbolo y estado, selector LOCAL/REMOTO, ABRIR y
 * CERRAR, contactos 52a/52b y el último evento. Lo demás va en ventanas aparte desde el menú:
 * panel del instructor, registro de eventos, datos de conexión, suscripción y publicación GOOSE.
 *
 * Los botones locales solo actúan con el selector en LOCAL, y como en la referencia se rechazan
 * si el aparato ya está en la posición pedida, si está en maniobra o si el instructor forzó la
 * posición. Por GOOSE el disparo actúa siempre; el cierre se bloquea en LOCAL.
 *
 * Todo pasa por IEC61850Server, que usa el mismo motor de mando del IEDsimulatorPRO: los cambios
 * salen por los reportes y actualizan el árbol, el monitor y los GOOSE que se estén publicando.
 */
final class InterruptorVirtualDialog extends JDialog {

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final int ANCHO = 360;
    private static final int ANCHO_INSTRUCTOR = 380;
    private static final Preferences PREFS = Preferences.userRoot().node("iednavigator/vint");

    private final IEC61850Server srv;
    /** Pestaña GOOSE de la ventana principal; null si no hay (prueba sin ventana). */
    private final GoosePanel goose;
    /** Aparato de esta ventana ("Virtualizar aparato"), o null en la ventana general. */
    private final String refFija;
    /** En la ventana de un aparato: prefijos de referencia cuyos eventos van a su registro. */
    private final List<String> prefijosPropios = new ArrayList<>();

    private final JComboBox<Item> cbAparato = new JComboBox<>();
    private final JLabel lblIdent = new JLabel(" ", SwingConstants.CENTER);
    private final JToggleButton btnEstadoCom = new JToggleButton(VintComponentes.icono("estado", null));
    private final JToggleButton btnPin = new JToggleButton(VintComponentes.icono("pin", VintComponentes.TENUE));
    private final JPanel cajaLeds = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
    private final VintComponentes.Led ledMms = new VintComponentes.Led(12);
    private final VintComponentes.Led ledPub = new VintComponentes.Led(12);
    private final VintComponentes.Led ledSub = new VintComponentes.Led(12);
    private final JLabel lblMms = new JLabel(), lblPub = new JLabel(), lblSub = new JLabel();
    private final VintComponentes.Simbolo simbolo = new VintComponentes.Simbolo();
    private final JLabel lblEstado = new JLabel(" ");
    private final JLabel lblCtl = VintComponentes.tenue(" ", 11f);
    private final JLabel lblComandado = VintComponentes.tenue(" ", 11f);
    private final JToggleButton tbLocal, tbRemoto;
    private final VintComponentes.BotonMando btnAbrir, btnCerrar;
    private final VintComponentes.Led led52a = new VintComponentes.Led(14), led52b = new VintComponentes.Led(14);
    private final JLabel lblUltimo = new JLabel(" ");

    private final SuscripcionGoose suscripcion;
    private final java.util.function.Consumer<GooseSubscriber.GooseMessage> oyenteGoose;
    private final Timer refresco;
    private final IEC61850Server.ObservadorMando observador;

    /** Eventos de esta ventana: texto completo y filas (hora, origen, evento) para CSV. */
    private final List<String> eventos = new ArrayList<>();
    private final List<String[]> filasEventos = new ArrayList<>();
    private JDialog dlgRegistro;
    private JTextArea taRegistro;
    private PanelInstructor instructor;

    /** Posición forzada por el instructor y la que tenía antes, para devolverla. */
    private volatile BdaDoubleBitPos.DoubleBitPos forzada;
    private String estadoAntesDeForzar;
    private boolean calidadInvalida;
    /** Aparato al que corresponden los forzados y la suscripción cargada (lo lee el hilo de captura). */
    private volatile String refActual;
    /** true mientras se actualiza la pantalla desde el modelo: los cambios no son del usuario. */
    private boolean actualizando;
    private String identTexto = " ";

    /** Elemento del combo de aparatos. */
    private static final class Item {
        final EstadoLocal.Objeto o;
        Item(EstadoLocal.Objeto o) { this.o = o; }
        @Override public String toString() {
            return o.nombre + "  —  " + o.ld + (o.comandadoPor.isEmpty() ? "" : "   (" + o.comandadoPor + ")");
        }
    }

    InterruptorVirtualDialog(Frame owner, IEC61850Server srv) {
        this(owner, srv, null);
    }

    InterruptorVirtualDialog(Frame owner, IEC61850Server srv, GoosePanel goose) {
        this(owner, srv, goose, null);
    }

    /**
     * @param refFija referencia del aparato ("LD/XCBR1.Pos") para una ventana propia de ese
     *        aparato ("Virtualizar aparato" del arbol), o null para la ventana general con el
     *        selector. La ventana de un aparato es independiente de la principal (sin dueño:
     *        no queda siempre encima y se puede tener una por aparato, lado a lado) y su
     *        registro muestra solo los eventos de ese aparato.
     */
    InterruptorVirtualDialog(Frame owner, IEC61850Server srv, GoosePanel goose, String refFija) {
        super(refFija == null ? owner : null, I18n.t("vint.titulo"), false);
        this.srv = srv;
        this.goose = goose;
        this.refFija = refFija;
        if (owner != null) setIconImages(owner.getIconImages());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JToggleButton[] seg = VintComponentes.segmentado(I18n.t("vint.llave.local"), I18n.t("vint.llave.remoto"));
        tbLocal = seg[0];
        tbRemoto = seg[1];
        btnAbrir = new VintComponentes.BotonMando(I18n.t("vint.abrir"), VintComponentes.VERDE);
        btnCerrar = new VintComponentes.BotonMando(I18n.t("vint.cerrar"), VintComponentes.ROJO);

        suscripcion = new SuscripcionGoose(this::ordenGoose);
        oyenteGoose = suscripcion::recibir;

        setJMenuBar(menu());
        setContentPane(contenido());

        cargarAparatos();
        cbAparato.addActionListener(e -> cambiarAparato());
        observador = (origen, texto, aceptado) -> SwingUtilities.invokeLater(() -> {
            if (esPropio(texto)) registrar(origen, texto, aceptado);
        });
        srv.agregarObservadorMando(observador);
        if (goose != null) goose.agregarOyenteGoose(oyenteGoose);
        refresco = new Timer(300, e -> refrescar());
        refresco.start();
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) { alCerrar(); }
        });

        Item it = (Item) cbAparato.getSelectedItem();
        if (refFija == null) {
            registrar(I18n.t("vint.origen.sistema"), I18n.t("vint.evt.abierto", String.valueOf(cbAparato.getItemCount())), true);
        } else {
            if (it != null) {
                setTitle(I18n.t("vint.titulo.aparato", it.o.nombre, it.o.ld));
                String ln = it.o.ref.contains(".") ? it.o.ref.substring(0, it.o.ref.indexOf('.')) : it.o.ref;
                prefijosPropios.add(ln);
                for (String c : it.o.comandadoPor.split("[,;\\s]+")) {
                    if (!c.isEmpty()) prefijosPropios.add(it.o.ld + "/" + c);
                }
            }
            registrar(I18n.t("vint.origen.sistema"), I18n.t("vint.evt.virtualizado", refFija), true);
        }
        cambiarAparato();
        btnEstadoCom.setSelected(PREFS.getBoolean("mostrarEstado", true));
        cajaLeds.setVisible(btnEstadoCom.isSelected());
        refrescar();
        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Ventana principal
    // ─────────────────────────────────────────────────────────────────────────

    private JMenuBar menu() {
        JMenuBar mb = new JMenuBar();
        int ctrl = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

        JMenu archivo = new JMenu(I18n.t("vint.m.archivo"));
        archivo.add(item(I18n.t("vint.m.guardar"), KeyStroke.getKeyStroke(KeyEvent.VK_S, ctrl), this::guardarRegistro));
        archivo.addSeparator();
        archivo.add(item(I18n.t("vint.m.cerrar"), KeyStroke.getKeyStroke(KeyEvent.VK_W, ctrl), this::dispose));
        mb.add(archivo);

        JMenu ver = new JMenu(I18n.t("vint.m.ver"));
        ver.add(item(I18n.t("vint.m.registro"), KeyStroke.getKeyStroke(KeyEvent.VK_L, ctrl), this::mostrarRegistro));
        JCheckBoxMenuItem encima = new JCheckBoxMenuItem(I18n.t("vint.m.encima"));
        encima.addActionListener(e -> ponerEncima(encima.isSelected()));
        btnPin.addItemListener(e -> encima.setSelected(btnPin.isSelected()));
        ver.add(encima);
        mb.add(ver);

        JMenu com = new JMenu(I18n.t("vint.m.com"));
        com.add(item(I18n.t("vint.m.conexion"), null, this::mostrarConexion));
        com.addSeparator();
        com.add(item(I18n.t("vint.m.sub"), null, this::editarSuscripcion));
        com.add(item(I18n.t("vint.m.pub"), null, this::editarPublicacion));
        mb.add(com);

        JMenu herr = new JMenu(I18n.t("vint.m.herr"));
        herr.add(item(I18n.t("vint.m.instructor"),
                KeyStroke.getKeyStroke(KeyEvent.VK_I, ctrl | InputEvent.SHIFT_DOWN_MASK), this::mostrarInstructor));
        mb.add(herr);
        return mb;
    }

    private static JMenuItem item(String texto, KeyStroke atajo, Runnable accion) {
        JMenuItem mi = new JMenuItem(texto);
        if (atajo != null) mi.setAccelerator(atajo);
        mi.addActionListener(e -> accion.run());
        return mi;
    }

    private JPanel contenido() {
        // Ancho fijo como la referencia: lo que no entra se recorta con "…" y queda en el tooltip
        JPanel p = new JPanel() {
            @Override public Dimension getPreferredSize() {
                return new Dimension(ANCHO, super.getPreferredSize().height);
            }
        };
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(new EmptyBorder(10, 16, 10, 16));

        if (refFija == null) {
            cbAparato.setToolTipText(I18n.t("vint.aparato"));
            p.add(ancho(cbAparato));
            p.add(Box.createVerticalStrut(8));
        }

        // Identidad: estado de comunicación · IED/aparato · IP:puerto · siempre visible
        JPanel ident = new JPanel(new BorderLayout(6, 0));
        estiloIcono(btnEstadoCom, I18n.t("vint.m.estado.tip"));
        btnEstadoCom.addActionListener(e -> {
            cajaLeds.setVisible(btnEstadoCom.isSelected());
            PREFS.putBoolean("mostrarEstado", btnEstadoCom.isSelected());
            pack();
        });
        estiloIcono(btnPin, I18n.t("vint.m.pin.tip"));
        btnPin.addActionListener(e -> ponerEncima(btnPin.isSelected()));
        lblIdent.setFont(lblIdent.getFont().deriveFont(Font.BOLD, 12f));
        lblIdent.setForeground(VintComponentes.ACENTO);
        lblIdent.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        lblIdent.setToolTipText("<html>" + I18n.t("vint.m.ident.tip") + "</html>");
        lblIdent.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e) || e.isPopupTrigger()) menuIdent(e);
                else if (SwingUtilities.isLeftMouseButton(e)) copiar(ipServidor(), "IP");
            }
            @Override public void mouseReleased(MouseEvent e) { if (e.isPopupTrigger()) menuIdent(e); }
        });
        ident.add(btnEstadoCom, BorderLayout.WEST);
        ident.add(lblIdent, BorderLayout.CENTER);
        ident.add(btnPin, BorderLayout.EAST);
        p.add(ancho(ident));

        // LEDs de comunicación
        cajaLeds.add(led(ledMms, lblMms, I18n.t("vint.m.mms"), null));
        cajaLeds.add(led(ledPub, lblPub, I18n.t("vint.m.pub.led"), this::editarPublicacion));
        cajaLeds.add(led(ledSub, lblSub, I18n.t("vint.m.sub.led"), this::editarSuscripcion));
        cajaLeds.setBorder(new EmptyBorder(6, 0, 0, 0));
        p.add(ancho(cajaLeds));

        // Símbolo y estado
        JPanel est = new JPanel(new BorderLayout(10, 0));
        est.add(simbolo, BorderLayout.WEST);
        JPanel textos = new JPanel();
        textos.setLayout(new BoxLayout(textos, BoxLayout.Y_AXIS));
        lblEstado.setFont(lblEstado.getFont().deriveFont(Font.BOLD, 26f));
        textos.add(Box.createVerticalGlue());
        textos.add(izq(lblEstado));
        textos.add(Box.createVerticalStrut(4));
        textos.add(izq(lblCtl));
        textos.add(izq(lblComandado));
        textos.add(Box.createVerticalGlue());
        est.add(textos, BorderLayout.CENTER);
        est.setBorder(new EmptyBorder(4, 0, 4, 0));
        p.add(ancho(est));

        // Selector LOCAL | REMOTO
        JPanel selector = new JPanel(new GridLayout(1, 2, 0, 0));
        selector.add(tbLocal);
        selector.add(tbRemoto);
        tbLocal.addActionListener(e -> ponerSelector(true));
        tbRemoto.addActionListener(e -> ponerSelector(false));
        p.add(ancho(selector));
        p.add(Box.createVerticalStrut(8));

        // ABRIR / CERRAR
        JPanel mando = new JPanel(new GridLayout(1, 2, 8, 0));
        btnAbrir.addActionListener(e -> mandoLocal(false));
        btnCerrar.addActionListener(e -> mandoLocal(true));
        mando.add(btnAbrir);
        mando.add(btnCerrar);
        p.add(ancho(mando));
        p.add(Box.createVerticalStrut(10));

        // Contactos auxiliares
        JPanel aux = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JLabel tAux = new JLabel(I18n.t("vint.contactos"));
        tAux.setFont(tAux.getFont().deriveFont(Font.BOLD, 11.5f));
        aux.add(tAux);
        JLabel l52a = new JLabel(I18n.t("vint.m.52a"));
        JLabel l52b = new JLabel(I18n.t("vint.m.52b"));
        led52a.setToolTipText(I18n.t("vint.m.52a.tip"));
        l52a.setToolTipText(I18n.t("vint.m.52a.tip"));
        led52b.setToolTipText(I18n.t("vint.m.52b.tip"));
        l52b.setToolTipText(I18n.t("vint.m.52b.tip"));
        aux.add(led52a);
        aux.add(l52a);
        aux.add(Box.createHorizontalStrut(6));
        aux.add(led52b);
        aux.add(l52b);
        p.add(ancho(aux));
        p.add(Box.createVerticalStrut(8));

        // Último evento
        JSeparator sep = new JSeparator();
        p.add(ancho(sep));
        JLabel tUlt = VintComponentes.tenue(I18n.t("vint.m.ultimo"), 10.5f);
        tUlt.setFont(tUlt.getFont().deriveFont(Font.BOLD));
        p.add(izq(tUlt));
        lblUltimo.setFont(lblUltimo.getFont().deriveFont(11.5f));
        lblUltimo.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        lblUltimo.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { mostrarRegistro(); }
        });
        p.add(ancho(lblUltimo));
        return p;
    }

    private static void estiloIcono(AbstractButton b, String tip) {
        b.setToolTipText(tip);
        b.setFocusPainted(false);
        b.setMargin(new Insets(1, 4, 1, 4));
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setPreferredSize(new Dimension(30, 24));
    }

    private JPanel led(VintComponentes.Led led, JLabel etiqueta, String texto, Runnable alClic) {
        JPanel f = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        etiqueta.setText(texto);
        etiqueta.setFont(etiqueta.getFont().deriveFont(11.5f));
        f.add(led);
        f.add(etiqueta);
        if (alClic != null) {
            f.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            MouseAdapter m = new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { alClic.run(); }
            };
            f.addMouseListener(m);
            etiqueta.addMouseListener(m);
            led.addMouseListener(m);
        }
        return f;
    }

    private static JComponent ancho(JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
        return c;
    }

    private static JComponent izq(JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
    }

    private void ponerEncima(boolean si) {
        setAlwaysOnTop(si);
        if (btnPin.isSelected() != si) btnPin.setSelected(si);
    }

    private void menuIdent(MouseEvent e) {
        Item it = (Item) cbAparato.getSelectedItem();
        String ip = ipServidor();
        String puerto = String.valueOf(srv.getPort());
        JPopupMenu m = new JPopupMenu();
        m.add(item(I18n.t("vint.m.copiar.ip", ip), null, () -> copiar(ip, "IP")));
        m.add(item(I18n.t("vint.m.copiar.puerto", puerto), null, () -> copiar(puerto, I18n.t("vint.c.puerto"))));
        m.add(item(I18n.t("vint.m.copiar.ipp", ip + ":" + puerto), null, () -> copiar(ip + ":" + puerto, "IP:" + puerto)));
        if (it != null) {
            m.add(item(I18n.t("vint.m.copiar.ld", it.o.ld), null, () -> copiar(it.o.ld, "LD")));
            m.add(item(I18n.t("vint.m.copiar.ref", it.o.ref), null, () -> copiar(it.o.ref, it.o.nombre)));
        }
        m.addSeparator();
        m.add(item(I18n.t("vint.m.conexion"), null, this::mostrarConexion));
        m.show(lblIdent, e.getX(), e.getY());
    }

    private void copiar(String texto, String que) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(texto), null);
        lblIdent.setText(I18n.t("vint.m.copiado", que));
        lblIdent.setForeground(VintComponentes.VERDE);
        Timer t = new Timer(1400, ev -> {
            lblIdent.setText(identTexto);
            lblIdent.setForeground(VintComponentes.ACENTO);
        });
        t.setRepeats(false);
        t.start();
    }

    private String ipServidor() {
        String ip = goose != null ? goose.ipPlacaElegida() : null;
        return ip != null ? ip : "127.0.0.1";
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Datos
    // ─────────────────────────────────────────────────────────────────────────

    private void cargarAparatos() {
        cbAparato.removeAllItems();
        for (EstadoLocal.Objeto o : srv.listarAparatos()) {
            if (o.esAparato && (refFija == null || refFija.equals(o.ref))) cbAparato.addItem(new Item(o));
        }
    }

    /** Aparato elegido releído del modelo, o null. En el hilo de Swing. */
    private EstadoLocal.Objeto aparato() {
        Item it = (Item) cbAparato.getSelectedItem();
        return it == null ? null : aparatoDe(it.o.ref);
    }

    /** El aparato de esa referencia releído del modelo, o null. Sirve desde cualquier hilo. */
    private EstadoLocal.Objeto aparatoDe(String ref) {
        if (ref == null) return null;
        for (EstadoLocal.Objeto x : srv.listarAparatos()) {
            if (x.ref.equals(ref)) return x;
        }
        return null;
    }

    /** Al elegir otro aparato: devuelve los forzados del anterior y carga su suscripción. */
    private void cambiarAparato() {
        Item it = (Item) cbAparato.getSelectedItem();
        String nueva = it == null ? null : it.o.ref;
        if (nueva != null && nueva.equals(refActual)) return;
        quitarForzado();
        refActual = nueva;
        calidadInvalida = false;
        if (it != null) {
            calidadInvalida = it.o.calidad != null && it.o.calidad.toLowerCase().contains("invalid");
        }
        for (SuscripcionGoose.Rol r : SuscripcionGoose.Rol.values()) {
            suscripcion.configurar(r, refActual == null ? null : SuscripcionGoose.Config.leer(PREFS.get(claveSub(r), null)));
        }
        if (suscripcion.hayAlguna() && goose != null && !goose.capturando()) goose.asegurarCaptura();
        if (instructor != null) instructor.sincronizar();
        refrescar();
    }

    /** Relee el aparato elegido del modelo y actualiza la pantalla (cada 300 ms). */
    private void refrescar() {
        EstadoLocal.Objeto o = aparato();
        actualizando = true;
        try {
            if (o == null) {
                lblEstado.setText(I18n.t("vint.sin.aparatos"));
                lblEstado.setForeground(VintComponentes.TENUE);
                simbolo.poner("INTERMEDIO");
                for (JComponent c : new JComponent[]{tbLocal, tbRemoto, btnAbrir, btnCerrar}) c.setEnabled(false);
                return;
            }
            String est = o.estado == null ? "" : o.estado;
            simbolo.poner(est);
            String stVal;
            switch (est) {
                case "CERRADO":
                    lblEstado.setText(I18n.t("vint.est.cerrado"));
                    lblEstado.setForeground(VintComponentes.ROJO);
                    stVal = "on (10)";
                    break;
                case "ABIERTO":
                    lblEstado.setText(I18n.t("vint.est.abierto"));
                    lblEstado.setForeground(VintComponentes.VERDE);
                    stVal = "off (01)";
                    break;
                case "INTERMEDIO":
                    lblEstado.setText(I18n.t("vint.m.indeterminado"));
                    lblEstado.setForeground(VintComponentes.AMARILLO.darker());
                    stVal = "intermediate-state (00)";
                    break;
                default:
                    lblEstado.setText(I18n.t("vint.m.invalido"));
                    lblEstado.setForeground(VintComponentes.VIOLETA);
                    stVal = "bad-state (11)";
            }
            boolean qMala = o.calidad != null && !o.calidad.startsWith("buena") && !o.calidad.startsWith("sin q");
            String tip = I18n.t("vint.m.pos.tip", o.ref, stVal) + (qMala ? I18n.t("vint.m.qinv") : "");
            lblEstado.setToolTipText(tip);
            simbolo.setToolTipText(tip);
            lblCtl.setText("ctlModel " + o.ctlModel + " · " + textoCtl(o.ctlModel));
            lblCtl.setToolTipText(lblCtl.getText());
            lblComandado.setText(o.comandadoPor.isEmpty() ? " " : I18n.t("vint.comandado", o.comandadoPor)
                    + (qMala ? "  ·  q " + o.calidad : ""));
            if (o.comandadoPor.isEmpty() && qMala) lblComandado.setText("q " + o.calidad);

            boolean cerrado = "CERRADO".equals(est), abierto = "ABIERTO".equals(est);
            boolean bad = !cerrado && !abierto && !"INTERMEDIO".equals(est);
            led52a.poner(cerrado || bad ? VintComponentes.VERDE_LED : VintComponentes.APAGADO);
            led52b.poner(abierto || bad ? VintComponentes.VERDE_LED : VintComponentes.APAGADO);

            Boolean local = srv.enLocal(o.ref);
            tbLocal.setEnabled(local != null);
            tbRemoto.setEnabled(local != null);
            String sinLlave = local == null ? I18n.t("vint.m.sinllave") : null;
            tbLocal.setToolTipText(sinLlave);
            tbRemoto.setToolTipText(sinLlave);
            tbLocal.setSelected(Boolean.TRUE.equals(local));
            tbRemoto.setSelected(Boolean.FALSE.equals(local));
            VintComponentes.pintarSegmento(tbLocal);
            VintComponentes.pintarSegmento(tbRemoto);
            boolean mandoLocal = local == null || local;
            btnAbrir.setEnabled(mandoLocal);
            btnCerrar.setEnabled(mandoLocal);
            btnAbrir.setIcon(mandoLocal ? null : VintComponentes.icono("candado", VintComponentes.mezclar(VintComponentes.VERDE, Color.WHITE, 0.35f)));
            btnCerrar.setIcon(mandoLocal ? null : VintComponentes.icono("candado", VintComponentes.mezclar(VintComponentes.ROJO, Color.WHITE, 0.35f)));
            btnAbrir.setDisabledIcon(btnAbrir.getIcon());
            btnCerrar.setDisabledIcon(btnCerrar.getIcon());
            btnAbrir.setIconTextGap(8);
            btnCerrar.setIconTextGap(8);
            String tipMando = mandoLocal ? null : I18n.t("vint.m.bloqueado");
            btnAbrir.setToolTipText(tipMando);
            btnCerrar.setToolTipText(tipMando);

            String ident = o.ld + " · " + o.nombre.replace(".Pos", "") + "  ·  " + ipServidor() + " : " + srv.getPort();
            if (!ident.equals(identTexto) && !lblIdent.getText().startsWith("✓")) lblIdent.setText(ident);
            identTexto = ident;

            refrescarLeds(o);
        } finally {
            actualizando = false;
        }
    }

    private void refrescarLeds(EstadoLocal.Objeto o) {
        if (srv.isRunning()) {
            ledMms.poner(VintComponentes.VERDE_LED);
            ledMms.setToolTipText(I18n.t("vint.m.mms.ok", ipServidor(), String.valueOf(srv.getPort())));
        } else {
            ledMms.poner(VintComponentes.ROJO);
            ledMms.setToolTipText(I18n.t("vint.m.mms.no"));
        }
        lblMms.setToolTipText(ledMms.getToolTipText());

        if (goose == null) {
            ledPub.poner(VintComponentes.APAGADO);
            ledSub.poner(VintComponentes.APAGADO);
            ledPub.setToolTipText(I18n.t("vint.goose.nodisponible"));
            ledSub.setToolTipText(I18n.t("vint.goose.nodisponible"));
            return;
        }
        List<Integer> gocbs = goose.indicesGocbQueLlevan(o.ld, lnDosDe(o));
        List<String> pub = new ArrayList<>(), sinPub = new ArrayList<>();
        for (int i : gocbs) {
            SclGoCB g = goose.gocb(i);
            if (g == null) continue;
            (goose.publicando(i) ? pub : sinPub).add(g.cbName);
        }
        if (gocbs.isEmpty()) {
            ledPub.poner(VintComponentes.APAGADO);
            ledPub.setToolTipText(I18n.t("vint.m.pub.nada"));
        } else if (!pub.isEmpty()) {
            ledPub.poner(VintComponentes.VERDE_LED);
            ledPub.setToolTipText(I18n.t("vint.m.pub.ok", String.join(", ", pub))
                    + (goose.publicaSimulado() ? " · " + I18n.t("vint.p.simulado") : ""));
        } else {
            ledPub.poner(VintComponentes.ROJO);
            ledPub.setToolTipText(I18n.t("vint.m.pub.no", String.join(", ", sinPub)));
        }
        lblPub.setToolTipText(ledPub.getToolTipText());

        SuscripcionGoose.Estado e = suscripcion.resumen();
        StringBuilder tip = new StringBuilder("<html>");
        if (e != SuscripcionGoose.Estado.SIN_CONFIGURAR && !goose.capturando()) {
            ledSub.poner(VintComponentes.ROJO);
            tip.append(I18n.t("vint.m.sub.captura")).append("<br>");
        } else {
            ledSub.poner(colorEstado(e));
        }
        tip.append(I18n.t("vint.s.disparo")).append(": ").append(html(suscripcion.textoEstado(SuscripcionGoose.Rol.DISPARO)))
           .append("<br>").append(I18n.t("vint.s.cierre")).append(": ").append(html(suscripcion.textoEstado(SuscripcionGoose.Rol.CIERRE)))
           .append("<br><i>").append(I18n.t("vint.m.sub.tip")).append("</i></html>");
        ledSub.setToolTipText(tip.toString());
        lblSub.setToolTipText(tip.toString());
    }

    static Color colorEstado(SuscripcionGoose.Estado e) {
        switch (e) {
            case RECIBIENDO: return VintComponentes.VERDE_LED;
            case SIN_DATOS:  return VintComponentes.AMARILLO;
            case PERDIDO:
            case ERROR:      return VintComponentes.ROJO;
            default:         return VintComponentes.APAGADO;
        }
    }

    private static String html(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** LN.DO del aparato y de los CSWI que lo comandan, para buscar los GoCB que lo llevan. */
    private static List<String> lnDosDe(EstadoLocal.Objeto o) {
        List<String> r = new ArrayList<>();
        r.add(o.nombre);
        String ln = o.nombre.contains(".") ? o.nombre.substring(0, o.nombre.indexOf('.')) : o.nombre;
        r.add(ln + ".Loc");
        for (String c : o.comandadoPor.split("[,;\\s]+")) {
            if (!c.isEmpty()) r.add(c + ".Pos");
        }
        return r;
    }

    private static String textoCtl(int ctl) {
        switch (ctl) {
            case 0:  return "status-only";
            case 1:  return "direct-with-normal-security";
            case 2:  return "sbo-with-normal-security";
            case 3:  return "direct-with-enhanced-security";
            case 4:  return "sbo-with-enhanced-security";
            default: return "?";
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Mando
    // ─────────────────────────────────────────────────────────────────────────

    private void ponerSelector(boolean local) {
        if (actualizando) return;
        EstadoLocal.Objeto o = aparato();
        if (o == null || srv.enLocal(o.ref) == null) return;
        srv.ponerLlave(o.ref, local);
        registrar(I18n.t("vint.origen.panel"), I18n.t("vint.evt.selector", local ? "LOCAL" : "REMOTO"), true);
        refrescar();
    }

    /**
     * Por qué no se puede maniobrar el aparato, o null si se puede. Igual que la referencia:
     * posición forzada por el instructor, en maniobra o ya en la posición pedida.
     */
    private String motivoRechazo(EstadoLocal.Objeto o, boolean cerrar) {
        if (forzada != null) return I18n.t("vint.rech.forzada");
        String est = o.estado == null ? "" : o.estado;
        if ("INTERMEDIO".equals(est)) return I18n.t("vint.rech.maniobra");
        if (cerrar ? "CERRADO".equals(est) : "ABIERTO".equals(est)) return I18n.t("vint.rech.enposicion");
        return null;
    }

    private void mandoLocal(boolean cerrar) {
        EstadoLocal.Objeto o = aparato();
        if (o == null) return;
        String verbo = I18n.t(cerrar ? "vint.verbo.cerrar" : "vint.verbo.abrir");
        String motivo = motivoRechazo(o, cerrar);
        if (motivo != null) {
            registrar(I18n.t("vint.origen.local"), I18n.t("vint.evt.local.rech", verbo, motivo), false);
            return;
        }
        srv.maniobrarLocal(o.ref, cerrar);
        refrescar();
    }

    /** Orden recibida por GOOSE (hilo de captura). El disparo actúa siempre; el cierre no en LOCAL. */
    private void ordenGoose(SuscripcionGoose.Rol rol, int stNum) {
        boolean cerrar = rol == SuscripcionGoose.Rol.CIERRE;
        EstadoLocal.Objeto o = aparatoDe(refActual);
        if (o == null) return;
        String recibido = I18n.t(cerrar ? "vint.evt.goose.rx.cierre" : "vint.evt.goose.rx.disparo", String.valueOf(stNum));
        String motivo = cerrar && Boolean.TRUE.equals(srv.enLocal(o.ref)) ? I18n.t("vint.rech.local") : motivoRechazo(o, cerrar);
        if (motivo != null) {
            SwingUtilities.invokeLater(() -> registrar("GOOSE", recibido + " → " + motivo, false));
            return;
        }
        SwingUtilities.invokeLater(() -> registrar("GOOSE", recibido, true));
        srv.ordenarAparato(o.ref, cerrar, "GOOSE");
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Forzados del instructor
    // ─────────────────────────────────────────────────────────────────────────

    /** Fuerza la posición (intermedio o bad-state) o, con null, devuelve la que tenía. */
    private void forzar(BdaDoubleBitPos.DoubleBitPos pos) {
        EstadoLocal.Objeto o = aparato();
        if (o == null) return;
        if (pos == null) {
            quitarForzado();
            return;
        }
        if (forzada == null) estadoAntesDeForzar = o.estado;
        forzada = pos;
        srv.forzarPosicion(o.ref, pos);
    }

    private void quitarForzado() {
        if (forzada == null || refActual == null) {
            forzada = null;
            return;
        }
        forzada = null;
        BdaDoubleBitPos.DoubleBitPos antes;
        if ("CERRADO".equals(estadoAntesDeForzar)) antes = BdaDoubleBitPos.DoubleBitPos.ON;
        else if ("ABIERTO".equals(estadoAntesDeForzar)) antes = BdaDoubleBitPos.DoubleBitPos.OFF;
        else if ("DISCORDANTE".equals(estadoAntesDeForzar)) antes = BdaDoubleBitPos.DoubleBitPos.BAD_STATE;
        else antes = BdaDoubleBitPos.DoubleBitPos.INTERMEDIATE_STATE;
        srv.forzarPosicion(refActual, antes);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Registro de eventos
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * En la ventana de un aparato, true si el evento es suyo: nombra una referencia propia (el
     * aparato o el CSWI que lo comanda), o no nombra ninguna (avisos generales, modo del IED).
     */
    private boolean esPropio(String texto) {
        if (refFija == null || texto == null) return true;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[A-Za-z0-9_]+/[A-Za-z0-9_]+").matcher(texto);
        boolean nombraAlguna = false;
        while (m.find()) {
            nombraAlguna = true;
            for (String p : prefijosPropios) if (m.group().equals(p)) return true;
        }
        return !nombraAlguna;
    }

    /** La referencia del aparato de esta ventana, o null en la ventana general. */
    String aparatoFijo() { return refFija; }

    private void registrar(String origen, String texto, boolean aceptado) {
        String hora = LocalTime.now().format(HORA);
        String evento = (aceptado ? "" : "✗ ") + texto;
        String linea = hora + "  " + String.format("%-12s", origen) + evento;
        eventos.add(linea);
        filasEventos.add(new String[]{hora, origen, evento});
        if (eventos.size() > 5000) {
            eventos.subList(0, 1000).clear();
            filasEventos.subList(0, 1000).clear();
        }
        String corto = hora.substring(0, 8) + " · " + origen + " · " + evento;
        FontMetrics fm = lblUltimo.getFontMetrics(lblUltimo.getFont());
        int max = ANCHO - 40;
        String visible = corto;
        if (fm.stringWidth(visible) > max) {
            while (visible.length() > 4 && fm.stringWidth(visible + "…") > max) visible = visible.substring(0, visible.length() - 1);
            visible += "…";
        }
        lblUltimo.setText(visible);
        lblUltimo.setForeground(aceptado ? UIManager.getColor("Label.foreground") : VintComponentes.ROJO);
        lblUltimo.setToolTipText("<html>" + html(corto) + "<br><i>" + I18n.t("vint.m.ultimo.tip") + "</i></html>");
        if (taRegistro != null) {
            taRegistro.append(linea + "\n");
            taRegistro.setCaretPosition(taRegistro.getDocument().getLength());
        }
    }

    private void mostrarRegistro() {
        if (dlgRegistro == null) {
            dlgRegistro = new JDialog(this, I18n.t("vint.r.titulo") + " — " + getTitle(), false);
            taRegistro = new JTextArea();
            taRegistro.setEditable(false);
            taRegistro.setFont(Estilo.mono(Font.PLAIN, 12));
            taRegistro.setText(String.join("\n", eventos) + (eventos.isEmpty() ? "" : "\n"));
            JPanel p = new JPanel(new BorderLayout(6, 6));
            p.setBorder(new EmptyBorder(8, 8, 8, 8));
            p.add(new JScrollPane(taRegistro), BorderLayout.CENTER);
            JPanel bot = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            JButton limpiar = new JButton(I18n.t("vint.r.limpiar"));
            limpiar.addActionListener(e -> {
                eventos.clear();
                filasEventos.clear();
                taRegistro.setText("");
            });
            JButton guardar = new JButton(I18n.t("vint.r.guardar"));
            guardar.addActionListener(e -> guardarRegistro());
            JButton cerrar = new JButton(I18n.t("vint.r.cerrar"));
            cerrar.addActionListener(e -> dlgRegistro.setVisible(false));
            bot.add(limpiar);
            bot.add(guardar);
            bot.add(cerrar);
            p.add(bot, BorderLayout.SOUTH);
            dlgRegistro.setContentPane(p);
            dlgRegistro.setSize(620, 360);
            dlgRegistro.setLocationRelativeTo(this);
            dlgRegistro.setDefaultCloseOperation(HIDE_ON_CLOSE);
        }
        taRegistro.setCaretPosition(taRegistro.getDocument().getLength());
        dlgRegistro.setVisible(true);
        dlgRegistro.toFront();
    }

    private void guardarRegistro() {
        Component padre = dlgRegistro != null && dlgRegistro.isVisible() ? dlgRegistro : this;
        JFileChooser fc = new JFileChooser();
        javax.swing.filechooser.FileNameExtensionFilter txt =
                new javax.swing.filechooser.FileNameExtensionFilter(I18n.t("vint.r.txt"), "txt");
        javax.swing.filechooser.FileNameExtensionFilter csv =
                new javax.swing.filechooser.FileNameExtensionFilter("CSV (*.csv)", "csv");
        fc.addChoosableFileFilter(txt);
        fc.addChoosableFileFilter(csv);
        fc.setFileFilter(txt);
        Item it = (Item) cbAparato.getSelectedItem();
        String nombre = "interruptor_virtual" + (it == null ? "" : "_" + it.o.nombre.replace(".Pos", ""))
                + "_" + java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        fc.setSelectedFile(new File(nombre + ".txt"));
        if (fc.showSaveDialog(padre) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        boolean esCsv = f.getName().toLowerCase().endsWith(".csv") || (fc.getFileFilter() == csv && !f.getName().contains("."));
        if (fc.getFileFilter() == csv && !f.getName().toLowerCase().endsWith(".csv")) {
            f = new File(f.getParentFile(), f.getName().replaceFirst("\\.txt$", "") + ".csv");
            esCsv = true;
        }
        StringBuilder sb = new StringBuilder();
        if (esCsv) {
            sb.append('﻿').append("hora;origen;evento\n");
            for (String[] r : filasEventos) {
                sb.append(r[0]).append(';').append(r[1]).append(';').append(r[2].replace(";", ",")).append('\n');
            }
        } else {
            sb.append(getTitle()).append("\n\n");
            for (String l : eventos) sb.append(l).append('\n');
        }
        try {
            Files.write(f.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(padre, ex.getMessage(), I18n.t("vint.titulo"), JOptionPane.ERROR_MESSAGE);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Ventanas de Comunicación
    // ─────────────────────────────────────────────────────────────────────────

    private void mostrarConexion() {
        if (aparato() != null) dialogoConexion().setVisible(true);
    }

    /** Arma la ventana de datos de conexión (modal) sin mostrarla. */
    private JDialog dialogoConexion() {
        EstadoLocal.Objeto o = aparato();
        List<String[]> filas = new ArrayList<>();
        filas.add(new String[]{I18n.t("vint.c.ip"), ipServidor()});
        filas.add(new String[]{I18n.t("vint.c.puerto"), String.valueOf(srv.getPort())});
        filas.add(new String[]{I18n.t("vint.c.ld"), o.ld});
        for (String c : o.comandadoPor.split("[,;\\s]+")) {
            if (!c.isEmpty()) filas.add(new String[]{I18n.t("vint.c.comando"),
                    o.ld + "/" + c + ".Pos (" + textoCtl(o.ctlModel) + ")"});
        }
        filas.add(new String[]{I18n.t("vint.c.posicion"), o.ref + ".stVal"});
        if (goose != null) {
            for (int i : goose.indicesGocbQueLlevan(o.ld, lnDosDe(o))) {
                SclGoCB g = goose.gocb(i);
                if (g == null) continue;
                filas.add(new String[]{I18n.t("vint.c.gocb"), GoosePanel.gocbRefDe(g)
                        + "  (" + I18n.t(goose.publicando(i) ? "vint.goose.publicando" : "vint.p.sinpublicar") + ")"});
                filas.add(new String[]{I18n.t("vint.c.goid"), nz(g.goID)});
                filas.add(new String[]{I18n.t("vint.c.dataset"), g.ldInst + "/LLN0$" + nz(g.datSet)});
                filas.add(new String[]{I18n.t("vint.c.appid"), "0x" + nz(g.appID)});
                filas.add(new String[]{I18n.t("vint.c.mac"), nz(g.macAddress)});
                filas.add(new String[]{I18n.t("vint.c.vlan"), (g.vlanId >= 0 ? String.valueOf(g.vlanId) : "—")
                        + (g.vlanPriority >= 0 ? " · " + I18n.t("vint.c.prioridad", String.valueOf(g.vlanPriority)) : "")});
            }
            filas.add(new String[]{I18n.t("vint.c.simulado"), I18n.t(goose.publicaSimulado() ? "vint.c.si" : "vint.c.no")});
            filas.add(new String[]{I18n.t("vint.c.interfaz"), nz(goose.placaElegida())});
        }

        JDialog d = new JDialog(this, I18n.t("vint.c.titulo"), true);
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(new EmptyBorder(12, 14, 6, 14));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(2, 4, 2, 4);
        g.anchor = GridBagConstraints.WEST;
        int y = 0;
        for (String[] f : filas) {
            g.gridy = y++;
            g.gridx = 0;
            g.weightx = 0;
            JLabel k = new JLabel(f[0]);
            k.setForeground(VintComponentes.TENUE);
            form.add(k, g);
            g.gridx = 1;
            g.weightx = 1;
            JTextField v = new JTextField(f[1]);
            v.setEditable(false);
            v.setBorder(null);
            v.setOpaque(false);
            v.setFont(Estilo.mono(Font.PLAIN, 12));
            form.add(v, g);
        }
        JPanel bot = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        JButton copiar = new JButton(I18n.t("vint.c.copiar"));
        copiar.addActionListener(e -> {
            StringBuilder sb = new StringBuilder();
            for (String[] f : filas) sb.append(f[0]).append(": ").append(f[1]).append('\n');
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(sb.toString()), null);
        });
        JButton cerrar = new JButton(I18n.t("vint.r.cerrar"));
        cerrar.addActionListener(e -> d.dispose());
        bot.add(copiar);
        bot.add(cerrar);
        JPanel p = new JPanel(new BorderLayout());
        p.add(form, BorderLayout.CENTER);
        p.add(bot, BorderLayout.SOUTH);
        d.setContentPane(p);
        d.pack();
        d.setLocationRelativeTo(this);
        return d;
    }

    private static String nz(String s) { return s == null || s.isEmpty() ? "—" : s; }

    private String claveSub(SuscripcionGoose.Rol rol) {
        String base = (srv.getSclPath() == null ? "" : srv.getSclPath()) + "|" + refActual;
        return "sub." + rol.name().toLowerCase() + "." + Integer.toHexString(base.hashCode());
    }

    private void editarSuscripcion() {
        if (aparato() == null) return;
        if (goose == null) {
            JOptionPane.showMessageDialog(this, I18n.t("vint.goose.nodisponible"), I18n.t("vint.s.titulo"),
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        new DialogoSuscripcion().setVisible(true);
    }

    /** Formulario manual de suscripción GOOSE (disparo y cierre), como en la referencia. */
    private final class DialogoSuscripcion extends JDialog {
        private final SuscripcionGoose.Rol[] roles = SuscripcionGoose.Rol.values();
        private final JTextField[][] campos = new JTextField[2][4];
        private final VintComponentes.Led[] leds = {new VintComponentes.Led(11), new VintComponentes.Led(11)};
        private final JLabel[] estados = {new JLabel(" "), new JLabel(" ")};
        private final JLabel lblCaptura = VintComponentes.tenue(" ", 11f);
        private final Timer timer;

        DialogoSuscripcion() {
            super(InterruptorVirtualDialog.this, I18n.t("vint.s.titulo"), true);
            JPanel p = new JPanel();
            p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
            p.setBorder(new EmptyBorder(12, 14, 8, 14));
            String[] etiquetas = {I18n.t("vint.s.appid"), I18n.t("vint.s.gocbref"), I18n.t("vint.s.indice"), I18n.t("vint.s.mac")};
            String[] ejemplos = {"0x0001", "IED_PROT1LD0/LLN0$GO$Trip", "0", "00-30-A7-12-34-56"};
            for (int r = 0; r < 2; r++) {
                SuscripcionGoose.Rol rol = roles[r];
                JPanel cab = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
                JLabel t = new JLabel(I18n.t(rol == SuscripcionGoose.Rol.DISPARO ? "vint.s.disparo" : "vint.s.cierre"));
                t.setFont(t.getFont().deriveFont(Font.BOLD, 13f));
                cab.add(t);
                cab.add(Box.createHorizontalStrut(8));
                cab.add(leds[r]);
                estados[r].setForeground(VintComponentes.TENUE);
                estados[r].setFont(estados[r].getFont().deriveFont(11f));
                cab.add(estados[r]);
                p.add(ancho(cab));

                JPanel form = new JPanel(new GridBagLayout());
                GridBagConstraints g = new GridBagConstraints();
                g.insets = new Insets(2, 4, 2, 4);
                g.fill = GridBagConstraints.HORIZONTAL;
                SuscripcionGoose.Config c = suscripcion.config(rol);
                String[] valores = c == null ? new String[]{"", "", "0", ""}
                        : new String[]{c.textoAppId(), c.gocbRef, String.valueOf(c.indice), c.textoMac()};
                for (int k = 0; k < 4; k++) {
                    g.gridy = k;
                    g.gridx = 0;
                    g.weightx = 0;
                    g.anchor = GridBagConstraints.EAST;
                    form.add(new JLabel(etiquetas[k]), g);
                    g.gridx = 1;
                    g.weightx = 1;
                    campos[r][k] = new JTextField(valores[k], k == 1 ? 26 : 12);
                    campos[r][k].putClientProperty("JTextField.placeholderText", ejemplos[k]);
                    form.add(campos[r][k], g);
                }
                final int fila = r;
                JButton tomar = new JButton(I18n.t("vint.s.tomar"));
                tomar.setToolTipText(I18n.t("vint.s.tomar.tip"));
                tomar.addActionListener(e -> menuRecibidos(tomar, fila));
                g.gridy = 4;
                g.gridx = 1;
                g.fill = GridBagConstraints.NONE;
                g.anchor = GridBagConstraints.WEST;
                form.add(tomar, g);
                p.add(ancho(form));
                p.add(Box.createVerticalStrut(10));
            }
            JLabel ayuda = new JLabel("<html><body style='width:290px'>" + I18n.t("vint.s.ayuda") + "</body></html>");
            ayuda.setForeground(VintComponentes.TENUE);
            ayuda.setFont(ayuda.getFont().deriveFont(11f));
            p.add(izq(ayuda));
            p.add(Box.createVerticalStrut(4));
            p.add(izq(lblCaptura));

            JPanel bot = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
            JButton cancelar = new JButton(I18n.t("vint.s.cancelar"));
            cancelar.addActionListener(e -> dispose());
            JButton guardar = new JButton(I18n.t("vint.s.guardar"));
            guardar.putClientProperty("JButton.buttonType", "default");
            guardar.addActionListener(e -> guardar());
            getRootPane().setDefaultButton(guardar);
            bot.add(cancelar);
            bot.add(guardar);

            JPanel raiz = new JPanel(new BorderLayout());
            raiz.add(p, BorderLayout.CENTER);
            raiz.add(bot, BorderLayout.SOUTH);
            setContentPane(raiz);
            timer = new Timer(250, e -> refrescarEstado());
            timer.start();
            addWindowListener(new WindowAdapter() {
                @Override public void windowClosed(WindowEvent e) { timer.stop(); }
            });
            refrescarEstado();
            pack();
            setResizable(false);
            setLocationRelativeTo(InterruptorVirtualDialog.this);
        }

        private void refrescarEstado() {
            for (int r = 0; r < 2; r++) {
                leds[r].poner(colorEstado(suscripcion.estado(roles[r])));
                estados[r].setText(suscripcion.textoEstado(roles[r]));
            }
            String placa = nz(goose.placaElegida());
            lblCaptura.setText(goose.capturando() ? I18n.t("vint.s.capturando", placa) : I18n.t("vint.s.captura", placa));
        }

        /** Menú con los GoCB que ya están llegando y sus datos booleanos. */
        private void menuRecibidos(JComponent donde, int fila) {
            JPopupMenu m = new JPopupMenu();
            List<GooseSubscriber.GooseMessage> recibidos = goose.mensajesRecibidos();
            for (GooseSubscriber.GooseMessage msg : recibidos) {
                if (msg.gocbRef == null || msg.gocbRef.isEmpty()) continue;
                JMenu sub = new JMenu(String.format("0x%04X  %s", msg.appId, msg.gocbRef));
                for (GooseSubscriber.DataEntry d : msg.dataEntries) {
                    Boolean b = SuscripcionGoose.primerBooleano(d.value);
                    if (b == null) continue;
                    String txt = "[" + d.index + "]  " + d.type + " = " + GoosePanel.textoDato(d.type, d.value);
                    JMenuItem mi = new JMenuItem(txt);
                    mi.addActionListener(e -> {
                        campos[fila][0].setText(String.format("0x%04X", msg.appId));
                        campos[fila][1].setText(msg.gocbRef);
                        campos[fila][2].setText(String.valueOf(d.index));
                        String mac = msg.srcMac != null && msg.srcMac.matches("([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}")
                                ? msg.srcMac.replace(':', '-').toUpperCase() : "";
                        campos[fila][3].setText(mac);
                    });
                    sub.add(mi);
                }
                if (sub.getItemCount() > 0) m.add(sub);
            }
            if (m.getComponentCount() == 0) {
                JMenuItem nada = new JMenuItem(I18n.t(goose.capturando() ? "vint.s.nada" : "vint.s.nada.captura"));
                nada.setEnabled(goose.capturando());
                if (!goose.capturando()) {
                    nada.setEnabled(true);
                    nada.addActionListener(e -> goose.asegurarCaptura());
                }
                m.add(nada);
            }
            m.show(donde, 0, donde.getHeight());
        }

        private void guardar() {
            SuscripcionGoose.Config[] nuevas = new SuscripcionGoose.Config[2];
            for (int r = 0; r < 2; r++) {
                if (campos[r][1].getText().trim().isEmpty()) continue;
                try {
                    nuevas[r] = SuscripcionGoose.Config.validar(campos[r][0].getText(), campos[r][1].getText(),
                            campos[r][2].getText(), campos[r][3].getText());
                } catch (IllegalArgumentException ex) {
                    String quien = I18n.t(roles[r] == SuscripcionGoose.Rol.DISPARO ? "vint.s.disparo" : "vint.s.cierre");
                    JOptionPane.showMessageDialog(this, quien + ": " + ex.getMessage(), I18n.t("vint.s.titulo"),
                            JOptionPane.WARNING_MESSAGE);
                    return;
                }
            }
            for (int r = 0; r < 2; r++) {
                SuscripcionGoose.Rol rol = roles[r];
                SuscripcionGoose.Config antes = suscripcion.config(rol);
                SuscripcionGoose.Config nueva = nuevas[r];
                String quien = I18n.t(rol == SuscripcionGoose.Rol.DISPARO ? "vint.s.disparo" : "vint.s.cierre");
                if (nueva == null) {
                    PREFS.remove(claveSub(rol));
                    if (antes != null) registrar("GOOSE", I18n.t("vint.evt.sub.quitada", quien), true);
                } else {
                    PREFS.put(claveSub(rol), nueva.serializar());
                    if (antes == null || !antes.serializar().equals(nueva.serializar())) {
                        registrar("GOOSE", I18n.t("vint.evt.sub", quien, nueva.gocbRef, nueva.textoAppId(),
                                String.valueOf(nueva.indice)), true);
                    }
                }
                if (antes == null ? nueva != null : nueva == null || !antes.serializar().equals(nueva.serializar())) {
                    suscripcion.configurar(rol, nueva);
                }
            }
            if (suscripcion.hayAlguna() && !goose.capturando()) goose.asegurarCaptura();
            dispose();
        }
    }

    private void editarPublicacion() {
        if (aparato() == null) return;
        if (goose == null) {
            JOptionPane.showMessageDialog(this, I18n.t("vint.goose.nodisponible"), I18n.t("vint.p.titulo"),
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        dialogoPublicacion().setVisible(true);
    }

    /** Arma la ventana de publicación GOOSE (modal) sin mostrarla. */
    private JDialog dialogoPublicacion() {
        EstadoLocal.Objeto o = aparato();
        JDialog d = new JDialog(this, I18n.t("vint.p.titulo"), true);
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(new EmptyBorder(12, 14, 8, 14));
        List<Integer> indices = goose.indicesGocbQueLlevan(o.ld, lnDosDe(o));
        JLabel ayuda = new JLabel("<html><body style='width:280px'>"
                + I18n.t(indices.isEmpty() ? "vint.p.ninguno" : "vint.p.ayuda") + "</body></html>");
        ayuda.setForeground(VintComponentes.TENUE);
        p.add(izq(ayuda));
        p.add(Box.createVerticalStrut(8));
        List<Runnable> refrescos = new ArrayList<>();
        for (int i : indices) {
            SclGoCB g = goose.gocb(i);
            if (g == null) continue;
            JPanel fila = new JPanel(new BorderLayout(8, 0));
            VintComponentes.Led led = new VintComponentes.Led(12);
            JPanel txt = new JPanel();
            txt.setLayout(new BoxLayout(txt, BoxLayout.Y_AXIS));
            JLabel nombre = new JLabel(GoosePanel.gocbRefDe(g));
            nombre.setFont(nombre.getFont().deriveFont(Font.BOLD));
            txt.add(izq(nombre));
            txt.add(izq(VintComponentes.tenue(I18n.t("vint.p.detalle", "0x" + nz(g.appID), nz(g.macAddress), nz(g.goID)), 11f)));
            JButton b = new JButton();
            b.addActionListener(e -> {
                boolean publicar = !goose.publicando(i);
                goose.publicarGoCB(i, publicar);
                registrar("GOOSE", I18n.t("vint.evt.pub", g.cbName,
                        I18n.t(goose.publicando(i) ? "vint.goose.publicando" : "vint.p.sinpublicar")), true);
                for (Runnable r : refrescos) r.run();
            });
            refrescos.add(() -> {
                boolean pub = goose.publicando(i);
                led.poner(pub ? VintComponentes.VERDE_LED : VintComponentes.APAGADO);
                b.setText(I18n.t(pub ? "vint.p.detener" : "vint.p.publicar"));
            });
            JPanel ledPanel = new JPanel(new GridBagLayout());
            ledPanel.add(led);
            fila.add(ledPanel, BorderLayout.WEST);
            fila.add(txt, BorderLayout.CENTER);
            fila.add(b, BorderLayout.EAST);
            fila.setBorder(new EmptyBorder(4, 0, 4, 0));
            p.add(ancho(fila));
        }
        if (!indices.isEmpty()) {
            p.add(Box.createVerticalStrut(8));
            JCheckBox sim = new JCheckBox(I18n.t("vint.p.simulado"), goose.publicaSimulado());
            sim.addActionListener(e -> {
                goose.ponerPublicaSimulado(sim.isSelected());
                registrar("GOOSE", I18n.t("vint.p.simulado") + ": " + I18n.t(sim.isSelected() ? "vint.c.si" : "vint.c.no"), true);
            });
            p.add(izq(sim));
            JLabel simAyuda = new JLabel("<html><body style='width:280px'>" + I18n.t("vint.p.simulado.h") + "</body></html>");
            simAyuda.setForeground(VintComponentes.TENUE);
            simAyuda.setFont(simAyuda.getFont().deriveFont(11f));
            p.add(izq(simAyuda));
            p.add(Box.createVerticalStrut(6));
            p.add(izq(VintComponentes.tenue(I18n.t("vint.p.placa", nz(goose.placaElegida())), 11f)));
        }
        for (Runnable r : refrescos) r.run();
        JPanel bot = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        JButton cerrar = new JButton(I18n.t("vint.r.cerrar"));
        cerrar.addActionListener(e -> d.dispose());
        bot.add(cerrar);
        JPanel raiz = new JPanel(new BorderLayout());
        raiz.add(p, BorderLayout.CENTER);
        raiz.add(bot, BorderLayout.SOUTH);
        d.setContentPane(raiz);
        d.pack();
        d.setResizable(false);
        d.setLocationRelativeTo(this);
        return d;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Panel del instructor (ventana aparte, no modal)
    // ─────────────────────────────────────────────────────────────────────────

    private void mostrarInstructor() {
        if (instructor == null || !instructor.isDisplayable()) {
            instructor = new PanelInstructor();
            Point q = getLocation();
            Rectangle pant = getGraphicsConfiguration().getBounds();
            int x = q.x + getWidth() + 8;
            if (x + instructor.getWidth() > pant.x + pant.width) x = Math.max(pant.x, q.x - instructor.getWidth() - 8);
            instructor.setLocation(x, Math.max(pant.y, q.y));
        }
        instructor.sincronizar();
        instructor.setVisible(true);
        instructor.toFront();
    }

    /** Inyección de fallas y ajuste de tiempos, como el panel del instructor de la referencia. */
    private final class PanelInstructor extends JDialog {
        private final VintComponentes.Deslizante fApertura = new VintComponentes.Deslizante();
        private final VintComponentes.Deslizante fCierre = new VintComponentes.Deslizante();
        private final VintComponentes.Deslizante resorte = new VintComponentes.Deslizante();
        private final VintComponentes.Deslizante sf6Alarma = new VintComponentes.Deslizante();
        private final VintComponentes.Deslizante sf6Bloqueo = new VintComponentes.Deslizante();
        private final VintComponentes.Deslizante fInter = new VintComponentes.Deslizante();
        private final VintComponentes.Deslizante fBad = new VintComponentes.Deslizante();
        private final VintComponentes.Deslizante qInv = new VintComponentes.Deslizante();
        private final VintComponentes.Deslizante sincro = new VintComponentes.Deslizante();
        private final JSlider slApertura = new JSlider(0, 250);
        private final JSlider slCierre = new JSlider(0, 250);
        private final JSlider slFalla = new JSlider(500, 10000);
        private final JLabel lApertura = new JLabel(), lCierre = new JLabel(), lFalla = new JLabel();
        private final JComboBox<EstadoLocal.Respuesta> cbRespuesta = new JComboBox<>(EstadoLocal.Respuesta.values());
        private final JToggleButton[] tbAutoridad = new JToggleButton[4];
        private JScrollPane scroll;
        private boolean sincronizando;

        PanelInstructor() {
            super(InterruptorVirtualDialog.this, I18n.t("vint.instructor"), false);
            setDefaultCloseOperation(HIDE_ON_CLOSE);
            JPanel p = new JPanel() {
                @Override public Dimension getPreferredSize() {
                    return new Dimension(ANCHO_INSTRUCTOR, super.getPreferredSize().height);
                }
            };
            p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
            p.setBorder(new EmptyBorder(6, 16, 12, 16));

            p.add(izq(VintComponentes.seccion(I18n.t("vint.i.mecanismo"))));
            p.add(toggle(fApertura, "vint.i.f.apertura", on -> conAparato(o -> {
                srv.ponerFallaApertura(o.ref, on);
                evento(on, "vint.i.f.apertura");
            })));
            p.add(toggle(fCierre, "vint.i.f.cierre", on -> conAparato(o -> {
                srv.ponerFallaCierre(o.ref, on);
                evento(on, "vint.i.f.cierre");
            })));
            p.add(toggle(resorte, "vint.i.resorte", on -> conAparato(o -> {
                srv.ponerResorteDescargado(o.ref, on);
                evento(on, "vint.i.resorte");
            })));

            p.add(izq(VintComponentes.seccion(I18n.t("vint.i.sf6"))));
            p.add(toggle(sf6Alarma, "vint.i.sf6.alarma", on -> {
                aplicarSf6();
                evento(on, "vint.i.sf6.alarma");
            }));
            p.add(toggle(sf6Bloqueo, "vint.i.sf6.bloqueo", on -> {
                aplicarSf6();
                evento(on, "vint.i.sf6.bloqueo");
            }));

            p.add(izq(VintComponentes.seccion(I18n.t("vint.i.senales"))));
            p.add(toggle(fInter, "vint.i.inter", on -> {
                if (on) fBad.poner(false, false);
                forzar(on ? BdaDoubleBitPos.DoubleBitPos.INTERMEDIATE_STATE : null);
                evento(on, "vint.i.inter");
            }));
            p.add(toggle(fBad, "vint.i.bad", on -> {
                if (on) fInter.poner(false, false);
                forzar(on ? BdaDoubleBitPos.DoubleBitPos.BAD_STATE : null);
                evento(on, "vint.i.bad");
            }));
            p.add(toggle(qInv, "vint.i.qinv", on -> conAparato(o -> {
                srv.ponerCalidadInvalida(o.ref, on);
                calidadInvalida = on;
                evento(on, "vint.i.qinv");
            })));
            p.add(izq(VintComponentes.seccion(I18n.t("vint.i.tiempos"))));
            p.add(deslizador(slApertura, lApertura, "vint.i.apertura"));
            p.add(deslizador(slCierre, lCierre, "vint.i.cierre"));
            slApertura.addChangeListener(e -> {
                lApertura.setText(I18n.t("vint.i.ms", String.valueOf(slApertura.getValue())));
                if (!sincronizando) srv.ponerTiempoAperturaMs(slApertura.getValue());
            });
            slCierre.addChangeListener(e -> {
                lCierre.setText(I18n.t("vint.i.ms", String.valueOf(slCierre.getValue())));
                if (!sincronizando) srv.ponerTiempoCierreMs(slCierre.getValue());
            });
            JLabel tAyuda = VintComponentes.tenue(I18n.t("vint.i.tiempos.h"), 10.5f);
            p.add(izq(tAyuda));

            // Lo que la referencia no tiene va plegado, para que el panel entre en pantallas bajas
            JPanel mas = new JPanel();
            mas.setLayout(new BoxLayout(mas, BoxLayout.Y_AXIS));
            mas.setAlignmentX(Component.LEFT_ALIGNMENT);
            JButton plegar = new JButton();
            plegar.putClientProperty("JButton.buttonType", "borderless");
            plegar.setForeground(VintComponentes.NARANJA);
            plegar.setFont(plegar.getFont().deriveFont(Font.BOLD, 11.5f));
            plegar.setHorizontalAlignment(SwingConstants.LEFT);
            plegar.setMargin(new Insets(10, 0, 4, 0));
            plegar.setFocusPainted(false);
            plegar.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            Runnable textoPlegar = () -> plegar.setText(I18n.t("vint.i.otras").toUpperCase()
                    + (mas.isVisible() ? "  ▾" : "  ▸"));
            mas.setVisible(PREFS.getBoolean("instructor.mas", false));
            textoPlegar.run();
            plegar.addActionListener(e -> {
                mas.setVisible(!mas.isVisible());
                PREFS.putBoolean("instructor.mas", mas.isVisible());
                textoPlegar.run();
                ajustarTamano();
            });
            p.add(izq(plegar));
            p.add(mas);
            JPanel pBase = p;
            p = mas;
            p.add(toggle(sincro, "vint.i.sync", on -> conAparato(o -> {
                srv.ponerSincronismo(o.ref, on);
                evento(on, "vint.i.sync");
            })));
            p.add(deslizador(slFalla, lFalla, "vint.i.falla"));
            slFalla.addChangeListener(e -> {
                lFalla.setText(I18n.t("vint.i.ms", String.valueOf(slFalla.getValue())));
                if (!sincronizando) srv.ponerTiempoFallaMs(slFalla.getValue());
            });
            cbRespuesta.setRenderer(new DefaultListCellRenderer() {
                @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                    return super.getListCellRendererComponent(l,
                            v instanceof EstadoLocal.Respuesta ? IEC61850Server.textoRespuesta((EstadoLocal.Respuesta) v) : v, i, s, f);
                }
            });
            cbRespuesta.addActionListener(e -> {
                if (sincronizando) return;
                conAparato(o -> srv.ponerRespuesta(o.ref, (EstadoLocal.Respuesta) cbRespuesta.getSelectedItem()));
            });
            JPanel resp = new JPanel(new BorderLayout(8, 0));
            resp.add(new JLabel(I18n.t("vint.i.respuesta")), BorderLayout.WEST);
            resp.add(cbRespuesta, BorderLayout.CENTER);
            p.add(ancho(resp));

            p.add(izq(VintComponentes.seccion(I18n.t("vint.i.autoridad"))));
            JPanel aut = new JPanel(new GridLayout(1, 4, 0, 0));
            ButtonGroup gAut = new ButtonGroup();
            String[] textos = {I18n.t("vint.aut.local"), I18n.t("vint.aut.bahia"),
                               I18n.t("vint.aut.estacion"), I18n.t("vint.aut.remoto")};
            for (int i = 0; i < 4; i++) {
                final EstadoLocal.Autoridad a = EstadoLocal.Autoridad.values()[i];
                JToggleButton t = new VintComponentes.Segmento(textos[i], i == 0 ? -1 : i == 3 ? 1 : 0);
                t.setFont(t.getFont().deriveFont(Font.BOLD, 11f));
                t.setBorder(BorderFactory.createEmptyBorder(5, 2, 5, 2));
                gAut.add(t);
                t.addActionListener(e -> {
                    if (sincronizando) return;
                    conAparato(o -> {
                        srv.ponerAutoridad(o.ld, a);
                        registrar(I18n.t("vint.origen.instructor"), I18n.t("vint.evt.autoridad", o.ld, a.name()), true);
                    });
                });
                tbAutoridad[i] = t;
                aut.add(t);
            }
            p.add(ancho(aut));
            p = pBase;

            p.add(Box.createVerticalStrut(14));
            JButton reset = new JButton(I18n.t("vint.i.reset"));
            reset.addActionListener(e -> restablecer());
            reset.setAlignmentX(Component.LEFT_ALIGNMENT);
            reset.setMaximumSize(new Dimension(Integer.MAX_VALUE, reset.getPreferredSize().height + 6));
            p.add(reset);

            scroll = new JScrollPane(p, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                    ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.setBorder(null);
            scroll.getVerticalScrollBar().setUnitIncrement(16);
            setContentPane(scroll);
            setResizable(false);
            ajustarTamano();
            getRootPane().registerKeyboardAction(e -> setVisible(false),
                    KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        }

        /** Al tamaño del contenido; si no entra en la pantalla, al alto útil con barra de desplazamiento. */
        private void ajustarTamano() {
            pack();
            Rectangle util = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            if (getHeight() > util.height - 20) {
                setSize(getWidth() + scroll.getVerticalScrollBar().getPreferredSize().width, util.height - 20);
            }
            if (getY() + getHeight() > util.y + util.height) setLocation(getX(), Math.max(util.y, util.y + util.height - getHeight()));
        }

        private JComponent toggle(VintComponentes.Deslizante d, String clave, java.util.function.Consumer<Boolean> accion) {
            JPanel f = new JPanel(new BorderLayout(10, 0));
            JPanel txt = new JPanel();
            txt.setLayout(new BoxLayout(txt, BoxLayout.Y_AXIS));
            JLabel titulo = new JLabel(I18n.t(clave));
            txt.add(izq(titulo));
            // Swing escala los px del HTML: 210 queda en unos 280 px reales
            JLabel ayuda = new JLabel("<html><body style='width:210px'>" + I18n.t(clave + ".h") + "</body></html>");
            ayuda.setForeground(VintComponentes.TENUE);
            ayuda.setFont(ayuda.getFont().deriveFont(10.5f));
            txt.add(izq(ayuda));
            d.alCambiar(on -> { if (!sincronizando) accion.accept(on); });
            JPanel dp = new JPanel(new GridBagLayout());
            dp.add(d);
            f.add(dp, BorderLayout.WEST);
            f.add(txt, BorderLayout.CENTER);
            f.setBorder(new EmptyBorder(3, 0, 3, 0));
            return ancho(f);
        }

        private JComponent deslizador(JSlider s, JLabel valor, String clave) {
            JPanel f = new JPanel(new BorderLayout(8, 0));
            JLabel t = new JLabel(I18n.t(clave));
            t.setPreferredSize(new Dimension(96, t.getPreferredSize().height));
            valor.setPreferredSize(new Dimension(64, valor.getPreferredSize().height));
            valor.setHorizontalAlignment(SwingConstants.RIGHT);
            f.add(t, BorderLayout.WEST);
            f.add(s, BorderLayout.CENTER);
            f.add(valor, BorderLayout.EAST);
            return ancho(f);
        }

        private void evento(boolean on, String clave) {
            registrar(I18n.t("vint.origen.instructor"),
                    I18n.t(on ? "vint.evt.instr.si" : "vint.evt.instr.no", I18n.t(clave)), true);
        }

        private void aplicarSf6() {
            EstadoLocal.Sf6 s = sf6Bloqueo.encendido() ? EstadoLocal.Sf6.BLOQUEO
                    : sf6Alarma.encendido() ? EstadoLocal.Sf6.ALARMA : EstadoLocal.Sf6.NORMAL;
            conAparato(o -> srv.ponerSf6(o.ref, s));
        }

        private void conAparato(java.util.function.Consumer<EstadoLocal.Objeto> accion) {
            EstadoLocal.Objeto o = aparato();
            if (o == null) return;
            accion.accept(o);
            refrescar();
        }

        /** Lleva los controles al estado del aparato elegido. */
        void sincronizar() {
            EstadoLocal.Objeto o = aparato();
            sincronizando = true;
            try {
                boolean hay = o != null;
                for (Component c : new Component[]{fApertura, fCierre, resorte, sf6Alarma, sf6Bloqueo, fInter, fBad, qInv, sincro, cbRespuesta}) {
                    c.setEnabled(hay);
                }
                if (hay) {
                    fApertura.poner(srv.fallaApertura(o.ref), false);
                    fCierre.poner(srv.fallaCierre(o.ref), false);
                    resorte.poner(srv.resorteDescargado(o.ref), false);
                    EstadoLocal.Sf6 s = srv.sf6De(o.ref);
                    sf6Bloqueo.poner(s == EstadoLocal.Sf6.BLOQUEO, false);
                    if (s == EstadoLocal.Sf6.ALARMA) sf6Alarma.poner(true, false);
                    if (s == EstadoLocal.Sf6.NORMAL) sf6Alarma.poner(false, false);
                    fInter.poner(forzada == BdaDoubleBitPos.DoubleBitPos.INTERMEDIATE_STATE, false);
                    fBad.poner(forzada == BdaDoubleBitPos.DoubleBitPos.BAD_STATE, false);
                    qInv.poner(calidadInvalida, false);
                    sincro.poner(srv.sincronismoEnFalla(o.ref), false);
                    cbRespuesta.setSelectedItem(srv.respuestaDe(o.ref));
                    EstadoLocal.Autoridad a = srv.autoridadDe(o.ld);
                    for (int i = 0; i < 4; i++) {
                        tbAutoridad[i].setEnabled(a != null);
                        tbAutoridad[i].setSelected(a != null && a.ordinal() == i);
                        VintComponentes.pintarSegmento(tbAutoridad[i]);
                    }
                }
                slApertura.setValue((int) srv.tiempoAperturaMs());
                slCierre.setValue((int) srv.tiempoCierreMs());
                slFalla.setValue((int) srv.tiempoFallaMs());
                lApertura.setText(I18n.t("vint.i.ms", String.valueOf(slApertura.getValue())));
                lCierre.setText(I18n.t("vint.i.ms", String.valueOf(slCierre.getValue())));
                lFalla.setText(I18n.t("vint.i.ms", String.valueOf(slFalla.getValue())));
            } finally {
                sincronizando = false;
            }
        }

        private void restablecer() {
            EstadoLocal.Objeto o = aparato();
            if (o != null) {
                srv.ponerRespuesta(o.ref, EstadoLocal.Respuesta.NORMAL);
                srv.ponerSincronismo(o.ref, false);
                if (calidadInvalida) srv.ponerCalidadInvalida(o.ref, false);
                calidadInvalida = false;
                srv.ponerFallaApertura(o.ref, false);
                srv.ponerFallaCierre(o.ref, false);
                srv.ponerResorteDescargado(o.ref, false);
                srv.ponerSf6(o.ref, EstadoLocal.Sf6.NORMAL);
                sf6Alarma.poner(false, false);
                quitarForzado();
            }
            long t = EstadoLocal.TIEMPO_MANIOBRA_POR_DEFECTO_MS;
            srv.ponerTiempoAperturaMs(t);
            srv.ponerTiempoCierreMs(t);
            srv.ponerTiempoFallaMs(2000);
            registrar(I18n.t("vint.origen.instructor"), I18n.t("vint.evt.reset", String.valueOf(t), String.valueOf(t)), true);
            sincronizar();
            refrescar();
        }
    }

    private void alCerrar() {
        refresco.stop();
        srv.quitarObservadorMando(observador);
        if (goose != null) goose.quitarOyenteGoose(oyenteGoose);
        for (SuscripcionGoose.Rol r : SuscripcionGoose.Rol.values()) suscripcion.configurar(r, null);
        quitarForzado();
        if (instructor != null) instructor.dispose();
        if (dlgRegistro != null) dlgRegistro.dispose();
    }
}
