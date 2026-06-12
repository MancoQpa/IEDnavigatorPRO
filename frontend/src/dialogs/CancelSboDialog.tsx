import { useState } from 'react';
import { getApi } from '../api/client';
import type { ModelNodeDto } from '../api/types';
import Modal from '../components/Modal';
import { log } from '../stores/log';

/** Cancela un SELECT pendiente (ctlModel SBO 2/4). */
export default function CancelSboDialog({
  node,
  onClose,
}: {
  node: ModelNodeDto;
  onClose: () => void;
}) {
  const [orIdent, setOrIdent] = useState('IEDNavigatorPRO');
  const [busy, setBusy] = useState(false);

  const execute = async () => {
    setBusy(true);
    try {
      const r = await getApi().cancelControl(node.ref, orIdent);
      if (r.success) {
        log.info(`[CANCEL OK] ${node.ref} — SELECT liberado (${r.ctlModelName})`);
        onClose();
      } else {
        log.error(`[CANCEL] ${node.ref}: ${r.error ?? 'falló'}${r.lastApplError ? ` — ${r.lastApplError}` : ''}`);
      }
    } catch (e) {
      log.error(`[CANCEL] ${(e as Error).message}`);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal title="Cancelar SELECT (SBO)" onClose={onClose} width={380}>
      <div className="space-y-3 text-xs">
        <div className="break-all font-mono text-[11px] text-gray-600 dark:text-gray-300">{node.ref}</div>
        <div className="text-gray-500">
          Envía CANCEL al nodo de control para liberar un SELECT pendiente. Solo aplica a ctlModel
          SBO (2 o 4).
        </div>
        <div>
          <div className="mb-1 text-gray-500">Identificador del operador (orIdent):</div>
          <input
            value={orIdent}
            onChange={(e) => setOrIdent(e.target.value)}
            className="w-full rounded border border-gray-300 bg-transparent px-2 py-1 text-xs outline-none focus:border-accent dark:border-surface-border dark:text-gray-200"
          />
        </div>
        <div className="flex justify-end gap-2 pt-2">
          <button onClick={onClose} className="rounded border border-gray-300 px-3 py-1.5 dark:border-surface-border dark:text-gray-300">
            Cerrar
          </button>
          <button
            onClick={execute}
            disabled={busy}
            className="rounded bg-red-600 px-4 py-1.5 font-medium text-white hover:bg-red-700 disabled:opacity-50"
          >
            {busy ? 'Enviando…' : 'CANCEL'}
          </button>
        </div>
      </div>
    </Modal>
  );
}
