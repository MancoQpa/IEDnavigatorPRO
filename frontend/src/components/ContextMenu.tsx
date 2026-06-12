import { useEffect, useRef } from 'react';

export interface MenuItem {
  label: string;
  onClick: () => void;
  danger?: boolean;
  separator?: boolean;
}

/** Menú contextual flotante posicionado en coordenadas de pantalla. */
export default function ContextMenu({
  x,
  y,
  items,
  onClose,
}: {
  x: number;
  y: number;
  items: MenuItem[];
  onClose: () => void;
}) {
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (!ref.current?.contains(e.target as Node)) onClose();
    };
    const esc = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('mousedown', handler);
    document.addEventListener('keydown', esc);
    return () => {
      document.removeEventListener('mousedown', handler);
      document.removeEventListener('keydown', esc);
    };
  }, [onClose]);

  // Evitar que el menú se salga de la ventana
  const style: React.CSSProperties = {
    left: Math.min(x, window.innerWidth - 230),
    top: Math.min(y, window.innerHeight - items.length * 28 - 16),
  };

  return (
    <div
      ref={ref}
      style={style}
      className="fixed z-50 min-w-[210px] rounded-md border border-gray-200 bg-white py-1 shadow-lg dark:border-surface-border dark:bg-surface-raised"
    >
      {items.map((item, i) =>
        item.separator ? (
          <div key={i} className="my-1 border-t border-gray-200 dark:border-surface-border" />
        ) : (
          <button
            key={i}
            onClick={() => {
              onClose();
              item.onClick();
            }}
            className={`block w-full px-3 py-1 text-left text-xs hover:bg-gray-100 dark:hover:bg-surface ${
              item.danger ? 'text-red-600 dark:text-red-400' : 'text-gray-800 dark:text-gray-200'
            }`}
          >
            {item.label}
          </button>
        ),
      )}
    </div>
  );
}
