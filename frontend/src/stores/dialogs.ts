import { create } from 'zustand';
import type { ModelNodeDto } from '../api/types';

export type DialogKind = 'control' | 'cancelSbo' | 'write' | 'serverWrite' | 'dictionary' | 'legend' | 'about';

interface DialogState {
  kind: DialogKind | null;
  node: ModelNodeDto | null;

  open: (kind: DialogKind, node?: ModelNodeDto) => void;
  close: () => void;
}

export const useDialogStore = create<DialogState>((set) => ({
  kind: null,
  node: null,
  open: (kind, node) => set({ kind, node: node ?? null }),
  close: () => set({ kind: null, node: null }),
}));
