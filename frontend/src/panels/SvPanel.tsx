import { useEffect, useMemo, useState } from 'react';
import { useConnectionStore } from '../stores/connection';
import { useGooseStore } from '../stores/goose';
import { useSvStore } from '../stores/sv';

/** Sparkline SVG simple de los últimos valores de un canal. */
function Sparkline({ values }: { values: number[] }) {
  if (values.length < 2) return null;
  const w = 280;
  const h = 48;
  const min = Math.min(...values);
  const max = Math.max(...values);
  const range = max - min || 1;
  const pts = values
    .map((v, i) => `${(i / (values.length - 1)) * w},${h - ((v - min) / range) * (h - 4) - 2}`)
    .join(' ');
  return (
    <svg width={w} height={h} className="block">
      <polyline points={pts} fill="none" stroke="currentColor" strokeWidth="1.5" className="stroke-accent" />
    </svg>
  );
}

/** Sampled Values: suscripción nativa (libiec61850) con vista de muestras. */
export default function SvPanel() {
  const bridgeReady = useConnectionStore((s) => s.bridgeReady);
  const status = useSvStore((s) => s.status);
  const messages = useSvStore((s) => s.messages);
  const busy = useSvStore((s) => s.busy);
  const store = useSvStore;
  const net = useGooseStore((s) => s.net);

  const [iface, setIface] = useState('');
  const [appIdHex, setAppIdHex] = useState('4000');
  const [channel, setChannel] = useState(0);

  useEffect(() => {
    if (bridgeReady) {
      void store.getState().refresh();
      void useGooseStore.getState().fetchInterfaces();
    }
  }, [bridgeReady, store]);

  const latest = messages[0];
  const chartValues = useMemo(
    () =>
      messages
        .slice(0, 100)
        .reverse()
        .map((m) => m.samples[channel]?.value)
        .filter((v): v is number => v !== undefined),
    [messages, channel],
  );

  const subscribe = () => {
    const appId = parseInt(appIdHex, 16);
    if (Number.isNaN(appId)) return;
    void store.getState().subscribe(iface, appId);
  };

  return (
    <div className="flex h-full flex-col bg-white dark:bg-surface">
      {/* ── Controles ── */}
      <div className="flex flex-wrap items-center gap-2 border-b border-gray-200 p-1.5 text-xs dark:border-surface-border">
        <select
          value={iface}
          onChange={(e) => setIface(e.target.value)}
          disabled={status.running}
          className="max-w-64 rounded border border-gray-300 bg-transparent px-1 py-1 dark:border-surface-border dark:bg-surface dark:text-gray-200"
        >
          <option value="">— Interfaz de red —</option>
          {net?.interfaces.map((i) => (
            <option key={i.name} value={i.name}>
              {i.description ?? i.name}
            </option>
          ))}
        </select>
        <label className="flex items-center gap-1 text-gray-500">
          APPID 0x
          <input
            value={appIdHex}
            onChange={(e) => setAppIdHex(e.target.value)}
            disabled={status.running}
            className="w-16 rounded border border-gray-300 bg-transparent px-1 py-1 font-mono disabled:opacity-50 dark:border-surface-border dark:text-gray-200"
          />
        </label>
        {status.running ? (
          <button
            onClick={() => void store.getState().unsubscribe()}
            disabled={busy}
            className="rounded bg-red-600 px-3 py-1 font-medium text-white hover:bg-red-700 disabled:opacity-40"
          >
            Detener
          </button>
        ) : (
          <button
            onClick={subscribe}
            disabled={busy || !iface || !status.nativeAvailable}
            title={!status.nativeAvailable ? 'iec61850.dll no disponible' : undefined}
            className="rounded bg-green-600 px-3 py-1 font-medium text-white hover:bg-green-700 disabled:opacity-40"
          >
            Suscribir
          </button>
        )}
        <div className="flex-1" />
        {status.running && (
          <span className="text-[11px] text-gray-500 dark:text-gray-400">
            ASDUs: {status.asduCount ?? 0} · Muestras: {status.sampleCount ?? 0}
          </span>
        )}
        <button
          onClick={() => store.getState().clear()}
          className="rounded border border-gray-300 px-2 py-1 hover:bg-gray-100 dark:border-surface-border dark:text-gray-300 dark:hover:bg-surface-raised"
        >
          Limpiar
        </button>
      </div>

      {!status.nativeAvailable && (
        <div className="border-b border-amber-200 bg-amber-50 px-2 py-1 text-[11px] text-amber-700 dark:border-amber-900 dark:bg-amber-950/40 dark:text-amber-400">
          iec61850.dll no disponible: la captura de Sampled Values está deshabilitada.
        </div>
      )}

      {/* ── Tendencia ── */}
      {latest && latest.samples.length > 0 && (
        <div className="flex items-center gap-3 border-b border-gray-200 p-2 dark:border-surface-border">
          <select
            value={channel}
            onChange={(e) => setChannel(Number(e.target.value))}
            className="rounded border border-gray-300 bg-transparent px-1 py-1 text-xs dark:border-surface-border dark:bg-surface dark:text-gray-200"
          >
            {latest.samples.map((s) => (
              <option key={s.index} value={s.index}>
                {s.name || `Canal ${s.index}`}
              </option>
            ))}
          </select>
          <Sparkline values={chartValues} />
          <span className="font-mono text-sm font-medium text-accent dark:text-accent-hover">
            {latest.samples[channel]?.value.toFixed(3) ?? '—'}
          </span>
        </div>
      )}

      {/* ── Mensajes ── */}
      <div className="min-h-0 flex-1 overflow-auto">
        {messages.length === 0 ? (
          <div className="flex h-full items-center justify-center p-4 text-center text-xs text-gray-400">
            Suscríbase a un stream SV (IEC 61850-9-2) para ver las muestras.
          </div>
        ) : (
          <table className="w-full border-collapse text-[11px]">
            <thead className="sticky top-0 bg-gray-50 text-left text-[10px] uppercase text-gray-500 dark:bg-surface-raised dark:text-gray-400">
              <tr>
                <th className="px-2 py-1">svID</th>
                <th className="px-2 py-1">smpCnt</th>
                <th className="px-2 py-1">Muestras</th>
              </tr>
            </thead>
            <tbody>
              {messages.slice(0, 100).map((m, i) => (
                <tr
                  key={i}
                  className="border-b border-gray-100 hover:bg-gray-50 dark:border-surface-border dark:hover:bg-surface-raised"
                >
                  <td className="px-2 py-0.5 font-mono dark:text-gray-300">{m.svId}</td>
                  <td className="px-2 py-0.5 font-mono dark:text-gray-300">{m.smpCnt}</td>
                  <td className="px-2 py-0.5 font-mono text-gray-500 dark:text-gray-400">
                    {m.samples.map((s) => s.value.toFixed(2)).join(', ')}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
