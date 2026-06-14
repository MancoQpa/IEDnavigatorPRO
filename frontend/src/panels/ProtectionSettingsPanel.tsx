import { useState } from 'react';
import { getApi } from '../api/client';
import type { ModelNodeDto } from '../api/types';
import { useConnectionStore } from '../stores/connection';
import { log } from '../stores/log';
import { useModelStore } from '../stores/model';
import { useServerStore } from '../stores/server';
import { useUiStore } from '../stores/ui';

interface SpRow {
  ref: string;
  type: string;
  value: string;
  newValue: string;
}

/** Opciones del editor según el tipo básico (como optionsFor del original). */
function optionsFor(type: string): string[] | null {
  const t = type.toUpperCase();
  if (t.includes('BOOLEAN') || t.includes('CHECK')) return ['true', 'false'];
  if (t.includes('DOUBLE_BIT') || t.includes('DBPOS')) return ['intermediate', 'off', 'on', 'bad'];
  if (t.includes('TAP')) return ['stop', 'lower', 'higher', 'reserved'];
  return null;
}

function collectSpLeaves(node: ModelNodeDto, inheritedFc: string | undefined, out: SpRow[]) {
  const fc = node.fc ?? inheritedFc;
  if (!node.children || node.children.length === 0) {
    if (fc === 'SP') {
      out.push({ ref: node.ref, type: node.type ?? '', value: node.value ?? '', newValue: '' });
    }
    return;
  }
  for (const child of node.children) collectSpLeaves(child, fc, out);
}

/**
 * Pestaña «Ajustes (SP)»: tabla editable con todos los atributos FC=SP
 * (setting points de protección) del modelo, réplica de ProtectionSettingsPanel.
 */
export default function ProtectionSettingsPanel() {
  const connected = useConnectionStore((s) => s.client.connected);
  const mode = useUiStore((s) => s.mode);
  const clientModel = useModelStore((s) => s.model);
  const serverModel = useServerStore((s) => s.model);

  const [rows, setRows] = useState<SpRow[]>([]);
  const [filter, setFilter] = useState('');
  const [typeFilter, setTypeFilter] = useState('');
  const [summary, setSummary] = useState(
    "Pulse 'Cargar ajustes' con un modelo cargado (cliente conectado o servidor)",
  );
  const [busy, setBusy] = useState(false);

  const model = connected ? clientModel : mode === 'servidor' ? serverModel : null;

  const loadSettings = () => {
    if (!model) {
      window.alert(
        'No hay modelo cargado.\nConéctate a un IED o carga un SCL en modo servidor.',
      );
      return;
    }
    const out: SpRow[] = [];
    for (const ld of model.logicalDevices) collectSpLeaves(ld, undefined, out);
    setRows(out);
    setSummary(`${out.length} ajustes FC=SP en el modelo`);
    log.info(`[AjustesSP] Cargados ${out.length} atributos FC=SP`);
    if (out.length === 0) {
      window.alert('El modelo no contiene atributos con FC=SP.');
    }
  };

  const readFromIed = async () => {
    if (!connected) {
      window.alert(
        'Se requiere modo cliente conectado para leer del IED.\n' +
          '(En modo servidor los valores ya son los del modelo local)',
      );
      return;
    }
    let current = rows;
    if (current.length === 0) {
      if (!model) return;
      const out: SpRow[] = [];
      for (const ld of model.logicalDevices) collectSpLeaves(ld, undefined, out);
      current = out;
      setRows(out);
      if (out.length === 0) return;
    }
    setBusy(true);
    setSummary(`Leyendo ${current.length} ajustes SP del IED...`);
    let ok = 0;
    let err = 0;
    const updated = [...current];
    for (let i = 0; i < updated.length; i++) {
      try {
        const r = await getApi().read(updated[i].ref, 'SP');
        updated[i] = { ...updated[i], value: r.value };
        ok++;
      } catch (e) {
        err++;
        log.warn(`[AjustesSP] Error leyendo ${updated[i].ref}: ${(e as Error).message}`);
      }
    }
    setRows(updated);
    setSummary(`${updated.length} ajustes | leídos ${ok}${err > 0 ? ` (${err} errores)` : ''}`);
    log.info(`[AjustesSP] Lectura completa: ${ok} OK, ${err} errores`);
    setBusy(false);
  };

  const writeChanges = async () => {
    if (!connected) {
      window.alert('Se requiere modo cliente conectado para escribir ajustes.');
      return;
    }
    const changes = rows
      .map((r, i) => ({ r, i }))
      .filter(({ r }) => r.newValue.trim() !== '' && r.newValue.trim() !== r.value);
    if (changes.length === 0) {
      window.alert("No hay cambios pendientes.\nEdita la columna 'Nuevo valor' primero.");
      return;
    }
    const resumen = changes
      .slice(0, 20)
      .map(({ r }) => `${r.ref}:  ${r.value}  ->  ${r.newValue.trim()}`)
      .join('\n');
    if (
      !window.confirm(
        `ATENCIÓN: vas a escribir ${changes.length} ajuste(s) de protección en el IED.\n\n` +
          resumen +
          (changes.length > 20 ? '\n…' : '') +
          '\n\n¿Continuar?',
      )
    ) {
      return;
    }
    setBusy(true);
    let ok = 0;
    let err = 0;
    const updated = [...rows];
    for (const { r, i } of changes) {
      const nuevo = r.newValue.trim();
      try {
        await getApi().write(r.ref, 'SP', nuevo);
        ok++;
        try {
          const rd = await getApi().read(r.ref, 'SP');
          updated[i] = { ...updated[i], value: rd.value, newValue: '' };
        } catch {
          updated[i] = { ...updated[i], value: nuevo, newValue: '' };
        }
        log.info(`[AjustesSP] OK: ${r.ref} = ${nuevo}`);
      } catch (e) {
        err++;
        log.error(`[AjustesSP] ERROR escribiendo ${r.ref}: ${(e as Error).message}`);
      }
    }
    setRows(updated);
    setSummary(`Escritura: ${ok} OK, ${err} errores`);
    if (err > 0) window.alert(`${ok} ajustes escritos, ${err} errores (ver log)`);
    setBusy(false);
  };

  const setNewValue = (ref: string, v: string) =>
    setRows((rs) => rs.map((r) => (r.ref === ref ? { ...r, newValue: v } : r)));

  const f = filter.trim().toLowerCase();
  const visible = rows.filter((r) => {
    if (f && !r.ref.toLowerCase().includes(f) && !r.type.toLowerCase().includes(f) && !r.value.toLowerCase().includes(f)) return false;
    if (typeFilter && !r.type.toUpperCase().includes(typeFilter)) return false;
    return true;
  });

  const btnCls =
    'rounded border border-gray-300 px-2 py-1 hover:bg-gray-100 disabled:opacity-40 dark:border-surface-border dark:text-gray-300 dark:hover:bg-surface-raised';
  const editCls =
    'w-full rounded border border-gray-300 bg-white px-1 py-0.5 text-xs outline-none focus:border-accent dark:border-surface-border dark:bg-surface dark:text-gray-200';

  return (
    <div className="flex h-full flex-col bg-white text-xs dark:bg-surface">
      <div className="flex items-center gap-2 border-b border-gray-200 p-1.5 dark:border-surface-border">
        <button onClick={loadSettings} disabled={busy} className={btnCls}>
          Cargar ajustes
        </button>
        <button onClick={() => void readFromIed()} disabled={busy} className={btnCls}>
          Leer del IED
        </button>
        <button onClick={() => void writeChanges()} disabled={busy} className={btnCls}>
          Escribir cambios
        </button>
        <span className="ml-2 text-gray-500 dark:text-gray-400">Filtro:</span>
        <input
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          placeholder="ref / tipo / valor…"
          className="w-44 rounded border border-gray-300 bg-white px-2 py-1 outline-none focus:border-accent dark:border-surface-border dark:bg-surface dark:text-gray-200"
        />
        <select
          value={typeFilter}
          onChange={(e) => setTypeFilter(e.target.value)}
          className="rounded border border-gray-300 bg-white px-1 py-1 dark:border-surface-border dark:bg-surface dark:text-gray-200"
        >
          <option value="">Tipo: todos</option>
          <option value="BOOLEAN">BOOLEAN</option>
          <option value="FLOAT">FLOAT</option>
          <option value="INT">INTEGER</option>
          <option value="DBPOS">DOUBLE_BIT</option>
          <option value="TAP">TAP</option>
        </select>
      </div>

      <div className="min-h-0 flex-1 overflow-auto">
        <table className="w-full border-collapse">
          <thead className="sticky top-0 bg-gray-50 dark:bg-surface-raised">
            <tr className="text-left text-gray-500 dark:text-gray-400">
              <th className="border-b border-gray-200 px-2 py-1 font-medium dark:border-surface-border">Referencia</th>
              <th className="border-b border-gray-200 px-2 py-1 font-medium dark:border-surface-border">Tipo</th>
              <th className="border-b border-gray-200 px-2 py-1 font-medium dark:border-surface-border">Valor actual</th>
              <th className="border-b border-gray-200 px-2 py-1 font-medium dark:border-surface-border">Nuevo valor</th>
            </tr>
          </thead>
          <tbody>
            {visible.map((r) => {
              const pending = r.newValue.trim() !== '' && r.newValue.trim() !== r.value;
              const opts = optionsFor(r.type);
              return (
                <tr
                  key={r.ref}
                  style={pending ? { backgroundColor: 'rgba(255,243,224,0.85)' } : undefined}
                  className="border-b border-gray-100 dark:border-surface-border/50"
                >
                  <td className="px-2 py-1 font-mono text-[11px] text-gray-700 dark:text-gray-200">{r.ref}</td>
                  <td className="px-2 py-1 text-gray-500 dark:text-gray-400">{r.type}</td>
                  <td className="px-2 py-1 font-mono text-[11px] text-gray-700 dark:text-gray-200">{r.value}</td>
                  <td className="px-2 py-1" style={pending ? { color: 'rgb(230,81,0)' } : undefined}>
                    {opts ? (
                      <select
                        value={r.newValue}
                        onChange={(e) => setNewValue(r.ref, e.target.value)}
                        className={editCls}
                      >
                        <option value="">—</option>
                        {opts.map((o) => (
                          <option key={o} value={o}>
                            {o}
                          </option>
                        ))}
                      </select>
                    ) : (
                      <input
                        value={r.newValue}
                        onChange={(e) => setNewValue(r.ref, e.target.value)}
                        className={editCls}
                        style={pending ? { color: 'rgb(230,81,0)' } : undefined}
                      />
                    )}
                  </td>
                </tr>
              );
            })}
            {visible.length === 0 && (
              <tr>
                <td colSpan={4} className="px-2 py-6 text-center text-gray-400">
                  {rows.length === 0
                    ? 'Sin ajustes cargados.'
                    : 'Ningún ajuste coincide con el filtro.'}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      <div className="border-t border-gray-200 px-2 py-1 text-[11px] text-gray-500 dark:border-surface-border dark:text-gray-400">
        {summary}
      </div>
    </div>
  );
}
