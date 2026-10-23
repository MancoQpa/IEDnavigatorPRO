package com.iednavigator;

import java.net.DatagramPacket;
import java.net.InetAddress;

/**
 * El estado que fija el botón Cambio viaja por GOOSE/UDP y se ve en la columna "Datos".
 *
 * Hasta 2026-10-23 el puente UDP mandaba stVal = stNum % 4: un número que dependía de cuántos
 * cambios hubo, no del estado elegido. En el receptor no se veía si había pasado de ON a OFF,
 * y la columna mostraba un número suelto.
 *
 * Arma el paquete como lo envía la app (createGooseUdpPacket) y lo procesa como el receptor
 * (processPacket), sin red.
 *
 * Uso:  javac -cp "classes;lib\*" -d test test\TestGooseUdpEstado.java
 *       java  -cp "classes;lib\*;test" com.iednavigator.TestGooseUdpEstado
 */
public class TestGooseUdpEstado {

    private static int fallas = 0;

    static void chk(String titulo, String esperado, String obtenido) {
        boolean ok = esperado.equals(obtenido);
        if (!ok) fallas++;
        System.out.printf("%-46s %-20s %s%n", titulo, obtenido, ok ? "OK" : "<<< FALLA (esperado " + esperado + ")");
    }

    public static void main(String[] args) throws Exception {
        GooseUdpBridge puente = new GooseUdpBridge();
        final GooseSubscriber.GooseMessage[] recibido = new GooseSubscriber.GooseMessage[1];
        puente.setMessageListener(m -> recibido[0] = m);

        // Los cuatro valores del combo Estado, en el orden en que los fija updatePublisherState()
        String[] combo = {"OFF", "ON", "INTERMEDIATE", "BAD"};
        int[] dbpos = {1, 2, 0, 3};
        for (int i = 0; i < combo.length; i++) {
            GoosePublisher pub = new GoosePublisher();
            pub.setDataValue(0, dbpos[i]);
            byte[] datos = puente.createGooseUdpPacket(pub);
            recibido[0] = null;
            puente.processPacket(new DatagramPacket(datos, datos.length, InetAddress.getLoopbackAddress(), 62746));
            String texto = recibido[0] == null || recibido[0].dataEntries.isEmpty() ? "nada"
                : GoosePanel.textoDato(recibido[0].dataEntries.get(0).type, recibido[0].dataEntries.get(0).value);
            chk("Estado " + combo[i] + " llega por UDP como", combo[i] + " (" + dbpos[i] + ")", texto);
        }

        // Lo que publica esta PC (filas LOCAL): el tipo es DBPOS
        chk("fila LOCAL, DBPOS 2", "ON (2)", GoosePanel.textoDato("DBPOS", 2));
        // Otros tipos, sin cambios
        chk("booleano, sin nombre de estado", "true", GoosePanel.textoDato("BOOLEAN", true));
        chk("bit-string de Capa 2, sin nombre", "2", GoosePanel.textoDato("BitString", 2));
        chk("Dbpos fuera de rango, el número", "7", GoosePanel.textoDato("DbPos", 7));

        System.out.println(fallas == 0 ? "\nTODO OK" : "\n" + fallas + " FALLAS");
        System.exit(fallas == 0 ? 0 : 1);
    }
}
