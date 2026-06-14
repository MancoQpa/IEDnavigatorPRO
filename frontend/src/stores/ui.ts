import { create } from 'zustand';

export type AppMode = 'cliente' | 'servidor';

interface UiState {
  mode: AppMode;
  setMode: (m: AppMode) => void;
  /** Ref para navegar/filtrar en el árbol de modelo (vacío = sin filtro externo). */
  treeSearch: string;
  setTreeSearch: (ref: string) => void;
}

/** Modo de trabajo (Servidor/Cliente), como en la GUI clásica. */
export const useUiStore = create<UiState>((set) => ({
  mode: (localStorage.getItem('iednav.mode') as AppMode) ?? 'cliente',
  setMode: (mode) => {
    localStorage.setItem('iednav.mode', mode);
    set({ mode });
  },
  treeSearch: '',
  setTreeSearch: (treeSearch) => set({ treeSearch }),
}));
