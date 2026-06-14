import { type MouseEvent, useState } from 'react';
import type { ModelNodeDto } from '../api/types';
import { useDialogStore } from '../stores/dialogs';
import { useServerStore } from '../stores/server';

function NodeRow({ node, depth }: { node: ModelNodeDto; depth: number }) {
  const [open, setOpen] = useState(depth < 1);
  const openDialog = useDialogStore((s) => s.open);
  const hasChildren = !!node.children?.length;
  const isEditable = (node.kind === 'DA' || node.kind === 'CDA') && !hasChildren;

  const handleDoubleClick = (e: MouseEvent) => {
    e.stopPropagation();
    if (isEditable) openDialog('serverWrite', node);
  };

  return (
    <>
      <div
        className="flex cursor-default items-center gap-1 rounded px-1 py-0.5 text-xs hover:bg-gray-100 dark:hover:bg-surface-raised"
        style={{ paddingLeft: depth * 14 + 4 }}
        onClick={() => hasChildren && setOpen(!open)}
        onDoubleClick={handleDoubleClick}
        title={isEditable ? 'Doble clic para editar valor' : undefined}
      >
        <span className="w-3 text-gray-400">{hasChildren ? (open ? '▾' : '▸') : ''}</span>
        <span className="text-gray-700 dark:text-gray-200">{node.name}</span>
        {node.fc && (
          <span className="rounded bg-gray-100 px-1 text-[10px] text-gray-500 dark:bg-surface-raised">
            {node.fc}
          </span>
        )}
        {node.value !== undefined && (
          <span className="ml-1 font-medium text-accent dark:text-accent-hover">
            {node.value}
          </span>
        )}
      </div>
      {open &&
        node.children?.map((c) => <NodeRow key={c.ref + (c.fc ?? '')} node={c} depth={depth + 1} />)}
    </>
  );
}

/** Árbol del modelo del servidor simulado, con edición de valores (doble clic). */
export function ServerModelTree() {
  const model = useServerStore((s) => s.model);

  if (!model) {
    return (
      <div className="flex h-full items-center justify-center p-4 text-center text-xs text-gray-400">
        Cargue un fichero SCL desde el panel «Simulador IED»
        <br />
        para ver y editar el modelo de datos.
      </div>
    );
  }
  return (
    <div className="h-full overflow-auto p-1">
      <div className="px-2 pb-1 text-[11px] font-semibold text-gray-500 dark:text-gray-400">
        {model.iedName}
      </div>
      {model.logicalDevices.map((ld) => (
        <NodeRow key={ld.ref} node={ld} depth={0} />
      ))}
    </div>
  );
}

/** Pestaña «Data Model»: estructura del modelo del servidor simulado. */
export default function DataModelPanel() {
  const status = useServerStore((s) => s.status);
  const store = useServerStore;

  return (
    <div className="flex h-full flex-col bg-white dark:bg-surface">
      <div className="flex items-center gap-2 border-b border-gray-200 p-1.5 text-xs dark:border-surface-border">
        <span className="font-medium text-gray-600 dark:text-gray-300">Estructura del Modelo</span>
        <span className="text-gray-400">
          {status.modelLoaded ? (status.iedName ?? '') : 'sin modelo'}
        </span>
        <div className="flex-1" />
        <button
          onClick={() => void store.getState().refreshStatus()}
          className="rounded border border-gray-300 px-2 py-1 hover:bg-gray-100 dark:border-surface-border dark:text-gray-300 dark:hover:bg-surface-raised"
        >
          Actualizar
        </button>
      </div>
      <div className="min-h-0 flex-1">
        <ServerModelTree />
      </div>
    </div>
  );
}
