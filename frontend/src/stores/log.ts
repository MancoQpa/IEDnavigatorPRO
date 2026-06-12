import { create } from 'zustand';

export type LogLevel = 'info' | 'warn' | 'error';

export interface LogEntry {
  ts: number;
  level: LogLevel;
  message: string;
}

const MAX_ENTRIES = 2000;

interface LogState {
  entries: LogEntry[];
  add: (message: string, level?: LogLevel) => void;
  clear: () => void;
}

export const useLogStore = create<LogState>((set) => ({
  entries: [],
  add: (message, level = 'info') =>
    set((s) => {
      const entries = [...s.entries, { ts: Date.now(), level, message }];
      if (entries.length > MAX_ENTRIES) entries.splice(0, entries.length - MAX_ENTRIES);
      return { entries };
    }),
  clear: () => set({ entries: [] }),
}));

export const log = {
  info: (m: string) => useLogStore.getState().add(m, 'info'),
  warn: (m: string) => useLogStore.getState().add(m, 'warn'),
  error: (m: string) => useLogStore.getState().add(m, 'error'),
};
