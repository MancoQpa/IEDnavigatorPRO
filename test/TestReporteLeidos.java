package com.iednavigator;

import com.beanit.iec61850bean.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * El reporte HTML marca "no leído" lo que el equipo no respondió, en lugar de mostrar el valor
 * inicial del modelo como si fuera del equipo.
 *
 * El caso de campo (2026-10-25): el reporte del NARI (modelo armado a mano, casi nada leído)
 * mostraba ctlModel 0 en un aparato que se maniobra por SBOw, ajustes en 0.0 y Beh/Mod/Health
 * en 0, que ni siquiera es un valor del enumerado.
 *
 * Uso:  javac -cp "classes;lib\*" -d test test\TestReporteLeidos.java
 *       java  -cp "classes;lib\*;test" com.iednavigator.TestReporteLeidos
 */
public class TestReporteLeidos {

    private static int fallas = 0;

    static void chk(String titulo, boolean ok, String detalle) {
        if (!ok) fallas++;
        System.out.printf("%-60s %-26s %s%n", titulo, detalle, ok ? "OK" : "<<< FALLA");
    }

    public static void main(String[] args) throws Exception {
        // La clave del objeto, como la arma leerTodosLosValores
        chk("clave de un atributo anidado", "IEDLD0/Q0CSWI1.Pos$ST".equals(
                ModelReportGenerator.claveDelObjeto("IEDLD0/Q0CSWI1.Pos.origin.orCat", Fc.ST)),
            ModelReportGenerator.claveDelObjeto("IEDLD0/Q0CSWI1.Pos.origin.orCat", Fc.ST));
        chk("clave de un atributo directo", "IEDLD0/LLN0.Beh$ST".equals(
                ModelReportGenerator.claveDelObjeto("IEDLD0/LLN0.Beh.stVal", Fc.ST)), "");

        List<ServerModel> modelos = SclParser.parse("test/test_bay_control.cid");
        ServerModel m = modelos.get(0);

        // Un solo objeto "leído": el Pos del CSWI en ST
        BasicDataAttribute stVal = null;
        for (ModelNode ld : m.getChildren()) for (ModelNode ln : ld.getChildren()) {
            ModelNode n = m.findModelNode(ln.getReference() + ".Pos.stVal", Fc.ST);
            if (n instanceof BasicDataAttribute && ln.getName().contains("CSWI")) { stVal = (BasicDataAttribute) n; break; }
        }
        chk("el modelo de prueba tiene un CSWI.Pos.stVal", stVal != null, stVal == null ? "no" : stVal.getReference().toString());
        if (stVal == null) { fin(); return; }
        String claveLeida = ModelReportGenerator.claveDelObjeto(stVal.getReference().toString(), Fc.ST);
        Set<String> leidos = new HashSet<>();
        leidos.add(claveLeida);

        File out = File.createTempFile("reporte", ".html");
        ModelReportGenerator.generate(out, m, "prueba", null, true, leidos);
        String html = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);

        String filaStVal = fila(html, stVal.getReference().toString(), "ST");
        chk("el atributo leído muestra su valor", filaStVal != null && !filaStVal.contains("no leído"), recorte(filaStVal));
        String ctl = stVal.getReference().toString().replace(".stVal", ".ctlModel");
        String filaCtl = fila(html, ctl, "CF");
        chk("ctlModel no leído dice \"no leído\" (no un 0)", filaCtl != null && filaCtl.contains("no leído"), recorte(filaCtl));
        chk("el encabezado dice cuántos se leyeron", html.contains("Valores leídos del equipo ("), "");
        chk("explica qué significa \"no leído\"", html.contains("no respondió"), "");

        // Sin conjunto (modo servidor): valores del archivo, sin marcas
        File out2 = File.createTempFile("reporte", ".html");
        ModelReportGenerator.generate(out2, m, "prueba", null, true, null);
        String html2 = new String(Files.readAllBytes(out2.toPath()), StandardCharsets.UTF_8);
        chk("modo servidor: sin \"no leído\"", !html2.contains("no leído"), "");
        chk("modo servidor: \"Valores del modelo\"", html2.contains("Valores del modelo ("), "");
        Files.deleteIfExists(out.toPath());
        Files.deleteIfExists(out2.toPath());
        fin();
    }

    static String fila(String html, String ref, String fc) {
        String abre = "<tr><td class='mono'>" + ref + "</td><td>" + fc + "</td>";
        int i = html.indexOf(abre);
        if (i < 0) return null;
        return html.substring(i, html.indexOf("</tr>", i));
    }

    static String recorte(String s) {
        if (s == null) return "sin fila";
        int i = s.lastIndexOf("<td");
        return s.substring(i).replaceAll("<[^>]+>", "");
    }

    static void fin() {
        System.out.println(fallas == 0 ? "\nTODO OK" : "\n" + fallas + " FALLAS");
        System.exit(fallas == 0 ? 0 : 1);
    }
}
