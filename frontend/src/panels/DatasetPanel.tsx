import { useEffect, useState } from 'react';
import { getApi } from '../api/client';
import type { DataSetInfo, DataSetValue } from '../api/types';
import { useConnectionStore } from '../stores/connection';
import { log } from '../stores/log';
import { useUiStore } from '../stores/ui';

const thCls = 'px-2 py-1 font-medium text-left text-[10px] uppercase tracking-wide text-gray-500 dark:text-gray-400 bg-gray-50 dark:bg-surface-raised';
const tdCls = 'px-2 py-1 text-xs truncate max-w-0';

/** Extrae el sufijo del atributo hoja (último componente del ref) */
function attrSuffix(memberRef: string, bdaRef: string): string {
  return bdaRef.startsWith(memberRef + '.') ? bdaRef.slice(memberRef.length + 1) : bdaRef;
}

/**
 * Valor para mostrar en la columna Valor.
 * - DA directo: el valor exacto.
 * - DO con hijos: "attr=val, attr=val" (igual que Swing extractNodeValue).
 */
function memberValue(memberRef: string, values: DataSetValue[]): string {
  const direct = values.find((v) => v.ref === memberRef);
  if (direct) return direct.value ?? '';

  const children = values.filter((v) => v.ref.startsWith(memberRef + '.'));
  if (!children.length) return '';
  return children
    .map((v) => `${attrSuffix(memberRef, v.ref)}=${v.value ?? ''}`)
    .join(', ');
}

/** DataSets del IED — réplica del layout doble tabla del Swing DatasetPanel.java */
export default function DatasetPanel() {
  const connected = useConnectionStore((s) => s.client.connected);
  const setTreeSearch = useUiStore((s) => s.setTreeSearch);

  const [datasets, setDatasets] = useState<DataSetInfo[]>([]);
  const [selected, setSelected] = useState<string>('');
  const [values, setValues] = useState<DataSetValue[]>([]);
  const [reading, setReading] = useState(false);

  // Cargar lista de datasets al conectar
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

  const readValues = async (ref: string) => {
    if (!ref) return;
    setReading(true);
    try {
      const r = await getApi().readDataSet(ref);
      setValues(r.values);
      log.info(`DataSet ${ref} leído: ${r.values.length} atributos`);
    } catch (e) {
      log.error(`Error leyendo DataSet ${ref}: ${(e as Error).message}`);
    } finally {
      setReading(false);
    }
  };

  // Auto-leer al seleccionar dataset (paridad con Swing showDatasetMembers)
  useEffect(() => {
    if (selected && connected) void readValues(selected);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selected, connected]);

  const handleRefresh = () => {
    setDatasets([]);
    setValues([]);
    if (!connected) return;
    getApi()
      .datasets()
      .then((r) => {
        setDatasets(r.datasets);
        if (r.datasets.length) {
          const keep = r.datasets.find((d) => d.ref === selected);
          const next = keep ? selected : r.datasets[0].ref;
          setSelected(next);
        }
      })
      .catch((e) => log.error(`Error actualizando DataSets: ${(e as Error).message}`));
  };

  if (!connected) {
    return (
      <div className="flex h-full items-center justify-center bg-white p-4 text-xs text-gray-400 dark:bg-surface">
        Sin conexión.
      </div>
    );
  }

  const currentDs = datasets.find((ds) => ds.ref === selected);

  return (
    <div className="flex h-full flex-col bg-white dark:bg-surface">

      {/* ── Toolbar ── */}
      <div className="flex items-center gap-1 border-b border-gray-200 p-1 text-xs dark:border-surface-border">
        <button
          onClick={handleRefresh}
          className="rounded border border-gray-300 px-2 py-0.5 hover:bg-gray-100 disabled:opacity-40 dark:border-surface-border dark:text-gray-300 dark:hover:bg-surface-raised"
        >
          Actualizar
        </button>
        <div className="h-4 w-px bg-gray-200 dark:bg-surface-border" />
        <button
          onClick={() => selected && void readValues(selected)}
          disabled={!selected || reading}
          className="rounded border border-gray-300 px-2 py-0.5 hover:bg-gray-100 disabled:opacity-40 dark:border-surface-border dark:text-gray-300 dark:hover:bg-surface-raised"
        >
          {reading ? 'Leyendo…' : 'Leer valores'}
        </button>
        <div className="flex-1" />
        {datasets.length === 0 && (
          <span className="text-gray-400">El IED no expone DataSets.</span>
        )}
      </div>

      {/* ── Split: tabla datasets (arriba) + tabla miembros (abajo) ── */}
      <div className="flex min-h-0 flex-1 flex-col">

        {/* Tabla de datasets */}
        <div className="min-h-0 flex-[2] overflow-auto border-b border-gray-200 dark:border-surface-border">
          <div className="px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-gray-500 dark:text-gray-400">
            Datasets
          </div>
          {datasets.length === 0 ? (
            <div className="px-2 py-2 text-center text-[11px] text-gray-400">
              Sin DataSets.
            </div>
          ) : (
            <table className="w-full border-collapse">
              <thead>
                <tr>
                  <th className={thCls}>Nombre</th>
                  <th className={`${thCls} w-16 text-center`}>Miembros</th>
                  <th className={`${thCls} w-14 text-center`}>Deletable</th>
                </tr>
              </thead>
              <tbody>
                {datasets.map((ds) => {
                  const active = ds.ref === selected;
                  return (
                    <tr
                      key={ds.ref}
                      onClick={() => setSelected(ds.ref)}
                      className={`cursor-pointer border-t border-gray-100 text-xs dark:border-surface-border/50 ${
                        active
                          ? 'bg-accent text-white dark:bg-accent'
                          : 'hover:bg-blue-50/50 dark:hover:bg-accent/10'
                      }`}
                    >
                      <td className={`${tdCls} font-mono text-[11px]`} title={ds.ref}>
                        {ds.ref}
                      </td>
                      <td className={`${tdCls} w-16 text-center`}>{ds.members.length}</td>
                      <td className={`${tdCls} w-14 text-center`}>
                        {ds.deletable ? 'Sí' : 'No'}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
        </div>

        {/* Tabla de miembros del dataset seleccionado */}
        <div className="min-h-0 flex-[3] overflow-auto">
          <div className="px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-gray-500 dark:text-gray-400">
            Miembros del Dataset
            <span className="ml-1 font-normal normal-case text-gray-400">
              (clic en una fila → navegar en árbol)
            </span>
          </div>
          {!currentDs || currentDs.members.length === 0 ? (
            <div className="px-2 py-2 text-center text-[11px] text-gray-400">
              {currentDs ? 'Este DataSet no tiene miembros FCDA.' : 'Seleccione un DataSet.'}
            </div>
          ) : (
            <table className="w-full border-collapse">
              <thead>
                <tr>
                  <th className={`${thCls} w-8 text-center`}>#</th>
                  <th className={thCls}>Referencia</th>
                  <th className={`${thCls} w-10`}>FC</th>
                  <th className={`${thCls} w-28`}>Valor</th>
                </tr>
              </thead>
              <tbody>
                {currentDs.members.map((m, i) => {
                  const val = memberValue(m.ref, values);
                  return (
                    <tr
                      key={m.ref + m.fc}
                      onClick={() => setTreeSearch(m.ref)}
                      className="cursor-pointer border-t border-gray-100 hover:bg-blue-50/50 dark:border-surface-border/50 dark:hover:bg-accent/10"
                      title="Clic para navegar en el árbol del modelo"
                    >
                      <td className="w-8 px-1 py-0.5 text-center text-[11px] text-gray-400">
                        {i + 1}
                      </td>
                      <td className={`${tdCls} font-mono text-[11px]`} title={m.ref}>
                        {m.ref}
                      </td>
                      <td className="w-10 px-2 py-0.5">
                        <span
                          className="rounded px-1 text-[9px] font-bold text-white"
                          style={{
                            backgroundColor: FC_COLORS[m.fc] ?? 'rgb(96,125,139)',
                          }}
                        >
                          {m.fc}
                        </span>
                      </td>
                      <td
                        className={`w-28 px-2 py-0.5 text-[11px] font-medium ${
                          val ? 'text-accent dark:text-accent-hover' : 'text-gray-300 dark:text-gray-600'
                        }`}
                        title={val || '—'}
                      >
                        {val || '—'}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </div>
  );
}

const FC_COLORS: Record<string, string> = {
  ST: 'rgb(21,101,192)', MX: 'rgb(0,105,92)', CO: 'rgb(183,28,28)',
  CF: 'rgb(230,81,0)', SP: 'rgb(74,20,140)', SG: 'rgb(27,94,32)',
  SE: 'rgb(0,96,100)', DC: 'rgb(33,33,33)', BL: 'rgb(120,80,180)',
};
