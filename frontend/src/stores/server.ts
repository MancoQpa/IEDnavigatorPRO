import { create } from 'zustand';
import { getApi } from '../api/client';
import type { ModelDto, ServerStatus } from '../api/types';
import { log } from './log';

interface ServerState {
  status: ServerStatus;
  sclPath: string;
  ieds: string[];
  iedIndex: number;
  model: ModelDto | null;
  busy: boolean;

  setSclPath: (p: string) => void;
  setIedIndex: (i: number) => void;
  setStatus: (s: ServerStatus) => void;
  parse: () => Promise<void>;
  load: () => Promise<void>;
  start: (port: number) => Promise<void>;
  stop: () => Promise<void>;
  refreshStatus: () => Promise<void>;
  setValue: (ref: string, value: string) => Promise<void>;
}

export const useServerStore = create<ServerState>((set, get) => ({
  status: { running: false, modelLoaded: false },
  sclPath: '',
  ieds: [],
  iedIndex: 0,
  model: null,
  busy: false,

  setSclPath: (sclPath) => set({ sclPath, ieds: [], iedIndex: 0 }),
  setIedIndex: (iedIndex) => set({ iedIndex }),
  setStatus: (status) => set({ status }),

  parse: async () => {
    const { sclPath } = get();
    set({ busy: true });
    try {
      const r = await getApi().serverParseScl(sclPath);
      set({ ieds: r.ieds, iedIndex: 0, sclPath: r.path });
      log.info(`SCL analizado: ${r.ieds.length} IED(s) — ${r.ieds.join(', ')}`);
    } catch (e) {
      log.error(`Error analizando SCL: ${(e as Error).message}`);
      throw e;
    } finally {
      set({ busy: false });
    }
  },

  load: async () => {
    const { sclPath, iedIndex, ieds } = get();
    set({ busy: true });
    try {
      const status = await getApi().serverLoad(sclPath, iedIndex);
      const model = await getApi().serverModel();
      set({ status, model });
      log.info(`Modelo cargado: ${ieds[iedIndex] ?? '?'} (${sclPath})`);
    } catch (e) {
      log.error(`Error cargando SCL: ${(e as Error).message}`);
      throw e;
    } finally {
      set({ busy: false });
    }
  },

  start: async (port) => {
    set({ busy: true });
    try {
      const status = await getApi().serverStart(port);
      set({ status });
      log.info(`Servidor IED arrancado en puerto ${status.port}`);
    } catch (e) {
      log.error(`Error arrancando servidor: ${(e as Error).message}`);
      throw e;
    } finally {
      set({ busy: false });
    }
  },

  stop: async () => {
    set({ busy: true });
    try {
      const status = await getApi().serverStop();
      set({ status });
      log.info('Servidor IED detenido');
    } catch (e) {
      log.error(`Error deteniendo servidor: ${(e as Error).message}`);
    } finally {
      set({ busy: false });
    }
  },

  refreshStatus: async () => {
    try {
      const status = await getApi().serverStatus();
      set({ status });
      if (status.modelLoaded && !get().model) {
        set({ model: await getApi().serverModel() });
      }
    } catch {
      /* bridge aún no listo */
    }
  },

  setValue: async (ref, value) => {
    try {
      await getApi().serverSetValue(ref, value);
      log.info(`Servidor: ${ref} = ${value}`);
      set({ model: await getApi().serverModel() });
    } catch (e) {
      log.error(`Error escribiendo ${ref}: ${(e as Error).message}`);
      throw e;
    }
  },
}));
