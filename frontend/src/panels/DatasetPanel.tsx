import { useEffect, useState } from 'react';
import { getApi } from '../api/client';
import type { DataSetInfo, DataSetValue } from '../api/types';
import { useConnectionStore } from '../stores/connection';
import { log } from '../stores/log';
import { useUiStore } from '../stores/ui';

/** DataSets del IED: listado y lectura GetDataSetValues bajo demanda. */
export default function DatasetPanel() {
  const connected = useConnectionStore((s) => s.client.connected);
  const setTreeSearch = useUiStore((s) => s.setTreeSearch);

  const [datasets, setDatasets] = useState<DataSetInfo[]>([]);
  const [selected, setSelected] = useState('');
  const [values, setValues] = useState<DataSetValue[]>([]);
  const [reading, setReading] = useState(false);
  const [readTs, setReadTs] = useState(0);

  useEffect(() => {
    if (!connected) {
      setDatasets([]);
      setSelected('');
      setValues([]);
      return;
    }
    getApi()
      .datasets()
      .then((r) => {
        setDatasets(r.datasets);
        if (r.datasets.length) setSelected(r.datasets[0].ref);
      })
      .catch((e) => log.error(`Error listando DataSets: ${(e as Error).message}`));
  }, [connected]);

  if (!connected) {
    return (
      <div className="flex h-full items-center justify-center bg-white p-4 text-xs text-gray-400 dark:bg-surface">
        Sin conexión.
      </div>
    );
  }

  const read = async () => {
    if (!selected) return;
    setReading(true);
    try {
      const r = await getApi().readDataSet(selected);
      setValues(r.values);
      setReadTs(Date.now());
    } catch (e) {
      log.error(`Error leyendo DataSet ${selected}: ${(e as Error).message}`);
    } finally {
      setReading(false);
    }
  };

  const currentDs = datasets.find((ds) => ds.ref === selected);

  /** Navega al nodo en el árbol de Modelo de Datos (filtra por ref del miembro). */
  const navigateTo = (ref: string) => {
    // Usar la referencia del DO/DA para filtrar el árbol (sin el IED name prefix si lo tiene)
    setTreeSearch(ref);
  };

  // Agrupar valores por miembro del dataset
  const grouped = (currentDs?.members ?? []).map((m) => ({
    member: m,
    values: values.filter((v) => v.ref === m.ref || v.ref.startsWith(m.ref + '.')),
  }));

  const thCls = 'px-2 py-1.5 font-medium text-left text-[10px] uppercase tracking-wide text-gray-500';
  const tdBase = 'px-2 py-1';

  return (
    <div className="flex h-full flex-col bg-white dark:bg-surface">
      {/* ── Toolbar ── */}
      <div className="flex items-center gap-2 border-b border-gray-200 p-1.5 text-xs dark:border-surface-border">
        <select
          value={selected}
          onChange={(e) => {
            setSelected(e.target.value);
            setValues([]);
            setReadTs(0);
          }}
          className="min-w-60 max-w-xs rounded border border-gray-300 bg-transparent px-1 py-1 text-xs dark:border-surface-border dark:bg-surface dark:text-gray-200"
        >
          {datasets.length === 0 && <option value="">— sin DataSets —</option>}
          {datasets.map((ds) => (
            <option key={ds.ref} value={ds.ref}>
              {ds.ref} ({ds.members.length} miembros)
            </option>
          ))}
        </select>
        <button
          onClick={() => void read()}
          disabled={!selected || reading}
          className="rounded bg-accent px-3 py-1 font-medium text-white hover:bg-accent-hover disabled:opacity-40"
        >
          {reading ? 'Leyendo…' : 'Leer valores'}
        </button>
        <div className="flex-1" />
        {readTs > 0 && (
          <span className="text-gray-400">Leído a las {new Date(readTs).toLocaleTimeString()}</span>
        )}
      </div>

      {/* ── Contenido ── */}
      <div className="min-h-0 flex-1 overflow-auto">
        {!currentDs ? (
          <div className="flex h-full items-center justify-center p-4 text-center text-xs text-gray-400">
            El IED no expone DataSets.
          </div>
        ) : (
          <table className="w-full border-collapse text-xs">
            <thead className="sticky top-0 bg-gray-50 dark:bg-surface-raised">
              <tr>
                <th className={thCls}>Miembro / Referencia</th>
                <th className={thCls}>FC</th>
                <th className={thCls}>Valor</th>
                <th className={thCls}>Tipo</th>
              </tr>
            </thead>
            <tbody>
              {grouped.map(({ member, values: mVals }) => (
                <>
                  {/* ── Cabecera del miembro ── */}
                  <tr
                    key={member.ref + member.fc}
                    className="border-t-2 border-gray-200 bg-gray-50 dark:border-surface-border dark:bg-surface-raised"
                  >
                    <td
                      colSpan={2}
                      className={`${tdBase} cursor-pointer font-semibold text-gray-700 hover:text-accent dark:text-gray-200 dark:hover:text-accent-hover`}
                      title="Doble clic para localizar en Modelo de Datos"
                      onDoubleClick={() => navigateTo(member.ref)}
                    >
                      {member.ref}
                    </td>
                    <td className={`${tdBase} text-gray-400`} colSpan={2}>
                      <span
                        className="rounded px-1 py-0.5 text-[10px] font-bold text-white"
                        style={{ backgroundColor: member.fc === 'ST' ? 'rgb(21,101,192)' : member.fc === 'MX' ? 'rgb(0,105,92)' : 'rgb(96,125,139)' }}
                      >
                        {member.fc}
                      </span>
                      {mVals.length === 0 && values.length > 0 && (
                        <span className="ml-2 text-[10px] text-gray-400">sin valores</span>
                      )}
                      {values.length === 0 && (
                        <span className="ml-2 text-[10px] text-gray-400 italic">pulse «Leer valores»</span>
                      )}
                    </td>
                  </tr>

                  {/* ── BDAs del miembro ── */}
                  {mVals.map((v, i) => (
                    <tr
                      key={v.ref + v.fc}
                      className={`border-t border-gray-100 dark:border-surface-border/50 ${
                        i % 2 ? 'bg-white dark:bg-surface' : 'bg-gray-50/40 dark:bg-surface-raised/30'
                      } cursor-pointer hover:bg-blue-50/60 dark:hover:bg-accent/10`}
                      title="Doble clic para localizar en Modelo de Datos"
                      onDoubleClick={() => navigateTo(v.ref)}
                    >
                      <td className={`${tdBase} pl-6 text-gray-600 dark:text-gray-300`}>
                        {/* Solo la parte tras el miembro */}
                        {v.ref.startsWith(member.ref + '.') ? v.ref.slice(member.ref.length + 1) : v.ref}
                      </td>
                      <td className={`${tdBase} text-gray-500`}>{v.fc}</td>
                      <td className={`${tdBase} font-medium text-accent dark:text-accent-hover`}>{v.value}</td>
                      <td className={`${tdBase} text-gray-500`}>{v.type}</td>
                    </tr>
                  ))}
                </>
              ))}
              {grouped.length === 0 && (
                <tr>
                  <td colSpan={4} className="px-2 py-6 text-center text-gray-400">
                    Pulse «Leer valores» (GetDataSetValues).
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
