import { useEffect, useState } from 'react';
import { getApi } from '../api/client';
import type { ControlInfo, ModelNodeDto } from '../api/types';
import Modal from '../components/Modal';
import { log } from '../stores/log';

/** Diálogo de control SBO/direct para un DO de control (FC=CO). */
export default function ControlDialog({
  node,
  onClose,
}: {
  node: ModelNodeDto;
  onClose: () => void;
}) {
  const [info, setInfo] = useState<ControlInfo | null>(null);
  const [loadError, setLoadError] = useState('');
  const [value, setValue] = useState('');
  const [test, setTest] = useState(false);
  const [synchroCheck, setSynchroCheck] = useState(false);
  const [interlockCheck, setInterlockCheck] = useState(false);
  const [orIdent, setOrIdent] = useState('IEDNavigatorPRO');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    getApi()
      .controlInfo(node.ref)
      .then((i) => {
        setInfo(i);
        // Valor por defecto según tipo
        if (i.ctlValType.includes('DoubleBit')) setValue('on');
        else if (i.ctlValType.includes('TapCommand')) setValue('stop');
        else if (i.ctlValType.includes('Boolean')) setValue('true');
        else setValue('');
      })
      .catch((e) => setLoadError((e as Error).message));
  }, [node.ref]);

  const execute = async () => {
    if (!info || !value) return;
    setBusy(true);
    try {
      const r = await getApi().operate({ ref: info.operRef, value, test, orIdent, synchroCheck, interlockCheck });
      if (r.success) {
        log.info(`[CONTROL OK] ${info.operRef} = ${value} (${r.ctlModelName})${test ? ' [TEST]' : ''}`);
        onClose();
      } else {
        log.error(`[CONTROL] ${info.operRef}: ${r.error ?? 'falló'}${r.lastApplError ? ` — LastApplError: ${r.lastApplError}` : ''}`);
      }
    } catch (e) {
      log.error(`[CONTROL] ${(e as Error).message}`);
    } finally {
      setBusy(false);
    }
  };

  const valueSelector = () => {
    if (!info) return null;
    const t = info.ctlValType;
    if (t.includes('DoubleBit')) {
      return (
        <select value={value} onChange={(e) => setValue(e.target.value)} className={inputCls}>
          {['on', 'off', 'intermediate', 'bad'].map((o) => (
            <option key={o} value={o}>{o}</option>
          ))}
        </select>
      );
    }
    if (t.includes('TapCommand')) {
      return (
        <select value={value} onChange={(e) => setValue(e.target.value)} className={inputCls}>
          {['stop', 'lower', 'higher', 'reserved'].map((o) => (
            <option key={o} value={o}>{o}</option>
          ))}
        </select>
      );
    }
    if (t.includes('Boolean')) {
      return (
        <div className="flex gap-2">
          {[
            { v: 'true', label: 'ON / Cerrar', cls: 'bg-green-600 hover:bg-green-700' },
            { v: 'false', label: 'OFF / Abrir', cls: 'bg-red-600 hover:bg-red-700' },
          ].map((b) => (
            <button
              key={b.v}
              onClick={() => setValue(b.v)}
              className={`rounded px-3 py-1.5 text-xs font-medium text-white ${b.cls} ${
                value === b.v ? 'ring-2 ring-accent ring-offset-1 dark:ring-offset-surface-raised' : 'opacity-60'
              }`}
            >
              {b.label}
            </button>
          ))}
        </div>
      );
    }
    return (
      <input
        value={value}
        onChange={(e) => setValue(e.target.value)}
        placeholder={t}
        className={inputCls}
      />
    );
  };

  return (
    <Modal title="Operación de control" onClose={onClose}>
      <div className="space-y-3 text-xs">
        <div className="break-all font-mono text-[11px] text-gray-600 dark:text-gray-300">{node.ref}</div>

        {loadError && <div className="text-red-500">{loadError}</div>}
        {!info && !loadError && <div className="text-gray-400">Consultando ctlModel…</div>}

        {info && (
          <>
            <div className={info.sbo ? 'font-medium text-red-600 dark:text-red-400' : 'font-medium text-green-700 dark:text-green-400'}>
              Modelo de control: {info.ctlModelName} (ctlModel={info.ctlModel})
            </div>
            {info.sbo && (
              <div className="italic text-gray-500">
                SELECT → OPERATE: el IED reservará el nodo antes de ejecutar.
              </div>
            )}

            <div>
              <div className="mb-1 text-gray-500">Valor (ctlVal — {info.ctlValType}):</div>
              {valueSelector()}
            </div>

            <label className="flex items-center gap-2">
              <input type="checkbox" checked={test} onChange={(e) => setTest(e.target.checked)} />
              Modo Test (el IED registra pero no actúa en hardware)
            </label>
            <label className="flex items-center gap-2">
              <input type="checkbox" checked={synchroCheck} onChange={(e) => setSynchroCheck(e.target.checked)} />
              Verificación de sincronismo (Check.synchroChk)
            </label>
            <label className="flex items-center gap-2">
              <input type="checkbox" checked={interlockCheck} onChange={(e) => setInterlockCheck(e.target.checked)} />
              Verificación de enclavamiento (Check.interlkChk)
            </label>

            <div>
              <div className="mb-1 text-gray-500">Identificador del operador (orIdent):</div>
              <input value={orIdent} onChange={(e) => setOrIdent(e.target.value)} className={inputCls} />
            </div>

            <div className="flex justify-end gap-2 pt-2">
              <button onClick={onClose} className="rounded border border-gray-300 px-3 py-1.5 dark:border-surface-border dark:text-gray-300">
                Cerrar
              </button>
              <button
                onClick={execute}
                disabled={busy || !value || info.ctlModel === 0}
                className="rounded bg-accent px-4 py-1.5 font-medium text-white hover:bg-accent-hover disabled:opacity-50"
              >
                {busy ? 'Ejecutando…' : info.sbo ? 'SELECT + OPERATE' : 'OPERATE'}
              </button>
            </div>
          </>
        )}
      </div>
    </Modal>
  );
}

const inputCls =
  'w-full rounded border border-gray-300 bg-transparent px-2 py-1 text-xs outline-none focus:border-accent dark:border-surface-border dark:text-gray-200';
