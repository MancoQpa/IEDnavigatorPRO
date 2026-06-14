import { useState } from 'react';
import { getApi } from '../api/client';
import { pickSclFile } from '../api/pickFile';
import type { GooseMapResult } from '../api/types';
import { log } from '../stores/log';
import { useServerStore } from '../stores/server';
import { useUiStore } from '../stores/ui';

/** Mapa de suscripciones GOOSE de un SCD: publicadores vs. ExtRef/LGOS. */
export default function GooseMapPanel() {
  const serverScl = useServerStore((s) => s.sclPath);
  const setTreeSearch = useUiStore((s) => s.setTreeSearch);
  const [path, setPath] = useState(serverScl);
  const [result, setResult] = useState<GooseMapResult | null>(null);
  const [busy, setBusy] = useState(false);

  const analyze = async () => {
    setBusy(true);
    try {
      const r = await getApi().sclGooseMap(path.trim());
      setResult(r);
      log.info(
        `Mapa GOOSE de ${r.file}: ${r.publishers.length} publicadores, ${r.subscriptions.length} suscripciones`,
      );
    } catch (e) {
      log.error(`Error analizando mapa GOOSE: ${(e as Error).message}`);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="flex h-full flex-col bg-white dark:bg-surface">
      {/* ── Controles ── */}
      <div className="flex flex-wrap items-center gap-2 border-b border-gray-200 p-1.5 text-xs dark:border-surface-border">
        <input
          value={path}
          onChange={(e) => setPath(e.target.value)}
          placeholder="Archivo SCL (idealmente SCD multi-IED)"
          className="min-w-72 flex-1 rounded border border-gray-300 bg-transparent px-2 py-1 font-mono text-[11px] dark:border-surface-border dark:text-gray-200"
        />
        <button
          onClick={() =>
            void pickSclFile('Seleccionar archivo SCD').then((p) => p && setPath(p))
          }
          disabled={busy}
          className="rounded border border-gray-300 px-2 py-1 hover:bg-gray-100 disabled:opacity-40 dark:border-surface-border dark:text-gray-300 dark:hover:bg-surface-raised"
        >
          Examinar…
        </button>
        <button
          onClick={() => void analyze()}
          disabled={busy || !path.trim()}
          className="rounded bg-accent px-3 py-1 font-medium text-white hover:bg-accent-hover disabled:opacity-40"
        >
          {busy ? 'Analizando…' : 'Analizar'}
        </button>
        {result && (
          <span className="text-gray-400">
            {result.file} · IEDs: {result.iedNames.join(', ')}
          </span>
        )}
      </div>

      <div className="min-h-0 flex-1 overflow-auto">
        {!result ? (
          <div className="flex h-full items-center justify-center p-4 text-center text-xs text-gray-400">
            Indique un archivo SCD y pulse «Analizar» para cruzar GSEControl con ExtRef/LGOS.
          </div>
        ) : (
          <div className="flex flex-col gap-3 p-2">
            {/* ── Publicadores ── */}
            <div>
              <div className="mb-1 text-[10px] font-medium uppercase tracking-wide text-gray-500">
                Publicadores ({result.publishers.length})
              </div>
              <table className="w-full border-collapse text-xs">
                <thead className="bg-gray-50 dark:bg-surface-raised">
                  <tr className="text-left text-[10px] uppercase tracking-wide text-gray-500">
                    <th className="px-2 py-1.5 font-medium">IED</th>
                    <th className="px-2 py-1.5 font-medium">GoCB</th>
                    <th className="px-2 py-1.5 font-medium">DataSet</th>
                    <th className="px-2 py-1.5 font-medium">goID</th>
                    <th className="px-2 py-1.5 font-medium">MAC</th>
                    <th className="px-2 py-1.5 font-medium">APPID</th>
                    <th className="px-2 py-1.5 font-medium">Miembros</th>
                    <th className="px-2 py-1.5 font-medium">Suscriptores</th>
                  </tr>
                </thead>
                <tbody>
                  {result.publishers.map((p, i) => (
                    <tr
                      key={i}
                      className={`border-t border-gray-100 dark:border-surface-border/50 ${
                        i % 2 ? 'bg-gray-50/60 dark:bg-surface-raised/40' : ''
                      }`}
                    >
                      <td className="px-2 py-1 font-medium text-gray-700 dark:text-gray-300">{p.iedName}</td>
                      <td className="px-2 py-1 font-mono text-[11px] dark:text-gray-300">
                        {p.ldInst}/{p.cbName}
                      </td>
                      <td
                        className="cursor-pointer px-2 py-1 font-mono text-[11px] text-accent hover:underline dark:text-accent-hover"
                        title="Clic para navegar en el árbol del modelo"
                        onClick={() => setTreeSearch(p.datSet.includes('/') ? p.datSet.split('/')[0] : p.datSet)}
                      >
                        {p.datSet}
                      </td>
                      <td className="px-2 py-1 font-mono text-[11px] text-gray-500">{p.appId}</td>
                      <td className="px-2 py-1 font-mono text-[11px] text-gray-500">{p.mac || '—'}</td>
                      <td className="px-2 py-1 font-mono text-[11px] text-gray-500">{p.appidHex || '—'}</td>
                      <td className="px-2 py-1 text-gray-500">{p.members.length}</td>
                      <td className="px-2 py-1">
                        <span
                          className={`rounded-full px-2 py-0.5 text-[10px] font-medium ${
                            p.subscriberCount > 0
                              ? 'bg-green-100 text-green-700 dark:bg-green-950 dark:text-green-300'
                              : 'bg-gray-100 text-gray-500 dark:bg-surface-raised dark:text-gray-400'
                          }`}
                        >
                          {p.subscriberCount}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {/* ── Suscripciones ── */}
            <div>
              <div className="mb-1 text-[10px] font-medium uppercase tracking-wide text-gray-500">
                Suscripciones ({result.subscriptions.length})
              </div>
              {result.subscriptions.length === 0 ? (
                <div className="p-2 text-xs text-gray-400">
                  Sin ExtRef/LGOS de tipo GOOSE en el archivo.
                </div>
              ) : (
                <table className="w-full border-collapse text-xs">
                  <thead className="bg-gray-50 dark:bg-surface-raised">
                    <tr className="text-left text-[10px] uppercase tracking-wide text-gray-500">
                      <th className="px-2 py-1.5 font-medium">Suscriptor</th>
                      <th className="px-2 py-1.5 font-medium">Publicador</th>
                      <th className="px-2 py-1.5 font-medium">Dato / GoCBRef</th>
                      <th className="px-2 py-1.5 font-medium">Destino</th>
                      <th className="px-2 py-1.5 font-medium">Vía</th>
                      <th className="px-2 py-1.5 font-medium">Estado</th>
                    </tr>
                  </thead>
                  <tbody>
                    {result.subscriptions.map((s, i) => (
                      <tr
                        key={i}
                        className={`border-t border-gray-100 dark:border-surface-border/50 ${
                          i % 2 ? 'bg-gray-50/60 dark:bg-surface-raised/40' : ''
                        }`}
                      >
                        <td className="px-2 py-1 font-medium text-gray-700 dark:text-gray-300">
                          {s.subscriberIed}
                        </td>
                        <td className="px-2 py-1 font-mono text-[11px] dark:text-gray-300">{s.pubRef}</td>
                        <td
                          className="cursor-pointer px-2 py-1 font-mono text-[11px] text-accent hover:underline dark:text-accent-hover"
                          title="Clic para navegar en el árbol del modelo"
                          onClick={() => setTreeSearch(s.dataRef)}
                        >
                          {s.dataRef}
                        </td>
                        <td className="px-2 py-1 font-mono text-[11px] text-gray-500">{s.target || '—'}</td>
                        <td className="px-2 py-1 text-gray-500">{s.via}</td>
                        <td className="px-2 py-1">
                          <span
                            className={`text-[10px] font-medium ${
                              s.resolved
                                ? 'text-green-600 dark:text-green-400'
                                : 'text-amber-600 dark:text-amber-400'
                            }`}
                          >
                            {s.resolved ? 'Resuelto' : 'No resuelto'}
                          </span>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
