import { useMemo, useState } from 'react';
import { getApi } from '../api/client';
import { pickSclFile } from '../api/pickFile';
import type { SclCompareResult } from '../api/types';
import { log } from '../stores/log';

const CATEGORY_COLORS: Record<string, string> = {
  IED: 'bg-blue-100 text-blue-700 dark:bg-blue-950 dark:text-blue-300',
  LN: 'bg-purple-100 text-purple-700 dark:bg-purple-950 dark:text-purple-300',
  DataSet: 'bg-teal-100 text-teal-700 dark:bg-teal-950 dark:text-teal-300',
  GoCB: 'bg-amber-100 text-amber-700 dark:bg-amber-950 dark:text-amber-300',
  Report: 'bg-pink-100 text-pink-700 dark:bg-pink-950 dark:text-pink-300',
  Communication: 'bg-cyan-100 text-cyan-700 dark:bg-cyan-950 dark:text-cyan-300',
  Valores: 'bg-green-100 text-green-700 dark:bg-green-950 dark:text-green-300',
};

/** Comparador de archivos SCL (ICD/CID/SCD) para puesta en servicio. */
export default function SclComparePanel() {
  const [pathA, setPathA] = useState('');
  const [pathB, setPathB] = useState('');
  const [ignoreIedName, setIgnoreIedName] = useState(false);
  const [result, setResult] = useState<SclCompareResult | null>(null);
  const [filter, setFilter] = useState('');
  const [busy, setBusy] = useState(false);

  const compare = async () => {
    setBusy(true);
    try {
      const r = await getApi().sclCompare(pathA.trim(), pathB.trim(), ignoreIedName);
      setResult(r);
      setFilter('');
      log.info(`Comparación SCL: ${r.total} diferencias entre ${r.fileA} y ${r.fileB}`);
    } catch (e) {
      log.error(`Error comparando SCL: ${(e as Error).message}`);
    } finally {
      setBusy(false);
    }
  };

  const visible = useMemo(() => {
    if (!result) return [];
    if (!filter) return result.differences;
    return result.differences.filter((d) => d.category === filter);
  }, [result, filter]);

  return (
    <div className="flex h-full flex-col bg-white dark:bg-surface">
      {/* ── Controles ── */}
      <div className="flex flex-wrap items-center gap-2 border-b border-gray-200 p-1.5 text-xs dark:border-surface-border">
        <input
          value={pathA}
          onChange={(e) => setPathA(e.target.value)}
          placeholder="Archivo A (ruta completa .icd/.cid/.scd)"
          className="min-w-72 flex-1 rounded border border-gray-300 bg-transparent px-2 py-1 font-mono text-[11px] dark:border-surface-border dark:text-gray-200"
        />
        <button
          onClick={() => void pickSclFile('Seleccionar archivo A').then((p) => p && setPathA(p))}
          disabled={busy}
          className="rounded border border-gray-300 px-2 py-1 hover:bg-gray-100 disabled:opacity-40 dark:border-surface-border dark:text-gray-300 dark:hover:bg-surface-raised"
        >
          A…
        </button>
        <input
          value={pathB}
          onChange={(e) => setPathB(e.target.value)}
          placeholder="Archivo B (ruta completa .icd/.cid/.scd)"
          className="min-w-72 flex-1 rounded border border-gray-300 bg-transparent px-2 py-1 font-mono text-[11px] dark:border-surface-border dark:text-gray-200"
        />
        <button
          onClick={() => void pickSclFile('Seleccionar archivo B').then((p) => p && setPathB(p))}
          disabled={busy}
          className="rounded border border-gray-300 px-2 py-1 hover:bg-gray-100 disabled:opacity-40 dark:border-surface-border dark:text-gray-300 dark:hover:bg-surface-raised"
        >
          B…
        </button>
        <label className="flex items-center gap-1 text-gray-500 dark:text-gray-400">
          <input
            type="checkbox"
            checked={ignoreIedName}
            onChange={(e) => setIgnoreIedName(e.target.checked)}
          />
          Ignorar nombre IED
        </label>
        <button
          onClick={() => void compare()}
          disabled={busy || !pathA.trim() || !pathB.trim()}
          className="rounded bg-accent px-3 py-1 font-medium text-white hover:bg-accent-hover disabled:opacity-40"
        >
          {busy ? 'Comparando…' : 'Comparar'}
        </button>
      </div>

      {/* ── Resumen por categoría ── */}
      {result && (
        <div className="flex flex-wrap items-center gap-1.5 border-b border-gray-200 px-2 py-1.5 text-[11px] dark:border-surface-border">
          <button
            onClick={() => setFilter('')}
            className={`rounded-full px-2 py-0.5 font-medium ${
              filter === ''
                ? 'bg-accent text-white'
                : 'bg-gray-100 text-gray-600 dark:bg-surface-raised dark:text-gray-300'
            }`}
          >
            Todas ({result.total})
          </button>
          {Object.entries(result.byCategory)
            .filter(([, n]) => n > 0)
            .map(([cat, n]) => (
              <button
                key={cat}
                onClick={() => setFilter(filter === cat ? '' : cat)}
                className={`rounded-full px-2 py-0.5 font-medium ${
                  filter === cat
                    ? 'bg-accent text-white'
                    : CATEGORY_COLORS[cat] ?? 'bg-gray-100 text-gray-600'
                }`}
              >
                {cat} ({n})
              </button>
            ))}
          <div className="flex-1" />
          <span className="text-gray-400">
            A: {result.fileA} · B: {result.fileB}
          </span>
        </div>
      )}

      {/* ── Diferencias ── */}
      <div className="min-h-0 flex-1 overflow-auto">
        {!result ? (
          <div className="flex h-full items-center justify-center p-4 text-center text-xs text-gray-400">
            Indique las rutas de dos archivos SCL y pulse «Comparar».
          </div>
        ) : result.total === 0 ? (
          <div className="flex h-full items-center justify-center p-4 text-center text-xs text-green-600 dark:text-green-400">
            Sin diferencias: las configuraciones son idénticas.
          </div>
        ) : (
          <table className="w-full border-collapse text-xs">
            <thead className="sticky top-0 bg-gray-50 dark:bg-surface-raised">
              <tr className="text-left text-[10px] uppercase tracking-wide text-gray-500">
                <th className="px-2 py-1.5 font-medium">Categoría</th>
                <th className="px-2 py-1.5 font-medium">Elemento</th>
                <th className="px-2 py-1.5 font-medium">Valor A</th>
                <th className="px-2 py-1.5 font-medium">Valor B</th>
                <th className="px-2 py-1.5 font-medium">Estado</th>
              </tr>
            </thead>
            <tbody>
              {visible.map((d, i) => (
                <tr
                  key={i}
                  className={`border-t border-gray-100 dark:border-surface-border/50 ${
                    i % 2 ? 'bg-gray-50/60 dark:bg-surface-raised/40' : ''
                  }`}
                >
                  <td className="px-2 py-1">
                    <span
                      className={`rounded px-1.5 py-0.5 text-[10px] font-medium ${
                        CATEGORY_COLORS[d.category] ?? 'bg-gray-100 text-gray-600'
                      }`}
                    >
                      {d.category}
                    </span>
                  </td>
                  <td className="px-2 py-1 font-mono text-[11px] text-gray-700 dark:text-gray-300">
                    {d.element}
                  </td>
                  <td className="px-2 py-1 font-mono text-[11px] text-gray-600 dark:text-gray-400">
                    {d.valueA ?? <span className="text-gray-300 dark:text-gray-600">—</span>}
                  </td>
                  <td className="px-2 py-1 font-mono text-[11px] text-gray-600 dark:text-gray-400">
                    {d.valueB ?? <span className="text-gray-300 dark:text-gray-600">—</span>}
                  </td>
                  <td className="px-2 py-1">
                    <span
                      className={`text-[10px] font-medium ${
                        d.status === 'Diferente'
                          ? 'text-amber-600 dark:text-amber-400'
                          : d.status === 'Solo en A'
                            ? 'text-red-600 dark:text-red-400'
                            : 'text-blue-600 dark:text-blue-400'
                      }`}
                    >
                      {d.status}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
