import { create } from 'zustand';
import { getApi } from '../api/client';
import type { ModelDto } from '../api/types';
import { log } from './log';

interface ModelState {
  model: ModelDto | null;
  loading: boolean;
  /** Últimos valores por referencia (actualizados por WS valueChanged). */
  values: Map<string, { value: string; type: string; ts: number }>;

  fetch: () => Promise<void>;
  clear: () => void;
  applyChanges: (changes: { ref: string; value: string; type: string; ts: number }[]) => void;
}

export const useModelStore = create<ModelState>((set, get) => ({
  model: null,
  loading: false,
  values: new Map(),

  fetch: async () => {
    set({ loading: true });
    try {
      const model = await getApi().model();
      set({ model });
      log.info(`Modelo cargado: ${model.iedName} (${model.logicalDevices.length} LDs)`);
    } catch (e) {
      log.error(`Error cargando modelo: ${(e as Error).message}`);
    } finally {
      set({ loading: false });
    }
  },

  clear: () => set({ model: null, values: new Map() }),

  applyChanges: (changes) => {
    const values = new Map(get().values);
    for (const c of changes) {
      values.set(c.ref, { value: c.value, type: c.type, ts: c.ts });
    }
    set({ values });
  },
}));
