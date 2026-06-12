import { create } from 'zustand';
import { getApi } from '../api/client';
import type { GooseMessageEvent, GooseState, NetInterfaces } from '../api/types';
import { log } from './log';

const MAX_MESSAGES = 300;

interface GooseStoreState {
  net: NetInterfaces | null;
  state: GooseState;
  messages: GooseMessageEvent[];
  busy: boolean;

  fetchInterfaces: () => Promise<void>;
  refresh: () => Promise<void>;
  loadScl: (path: string, iedIndex: number) => Promise<void>;
  publish: (index: number, interfaceName: string) => Promise<void>;
  stop: (index: number) => Promise<void>;
  setValue: (index: number, dataIndex: number, value: string) => Promise<void>;
  subscribe: (interfaceName: string) => Promise<void>;
  unsubscribe: () => Promise<void>;
  udpStart: (receive: boolean, send: boolean, targetIp?: string) => Promise<void>;
  udpStop: () => Promise<void>;
  addMessage: (msg: GooseMessageEvent) => void;
  clearMessages: () => void;
}

const EMPTY: GooseState = { subscribing: false, gocbs: [] };

export const useGooseStore = create<GooseStoreState>((set, get) => ({
  net: null,
  state: EMPTY,
  messages: [],
  busy: false,

  fetchInterfaces: async () => {
    try {
      set({ net: await getApi().netInterfaces() });
    } catch {
      /* bridge aún no listo */
    }
  },

  refresh: async () => {
    try {
      set({ state: await getApi().gooseStatus() });
    } catch {
      /* bridge aún no listo */
    }
  },

  loadScl: async (path, iedIndex) => {
    set({ busy: true });
    try {
      const state = await getApi().gooseLoadScl(path, iedIndex);
      set({ state });
      log.info(`GOOSE: ${state.gocbs.length} GoCB(s) en ${state.iedName ?? '?'}`);
    } catch (e) {
      log.error(`Error cargando GoCBs: ${(e as Error).message}`);
      throw e;
    } finally {
      set({ busy: false });
    }
  },

  publish: async (index, interfaceName) => {
    set({ busy: true });
    try {
      const state = await getApi().goosePublish(index, interfaceName);
      set({ state });
      log.info(index < 0 ? 'Publicando todos los GoCBs' : `GoCB #${index} publicando`);
    } catch (e) {
      log.error(`Error publicando GOOSE: ${(e as Error).message}`);
      throw e;
    } finally {
      set({ busy: false });
    }
  },

  stop: async (index) => {
    set({ busy: true });
    try {
      set({ state: await getApi().gooseStop(index) });
      log.info(index < 0 ? 'Publicación GOOSE detenida' : `GoCB #${index} detenido`);
    } catch (e) {
      log.error(`Error deteniendo GOOSE: ${(e as Error).message}`);
    } finally {
      set({ busy: false });
    }
  },

  setValue: async (index, dataIndex, value) => {
    try {
      set({ state: await getApi().gooseSetValue(index, dataIndex, value) });
      log.info(`GoCB #${index} [${dataIndex}] = ${value}`);
    } catch (e) {
      log.error(`Error escribiendo valor GOOSE: ${(e as Error).message}`);
      throw e;
    }
  },

  subscribe: async (interfaceName) => {
    set({ busy: true });
    try {
      set({ state: await getApi().gooseSubscribe(interfaceName) });
      log.info(`Captura GOOSE iniciada en ${interfaceName}`);
    } catch (e) {
      log.error(`Error suscribiendo GOOSE: ${(e as Error).message}`);
      throw e;
    } finally {
      set({ busy: false });
    }
  },

  unsubscribe: async () => {
    set({ busy: true });
    try {
      set({ state: await getApi().gooseUnsubscribe() });
      log.info('Captura GOOSE detenida');
    } finally {
      set({ busy: false });
    }
  },

  udpStart: async (receive, send, targetIp) => {
    set({ busy: true });
    try {
      set({ state: await getApi().gooseUdpStart(receive, send, targetIp) });
      log.info(
        `Puente GOOSE-UDP activo (${[receive && 'recepción', send && 'envío'].filter(Boolean).join(' + ')})`,
      );
    } catch (e) {
      log.error(`Error iniciando puente GOOSE-UDP: ${(e as Error).message}`);
      throw e;
    } finally {
      set({ busy: false });
    }
  },

  udpStop: async () => {
    set({ busy: true });
    try {
      set({ state: await getApi().gooseUdpStop() });
      log.info('Puente GOOSE-UDP detenido');
    } finally {
      set({ busy: false });
    }
  },

  addMessage: (msg) => {
    const messages = [msg, ...get().messages];
    if (messages.length > MAX_MESSAGES) messages.length = MAX_MESSAGES;
    set({ messages });
  },

  clearMessages: () => set({ messages: [] }),
}));
