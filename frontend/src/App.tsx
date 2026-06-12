import { useEffect, useState } from 'react';
import { BridgeApi, getApi, resolveBridgeInfo, setApi } from './api/client';
import type { GooseMessageEvent, ReportEvent, SvMessage, ValueChange, WsEnvelope } from './api/types';
import { BridgeSocket, setSocket } from './api/ws';
import StatusBar from './components/StatusBar';
import Toolbar from './components/Toolbar';
import DialogHost from './dialogs/DialogHost';
import DockManager from './layout/DockManager';
import { useConnectionStore } from './stores/connection';
import { useGooseStore } from './stores/goose';
import { log } from './stores/log';
import { useModelStore } from './stores/model';
import { useMonitorStore } from './stores/monitor';
import { useReportsStore } from './stores/reports';
import { useServerStore } from './stores/server';
import { useSvStore } from './stores/sv';

type BootState = 'booting' | 'ready' | 'failed';

export default function App() {
  const [boot, setBoot] = useState<BootState>('booting');
  const [bootError, setBootError] = useState('');

  useEffect(() => {
    let socket: BridgeSocket | null = null;
    let cancelled = false;

    (async () => {
      try {
        const info = await resolveBridgeInfo();
        if (cancelled) return;
        setApi(new BridgeApi(info));
        useConnectionStore.getState().setBridgeReady(info.port);

        const sysInfo = await getApi().systemInfo();
        useConnectionStore.getState().setSystemInfo(sysInfo);
        log.info(`Backend listo en 127.0.0.1:${info.port} — Java ${sysInfo.javaVersion}`);
        if (!sysInfo.npcapAvailable) {
          log.warn('Npcap no detectado: funciones GOOSE/SV no disponibles');
        }

        socket = new BridgeSocket(getApi().wsUrl);
        setSocket(socket);
        socket.onState((s) => useConnectionStore.getState().setWsState(s));
        socket.on('client.valueChanged', (env: WsEnvelope) => {
          const payload = env.payload as { changes: ValueChange[] };
          useModelStore.getState().applyChanges(payload.changes);
        });
        socket.on('client.report', (env: WsEnvelope) => {
          useReportsStore.getState().addReport(env.payload as ReportEvent);
        });
        socket.on('client.connectionClosed', (env: WsEnvelope) => {
          const { reason } = env.payload as { reason: string };
          log.warn(`Conexión con el IED cerrada: ${reason}`);
          useConnectionStore.getState().setClientStatus({ connected: false });
          useModelStore.getState().clear();
          useMonitorStore.getState().reset();
          useReportsStore.getState().reset();
        });
        socket.on('client.error', (env: WsEnvelope) => {
          const { ref, error } = env.payload as { ref: string; error: string };
          log.error(`Error en ${ref}: ${error}`);
        });
        socket.on('server.started', (env: WsEnvelope) => {
          const { port } = env.payload as { port: number };
          log.info(`Servidor IED en marcha (puerto ${port})`);
          void useServerStore.getState().refreshStatus();
        });
        socket.on('server.stopped', () => {
          log.info('Servidor IED detenido');
          void useServerStore.getState().refreshStatus();
        });
        socket.on('server.clientWrite', (env: WsEnvelope) => {
          const { ref, value } = env.payload as { ref: string; value: string };
          log.info(`Escritura de cliente MMS: ${ref} = ${value}`);
        });
        socket.on('server.error', (env: WsEnvelope) => {
          const { message } = env.payload as { message: string };
          log.error(`Servidor IED: ${message}`);
        });
        socket.on('server.log', (env: WsEnvelope) => {
          const { message } = env.payload as { message: string };
          log.info(`Servidor IED: ${message}`);
        });
        socket.on('goose.message', (env: WsEnvelope) => {
          const msg = env.payload as GooseMessageEvent;
          useGooseStore.getState().addMessage({ ...msg, ts: env.ts });
        });
        socket.on('goose.log', (env: WsEnvelope) => {
          const { message } = env.payload as { message: string };
          log.info(`GOOSE: ${message}`);
        });
        socket.on('sv.batch', (env: WsEnvelope) => {
          const { messages } = env.payload as { messages: SvMessage[] };
          useSvStore.getState().addBatch(messages);
        });
        socket.on('sv.log', (env: WsEnvelope) => {
          const { message } = env.payload as { message: string };
          log.info(`SV: ${message}`);
        });
        socket.connect();

        // Reanudar estado si el bridge ya tenía una sesión (p.ej. tras recargar la UI)
        const status = await getApi().status();
        if (status.connected) {
          useConnectionStore.getState().setClientStatus(status);
          await useModelStore.getState().fetch();
        }

        setBoot('ready');
      } catch (e) {
        if (!cancelled) {
          setBootError((e as Error).message);
          setBoot('failed');
        }
      }
    })();

    return () => {
      cancelled = true;
      socket?.close();
    };
  }, []);

  if (boot === 'booting') {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-3 bg-surface text-gray-300">
        <div className="text-2xl font-light">
          <span className="font-semibold text-accent">IED</span>Navigator{' '}
          <span className="text-accent">PRO</span>
        </div>
        <div className="text-xs text-gray-500">Iniciando backend Java…</div>
      </div>
    );
  }

  if (boot === 'failed') {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-3 bg-surface p-8 text-center text-gray-300">
        <div className="text-lg text-red-400">No se pudo iniciar el backend</div>
        <div className="max-w-md select-text text-xs text-gray-400">{bootError}</div>
        <button
          onClick={() => location.reload()}
          className="mt-2 rounded bg-accent px-4 py-1.5 text-xs font-medium text-white hover:bg-accent-hover"
        >
          Reintentar
        </button>
      </div>
    );
  }

  return (
    <div className="flex h-full flex-col">
      <Toolbar />
      <div className="min-h-0 flex-1">
        <DockManager />
      </div>
      <StatusBar />
      <DialogHost />
    </div>
  );
}
