import { useState } from 'react';
import { getApi } from '../api/client';
import type { ModelNodeDto } from '../api/types';
import Modal from '../components/Modal';
import { log } from '../stores/log';
import { useModelStore } from '../stores/model';

/** Escritura de un DA (setDataValues) via el bridge. */
export default function ValueEditDialog({
  node,
  onClose,
}: {
  node: ModelNodeDto;
  onClose: () => void;
}) {
  const live = useModelStore((s) => s.values.get(node.ref));
  const [value, setValue] = useState(live?.value ?? node.value ?? '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const write = async () => {
    if (!node.fc) return;
    setBusy(true);
    setError('');
    try {
      await getApi().write(node.ref, node.fc, value);
      log.info(`[WRITE OK] ${node.ref} [${node.fc}] = ${value}`);
      onClose();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal title="Escribir valor" onClose={onClose} width={380}>
      <div className="space-y-3 text-xs">
        <div className="break-all font-mono text-[11px] text-gray-600 dark:text-gray-300">
          {node.ref} [{node.fc}]
        </div>
        {node.type && <div className="text-gray-500">Tipo: {node.type}</div>}
        <input
          autoFocus
          value={value}
          onChange={(e) => setValue(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && write()}
          className="w-full rounded border border-gray-300 bg-transparent px-2 py-1 font-mono text-xs outline-none focus:border-accent dark:border-surface-border dark:text-gray-200"
        />
        {error && <div className="text-red-500">{error}</div>}
        <div className="flex justify-end gap-2 pt-2">
          <button onClick={onClose} className="rounded border border-gray-300 px-3 py-1.5 dark:border-surface-border dark:text-gray-300">
            Cancelar
          </button>
          <button
            onClick={write}
            disabled={busy}
            className="rounded bg-accent px-4 py-1.5 font-medium text-white hover:bg-accent-hover disabled:opacity-50"
          >
            {busy ? 'Escribiendo…' : 'Escribir'}
          </button>
        </div>
      </div>
    </Modal>
  );
}
