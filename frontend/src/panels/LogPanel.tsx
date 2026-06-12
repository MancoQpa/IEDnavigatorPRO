import { useEffect, useRef } from 'react';
import { useLogStore } from '../stores/log';

const LEVEL_COLOR: Record<string, string> = {
  info: 'text-gray-700 dark:text-gray-300',
  warn: 'text-yellow-600 dark:text-yellow-400',
  error: 'text-red-600 dark:text-red-400',
};

function fmtTime(ts: number): string {
  return new Date(ts).toLocaleTimeString('es', { hour12: false });
}

export default function LogPanel() {
  const entries = useLogStore((s) => s.entries);
  const clear = useLogStore((s) => s.clear);
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'auto' });
  }, [entries.length]);

  return (
    <div className="flex h-full flex-col bg-white dark:bg-[#171819]">
      <div className="flex items-center justify-between border-b border-gray-200 px-2 py-1 dark:border-surface-border">
        <span className="text-[11px] font-medium uppercase tracking-wide text-gray-400">
          Registro de eventos
        </span>
        <button
          onClick={clear}
          className="rounded px-2 py-0.5 text-[11px] text-gray-400 hover:bg-gray-100 hover:text-gray-700 dark:hover:bg-surface-raised dark:hover:text-gray-200"
        >
          Limpiar
        </button>
      </div>
      <div className="flex-1 select-text overflow-auto p-1 font-mono text-xs leading-5">
        {entries.map((e, i) => (
          <div key={i} className={LEVEL_COLOR[e.level]}>
            <span className="text-gray-400 dark:text-gray-600">[{fmtTime(e.ts)}]</span> {e.message}
          </div>
        ))}
        <div ref={bottomRef} />
      </div>
    </div>
  );
}
