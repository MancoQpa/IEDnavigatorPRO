import type { ReactNode } from 'react';

/** Modal básico centrado con overlay. */
export default function Modal({
  title,
  children,
  onClose,
  width = 420,
}: {
  title: string;
  children: ReactNode;
  onClose: () => void;
  width?: number;
}) {
  return (
    <div
      className="fixed inset-0 z-40 flex items-center justify-center bg-black/40"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div
        style={{ width }}
        className="max-h-[85vh] overflow-auto rounded-lg border border-gray-200 bg-white shadow-xl dark:border-surface-border dark:bg-surface-raised"
      >
        <div className="flex items-center justify-between border-b border-gray-200 px-4 py-2.5 dark:border-surface-border">
          <span className="text-sm font-semibold text-gray-800 dark:text-gray-100">{title}</span>
          <button
            onClick={onClose}
            className="rounded px-1.5 text-gray-400 hover:bg-gray-100 hover:text-gray-600 dark:hover:bg-surface"
          >
            ✕
          </button>
        </div>
        <div className="p-4">{children}</div>
      </div>
    </div>
  );
}
