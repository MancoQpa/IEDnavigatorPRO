import { useState } from 'react';
import { obtenerCid, guardarCid } from '../api/cid';
import { getApi } from '../api/client';
import { pickSclFile } from '../api/pickFile';
import { useConnectionStore } from '../stores/connection';
import { log } from '../stores/log';
import { useMonitorStore } from '../stores/monitor';
import { useServerStore } from '../stores/server';

const labelCls = 'w-16 shrink-0 text-gray-500 dark:text-gray-400';
const inputCls =
  'min-w-0 flex-1 rounded border border-gray-300 bg-white px-2 py-1 text-xs outline-none focus:border-accent disabled:opacity-50 dark:border-surface-border dark:bg-surface dark:text-gray-200';

function CardFrame({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="m-1.5 rounded-md border border-gray-200 bg-white p-2 dark:border-surface-border dark:bg-surface">
      <div className="mb-2 text-[11px] font-semibold uppercase tracking-wide text-gray-500 dark:text-gray-400">
        {title}
      </div>
      <div className="flex flex-col gap-2 text-xs">{children}</div>
    </div>
  );
}

/** Tarjeta «Cliente IEC 61850»: conexión MMS, como en la GUI clásica. */
export function ClientCard() {
  const { client, connecting, connect, disconnect } = useConnectionStore();
  const watchCount = useMonitorStore((s) => s.items.length);
  const clearWatch = useMonitorStore((s) => s.clear);
  const intervalMs = useMonitorStore((s) => s.intervalMs);
  const setIntervalMs = useMonitorStore((s) => s.setInterval);

  const [host, setHost] = useState(localStorage.getItem('lastHost') ?? '127.0.0.1');
  const [port, setPort] = useState(localStorage.getItem('lastPort') ?? '102');
  const [timeout, setTimeoutS] = useState(localStorage.getItem('lastTimeout') ?? '10');
  const [interval, setIntervalText] = useState(String(intervalMs));

  const onConnect = async () => {
    localStorage.setItem('lastHost', host);
    localStorage.setItem('lastPort', port);
    localStorage.setItem('lastTimeout', timeout);
    try {
      const t = Math.min(60, Math.max(5, Number(timeout) || 10));
      await connect(host, Number(port), t * 1000);
    } catch {
      /* ya logueado */
    }
  };

  const applyInterval = () => {
    const ms = Math.min(60_000, Math.max(500, Number(interval) || 1000));
    setIntervalText(String(ms));
    setIntervalMs(ms);
  };

  return (
    <CardFrame title="Cliente IEC 61850">
      <label className="flex items-center gap-2">
        <span className={labelCls}>Host:</span>
        <input
          value={host}
          onChange={(e) => setHost(e.target.value)}
          disabled={client.connected}
          className={inputCls}
        />
      </label>
      <label className="flex items-center gap-2">
        <span className={labelCls}>Puerto:</span>
        <input
          value={port}
          onChange={(e) => setPort(e.target.value.replace(/\D/g, ''))}
          disabled={client.connected}
          className={inputCls}
        />
      </label>
      <label className="flex items-center gap-2" title="Tiempo máximo de espera al conectar (5-60 s)">
        <span className={labelCls}>Timeout:</span>
        <input
          value={timeout}
          onChange={(e) => setTimeoutS(e.target.value.replace(/\D/g, ''))}
          disabled={client.connected}
          className={inputCls}
        />
        <span className="shrink-0 text-[10px] text-gray-400">s (5-60)</span>
      </label>
      {client.connected ? (
        <button
          onClick={() => void disconnect()}
          className="w-full rounded bg-red-600 px-3 py-1.5 font-medium text-white hover:bg-red-500"
        >
          Desconectar
        </button>
      ) : (
        <button
          onClick={() => void onConnect()}
          disabled={connecting || !host || !port}
          className="w-full rounded bg-accent px-3 py-1.5 font-medium text-white hover:bg-accent-hover disabled:opacity-50"
        >
          {connecting ? 'Conectando…' : 'Conectar'}
        </button>
      )}
      <label
        className="flex items-center gap-2"
        title="Intervalo de sondeo del monitor (500-60000 ms)"
      >
        <span className={labelCls}>Intervalo:</span>
        <input
          value={interval}
          onChange={(e) => setIntervalText(e.target.value.replace(/\D/g, ''))}
          onBlur={applyInterval}
          onKeyDown={(e) => e.key === 'Enter' && applyInterval()}
          className={inputCls}
        />
        <span className="shrink-0 text-[10px] text-gray-400">ms</span>
      </label>
      <div className="flex items-center gap-2 text-[11px]">
        <span className="text-accent dark:text-accent-hover">Watchlist: {watchCount} nodos</span>
        <button
          onClick={() => void clearWatch()}
          disabled={watchCount === 0}
          className="rounded border border-gray-300 px-2 py-0.5 hover:bg-gray-100 disabled:opacity-40 dark:border-surface-border dark:text-gray-300 dark:hover:bg-surface-raised"
        >
          Limpiar
        </button>
      </div>
      <div className="flex items-center gap-2">
        <button
          onClick={() => void obtenerCid(client.connected)}
          disabled={!client.connected}
          title="Descarga el fichero SCL/CID expuesto por el IED (servicios de fichero MMS)"
          className="flex-1 rounded border border-gray-300 px-2 py-1 hover:bg-gray-100 disabled:opacity-40 dark:border-surface-border dark:text-gray-300 dark:hover:bg-surface-raised"
        >
          Obtener CID
        </button>
        <button
          onClick={guardarCid}
          title="Guarda el CID previamente obtenido del IED"
          className="flex-1 rounded border border-gray-300 px-2 py-1 hover:bg-gray-100 disabled:opacity-40 dark:border-surface-border dark:text-gray-300 dark:hover:bg-surface-raised"
        >
          Guardar CID
        </button>
      </div>
    </CardFrame>
  );
}

/** Tarjeta «Simulador IED»: carga SCL y arranque del servidor, como en la GUI clásica. */
export function ServerCard() {
  const status = useServerStore((s) => s.status);
  const sclPath = useServerStore((s) => s.sclPath);
  const ieds = useServerStore((s) => s.ieds);
  const iedIndex = useServerStore((s) => s.iedIndex);
  const busy = useServerStore((s) => s.busy);
  const store = useServerStore;

  const [port, setPort] = useState('102');
  const [portMsg, setPortMsg] = useState<{ ok: boolean; text: string } | null>(null);

  const onCheckPort = async () => {
    try {
      const res = await getApi().portCheck(Number(port));
      if (res.free) {
        setPortMsg({ ok: true, text: `Puerto ${res.port} libre ✓` });
        log.info(`Puerto ${res.port} disponible para el servidor`);
      } else {
        setPortMsg({ ok: false, text: `Puerto ${res.port} ocupado` });
        log.warn(`Puerto ${res.port} ocupado: ${res.error ?? 'en uso'}. Pruebe 49151 o ejecute como administrador.`);
      }
    } catch (e) {
      setPortMsg({ ok: false, text: (e as Error).message });
    }
  };

  const onStart = async () => {
    setPortMsg(null);
    await store.getState().start(Number(port));
    // Auto-conectar el cliente interno para poblar pestañas (paso 3 de la GUI clásica).
    // Con reintentos: el servidor puede tardar unos instantes en aceptar conexiones.
    if (useServerStore.getState().status.running && !useConnectionStore.getState().client.connected) {
      for (let attempt = 1; attempt <= 6; attempt++) {
        await new Promise((r) => setTimeout(r, attempt === 1 ? 400 : 800));
        try {
          await useConnectionStore.getState().connect('127.0.0.1', Number(port));
          break;
        } catch {
          if (attempt === 6) {
            log.warn(
              'No se pudo conectar el cliente interno al servidor simulado. ' +
                'Use el panel Cliente para conectar manualmente a 127.0.0.1:' + port,
            );
          }
        }
      }
    }
  };

  const onReleasePort = async () => {
    if (
      !window.confirm(
        `Se terminará el proceso que está usando el puerto ${port}.\n¿Continuar?`,
      )
    ) {
      return;
    }
    try {
      const res = await getApi().portRelease(Number(port));
      if (res.released) {
        setPortMsg({ ok: true, text: `Puerto ${port} liberado (PID ${res.pid})` });
        log.info(`Puerto ${port} liberado: proceso ${res.pid} terminado`);
      } else {
        setPortMsg({ ok: false, text: res.message ?? 'No se pudo liberar' });
        log.warn(`Liberar puerto ${port}: ${res.message ?? 'sin proceso en escucha'}`);
      }
    } catch (e) {
      setPortMsg({ ok: false, text: (e as Error).message });
    }
  };

  const onStop = async () => {
    if (useConnectionStore.getState().client.connected) {
      await useConnectionStore.getState().disconnect();
    }
    await store.getState().stop();
  };

  const onPickScl = async () => {
    const p = await pickSclFile('Seleccionar SCL/ICD/CID');
    if (!p) return;
    store.getState().setSclPath(p);
    await store.getState().parse();
    // Con un único IED, cargar directamente
    if (store.getState().ieds.length === 1) await store.getState().load();
  };

  const fileName = sclPath ? sclPath.replace(/^.*[\\/]/, '') : null;

  return (
    <CardFrame title="Simulador IED (Servidor IEC 61850)">
      <button
        onClick={() => void onPickScl()}
        disabled={busy || status.running}
        className="w-full rounded border border-gray-300 px-3 py-1.5 font-medium hover:bg-gray-100 disabled:opacity-40 dark:border-surface-border dark:text-gray-200 dark:hover:bg-surface-raised"
      >
        Cargar SCL/ICD/CID…
      </button>
      {fileName && (
        <div className="truncate font-mono text-[10px] text-gray-400" title={sclPath}>
          {fileName}
        </div>
      )}
      {ieds.length > 1 && (
        <div className="flex items-center gap-2">
          <select
            value={iedIndex}
            onChange={(e) => store.getState().setIedIndex(Number(e.target.value))}
            disabled={status.running}
            className={inputCls}
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
        </div>
      )}
      <label className="flex items-center gap-2">
        <span className={labelCls}>Puerto:</span>
        <input
          value={port}
          onChange={(e) => setPort(e.target.value.replace(/\D/g, ''))}
          disabled={status.running}
          className={inputCls}
        />
        <span className="shrink-0 text-[10px] text-gray-400">(102=MMS)</span>
      </label>
      <div className="flex items-center gap-2">
        <button
          onClick={() => void onCheckPort()}
          disabled={status.running || !port}
          title="Verifica si el puerto indicado esta libre, en uso o requiere permisos de administrador"
          className="rounded border border-gray-300 px-2 py-1 text-[rgb(0,80,160)] hover:bg-gray-100 disabled:opacity-40 dark:border-surface-border dark:text-sky-400 dark:hover:bg-surface-raised"
        >
          Verificar Puerto
        </button>
        <button
          onClick={() => void onReleasePort()}
          disabled={status.running || !port}
          title="Termina el proceso que esta usando el puerto indicado"
          className="rounded border border-gray-300 px-2 py-1 text-[rgb(160,30,0)] hover:bg-gray-100 disabled:opacity-40 dark:border-surface-border dark:text-red-400 dark:hover:bg-surface-raised"
        >
          Liberar Puerto
        </button>
      </div>
      {portMsg && (
        <div className={`text-[10px] ${portMsg.ok ? 'text-green-500' : 'text-red-400'}`}>
          {portMsg.text}
        </div>
      )}
      {status.running ? (
        <button
          onClick={() => void onStop()}
          disabled={busy}
          className="w-full rounded bg-red-600 px-3 py-1.5 font-medium text-white hover:bg-red-500 disabled:opacity-40"
        >
          Detener Simulación
        </button>
      ) : (
        <button
          onClick={() => void onStart()}
          disabled={busy || !status.modelLoaded}
          className="w-full rounded bg-green-600 px-3 py-1.5 font-medium text-white hover:bg-green-700 disabled:opacity-40"
        >
          Iniciar Simulación
        </button>
      )}
      <div className="text-[10px] text-gray-400">
        1. Carga SCL/ICD&ensp;2. Inicia servidor&ensp;3. Conecta cliente
      </div>
    </CardFrame>
  );
}
