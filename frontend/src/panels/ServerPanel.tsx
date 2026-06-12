import { useEffect, useState } from 'react';
import type { ModelNodeDto } from '../api/types';
import { useConnectionStore } from '../stores/connection';
import { useServerStore } from '../stores/server';

function NodeRow({ node, depth }: { node: ModelNodeDto; depth: number }) {
  const [open, setOpen] = useState(depth < 1);
  const setValue = useServerStore((s) => s.setValue);
  const hasChildren = !!node.children?.length;

  const edit = () => {
    if (node.kind !== 'DA' && node.kind !== 'CDA') return;
    if (hasChildren) return;
    const v = window.prompt(`Nuevo valor para ${node.ref}`, node.value ?? '');
    if (v !== null) void setValue(node.ref, v);
  };

  return (
    <>
      <div
        className="flex cursor-default items-center gap-1 rounded px-1 py-0.5 text-xs hover:bg-gray-100 dark:hover:bg-surface-raised"
        style={{ paddingLeft: depth * 14 + 4 }}
        onClick={() => hasChildren && setOpen(!open)}
        onDoubleClick={edit}
        title={node.kind === 'DA' && !hasChildren ? 'Doble clic para editar' : undefined}
      >
        <span className="w-3 text-gray-400">{hasChildren ? (open ? '▾' : '▸') : ''}</span>
        <span className="text-gray-700 dark:text-gray-200">{node.name}</span>
        {node.fc && (
          <span className="rounded bg-gray-100 px-1 text-[10px] text-gray-500 dark:bg-surface-raised">
            {node.fc}
          </span>
        )}
        {node.value !== undefined && (
          <span className="ml-1 font-mono text-[11px] font-medium text-accent dark:text-accent-hover">
            {node.value}
          </span>
        )}
      </div>
      {open &&
        node.children?.map((c) => <NodeRow key={c.ref + (c.fc ?? '')} node={c} depth={depth + 1} />)}
    </>
  );
}

/** Servidor IED simulado: carga SCL, arranque/parada y edición de valores. */
export default function ServerPanel() {
  const bridgeReady = useConnectionStore((s) => s.bridgeReady);
  const status = useServerStore((s) => s.status);
  const sclPath = useServerStore((s) => s.sclPath);
  const ieds = useServerStore((s) => s.ieds);
  const iedIndex = useServerStore((s) => s.iedIndex);
  const model = useServerStore((s) => s.model);
  const busy = useServerStore((s) => s.busy);
  const store = useServerStore;

  const [port, setPort] = useState(102);

  useEffect(() => {
    if (bridgeReady) void store.getState().refreshStatus();
  }, [bridgeReady, store]);

  return (
    <div className="flex h-full flex-col bg-white dark:bg-surface">
      {/* ── Carga SCL ── */}
      <div className="flex flex-wrap items-center gap-2 border-b border-gray-200 p-1.5 text-xs dark:border-surface-border">
        <input
          value={sclPath}
          onChange={(e) => store.getState().setSclPath(e.target.value)}
          placeholder="Ruta del fichero SCL (ICD/CID/SCD)…"
          disabled={status.running}
          className="min-w-72 flex-1 rounded border border-gray-300 bg-transparent px-2 py-1 font-mono text-[11px] outline-none focus:border-accent disabled:opacity-50 dark:border-surface-border dark:text-gray-200"
        />
        <button
          onClick={() => void store.getState().parse()}
          disabled={!sclPath || busy || status.running}
          className="rounded border border-gray-300 px-2 py-1 hover:bg-gray-100 disabled:opacity-40 dark:border-surface-border dark:text-gray-300 dark:hover:bg-surface-raised"
        >
          Analizar
        </button>
        {ieds.length > 0 && (
          <>
            <select
              value={iedIndex}
              onChange={(e) => store.getState().setIedIndex(Number(e.target.value))}
              disabled={status.running}
              className="rounded border border-gray-300 bg-transparent px-1 py-1 dark:border-surface-border dark:bg-surface dark:text-gray-200"
            >
              {ieds.map((name, i) => (
                <option key={name + i} value={i}>
                  {name}
                </option>
              ))}
            </select>
            <button
              onClick={() => void store.getState().load()}
              disabled={busy || status.running}
              className="rounded bg-accent px-2 py-1 font-medium text-white hover:bg-accent-hover disabled:opacity-40"
            >
              Cargar
            </button>
          </>
        )}
      </div>

      {/* ── Arranque/parada ── */}
      <div className="flex items-center gap-2 border-b border-gray-200 p-1.5 text-xs dark:border-surface-border">
        <span
          className={`h-2 w-2 rounded-full ${status.running ? 'bg-green-500' : 'bg-gray-300 dark:bg-gray-600'}`}
        />
        <span className="text-gray-600 dark:text-gray-300">
          {status.running
            ? `En marcha en puerto ${status.port} — ${status.iedName ?? ''}`
            : status.modelLoaded
              ? `Modelo cargado: ${status.iedName ?? '?'} (detenido)`
              : 'Sin modelo cargado'}
        </span>
        <div className="flex-1" />
        <label className="flex items-center gap-1 text-gray-500">
          Puerto
          <input
            type="number"
            min={1}
            max={65535}
            value={port}
            onChange={(e) => setPort(Number(e.target.value))}
            disabled={status.running}
            className="w-20 rounded border border-gray-300 bg-transparent px-1 py-1 disabled:opacity-50 dark:border-surface-border dark:text-gray-200"
          />
        </label>
        {status.running ? (
          <button
            onClick={() => void store.getState().stop()}
            disabled={busy}
            className="rounded bg-red-600 px-3 py-1 font-medium text-white hover:bg-red-700 disabled:opacity-40"
          >
            Detener
          </button>
        ) : (
          <button
            onClick={() => void store.getState().start(port)}
            disabled={busy || !status.modelLoaded}
            className="rounded bg-green-600 px-3 py-1 font-medium text-white hover:bg-green-700 disabled:opacity-40"
          >
            Arrancar
          </button>
        )}
      </div>

      {/* ── Modelo ── */}
      <div className="min-h-0 flex-1 overflow-auto p-1">
        {!model ? (
          <div className="flex h-full items-center justify-center p-4 text-center text-xs text-gray-400">
            Cargue un fichero SCL para simular un IED.
            <br />
            Doble clic en un atributo para editar su valor.
          </div>
        ) : (
          model.logicalDevices.map((ld) => <NodeRow key={ld.ref} node={ld} depth={0} />)
        )}
      </div>
    </div>
  );
}
