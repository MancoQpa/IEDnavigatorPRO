import { create } from 'zustand';
import { getApi } from '../api/client';
import type { RcbInfo, ReportEvent } from '../api/types';
import { log } from './log';

const MAX_REPORTS = 500;

interface ReportsState {
  rcbs: RcbInfo[];
  reports: ReportEvent[];
  loading: boolean;

  fetch: (refresh?: boolean) => Promise<void>;
  enable: (ref: string) => Promise<void>;
  disable: (ref: string) => Promise<void>;
  addReport: (r: ReportEvent) => void;
  clearReports: () => void;
  /** Limpieza local al perder la conexión. */
  reset: () => void;
}

export const useReportsStore = create<ReportsState>((set, get) => ({
  rcbs: [],
  reports: [],
  loading: false,

  fetch: async (refresh = false) => {
    set({ loading: true });
    try {
      const { rcbs } = await getApi().rcbs(refresh);
      set({ rcbs });
    } catch (e) {
      log.error(`Error listando RCBs: ${(e as Error).message}`);
    } finally {
      set({ loading: false });
    }
  },

  enable: async (ref) => {
    try {
      await getApi().enableRcb(ref);
      log.info(`Reporting habilitado: ${ref}`);
      await get().fetch(true);
    } catch (e) {
      log.error(`Error habilitando RCB ${ref}: ${(e as Error).message}`);
    }
  },

  disable: async (ref) => {
    try {
      await getApi().disableRcb(ref);
      log.info(`Reporting deshabilitado: ${ref}`);
      await get().fetch(true);
    } catch (e) {
      log.error(`Error deshabilitando RCB ${ref}: ${(e as Error).message}`);
    }
  },

  addReport: (r) =>
    set((s) => ({ reports: [r, ...s.reports].slice(0, MAX_REPORTS) })),

  clearReports: () => set({ reports: [] }),

  reset: () => set({ rcbs: [], reports: [], loading: false }),
}));
