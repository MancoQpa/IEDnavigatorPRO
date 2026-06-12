import { create } from 'zustand';
import { getApi } from '../api/client';
import type { SvMessage, SvStatus } from '../api/types';
import { log } from './log';

const MAX_MESSAGES = 200;

interface SvStoreState {
  status: SvStatus;
  messages: SvMessage[]; // más reciente primero
  busy: boolean;

  refresh: () => Promise<void>;
  subscribe: (interfaceId: string, appId: number) => Promise<void>;
  unsubscribe: () => Promise<void>;
  addBatch: (msgs: SvMessage[]) => void;
  clear: () => void;
}

export const useSvStore = create<SvStoreState>((set, get) => ({
  status: { running: false, nativeAvailable: false },
  messages: [],
  busy: false,

  refresh: async () => {
    try {
      set({ status: await getApi().svStatus() });
    } catch {
      /* bridge aún no listo */
    }
  },

  subscribe: async (interfaceId, appId) => {
    set({ busy: true });
    try {
      const status = await getApi().svSubscribe(interfaceId, appId);
      set({ status });
      log.info(`Captura SV iniciada en ${interfaceId} (APPID 0x${appId.toString(16)})`);
    } catch (e) {
      log.error(`Error suscribiendo SV: ${(e as Error).message}`);
      throw e;
    } finally {
      set({ busy: false });
    }
  },

  unsubscribe: async () => {
    set({ busy: true });
    try {
      set({ status: await getApi().svUnsubscribe() });
      log.info('Captura SV detenida');
    } finally {
      set({ busy: false });
    }
  },

  addBatch: (msgs) => {
    const messages = [...msgs.slice().reverse(), ...get().messages];
    if (messages.length > MAX_MESSAGES) messages.length = MAX_MESSAGES;
    set({ messages });
  },

  clear: () => set({ messages: [] }),
}));
