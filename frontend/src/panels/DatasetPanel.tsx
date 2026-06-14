import { useEffect, useState } from 'react';
import { getApi } from '../api/client';
import type { DataSetInfo, DataSetMember, DataSetValue } from '../api/types';
import { useConnectionStore } from '../stores/connection';
import { log } from '../stores/log';
import { useUiStore } from '../stores/ui';

const FC_COLOR: Record<string, string> = {
  ST: 'rgb(21,101,192)', MX: 'rgb(0,105,92)', CO: 'rgb(183,28,28)',
  CF: 'rgb(230,81,0)', SP: 'rgb(74,20,140)', SG: 'rgb(27,94,32)',
  SE: 'rgb(0,96,100)', DC: 'rgb(33,33,33)', BL: 'rgb(120,80,180)',
};

const thCls = 'px-2 py-1 font-medium text-left text-[10px] uppercase tracking-wide text-gray-500 dark:text-gray-400';
const tdBase = 'px-2 py-1';

function FcBadge({ fc }: { fc: string }) {
  return (
    <span
      className="rounded px-1 py-0.5 text-[10px] font-bold text-white"
      style={{ backgroundColor: FC_COLOR[fc] ?? 'rgb(96,125,139)' }}
    >
      {fc}
    </span>
  );
}

/** Fila de miembro con sus BDAs desplegables. */
function MemberRow({
  member,
  allValues,
  hasValues,
  onNavigate,
}: {
  member: DataSetMember;
  allValues: DataSetValue[];
  hasValues: boolean;
  onNavigate: (ref: string) => void;
}) {
  const [open, setOpen] = useState(false);

  // BDAs que pertenecen a este miembro
  const bdas = allValues.filter(
    (v) => v.ref === member.ref || v.ref.startsWith(member.ref + '.'),
  );

  // Valor directo (si el miembro ES un DA)
  const directValue = bdas.find((v) => v.ref === member.ref);
  const hasChildren = bdas.some((v) => v.ref !== member.ref);
  const isLeaf = !hasChildren;

  const toggle = () => {
    if (!isLeaf) setOpen((o) => !o);
  };

  return (
    <>
      {/* ── Fila del miembro ── */}
      <tr
        className="border-t border-gray-200 bg-gray-50 hover:bg-blue-50/40 dark:border-surface-border dark:bg-surface-raised dark:hover:bg-accent/10"
        title={hasChildren ? 'Clic para desplegar componentes · doble clic para localizar en árbol' : 'Doble clic para localizar en Modelo de Datos'}
        onClick={toggle}
        onDoubleClick={() => onNavigate(member.ref)}
      >
        <td className={`${tdBase} w-5 text-center text-gray-400 select-none`}>
          {hasChildren ? (open ? '▾' : '▸') : ''}
        </td>
        <td className={`${tdBase} font-semibold text-gray-700 dark:text-gray-200`}>
          {member.ref}
        </td>
        <td className={tdBase}>
          <FcBadge fc={member.fc} />
        </td>
        <td className={`${tdBase} font-medium text-accent dark:text-accent-hover`}>
          {directValue?.value ?? (hasValues && !isLeaf ? <span className="text-[10px] text-gray-400 italic">{bdas.length} attr.</span> : '')}
        </td>
        <td className={`${tdBase} text-gray-500`}>
          {directValue?.type ?? ''}
        </td>
      </tr>

      {/* ── BDAs hijos (desplegados) ── */}
      {open &&
        bdas
          .filter((v) => v.ref !== member.ref)
          .map((v, i) => {
            const suffix = v.ref.startsWith(member.ref + '.') ? v.ref.slice(member.ref.length + 1) : v.ref;
            return (
              <tr
                key={v.ref}
                className={`border-t border-gray-100 dark:border-surface-border/40 ${
                  i % 2 ? 'bg-white dark:bg-surface' : 'bg-gray-50/40 dark:bg-surface-raised/20'
                } cursor-pointer hover:bg-blue-50/60 dark:hover:bg-accent/10`}
                title="Doble clic para localizar en Modelo de Datos"
                onDoubleClick={(e) => { e.stopPropagation(); onNavigate(v.ref); }}
              >
                <td className={tdBase} />
                <td className={`${tdBase} pl-5 text-gray-600 dark:text-gray-300`}>{suffix}</td>
                <td className={tdBase}>
                  <FcBadge fc={v.fc} />
                </td>
                <td className={`${tdBase} font-medium text-accent dark:text-accent-hover`}>{v.value}</td>
                <td className={`${tdBase} text-gray-500`}>{v.type}</td>
              </tr>
            );
          })}
    </>
  );
}

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
  const navigateTo = (ref: string) => setTreeSearch(ref);

  return (
    <div className="flex h-full flex-col bg-white dark:bg-surface">
      {/* ── Toolbar ── */}
      <div className="flex items-center gap-2 border-b border-gray-200 p-1.5 text-xs dark:border-surface-border">
        <select
          value={selected}
          onChange={(e) => { setSelected(e.target.value); setValues([]); setReadTs(0); }}
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

      {/* ── Tabla ── */}
      <div className="min-h-0 flex-1 overflow-auto">
        {!currentDs ? (
          <div className="flex h-full items-center justify-center p-4 text-center text-xs text-gray-400">
            El IED no expone DataSets.
          </div>
        ) : currentDs.members.length === 0 ? (
          <div className="flex h-full items-center justify-center p-4 text-center text-xs text-gray-400">
            Este DataSet no tiene miembros FCDA.
          </div>
        ) : (
          <table className="w-full border-collapse text-xs">
            <thead className="sticky top-0 bg-gray-50 dark:bg-surface-raised">
              <tr>
                <th className={`${thCls} w-5`} />
                <th className={thCls}>Referencia</th>
                <th className={thCls}>FC</th>
                <th className={thCls}>Valor</th>
                <th className={thCls}>Tipo</th>
              </tr>
            </thead>
            <tbody>
              {currentDs.members.map((m) => (
                <MemberRow
                  key={m.ref + m.fc}
                  member={m}
                  allValues={values}
                  hasValues={values.length > 0}
                  onNavigate={navigateTo}
                />
              ))}
            </tbody>
          </table>
        )}
        {readTs === 0 && currentDs && currentDs.members.length > 0 && (
          <div className="px-2 py-1 text-center text-[10px] text-gray-400 italic">
            Pulse «Leer valores» para ver los valores actuales del DataSet.
          </div>
        )}
      </div>
    </div>
  );
}
