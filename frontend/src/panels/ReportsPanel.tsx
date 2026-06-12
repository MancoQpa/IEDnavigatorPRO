import { useEffect } from 'react';
import { useConnectionStore } from '../stores/connection';
import { useReportsStore } from '../stores/reports';

const cellCls = 'px-2 py-1';
const headCls = 'px-2 py-1.5 font-medium';

/** Reports IEC 61850: RCBs del modelo (URCB/BRCB) + feed de reports recibidos. */
export default function ReportsPanel() {
  const connected = useConnectionStore((s) => s.client.connected);
  const rcbs = useReportsStore((s) => s.rcbs);
  const reports = useReportsStore((s) => s.reports);
  const loading = useReportsStore((s) => s.loading);
  const fetch = useReportsStore((s) => s.fetch);
  const enable = useReportsStore((s) => s.enable);
  const disable = useReportsStore((s) => s.disable);
  const clearReports = useReportsStore((s) => s.clearReports);

  useEffect(() => {
    if (connected) void fetch(true);
  }, [connected, fetch]);

  if (!connected) {
    return (
      <div className="flex h-full items-center justify-center bg-white p-4 text-xs text-gray-400 dark:bg-surface">
        Sin conexión.
      </div>
    );
  }

  return (
    <div className="flex h-full flex-col bg-white dark:bg-surface">
      {/* ── RCBs ── */}
      <div className="flex items-center gap-2 border-b border-gray-200 p-1.5 text-xs dark:border-surface-border">
        <span className="font-medium text-gray-600 dark:text-gray-300">
          Report Control Blocks ({rcbs.length})
        </span>
        <div className="flex-1" />
        <button
          onClick={() => void fetch(true)}
          disabled={loading}
          className="rounded border border-gray-300 px-2 py-1 hover:bg-gray-100 disabled:opacity-40 dark:border-surface-border dark:text-gray-300 dark:hover:bg-surface-raised"
        >
          {loading ? 'Actualizando…' : 'Actualizar'}
        </button>
      </div>
      <div className="max-h-[45%] overflow-auto border-b border-gray-200 dark:border-surface-border">
        <table className="w-full border-collapse text-xs">
          <thead className="sticky top-0 bg-gray-50 dark:bg-surface-raised">
            <tr className="text-left text-[10px] uppercase tracking-wide text-gray-500">
              <th className={headCls}>RCB</th>
              <th className={headCls}>Tipo</th>
              <th className={headCls}>DataSet</th>
              <th className={headCls}>RptID</th>
              <th className={headCls}>Estado</th>
              <th className="w-24" />
            </tr>
          </thead>
          <tbody>
            {rcbs.map((r, i) => (
              <tr
                key={r.ref}
                className={`border-t border-gray-100 dark:border-surface-border/50 ${
                  i % 2 ? 'bg-gray-50/60 dark:bg-surface-raised/40' : ''
                }`}
              >
                <td className={`${cellCls} font-mono text-[11px] text-gray-700 dark:text-gray-300`}>
                  {r.ref}
                </td>
                <td className={cellCls}>
                  <span
                    className={`rounded px-1 text-[10px] font-semibold ${
                      r.type === 'BRCB'
                        ? 'bg-purple-100 text-purple-700 dark:bg-purple-900/40 dark:text-purple-300'
                        : 'bg-sky-100 text-sky-700 dark:bg-sky-900/40 dark:text-sky-300'
                    }`}
                  >
                    {r.type}
                  </span>
                </td>
                <td className={`${cellCls} font-mono text-[11px] text-gray-500`}>{r.datSet}</td>
                <td className={`${cellCls} text-gray-500`}>{r.rptId}</td>
                <td className={cellCls}>
                  {r.rptEna ? (
                    <span className="font-medium text-green-600 dark:text-green-400">habilitado</span>
                  ) : (
                    <span className="text-gray-400">inactivo</span>
                  )}
                </td>
                <td className={cellCls}>
                  {r.rptEna ? (
                    <button
                      onClick={() => void disable(r.ref)}
                      className="rounded border border-gray-300 px-2 py-0.5 text-red-600 hover:bg-red-50 dark:border-surface-border dark:text-red-400 dark:hover:bg-surface-raised"
                    >
                      Deshabilitar
                    </button>
                  ) : (
                    <button
                      onClick={() => void enable(r.ref)}
                      className="rounded bg-accent px-2 py-0.5 text-white hover:bg-accent-hover"
                    >
                      Habilitar
                    </button>
                  )}
                </td>
              </tr>
            ))}
            {rcbs.length === 0 && (
              <tr>
                <td colSpan={6} className="px-2 py-4 text-center text-gray-400">
                  El IED no expone Report Control Blocks.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {/* ── Feed de reports ── */}
      <div className="flex items-center gap-2 border-b border-gray-200 p-1.5 text-xs dark:border-surface-border">
        <span className="font-medium text-gray-600 dark:text-gray-300">
          Reports recibidos ({reports.length})
        </span>
        <div className="flex-1" />
        <button
          onClick={clearReports}
          disabled={!reports.length}
          className="rounded border border-gray-300 px-2 py-1 text-red-600 hover:bg-red-50 disabled:opacity-40 dark:border-surface-border dark:text-red-400 dark:hover:bg-surface-raised"
        >
          Limpiar
        </button>
      </div>
      <div className="min-h-0 flex-1 overflow-auto">
        {reports.length === 0 ? (
          <div className="flex h-full items-center justify-center p-4 text-center text-xs text-gray-400">
            Habilite un RCB para recibir reports espontáneos del IED.
          </div>
        ) : (
          <table className="w-full border-collapse text-xs">
            <thead className="sticky top-0 bg-gray-50 dark:bg-surface-raised">
              <tr className="text-left text-[10px] uppercase tracking-wide text-gray-500">
                <th className={headCls}>Hora</th>
                <th className={headCls}>RptID</th>
                <th className={headCls}>Seq</th>
                <th className={headCls}>Referencia</th>
                <th className={headCls}>Valor</th>
                <th className={headCls}>Razón</th>
              </tr>
            </thead>
            <tbody>
              {reports.map((rep, ri) =>
                rep.entries.map((e, ei) => (
                  <tr
                    key={`${rep.ts}-${ri}-${ei}`}
                    className={`border-t border-gray-100 dark:border-surface-border/50 ${
                      ri % 2 ? 'bg-gray-50/60 dark:bg-surface-raised/40' : ''
                    }`}
                  >
                    <td className={`${cellCls} whitespace-nowrap text-gray-400`}>
                      {ei === 0 ? new Date(rep.ts).toLocaleTimeString() : ''}
                    </td>
                    <td className={`${cellCls} text-gray-500`}>{ei === 0 ? rep.rptId : ''}</td>
                    <td className={`${cellCls} text-gray-500`}>{ei === 0 ? rep.sqNum : ''}</td>
                    <td className={`${cellCls} font-mono text-[11px] text-gray-700 dark:text-gray-300`}>
                      {e.ref}
                    </td>
                    <td className={`${cellCls} font-mono text-[11px] font-medium text-accent dark:text-accent-hover`}>
                      {e.value}
                    </td>
                    <td className={`${cellCls} text-gray-500`}>{e.reason}</td>
                  </tr>
                )),
              )}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
