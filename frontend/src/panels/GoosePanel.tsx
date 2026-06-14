import { memo, useEffect, useState } from 'react';
import { pickSclFile } from '../api/pickFile';
import type { GoCBInfo, GooseDataValue, GooseMessageEvent } from '../api/types';
import ContextMenu, { type MenuItem } from '../components/ContextMenu';
import Modal from '../components/Modal';
import { useConnectionStore } from '../stores/connection';
import { useGooseStore } from '../stores/goose';
import { useServerStore } from '../stores/server';

type EditTarget = { gcbIndex: number; dataIndex: number; name: string; type: string; current: string | null };
type CtxMenu = { x: number; y: number; gcb: GoCBInfo };

/* Colores semánticos para valores GOOSE (iguales a ModelTreePanel) */
const ACTIVE = ['on', 'ok', 'closed', 'true', 'good'];
const INACTIVE = ['off', 'alarm', 'open', 'false'];
const WARNING = ['intermediate', 'bad', 'warning', 'test', 'bad-state', 'invalid'];
function valColor(v: string | null): string | undefined {
  if (!v) return undefined;
  const l = v.trim().toLowerCase();
  if (ACTIVE.includes(l)) return 'rgb(0,150,0)';
  if (INACTIVE.includes(l)) return 'rgb(200,0,0)';
  if (WARNING.some((w) => l === w || l.includes(w))) return 'rgb(255,140,0)';
  return undefined;
}

/** Construye ítems de menú contextual para un GoCB: un grupo por cada DataValue. */
function buildGoCBMenuItems(
  gcb: GoCBInfo,
  onQuickSet: (gcbIdx: number, dvIdx: number, val: string) => void,
  onEdit: (t: EditTarget) => void,
): MenuItem[] {
  const items: MenuItem[] = [];
  for (const dv of gcb.dataValues) {
    if (items.length) items.push({ label: '', separator: true, onClick: () => {} });
    const t = (dv.type ?? '').toUpperCase();
    const hdr = `[${dv.index}] ${dv.name}  =  ${dv.value ?? '—'}`;
    // Header (no-op, solo informativo)
    items.push({ label: `📋 ${hdr}`, onClick: () => {} });

    if (t.includes('BOOLEAN')) {
      items.push({ label: '    Establecer TRUE', onClick: () => onQuickSet(gcb.index, dv.index, 'true') });
      items.push({ label: '    Establecer FALSE', onClick: () => onQuickSet(gcb.index, dv.index, 'false') });
    } else if (t.includes('DBPOS') || t.includes('DOUBLE')) {
      items.push({ label: '    Establecer ON (cerrado)', onClick: () => onQuickSet(gcb.index, dv.index, 'on') });
      items.push({ label: '    Establecer OFF (abierto)', onClick: () => onQuickSet(gcb.index, dv.index, 'off') });
      items.push({ label: '    Establecer INTERMEDIATE', onClick: () => onQuickSet(gcb.index, dv.index, 'intermediate') });
      items.push({ label: '    Establecer BAD_STATE', onClick: () => onQuickSet(gcb.index, dv.index, 'bad-state') });
    } else if (t.includes('QUALITY') || t.includes('BITSTRING')) {
      items.push({ label: '    Establecer GOOD (0x0000)', onClick: () => onQuickSet(gcb.index, dv.index, '0000') });
      items.push({ label: '    Establecer INVALID (0x0004)', onClick: () => onQuickSet(gcb.index, dv.index, '0004') });
    }
    items.push({
      label: '    Editar valor…',
      onClick: () => onEdit({ gcbIndex: gcb.index, dataIndex: dv.index, name: dv.name, type: dv.type, current: dv.value }),
    });
  }
  return items;
}

function GoCBRow({
  gcb,
  iface,
  onEditValue,
  onContextMenu,
}: {
  gcb: GoCBInfo;
  iface: string;
  onEditValue: (t: EditTarget) => void;
  onContextMenu: (e: React.MouseEvent, gcb: GoCBInfo) => void;
}) {
  const [open, setOpen] = useState(false);
  const store = useGooseStore;
  const busy = useGooseStore((s) => s.busy);

  return (
    <>
      <tr
        className="border-b border-gray-100 text-xs hover:bg-gray-50 dark:border-surface-border dark:hover:bg-surface-raised"
        onContextMenu={(e) => { e.preventDefault(); onContextMenu(e, gcb); }}
      >
        <td className="cursor-pointer px-2 py-1 text-gray-400" onClick={() => setOpen(!open)}>
          {open ? '▾' : '▸'}
        </td>
        <td className="px-2 py-1 font-mono text-[11px] dark:text-gray-200">
          {gcb.ldInst}/LLN0.{gcb.cbName}
        </td>
        <td className="px-2 py-1 dark:text-gray-300">{gcb.goId ?? '—'}</td>
        <td className="px-2 py-1 dark:text-gray-300">{gcb.datSet ?? '—'}</td>
        <td className="px-2 py-1 font-mono dark:text-gray-300">{gcb.appId ?? '—'}</td>
        <td className="px-2 py-1 font-mono text-[11px] dark:text-gray-300">{gcb.macAddress ?? '—'}</td>
        <td className="px-2 py-1">
          {gcb.publishing ? (
            <span className="rounded bg-green-100 px-1.5 py-0.5 text-[10px] font-medium text-green-700 dark:bg-green-900/40 dark:text-green-400">
              stNum {gcb.stNum} / sq {gcb.sqNum}
            </span>
          ) : (
            <span className="text-[10px] text-gray-400">detenido</span>
          )}
        </td>
        <td className="px-2 py-1 text-right">
          {gcb.publishing ? (
            <button
              onClick={() => void store.getState().stop(gcb.index)}
              disabled={busy}
              className="rounded bg-red-600 px-2 py-0.5 text-[11px] font-medium text-white hover:bg-red-700 disabled:opacity-40"
            >
              Detener
            </button>
          ) : (
            <button
              onClick={() => void store.getState().publish(gcb.index, iface)}
              disabled={busy || !iface}
              className="rounded bg-accent px-2 py-0.5 text-[11px] font-medium text-white hover:bg-accent-hover disabled:opacity-40"
            >
              Publicar
            </button>
          )}
        </td>
      </tr>
      {open &&
        gcb.dataValues.map((dv) => (
          <tr key={dv.index} className="border-b border-gray-50 text-[11px] dark:border-surface-border/50">
            <td />
            <td colSpan={3} className="px-2 py-0.5 pl-6 font-mono text-gray-500 dark:text-gray-400">
              [{dv.index}] {dv.name}
            </td>
            <td className="px-2 py-0.5 text-gray-400">{dv.type}</td>
            <td
              colSpan={2}
              className={`px-2 py-0.5 font-mono font-bold ${gcb.publishing ? 'cursor-pointer hover:underline' : ''}`}
              style={{ color: valColor(dv.value) }}
              onDoubleClick={() =>
                gcb.publishing &&
                onEditValue({
                  gcbIndex: gcb.index,
                  dataIndex: dv.index,
                  name: dv.name,
                  type: dv.type,
                  current: dv.value,
                })
              }
              title={gcb.publishing ? 'Doble clic para editar y publicar estado' : undefined}
            >
              {dv.value ?? '—'}
            </td>
            <td />
          </tr>
        ))}
    </>
  );
}

/* Fast time format — avoid toLocaleTimeString() per row */
function fmtTime(ts?: number): string {
  if (!ts) return '—';
  const d = new Date(ts);
  const h = d.getHours();
  const m = d.getMinutes();
  const s = d.getSeconds();
  const ms = d.getMilliseconds();
  return `${h < 10 ? '0' : ''}${h}:${m < 10 ? '0' : ''}${m}:${s < 10 ? '0' : ''}${s}.${ms < 100 ? (ms < 10 ? '00' : '0') : ''}${ms}`;
}

const SOURCE_CLS: Record<string, string> = {
  local: 'bg-blue-100 text-blue-700 dark:bg-blue-900/40 dark:text-blue-400',
  udp: 'bg-teal-100 text-teal-700 dark:bg-teal-900/40 dark:text-teal-400',
  network: 'bg-purple-100 text-purple-700 dark:bg-purple-900/40 dark:text-purple-400',
};
const SOURCE_LBL: Record<string, string> = { local: 'LOCAL', udp: 'UDP', network: 'RED' };

const MsgRow = memo(function MsgRow({ m }: { m: GooseMessageEvent }) {
  return (
    <tr className="border-b border-gray-100 hover:bg-gray-50 dark:border-surface-border dark:hover:bg-surface-raised">
      <td className="px-2 py-0.5 font-mono text-gray-500 dark:text-gray-400">{fmtTime(m.ts)}</td>
      <td className="px-2 py-0.5">
        <span className={`rounded px-1 text-[10px] font-medium ${SOURCE_CLS[m.source] ?? SOURCE_CLS.network}`}>
          {SOURCE_LBL[m.source] ?? 'RED'}
        </span>
      </td>
      <td className="px-2 py-0.5 font-mono dark:text-gray-300">{m.gocbRef}</td>
      <td className="px-2 py-0.5 dark:text-gray-300">{m.goId}</td>
      <td className="px-2 py-0.5 font-mono dark:text-gray-300">{m.stNum}</td>
      <td className="px-2 py-0.5 font-mono dark:text-gray-300">{m.sqNum}</td>
      <td className="px-2 py-0.5 font-mono text-gray-500 dark:text-gray-400">
        {m.entries.map((e) => e.value ?? '?').join(', ')}
      </td>
    </tr>
  );
});

/** GOOSE: publicación de GoCBs del SCL y captura de mensajes de la red. */
export default function GoosePanel() {
  const bridgeReady = useConnectionStore((s) => s.bridgeReady);
  const net = useGooseStore((s) => s.net);
  const state = useGooseStore((s) => s.state);
  const messages = useGooseStore((s) => s.messages);
  const busy = useGooseStore((s) => s.busy);
  const store = useGooseStore;
  const serverScl = useServerStore((s) => s.sclPath);
  const serverIedIndex = useServerStore((s) => s.iedIndex);

  const [path, setPath] = useState('');
  const [iface, setIface] = useState('');
  const [udpRx, setUdpRx] = useState(true);
  const [udpTx, setUdpTx] = useState(false);
  const [udpIp, setUdpIp] = useState('');
  const [editTarget, setEditTarget] = useState<EditTarget | null>(null);
  const [editVal, setEditVal] = useState('');
  const [ctxMenu, setCtxMenu] = useState<CtxMenu | null>(null);

  const openEdit = (t: EditTarget) => {
    setEditTarget(t);
    setEditVal(t.current ?? '');
  };

  const submitEdit = () => {
    if (!editTarget) return;
    void store.getState().setValue(editTarget.gcbIndex, editTarget.dataIndex, editVal);
    setEditTarget(null);
  };

  const quickSet = (gcbIdx: number, dvIdx: number, val: string) => {
    void store.getState().setValue(gcbIdx, dvIdx, val);
  };

  const handleCtxMenu = (e: React.MouseEvent, gcb: GoCBInfo) => {
    if (!gcb.publishing) return; // solo si está publicando
    setCtxMenu({ x: e.clientX, y: e.clientY, gcb });
  };

  useEffect(() => {
    if (bridgeReady) {
      void store.getState().fetchInterfaces();
      void store.getState().refresh();
    }
  }, [bridgeReady, store]);

  // Predefinir la ruta SCL del panel Servidor si existe
  useEffect(() => {
    if (serverScl && !path) setPath(serverScl);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [serverScl]);

  const npcap = net?.npcapAvailable ?? false;
  const udp = state.udp;
  const udpActive = !!udp && (udp.receiving || udp.sending);

  return (
    <div className="flex h-full flex-col bg-white dark:bg-surface">
      {/* ── Carga SCL + interfaz ── */}
      <div className="flex flex-wrap items-center gap-2 border-b border-gray-200 p-1.5 text-xs dark:border-surface-border">
        <input
          value={path}
          onChange={(e) => setPath(e.target.value)}
          placeholder="Ruta del fichero SCL con GoCBs…"
          className="min-w-64 flex-1 rounded border border-gray-300 bg-transparent px-2 py-1 font-mono text-[11px] outline-none focus:border-accent dark:border-surface-border dark:text-gray-200"
        />
        <button
          onClick={() =>
            void pickSclFile('Seleccionar SCL con GoCBs').then((p) => p && setPath(p))
          }
          disabled={busy}
          className="rounded border border-gray-300 px-2 py-1 hover:bg-gray-100 disabled:opacity-40 dark:border-surface-border dark:text-gray-300 dark:hover:bg-surface-raised"
        >
          Examinar…
        </button>
        <button
          onClick={() => void store.getState().loadScl(path, serverIedIndex)}
          disabled={!path || busy}
          className="rounded border border-gray-300 px-2 py-1 hover:bg-gray-100 disabled:opacity-40 dark:border-surface-border dark:text-gray-300 dark:hover:bg-surface-raised"
        >
          Cargar GoCBs
        </button>
        <select
          value={iface}
          onChange={(e) => setIface(e.target.value)}
          className="max-w-64 rounded border border-gray-300 bg-transparent px-1 py-1 dark:border-surface-border dark:bg-surface dark:text-gray-200"
        >
          <option value="">— Interfaz de red —</option>
          <option value="loopback">Loopback interno (sin red)</option>
          {net?.interfaces.map((i) => (
            <option key={i.name} value={i.name}>
              {i.description ?? i.name}
            </option>
          ))}
        </select>
        {state.gocbs.length > 0 && (
          <>
            <button
              onClick={() => void store.getState().publish(-1, iface)}
              disabled={busy || !iface}
              className="rounded bg-accent px-2 py-1 font-medium text-white hover:bg-accent-hover disabled:opacity-40"
            >
              Publicar todos
            </button>
            <button
              onClick={() => void store.getState().stop(-1)}
              disabled={busy}
              className="rounded border border-gray-300 px-2 py-1 hover:bg-gray-100 disabled:opacity-40 dark:border-surface-border dark:text-gray-300 dark:hover:bg-surface-raised"
            >
              Detener todos
            </button>
          </>
        )}
        <div className="flex-1" />
        {state.subscribing ? (
          <button
            onClick={() => void store.getState().unsubscribe()}
            disabled={busy}
            className="rounded bg-red-600 px-2 py-1 font-medium text-white hover:bg-red-700 disabled:opacity-40"
          >
            Detener captura
          </button>
        ) : (
          <button
            onClick={() => void store.getState().subscribe(iface)}
            disabled={busy || !iface || iface === 'loopback' || !npcap}
            title={!npcap ? 'Npcap no disponible' : undefined}
            className="rounded bg-green-600 px-2 py-1 font-medium text-white hover:bg-green-700 disabled:opacity-40"
          >
            Capturar
          </button>
        )}
      </div>

      {!npcap && (
        <div className="border-b border-amber-200 bg-amber-50 px-2 py-1 text-[11px] text-amber-700 dark:border-amber-900 dark:bg-amber-950/40 dark:text-amber-400">
          Npcap no detectado: solo está disponible el modo loopback (sin envío a la red). Para
          GOOSE/SV en red instale Npcap desde{' '}
          <span className="font-mono select-all">https://npcap.com</span> (con «WinPcap
          API-compatible mode») y reinicie la aplicación.
        </div>
      )}

      {/* ── Puente GOOSE-UDP (WiFi / redes enrutadas) ── */}
      <div className="flex flex-wrap items-center gap-2 border-b border-gray-200 px-2 py-1 text-[11px] dark:border-surface-border">
        <span className="font-medium text-gray-500 dark:text-gray-400">GOOSE sobre UDP</span>
        {udpActive ? (
          <>
            <span className="rounded bg-green-100 px-1.5 py-0.5 text-[10px] font-medium text-green-700 dark:bg-green-900/40 dark:text-green-400">
              {[udp?.receiving && 'Rx', udp?.sending && 'Tx'].filter(Boolean).join(' + ')} · puerto{' '}
              {udp?.port}
              {udp?.targetIp ? ` → ${udp.targetIp}` : udp?.sending ? ' (broadcast)' : ''}
            </span>
            <span className="text-gray-400">
              enviados {udp?.sentCount ?? 0} · recibidos {udp?.receivedCount ?? 0}
            </span>
            <button
              onClick={() => void store.getState().udpStop()}
              disabled={busy}
              className="rounded bg-red-600 px-2 py-0.5 font-medium text-white hover:bg-red-700 disabled:opacity-40"
            >
              Detener puente
            </button>
          </>
        ) : (
          <>
            <label className="flex items-center gap-1 text-gray-500 dark:text-gray-400">
              <input type="checkbox" checked={udpRx} onChange={(e) => setUdpRx(e.target.checked)} />
              Recibir
            </label>
            <label className="flex items-center gap-1 text-gray-500 dark:text-gray-400">
              <input type="checkbox" checked={udpTx} onChange={(e) => setUdpTx(e.target.checked)} />
              Enviar
            </label>
            <input
              value={udpIp}
              onChange={(e) => setUdpIp(e.target.value)}
              disabled={!udpTx}
              placeholder="IP destino (vacío = broadcast)"
              className="w-48 rounded border border-gray-300 bg-transparent px-1 py-0.5 font-mono disabled:opacity-40 dark:border-surface-border dark:text-gray-200"
            />
            <button
              onClick={() => void store.getState().udpStart(udpRx, udpTx, udpIp.trim() || undefined)}
              disabled={busy || (!udpRx && !udpTx)}
              className="rounded border border-gray-300 px-2 py-0.5 hover:bg-gray-100 disabled:opacity-40 dark:border-surface-border dark:text-gray-300 dark:hover:bg-surface-raised"
            >
              Iniciar puente
            </button>
            <span className="text-gray-400">Para GOOSE sobre WiFi o redes enrutadas (sin L2)</span>
          </>
        )}
      </div>

      {/* ── GoCBs ── */}
      <div className="max-h-[45%] min-h-24 overflow-auto border-b border-gray-200 dark:border-surface-border">
        {state.gocbs.length === 0 ? (
          <div className="p-4 text-center text-xs text-gray-400">
            Cargue un fichero SCL para listar sus GOOSE Control Blocks.
          </div>
        ) : (
          <table className="w-full border-collapse">
            <thead className="sticky top-0 bg-gray-50 text-left text-[10px] uppercase text-gray-500 dark:bg-surface-raised dark:text-gray-400">
              <tr>
                <th className="w-5 px-2 py-1" />
                <th className="px-2 py-1">GoCB</th>
                <th className="px-2 py-1">goID</th>
                <th className="px-2 py-1">DataSet</th>
                <th className="px-2 py-1">AppID</th>
                <th className="px-2 py-1">MAC</th>
                <th className="px-2 py-1">Estado</th>
                <th className="px-2 py-1" />
              </tr>
            </thead>
            <tbody>
              {state.gocbs.map((g) => (
                <GoCBRow key={g.index} gcb={g} iface={iface} onEditValue={openEdit} onContextMenu={handleCtxMenu} />
              ))}
            </tbody>
          </table>
        )}
      </div>

      {/* ── Mensajes ── */}
      <div className="flex items-center gap-2 border-b border-gray-200 px-2 py-1 text-[11px] text-gray-500 dark:border-surface-border dark:text-gray-400">
        <span>Mensajes GOOSE ({messages.length})</span>
        <div className="flex-1" />
        <button
          onClick={() => store.getState().clearMessages()}
          className="rounded border border-gray-300 px-2 py-0.5 hover:bg-gray-100 dark:border-surface-border dark:hover:bg-surface-raised"
        >
          Limpiar
        </button>
      </div>
      <div className="min-h-0 flex-1 overflow-auto">
        <table className="w-full border-collapse text-[11px]">
          <thead className="sticky top-0 bg-gray-50 text-left text-[10px] uppercase text-gray-500 dark:bg-surface-raised dark:text-gray-400">
            <tr>
              <th className="px-2 py-1">Hora</th>
              <th className="px-2 py-1">Origen</th>
              <th className="px-2 py-1">GoCB</th>
              <th className="px-2 py-1">goID</th>
              <th className="px-2 py-1">stNum</th>
              <th className="px-2 py-1">sqNum</th>
              <th className="px-2 py-1">Valores</th>
            </tr>
          </thead>
          <tbody>
            {messages.map((m, i) => (
              <MsgRow key={i} m={m} />
            ))}
          </tbody>
        </table>
      </div>

      {/* ── Menú contextual GoCB ── */}
      {ctxMenu && (
        <ContextMenu
          x={ctxMenu.x}
          y={ctxMenu.y}
          items={buildGoCBMenuItems(ctxMenu.gcb, quickSet, openEdit)}
          onClose={() => setCtxMenu(null)}
        />
      )}

      {/* ── Diálogo edición valor GOOSE ── */}
      {editTarget && (
        <Modal
          title={`Publicar nuevo valor — [${editTarget.dataIndex}] ${editTarget.name}`}
          onClose={() => setEditTarget(null)}
          width={380}
        >
          <div className="space-y-3 text-xs">
            <div className="text-gray-500 dark:text-gray-400">
              Tipo: <span className="font-medium text-gray-700 dark:text-gray-200">{editTarget.type}</span>
              {editTarget.current != null && (
                <> &nbsp;·&nbsp; Actual: <span className="font-medium text-accent">{editTarget.current}</span></>
              )}
            </div>
            <input
              autoFocus
              value={editVal}
              onChange={(e) => setEditVal(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && submitEdit()}
              placeholder="Nuevo valor…"
              className="w-full rounded border border-gray-300 bg-transparent px-2 py-1.5 font-mono text-xs outline-none focus:border-accent dark:border-surface-border dark:bg-surface dark:text-gray-200"
            />
            <div className="flex justify-end gap-2 pt-1">
              <button
                onClick={() => setEditTarget(null)}
                className="rounded border border-gray-300 px-3 py-1.5 dark:border-surface-border dark:text-gray-300"
              >
                Cancelar
              </button>
              <button
                onClick={submitEdit}
                disabled={!editVal.trim()}
                className="rounded bg-accent px-4 py-1.5 font-medium text-white hover:bg-accent-hover disabled:opacity-50"
              >
                Publicar
              </button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  );
}
