import { create } from 'zustand';
import { getApi } from '../api/client';
import { log } from './log';

export interface MonitorItem {
  /** Referencia del nodo (sin FC), p.ej. "LD0/GGIO1.AnIn1" */
  ref: string;
  fc: string;
}

interface MonitorState {
  items: MonitorItem[];
  intervalMs: number;

  add: (ref: string, fc: string) => void;
  remove: (ref: string, fc: string) => void;
  clear: () => void;
  /** Limpieza local sin tocar el bridge (al perder la conexión). */
  reset: () => void;
  setInterval: (ms: number) => void;
}

/** Sincroniza la watchlist con el bridge (formato "ref$FC"). */
async function pushWatchlist(items: MonitorItem[], intervalMs: number) {
  try {
    await getApi().setWatchlist(
      items.map((i) => `${i.ref}$${i.fc}`),
      intervalMs,
    );
  } catch (e) {
    log.error(`Error actualizando watchlist: ${(e as Error).message}`);
  }
}

export const useMonitorStore = create<MonitorState>((set, get) => ({
  items: [],
  intervalMs: 1000,

  add: (ref, fc) => {
    const { items, intervalMs } = get();
    if (items.some((i) => i.ref === ref && i.fc === fc)) return;
    const next = [...items, { ref, fc }];
    set({ items: next });
    void pushWatchlist(next, intervalMs);
  },

  remove: (ref, fc) => {
    const { items, intervalMs } = get();
    const next = items.filter((i) => !(i.ref === ref && i.fc === fc));
    set({ items: next });
    void pushWatchlist(next, intervalMs);
  },

  clear: () => {
    set({ items: [] });
    void pushWatchlist([], get().intervalMs);
  },

  reset: () => set({ items: [] }),

  setInterval: (ms) => {
    const clamped = Math.max(100, Math.min(60_000, ms));
    set({ intervalMs: clamped });
    void pushWatchlist(get().items, clamped);
  },
}));
