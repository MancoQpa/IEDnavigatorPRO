import { create } from 'zustand';
import { getApi } from '../api/client';
import type { RcbInfo, ReportEvent } from '../api/types';
import { log } from './log';

const MAX_REPORTS = 500;

interface ReportsState {
  rcbs: RcbInfo[];
  reports: ReportEvent[];
  loading: boolean;

  fetch: (refresh?: boolean, type?: 'URCB' | 'BRCB') => Promise<void>;
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

  fetch: async (refresh = false, type?: 'URCB' | 'BRCB') => {
    set({ loading: true });
    try {
      const { rcbs } = await getApi().rcbs(refresh, type);
      if (type) {
        // Merge: reemplazar solo los del tipo solicitado, conservar los otros
        const other = get().rcbs.filter((r) => r.type !== type);
        set({ rcbs: [...other, ...rcbs] });
      } else {
        set({ rcbs });
      }
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
      // Actualizar solo el RCB afectado localmente (evita 24 getRcbValues)
      set({ rcbs: get().rcbs.map((r) => r.ref === ref ? { ...r, rptEna: true, enabledByBridge: true } : r) });
    } catch (e) {
      log.error(`Error habilitando RCB ${ref}: ${(e as Error).message}`);
    }
  },

  disable: async (ref) => {
    try {
      await getApi().disableRcb(ref);
      log.info(`Reporting deshabilitado: ${ref}`);
      set({ rcbs: get().rcbs.map((r) => r.ref === ref ? { ...r, rptEna: false, enabledByBridge: false } : r) });
    } catch (e) {
      log.error(`Error deshabilitando RCB ${ref}: ${(e as Error).message}`);
    }
  },

  addReport: (r) =>
    set((s) => ({ reports: [r, ...s.reports].slice(0, MAX_REPORTS) })),

  clearReports: () => set({ reports: [] }),

  reset: () => set({ rcbs: [], reports: [], loading: false }),
}));
