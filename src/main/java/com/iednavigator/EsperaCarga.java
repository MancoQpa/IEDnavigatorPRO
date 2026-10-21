package com.iednavigator;

import javax.swing.*;
import java.awt.*;

/**
 * Ventana de espera para la carga de un archivo SCL: el nombre del archivo, el aviso de que un
 * SCD grande puede tardar minutos, los segundos transcurridos y "Seguir en segundo plano".
 *
 * Nació en la carga de SCL de la pestaña GOOSE (GoosePanel): con un SCD de 14 MB la ventana
 * quedaba congelada 2 min 37 s sin ningún aviso, y con uno de 23,6 MB seis minutos (medido
 * 2026-08). El 2026-10-21 se sacó a esta clase para usarla también en la carga del modo
 * servidor, que no avisaba nada: solo "Analizando SCL..." en la cabecera.
 *
 * Solo aparece si la carga pasa de medio segundo: un CID chico se carga en milisegundos y la
 * ventana sería un parpadeo. Todos los métodos se llaman en el EDT.
 */
final class EsperaCarga {

    private static final int DEMORA_MS = 500;

    private final JDialog dialogo;
    private final javax.swing.Timer reloj;
    private final javax.swing.Timer mostrar;
    private final long inicio = System.currentTimeMillis();
    private boolean terminada = false;
    private boolean enSegundoPlano = false;
    private boolean pausada = false;

    /**
     * @param padre     ventana o componente sobre el que se centra
     * @param archivo   nombre del archivo, para el texto
     * @param alSeguirEnFondo se llama si el usuario elige "Seguir en segundo plano" (para
     *                  dejarlo en el log); puede ser null
     */
    EsperaCarga(Component padre, String archivo, Runnable alSeguirEnFondo) {
        Window duena = (padre instanceof Window) ? (Window) padre : SwingUtilities.getWindowAncestor(padre);
        dialogo = new JDialog(duena, I18n.t("goose.sclwait.title"), Dialog.ModalityType.APPLICATION_MODAL);
        dialogo.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        JLabel lblTiempo = new JLabel(I18n.t("goose.sclwait.elapsed", "0"));
        JProgressBar barra = new JProgressBar();
        barra.setIndeterminate(true);
        JButton btnFondo = new JButton(I18n.t("goose.sclwait.background"));
        JPanel cuerpo = new JPanel(new BorderLayout(8, 8));
        cuerpo.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        JPanel textos = new JPanel(new GridLayout(0, 1, 0, 4));
        textos.add(new JLabel(I18n.t("goose.sclwait.msg", archivo)));
        textos.add(lblTiempo);
        cuerpo.add(textos, BorderLayout.NORTH);
        cuerpo.add(barra, BorderLayout.CENTER);
        JPanel botones = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        botones.add(btnFondo);
        cuerpo.add(botones, BorderLayout.SOUTH);
        dialogo.setContentPane(cuerpo);
        dialogo.pack();
        dialogo.setLocationRelativeTo(padre);

        reloj = new javax.swing.Timer(1000, e -> lblTiempo.setText(
            I18n.t("goose.sclwait.elapsed", String.valueOf(segundos()))));
        btnFondo.addActionListener(e -> {
            enSegundoPlano = true;
            if (alSeguirEnFondo != null) alSeguirEnFondo.run();
            reloj.stop();
            dialogo.setVisible(false);
        });
        mostrar = new javax.swing.Timer(DEMORA_MS, e -> mostrarSiCorresponde());
        mostrar.setRepeats(false);
    }

    /** Arranca la cuenta; la ventana aparece si a los 500 ms la carga sigue. */
    void empezar() {
        mostrar.start();
    }

    /**
     * Oculta la ventana mientras se muestra otro diálogo (la elección del IED de un SCD). El
     * reloj sigue: el tiempo de elegir también es tiempo de carga para quien espera.
     */
    void pausar() {
        pausada = true;
        mostrar.stop();
        if (dialogo.isVisible()) dialogo.setVisible(false);
    }

    /** Vuelve a mostrarla tras pausar(), salvo que la carga ya terminó o se mandó al fondo. */
    void reanudar() {
        pausada = false;
        mostrar.restart();
    }

    /** La carga terminó (bien, mal o cancelada): cierra todo. */
    void terminar() {
        terminada = true;
        mostrar.stop();
        reloj.stop();
        dialogo.dispose();
    }

    long segundos() {
        return (System.currentTimeMillis() - inicio) / 1000;
    }

    private void mostrarSiCorresponde() {
        if (terminada || enSegundoPlano || pausada || dialogo.isVisible()) return;
        reloj.start();
        dialogo.setVisible(true);   // modal: vuelve cuando se oculta o se cierra
    }
}
