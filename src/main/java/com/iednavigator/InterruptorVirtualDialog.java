package com.iednavigator;

import com.beanit.iec61850bean.BdaDoubleBitPos;
import com.iednavigator.mando.EstadoLocal;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Interruptor virtual: un aparato de maniobra del modelo servido, manejado como a pie de equipo.
 *
 * Trabaja sobre cualquier CID cargado en modo servidor: se elige uno de sus aparatos (posiciones
 * de XCBR, XSWI, CSWI…) y se lo maniobra localmente, se cambia la llave del aparato y la
 * autoridad del LD, y desde el panel del instructor se provocan fallas: cómo responde el aparato
 * a un mando (normal, sin cablear, trabado, lento), sincronismo en falla, posición forzada a
 * intermedio o bad-state y calidad inválida. El registro muestra lo que llega por MMS y lo que
 * se hace desde el panel.
 *
 * Todo pasa por IEC61850Server, que usa el mismo motor de mando del IEDsimulatorPRO: los cambios
 * salen por los reportes y actualizan el árbol, el monitor y los GOOSE que se estén publicando.
 */
final class InterruptorVirtualDialog extends JDialog {

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final Color VERDE = new Color(0x2E7D32), ROJO = new Color(0xC62828),
            AMBAR = new Color(0xEF6C00), GRIS = new Color(0x546E7A);

    private final IEC61850Server srv;
    private final JComboBox<Item> cbAparato = new JComboBox<>();
    private final JLabel lblEstado = new JLabel("—", SwingConstants.CENTER);
    private final JLabel lblCalidad = new JLabel();
    private final JLabel lblCtl = new JLabel();
    private final JLabel lbl52a = new JLabel(), lbl52b = new JLabel();
    private final JLabel lblComandado = new JLabel();
    private final JToggleButton tbLocal = new JToggleButton(I18n.t("vint.llave.local")), tbRemoto = new JToggleButton(I18n.t("vint.llave.remoto"));
    private final JToggleButton[] tbAutoridad = new JToggleButton[4];
    private final JComboBox<EstadoLocal.Respuesta> cbRespuesta = new JComboBox<>(EstadoLocal.Respuesta.values());
    private final JSpinner spRecorrido = new JSpinner(new SpinnerNumberModel(4000, 0, 60000, 50));
    private final JCheckBox chkSincronismo = new JCheckBox();
    private final JCheckBox chkCalidad = new JCheckBox();
    private final JTextArea taRegistro = new JTextArea(10, 60);
    private final Timer refresco;
    private final IEC61850Server.ObservadorMando observador;
    /** true mientras se actualiza la pantalla desde el modelo: los cambios no son del usuario. */
    private boolean actualizando;

    /** Elemento del combo de aparatos. */
    private static final class Item {
        final EstadoLocal.Objeto o;
        Item(EstadoLocal.Objeto o) { this.o = o; }
        @Override public String toString() {
            return o.nombre + "  —  " + o.ld + (o.comandadoPor.isEmpty() ? "" : "   (" + o.comandadoPor + ")");
        }
    }

    InterruptorVirtualDialog(Frame owner, IEC61850Server srv) {
        super(owner, I18n.t("vint.titulo"), false);
        this.srv = srv;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel raiz = new JPanel(new BorderLayout(8, 8));
        raiz.setBorder(new EmptyBorder(10, 10, 10, 10));
        setContentPane(raiz);

        // ── Aparato ─────────────────────────────────────────────────────────
        JPanel arriba = new JPanel(new BorderLayout(6, 0));
        arriba.add(new JLabel(I18n.t("vint.aparato")), BorderLayout.WEST);
        arriba.add(cbAparato, BorderLayout.CENTER);
        raiz.add(arriba, BorderLayout.NORTH);

        // ── Centro: estado + mando | instructor ────────────────────────────
        JPanel centro = new JPanel(new GridLayout(1, 2, 10, 0));
        centro.add(panelEstadoYMando());
        centro.add(panelInstructor());
        raiz.add(centro, BorderLayout.CENTER);

        // ── Registro ────────────────────────────────────────────────────────
        taRegistro.setEditable(false);
        taRegistro.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JPanel registro = new JPanel(new BorderLayout(4, 4));
        registro.setBorder(new TitledBorder(I18n.t("vint.registro")));
        registro.add(new JScrollPane(taRegistro), BorderLayout.CENTER);
        JPanel botonesReg = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton btnLimpiar = new JButton(I18n.t("vint.registro.limpiar"));
        btnLimpiar.addActionListener(e -> taRegistro.setText(""));
        JButton btnGuardar = new JButton(I18n.t("vint.registro.guardar"));
        btnGuardar.addActionListener(e -> guardarRegistro());
        botonesReg.add(btnLimpiar);
        botonesReg.add(btnGuardar);
        registro.add(botonesReg, BorderLayout.SOUTH);
        raiz.add(registro, BorderLayout.SOUTH);

        // ── Datos y eventos ─────────────────────────────────────────────────
        cargarAparatos();
        cbAparato.addActionListener(e -> refrescar());
        observador = (origen, texto, aceptado) -> SwingUtilities.invokeLater(() ->
                registrar(origen, texto, aceptado));
        srv.agregarObservadorMando(observador);
        refresco = new Timer(400, e -> refrescar());
        refresco.start();
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) {
                refresco.stop();
                srv.quitarObservadorMando(observador);
            }
        });

        registrar(I18n.t("vint.origen.sistema"), I18n.t("vint.evt.abierto", String.valueOf(cbAparato.getItemCount())), true);
        refrescar();
        pack();
        setMinimumSize(new Dimension(820, 640));
        setLocationRelativeTo(owner);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Paneles
    // ─────────────────────────────────────────────────────────────────────────

    private JPanel panelEstadoYMando() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(new TitledBorder(I18n.t("vint.estado")));

        lblEstado.setFont(lblEstado.getFont().deriveFont(Font.BOLD, 28f));
        lblEstado.setAlignmentX(Component.LEFT_ALIGNMENT);
        lblEstado.setBorder(new EmptyBorder(6, 4, 6, 4));
        p.add(lblEstado);
        p.add(alineado(lblCalidad));
        p.add(alineado(lblCtl));
        p.add(alineado(lblComandado));

        JPanel aux = new JPanel(new GridLayout(1, 2, 8, 0));
        aux.setBorder(new TitledBorder(I18n.t("vint.contactos")));
        aux.add(lbl52a);
        aux.add(lbl52b);
        aux.setMaximumSize(new Dimension(Integer.MAX_VALUE, aux.getPreferredSize().height));
        p.add(alineado(aux));

        JPanel mando = new JPanel(new GridLayout(1, 2, 8, 0));
        mando.setBorder(new TitledBorder(I18n.t("vint.mando.local")));
        JButton btnAbrir = new JButton(I18n.t("vint.abrir"));
        btnAbrir.setForeground(ROJO);
        btnAbrir.addActionListener(e -> conAparato(o -> srv.maniobrarLocal(o.ref, false)));
        JButton btnCerrar = new JButton(I18n.t("vint.cerrar"));
        btnCerrar.setForeground(VERDE);
        btnCerrar.addActionListener(e -> conAparato(o -> srv.maniobrarLocal(o.ref, true)));
        mando.add(btnAbrir);
        mando.add(btnCerrar);
        mando.setMaximumSize(new Dimension(Integer.MAX_VALUE, mando.getPreferredSize().height));
        p.add(alineado(mando));

        JPanel llave = new JPanel(new GridLayout(1, 2, 8, 0));
        llave.setBorder(new TitledBorder(I18n.t("vint.llave")));
        ButtonGroup gLlave = new ButtonGroup();
        gLlave.add(tbLocal);
        gLlave.add(tbRemoto);
        tbLocal.addActionListener(e -> { if (!actualizando) conAparato(o -> srv.ponerLlave(o.ref, true)); });
        tbRemoto.addActionListener(e -> { if (!actualizando) conAparato(o -> srv.ponerLlave(o.ref, false)); });
        llave.add(tbLocal);
        llave.add(tbRemoto);
        llave.setMaximumSize(new Dimension(Integer.MAX_VALUE, llave.getPreferredSize().height));
        p.add(alineado(llave));

        JPanel autoridad = new JPanel(new GridLayout(1, 4, 6, 0));
        autoridad.setBorder(new TitledBorder(I18n.t("vint.autoridad")));
        ButtonGroup gAut = new ButtonGroup();
        String[] textos = {I18n.t("vint.aut.local"), I18n.t("vint.aut.bahia"),
                           I18n.t("vint.aut.estacion"), I18n.t("vint.aut.remoto")};
        for (int i = 0; i < 4; i++) {
            final EstadoLocal.Autoridad a = EstadoLocal.Autoridad.values()[i];
            tbAutoridad[i] = new JToggleButton(textos[i]);
            gAut.add(tbAutoridad[i]);
            tbAutoridad[i].addActionListener(e -> {
                if (actualizando) return;
                conAparato(o -> {
                    srv.ponerAutoridad(o.ld, a);
                    registrar("Panel", I18n.t("vint.evt.autoridad", o.ld, a.name()), true);
                });
            });
            autoridad.add(tbAutoridad[i]);
        }
        autoridad.setMaximumSize(new Dimension(Integer.MAX_VALUE, autoridad.getPreferredSize().height));
        p.add(alineado(autoridad));
        p.add(Box.createVerticalGlue());
        return p;
    }

    private JPanel panelInstructor() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(new TitledBorder(I18n.t("vint.instructor")));

        JLabel ayuda = new JLabel("<html><body style='width:320px'>" + I18n.t("vint.instructor.ayuda") + "</body></html>");
        ayuda.setForeground(GRIS);
        p.add(alineado(ayuda));

        cbRespuesta.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                return super.getListCellRendererComponent(l,
                        v instanceof EstadoLocal.Respuesta ? IEC61850Server.textoRespuesta((EstadoLocal.Respuesta) v) : v,
                        i, s, f);
            }
        });
        cbRespuesta.addActionListener(e -> {
            if (actualizando) return;
            conAparato(o -> srv.ponerRespuesta(o.ref, (EstadoLocal.Respuesta) cbRespuesta.getSelectedItem()));
        });
        p.add(fila(I18n.t("vint.respuesta"), cbRespuesta));

        spRecorrido.setEditor(new JSpinner.NumberEditor(spRecorrido, "#"));
        spRecorrido.setValue((int) srv.tiempoRecorridoMs());
        spRecorrido.addChangeListener(e -> srv.ponerTiempoRecorridoMs(((Number) spRecorrido.getValue()).longValue()));
        p.add(fila(I18n.t("vint.recorrido"), spRecorrido));

        chkSincronismo.setText(I18n.t("vint.sincronismo"));
        chkSincronismo.addActionListener(e -> {
            if (actualizando) return;
            conAparato(o -> srv.ponerSincronismo(o.ref, chkSincronismo.isSelected()));
        });
        p.add(alineado(chkSincronismo));

        JPanel forzar = new JPanel(new GridLayout(1, 2, 8, 0));
        forzar.setBorder(new TitledBorder(I18n.t("vint.forzar")));
        JButton btnIntermedio = new JButton(I18n.t("vint.forzar.intermedio"));
        btnIntermedio.addActionListener(e -> conAparato(o ->
                srv.forzarPosicion(o.ref, BdaDoubleBitPos.DoubleBitPos.INTERMEDIATE_STATE)));
        JButton btnBad = new JButton(I18n.t("vint.forzar.bad"));
        btnBad.addActionListener(e -> conAparato(o ->
                srv.forzarPosicion(o.ref, BdaDoubleBitPos.DoubleBitPos.BAD_STATE)));
        forzar.add(btnIntermedio);
        forzar.add(btnBad);
        forzar.setMaximumSize(new Dimension(Integer.MAX_VALUE, forzar.getPreferredSize().height));
        p.add(alineado(forzar));

        chkCalidad.setText(I18n.t("vint.calidad.invalida"));
        chkCalidad.addActionListener(e -> {
            if (actualizando) return;
            conAparato(o -> srv.ponerCalidadInvalida(o.ref, chkCalidad.isSelected()));
        });
        p.add(alineado(chkCalidad));

        JButton btnReset = new JButton(I18n.t("vint.restablecer"));
        btnReset.addActionListener(e -> conAparato(o -> {
            srv.ponerRespuesta(o.ref, EstadoLocal.Respuesta.NORMAL);
            srv.ponerSincronismo(o.ref, false);
            srv.ponerCalidadInvalida(o.ref, false);
        }));
        p.add(Box.createVerticalStrut(8));
        p.add(alineado(btnReset));
        p.add(Box.createVerticalGlue());
        return p;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Datos
    // ─────────────────────────────────────────────────────────────────────────

    private void cargarAparatos() {
        cbAparato.removeAllItems();
        for (EstadoLocal.Objeto o : srv.listarAparatos()) {
            if (o.esAparato) cbAparato.addItem(new Item(o));
        }
    }

    /** Relee el aparato elegido del modelo y actualiza la pantalla (cada 400 ms). */
    private void refrescar() {
        Item it = (Item) cbAparato.getSelectedItem();
        if (it == null) {
            lblEstado.setText(I18n.t("vint.sin.aparatos"));
            lblEstado.setForeground(GRIS);
            return;
        }
        EstadoLocal.Objeto o = null;
        for (EstadoLocal.Objeto x : srv.listarAparatos()) {
            if (x.ref.equals(it.o.ref)) { o = x; break; }
        }
        if (o == null) return;
        actualizando = true;
        try {
            String est = o.estado == null ? "" : o.estado;
            switch (est) {
                case "CERRADO":    lblEstado.setText(I18n.t("vint.est.cerrado"));    lblEstado.setForeground(VERDE); break;
                case "ABIERTO":    lblEstado.setText(I18n.t("vint.est.abierto"));    lblEstado.setForeground(ROJO);  break;
                case "INTERMEDIO": lblEstado.setText(I18n.t("vint.est.intermedio")); lblEstado.setForeground(AMBAR); break;
                default:           lblEstado.setText(I18n.t("vint.est.invalido"));   lblEstado.setForeground(GRIS);
            }
            lblCalidad.setText(I18n.t("vint.calidad", o.calidad == null ? "-" : o.calidad));
            lblCtl.setText(I18n.t("vint.ctlmodel", String.valueOf(o.ctlModel), textoCtl(o.ctlModel)));
            lblComandado.setText(o.comandadoPor.isEmpty() ? " " : I18n.t("vint.comandado", o.comandadoPor));
            boolean cerrado = "CERRADO".equals(est), abierto = "ABIERTO".equals(est);
            boolean bad = !cerrado && !abierto && !"INTERMEDIO".equals(est);
            contacto(lbl52a, "52a", cerrado || bad);
            contacto(lbl52b, "52b", abierto || bad);

            Boolean local = srv.enLocal(o.ref);
            tbLocal.setEnabled(local != null);
            tbRemoto.setEnabled(local != null);
            tbLocal.setSelected(Boolean.TRUE.equals(local));
            tbRemoto.setSelected(Boolean.FALSE.equals(local));

            EstadoLocal.Autoridad a = srv.autoridadDe(o.ld);
            for (int i = 0; i < 4; i++) {
                tbAutoridad[i].setEnabled(a != null);
                tbAutoridad[i].setSelected(a != null && a.ordinal() == i);
            }
            cbRespuesta.setSelectedItem(srv.respuestaDe(o.ref));
            chkSincronismo.setSelected(srv.sincronismoEnFalla(o.ref));
        } finally {
            actualizando = false;
        }
    }

    private static void contacto(JLabel l, String nombre, boolean cerrado) {
        l.setText(nombre + ":  " + I18n.t(cerrado ? "vint.contacto.cerrado" : "vint.contacto.abierto"));
        l.setForeground(cerrado ? VERDE : ROJO);
        l.setFont(l.getFont().deriveFont(Font.BOLD));
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

    private void conAparato(java.util.function.Consumer<EstadoLocal.Objeto> accion) {
        Item it = (Item) cbAparato.getSelectedItem();
        if (it == null) return;
        accion.accept(it.o);
        refrescar();
    }

    private void registrar(String origen, String texto, boolean aceptado) {
        taRegistro.append(LocalTime.now().format(HORA) + "  [" + origen + "]  "
                + (aceptado ? "" : "✗ ") + texto + "\n");
        taRegistro.setCaretPosition(taRegistro.getDocument().getLength());
    }

    private void guardarRegistro() {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("interruptor_virtual.txt"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            Files.write(fc.getSelectedFile().toPath(), taRegistro.getText().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), I18n.t("vint.titulo"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private static JComponent alineado(JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
    }

    private static JComponent fila(String etiqueta, JComponent campo) {
        JPanel f = new JPanel(new BorderLayout(6, 0));
        f.add(new JLabel(etiqueta), BorderLayout.WEST);
        f.add(campo, BorderLayout.CENTER);
        f.setMaximumSize(new Dimension(Integer.MAX_VALUE, f.getPreferredSize().height + 4));
        return alineado(f);
    }
}
