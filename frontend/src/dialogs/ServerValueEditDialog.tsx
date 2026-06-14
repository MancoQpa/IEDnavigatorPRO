import { useState } from 'react';
import type { ModelNodeDto } from '../api/types';
import Modal from '../components/Modal';
import { log } from '../stores/log';
import { useServerStore } from '../stores/server';

/** Opciones predefinidas según el tipo básico IEC 61850 (igual que el original). */
function optionsFor(type: string): string[] | null {
  const t = type.toUpperCase();
  if (t.includes('BOOLEAN') || t.includes('BOOL')) return ['true', 'false'];
  if (t.includes('DBPOS') || t.includes('DOUBLE_BIT') || t.includes('DOUBLE-BIT'))
    return ['off', 'intermediate', 'on', 'bad'];
  if (t.includes('TAPCOMMAND') || t.includes('TAP_COMMAND') || t.includes('TCMD'))
    return ['stop', 'lower', 'higher', 'reserved'];
  if (t.includes('HEALTH') || t.includes('ORCAT'))
    return ['ok', 'warning', 'alarm'];
  if (t.includes('BEHAV') || t.includes('MODTYPE'))
    return ['on', 'blocked', 'test', 'test/blocked', 'off'];
  return null;
}

/** Edición de valor en el simulador IED (modo servidor). */
export default function ServerValueEditDialog({
  node,
  onClose,
}: {
  node: ModelNodeDto;
  onClose: () => void;
}) {
  const setValue = useServerStore((s) => s.setValue);
  const opts = optionsFor(node.type ?? '');
  const [value, setValue_] = useState(node.value ?? '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const write = async () => {
    if (!value.trim()) return;
    setBusy(true);
    setError('');
    try {
      await setValue(node.ref, value.trim());
      log.info(`[Servidor] ${node.ref} = ${value.trim()}`);
      onClose();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const inputCls =
    'w-full rounded border border-gray-300 bg-transparent px-2 py-1.5 font-mono text-xs outline-none focus:border-accent dark:border-surface-border dark:bg-surface dark:text-gray-200';

  return (
    <Modal title="Editar valor del simulador" onClose={onClose} width={420}>
      <div className="space-y-3 text-xs">
        <div className="break-all font-mono text-[11px] text-gray-500 dark:text-gray-400">
          {node.ref}
        </div>
        <div className="flex gap-4 text-gray-500">
          {node.type && <span>Tipo: <span className="font-medium text-gray-700 dark:text-gray-200">{node.type}</span></span>}
          {node.value !== undefined && (
            <span>Actual: <span className="font-medium text-accent dark:text-accent-hover">{node.value}</span></span>
          )}
        </div>

        {opts ? (
          /* Tipo con valores predefinidos: dropdown + libre */
          <div className="space-y-1.5">
            <select
              autoFocus
              value={opts.includes(value) ? value : ''}
              onChange={(e) => setValue_(e.target.value)}
              className={inputCls}
            >
              <option value="">— seleccionar —</option>
              {opts.map((o) => (
                <option key={o} value={o}>{o}</option>
              ))}
            </select>
            <div className="text-[10px] text-gray-400">O escribir valor libre:</div>
            <input
              value={value}
              onChange={(e) => setValue_(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && void write()}
              placeholder="valor libre…"
              className={inputCls}
            />
          </div>
        ) : (
          /* Tipo numérico o libre: solo input */
          <input
            autoFocus
            value={value}
            onChange={(e) => setValue_(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && void write()}
            placeholder="Nuevo valor…"
            className={inputCls}
          />
        )}

        {error && <div className="text-red-500">{error}</div>}

        <div className="flex justify-end gap-2 pt-1">
          <button
            onClick={onClose}
            className="rounded border border-gray-300 px-3 py-1.5 dark:border-surface-border dark:text-gray-300"
          >
            Cancelar
          </button>
          <button
            onClick={() => void write()}
            disabled={busy || !value.trim()}
            className="rounded bg-accent px-4 py-1.5 font-medium text-white hover:bg-accent-hover disabled:opacity-50"
          >
            {busy ? 'Aplicando…' : 'Aplicar'}
          </button>
        </div>
      </div>
    </Modal>
  );
}
