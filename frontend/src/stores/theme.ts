import { create } from 'zustand';

export type Theme = 'dark' | 'light';

interface ThemeState {
  theme: Theme;
  toggle: () => void;
  set: (t: Theme) => void;
}

function apply(theme: Theme) {
  document.documentElement.classList.toggle('dark', theme === 'dark');
  localStorage.setItem('theme', theme);
}

const initial = (localStorage.getItem('theme') as Theme) || 'dark';
apply(initial);

export const useThemeStore = create<ThemeState>((set) => ({
  theme: initial,
  toggle: () =>
    set((s) => {
      const next: Theme = s.theme === 'dark' ? 'light' : 'dark';
      apply(next);
      return { theme: next };
    }),
  set: (t) => {
    apply(t);
    set({ theme: t });
  },
}));
