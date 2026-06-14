import { useState } from 'react';
import type { ModelNodeDto } from '../api/types';
import { useConnectionStore } from '../stores/connection';
import { useDialogStore } from '../stores/dialogs';
import { useModelStore } from '../stores/model';
import { useMonitorStore } from '../stores/monitor';

const READONLY_FC = ['ST', 'MX'];

const FC_OPTIONS = ['', 'ST', 'MX', 'CO', 'CF', 'DC', 'SP', 'SG', 'BL'];

/**
 * Monitor de valores: refs vigiladas via watchlist del bridge.
 * Los valores llegan push por WS (client.valueChanged) al modelStore.
 */
export default function MonitorPanel() {
  const items = useMonitorStore((s) => s.items);
  const intervalMs = useMonitorStore((s) => s.intervalMs);
  const setInterval_ = useMonitorStore((s) => s.setInterval);
  const remove = useMonitorStore((s) => s.remove);
  const clear = useMonitorStore((s) => s.clear);
  const add = useMonitorStore((s) => s.add);
  const values = useModelStore((s) => s.values);
  const connected = useConnectionStore((s) => s.client.connected);

  const openDialog = useDialogStore((s) => s.open);
  const [filter, setFilter] = useState('');
  const [fcFilter, setFcFilter] = useState('');
  const [dragOver, setDragOver] = useState(false);

  const handleRowDoubleClick = (r: { ref: string; fc: string; value: string; type: string }) => {
    if (!connected || READONLY_FC.includes(r.fc)) return;
    const node: ModelNodeDto = {
      name: r.ref.split('.').pop() ?? r.ref,
      ref: r.ref,
      kind: 'DA',
      fc: r.fc,
      value: r.value,
      type: r.type,
    };
    openDialog('write', node);
  };

  if (!connected) {
    return (
      <div className="flex h-full items-center justify-center bg-white p-4 text-xs text-gray-400 dark:bg-surface">
        Sin conexión.
      </div>
    );
  }

  // Filas: por cada item vigilado, mostrar sus BDAs con valor recibido
  // (las claves de `values` son refs de BDA, prefijadas por la ref del item)
  const rows: { ref: string; itemRef: string; fc: string; value: string; type: string; ts: number }[] = [];
  for (const item of items) {
    let matched = false;
    for (const [ref, v] of values) {
      if (ref === item.ref || ref.startsWith(item.ref + '.')) {
        rows.push({ ref, itemRef: item.ref, fc: item.fc, value: v.value, type: v.type, ts: v.ts });
        matched = true;
      }
    }
    if (!matched) {
      rows.push({ ref: item.ref, itemRef: item.ref, fc: item.fc, value: '…', type: '', ts: 0 });
    }
  }

  const f = filter.toLowerCase();
  const visible = rows.filter(
    (r) =>
      (!f || r.ref.toLowerCase().includes(f)) &&
      (!fcFilter || r.fc === fcFilter),
  );

  const exportCsv = () => {
    const lines = ['Referencia;FC;Valor;Tipo;Timestamp'];
    for (const r of visible) {
      lines.push(
        `${r.ref};${r.fc};"${r.value.replace(/"/g, '""')}";${r.type};${r.ts ? new Date(r.ts).toISOString() : ''}`,
      );
    }
    const blob = new Blob(['\uFEFF' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `monitor_${new Date().toISOString().replace(/[:.]/g, '-')}.csv`;
    a.click();
    URL.revokeObjectURL(a.href);
  };

  return (
    <div
      className={`flex h-full flex-col bg-white dark:bg-surface ${dragOver ? 'ring-2 ring-inset ring-accent' : ''}`}
      onDragOver={(e) => {
        if (e.dataTransfer.types.includes('application/x-iednav-node')) {
          e.preventDefault();
          setDragOver(true);
        }
      }}
      onDragLeave={() => setDragOver(false)}
      onDrop={(e) => {
        setDragOver(false);
        const data = e.dataTransfer.getData('application/x-iednav-node');
        if (!data) return;
        try {
          const { ref, fc } = JSON.parse(data) as { ref: string; fc: string };
          if (ref && fc) add(ref, fc);
        } catch {
          /* payload inválido */
        }
      }}
    >
      <div className="flex items-center gap-2 border-b border-gray-200 p-1.5 text-xs dark:border-surface-border">
        <input
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          placeholder="Filtrar referencia…"
          className="w-44 rounded border border-gray-300 bg-transparent px-2 py-1 outline-none focus:border-accent dark:border-surface-border dark:text-gray-200"
        />
        <select
          value={fcFilter}
          onChange={(e) => setFcFilter(e.target.value)}
          className="rounded border border-gray-300 bg-transparent px-1 py-1 dark:border-surface-border dark:bg-surface dark:text-gray-200"
        >
          {FC_OPTIONS.map((fc) => (
            <option key={fc} value={fc}>{fc || 'FC: todas'}</option>
          ))}
        </select>
        <label className="flex items-center gap-1 text-gray-500">
          Intervalo
          <input
            type="number"
            min={100}
            step={100}
            value={intervalMs}
            onChange={(e) => setInterval_(Number(e.target.value))}
            className="w-16 rounded border border-gray-300 bg-transparent px-1 py-1 dark:border-surface-border dark:text-gray-200"
          />
          ms
        </label>
        <div className="flex-1" />
        <button
          onClick={exportCsv}
          disabled={!visible.length}
          className="rounded border border-gray-300 px-2 py-1 hover:bg-gray-100 disabled:opacity-40 dark:border-surface-border dark:text-gray-300 dark:hover:bg-surface-raised"
        >
          Exportar CSV
        </button>
        <button
          onClick={clear}
          disabled={!items.length}
          className="rounded border border-gray-300 px-2 py-1 text-red-600 hover:bg-red-50 disabled:opacity-40 dark:border-surface-border dark:text-red-400 dark:hover:bg-surface-raised"
        >
          Vaciar
        </button>
      </div>

      {items.length === 0 ? (
        <div className="flex flex-1 items-center justify-center p-4 text-center text-xs text-gray-400">
          Arrastre nodos del árbol aquí
          <br />o use el menú contextual «Añadir al monitor».
        </div>
      ) : (
        <div className="flex-1 overflow-auto">
          <table className="w-full border-collapse text-xs">
            <thead className="sticky top-0 bg-gray-50 dark:bg-surface-raised">
              <tr className="text-left text-[10px] uppercase tracking-wide text-gray-500">
                <th className="px-2 py-1.5 font-medium">Referencia</th>
                <th className="px-2 py-1.5 font-medium">FC</th>
                <th className="px-2 py-1.5 font-medium">Valor</th>
                <th className="px-2 py-1.5 font-medium">Tipo</th>
                <th className="px-2 py-1.5 font-medium">Actualizado</th>
                <th className="w-7" />
              </tr>
            </thead>
            <tbody>
              {visible.map((r, i) => (
                <tr
                  key={r.ref + r.fc}
                  onDoubleClick={() => handleRowDoubleClick(r)}
                  title={!READONLY_FC.includes(r.fc) ? 'Doble clic para escribir valor' : undefined}
                  className={`border-t border-gray-100 dark:border-surface-border/50 ${
                    i % 2 ? 'bg-gray-50/60 dark:bg-surface-raised/40' : ''
                  } ${!READONLY_FC.includes(r.fc) ? 'cursor-pointer' : ''}`}
                >
                  <td className="px-2 py-1 text-gray-700 dark:text-gray-300">{r.ref}</td>
                  <td className="px-2 py-1 text-gray-500">{r.fc}</td>
                  <td className="px-2 py-1 font-medium text-accent dark:text-accent-hover">
                    {r.value}
                  </td>
                  <td className="px-2 py-1 text-gray-500">{r.type}</td>
                  <td className="px-2 py-1 text-gray-400">
                    {r.ts ? new Date(r.ts).toLocaleTimeString() : '—'}
                  </td>
                  <td className="px-1 py-1">
                    {visible.findIndex((x) => x.itemRef === r.itemRef) === i ? (
                      <button
                        onClick={() => remove(r.itemRef, r.fc)}
                        title="Quitar del monitor"
                        className="rounded px-1 text-gray-400 hover:bg-red-50 hover:text-red-600 dark:hover:bg-surface-raised"
                      >
                        ✕
                      </button>
                    ) : null}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
