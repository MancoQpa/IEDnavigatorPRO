import { create } from 'zustand';
import { getApi } from '../api/client';
import type { ClientStatus, SystemInfo } from '../api/types';
import type { WsState } from '../api/ws';
import { log } from './log';
import { useModelStore } from './model';
import { useMonitorStore } from './monitor';
import { useReportsStore } from './reports';

interface ConnectionState {
  bridgeReady: boolean;
  bridgePort: number | null;
  wsState: WsState;
  systemInfo: SystemInfo | null;
  client: ClientStatus;
  connecting: boolean;

  setBridgeReady: (port: number) => void;
  setWsState: (s: WsState) => void;
  setSystemInfo: (info: SystemInfo) => void;
  setClientStatus: (c: ClientStatus) => void;
  connect: (host: string, port: number, timeoutMs?: number) => Promise<void>;
  disconnect: () => Promise<void>;
}

export const useConnectionStore = create<ConnectionState>((set, get) => ({
  bridgeReady: false,
  bridgePort: null,
  wsState: 'closed',
  systemInfo: null,
  client: { connected: false },
  connecting: false,

  setBridgeReady: (port) => set({ bridgeReady: true, bridgePort: port }),
  setWsState: (wsState) => set({ wsState }),
  setSystemInfo: (systemInfo) => set({ systemInfo }),
  setClientStatus: (client) => set({ client }),

  connect: async (host, port, timeoutMs) => {
    if (get().connecting) return;
    set({ connecting: true });
    log.info(`Conectando a ${host}:${port}...`);
    try {
      const status = await getApi().connect(host, port, timeoutMs);
      set({ client: status });
      log.info(`Conectado a ${host}:${port} (IED: ${status.iedName ?? '?'})`);
      await useModelStore.getState().fetch();
    } catch (e) {
      log.error(`Error de conexión: ${(e as Error).message}`);
      throw e;
    } finally {
      set({ connecting: false });
    }
  },

  disconnect: async () => {
    try {
      await getApi().disconnect();
    } catch (e) {
      log.warn(`Error al desconectar: ${(e as Error).message}`);
    }
    set({ client: { connected: false } });
    useModelStore.getState().clear();
    useMonitorStore.getState().reset();
    useReportsStore.getState().reset();
    log.info('Desconectado');
  },
}));
