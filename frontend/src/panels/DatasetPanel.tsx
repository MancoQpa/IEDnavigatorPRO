import { useEffect, useState } from 'react';
import { getApi } from '../api/client';
import type { DataSetInfo, DataSetValue } from '../api/types';
import { useConnectionStore } from '../stores/connection';
import { log } from '../stores/log';

/** DataSets del IED: listado y lectura GetDataSetValues bajo demanda. */
export default function DatasetPanel() {
  const connected = useConnectionStore((s) => s.client.connected);
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

  return (
    <div className="flex h-full flex-col bg-white dark:bg-surface">
      <div className="flex items-center gap-2 border-b border-gray-200 p-1.5 text-xs dark:border-surface-border">
        <select
          value={selected}
          onChange={(e) => {
            setSelected(e.target.value);
            setValues([]);
          }}
          className="min-w-60 rounded border border-gray-300 bg-transparent px-1 py-1 font-mono text-[11px] dark:border-surface-border dark:bg-surface dark:text-gray-200"
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

      <div className="min-h-0 flex-1 overflow-auto">
        {values.length === 0 ? (
          <div className="flex h-full items-center justify-center p-4 text-center text-xs text-gray-400">
            {datasets.length === 0
              ? 'El IED no expone DataSets.'
              : 'Pulse «Leer valores» (GetDataSetValues).'}
          </div>
        ) : (
          <table className="w-full border-collapse text-xs">
            <thead className="sticky top-0 bg-gray-50 dark:bg-surface-raised">
              <tr className="text-left text-[10px] uppercase tracking-wide text-gray-500">
                <th className="px-2 py-1.5 font-medium">Referencia</th>
                <th className="px-2 py-1.5 font-medium">FC</th>
                <th className="px-2 py-1.5 font-medium">Valor</th>
                <th className="px-2 py-1.5 font-medium">Tipo</th>
              </tr>
            </thead>
            <tbody>
              {values.map((v, i) => (
                <tr
                  key={v.ref + v.fc}
                  className={`border-t border-gray-100 dark:border-surface-border/50 ${
                    i % 2 ? 'bg-gray-50/60 dark:bg-surface-raised/40' : ''
                  }`}
                >
                  <td className="px-2 py-1 font-mono text-[11px] text-gray-700 dark:text-gray-300">{v.ref}</td>
                  <td className="px-2 py-1 text-gray-500">{v.fc}</td>
                  <td className="px-2 py-1 font-mono text-[11px] font-medium text-accent dark:text-accent-hover">
                    {v.value}
                  </td>
                  <td className="px-2 py-1 text-gray-500">{v.type}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
