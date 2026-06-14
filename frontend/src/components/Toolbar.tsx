import { useLogStore } from '../stores/log';
import { useThemeStore } from '../stores/theme';

/** Barra superior: marca, acciones rápidas y tema (como la GUI clásica). */
export default function Toolbar() {
  const theme = useThemeStore((s) => s.theme);
  const toggleTheme = useThemeStore((s) => s.toggle);
  const clearLog = useLogStore((s) => s.clear);

  return (
    <div className="flex h-10 shrink-0 items-center gap-2 border-b border-gray-200 bg-gray-50 px-3 dark:border-surface-border dark:bg-surface-raised">
      <span className="mr-3 text-sm font-semibold tracking-tight text-gray-800 dark:text-gray-100">
        <span className="text-accent">IED</span>Navigator <span className="text-accent">PRO</span>
      </span>

      <button
        onClick={clearLog}
        className="rounded px-2 py-1 text-xs text-gray-600 hover:bg-gray-200 dark:text-gray-300 dark:hover:bg-surface"
      >
        Limpiar Log
      </button>

      <div className="flex-1" />

      <span className="text-[11px] text-gray-400">v1.0 | IEC 61850</span>
      <button
        onClick={toggleTheme}
        title="Cambiar tema"
        className="rounded px-2 py-1 text-xs text-gray-500 hover:bg-gray-200 dark:text-gray-400 dark:hover:bg-surface"
      >
        {theme === 'dark' ? '☀' : '☾'}
      </button>
    </div>
  );
}
