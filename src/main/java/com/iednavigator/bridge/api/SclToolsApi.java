package com.iednavigator.bridge.api;

import com.iednavigator.GooseMapAnalyzer;
import com.iednavigator.SclCompare;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Utilidades SCL sin estado: comparación de archivos (puesta en servicio)
 * y mapa de suscripciones GOOSE (publicadores vs. ExtRef/LGOS).
 */
public final class SclToolsApi {

    // ── DTOs ──────────────────────────────────────────────────────────────

    public static class CompareRequest {
        public String pathA;
        public String pathB;
        public boolean ignoreIedName;
    }

    public static class GooseMapRequest {
        public String path;
    }

    // ── Handlers ──────────────────────────────────────────────────────────

    /** POST /scl/compare {pathA, pathB, ignoreIedName}. */
    public void compare(Context ctx) throws Exception {
        CompareRequest req = ctx.bodyAsClass(CompareRequest.class);
        File a = requireFile(req.pathA, "pathA");
        File b = requireFile(req.pathB, "pathB");

        List<SclCompare.Difference> diffs = SclCompare.compare(a, b, req.ignoreIedName);

        Map<String, Integer> byCategory = new LinkedHashMap<>();
        for (String cat : SclCompare.CATEGORIES) {
            byCategory.put(cat, 0);
        }
        List<Map<String, Object>> items = new ArrayList<>(diffs.size());
        for (SclCompare.Difference d : diffs) {
            byCategory.merge(d.category, 1, Integer::sum);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("category", d.category);
            m.put("element", d.element);
            m.put("valueA", d.valueA);
            m.put("valueB", d.valueB);
            m.put("status", d.status());
            items.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fileA", a.getName());
        out.put("fileB", b.getName());
        out.put("total", diffs.size());
        out.put("byCategory", byCategory);
        out.put("differences", items);
        ctx.json(out);
    }

    /** POST /scl/goose-map {path}. */
    public void gooseMap(Context ctx) throws Exception {
        GooseMapRequest req = ctx.bodyAsClass(GooseMapRequest.class);
        File f = requireFile(req.path, "path");

        GooseMapAnalyzer.Result res = GooseMapAnalyzer.analyze(f);

        List<Map<String, Object>> pubs = new ArrayList<>(res.publishers.size());
        for (GooseMapAnalyzer.Publisher p : res.publishers) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("iedName", p.iedName);
            m.put("ldInst", p.ldInst);
            m.put("cbName", p.cbName);
            m.put("datSet", p.datSet);
            m.put("appId", p.appId);
            m.put("mac", p.mac);
            m.put("appidHex", p.appidHex);
            m.put("members", p.members);
            m.put("subscriberCount", p.subscriberCount);
            pubs.add(m);
        }

        List<Map<String, Object>> subs = new ArrayList<>(res.subscriptions.size());
        for (GooseMapAnalyzer.Subscription s : res.subscriptions) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("subscriberIed", s.subscriberIed);
            m.put("pubIed", s.pubIed);
            m.put("pubLd", s.pubLd);
            m.put("pubCb", s.pubCb);
            m.put("pubRef", s.pubRef());
            m.put("dataRef", s.dataRef);
            m.put("target", s.target);
            m.put("via", s.via);
            m.put("resolved", s.resolved);
            subs.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("file", f.getName());
        out.put("iedNames", res.iedNames);
        out.put("publishers", pubs);
        out.put("subscriptions", subs);
        ctx.json(out);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static File requireFile(String path, String field) {
        if (path == null || path.isEmpty()) {
            throw new BadRequestResponse(field + " requerido");
        }
        File f = new File(path);
        if (!f.isFile()) {
            throw new BadRequestResponse("Archivo no encontrado: " + path);
        }
        return f;
    }
}
