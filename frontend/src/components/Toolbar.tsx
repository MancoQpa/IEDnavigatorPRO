import { useState } from 'react';
import { resetDockLayout } from '../layout/DockManager';
import { useConnectionStore } from '../stores/connection';
import { useThemeStore } from '../stores/theme';

export default function Toolbar() {
  const { client, connecting, connect, disconnect } = useConnectionStore();
  const theme = useThemeStore((s) => s.theme);
  const toggleTheme = useThemeStore((s) => s.toggle);

  const [host, setHost] = useState(localStorage.getItem('lastHost') ?? '127.0.0.1');
  const [port, setPort] = useState(localStorage.getItem('lastPort') ?? '102');

  const onConnect = async () => {
    localStorage.setItem('lastHost', host);
    localStorage.setItem('lastPort', port);
    try {
      await connect(host, Number(port));
    } catch {
      /* ya logueado */
    }
  };

  return (
    <div className="flex h-10 shrink-0 items-center gap-2 border-b border-gray-200 bg-gray-50 px-3 dark:border-surface-border dark:bg-surface-raised">
      <span className="mr-2 text-sm font-semibold tracking-tight text-gray-800 dark:text-gray-100">
        <span className="text-accent">IED</span>Navigator <span className="text-accent">PRO</span>
      </span>

      <input
        value={host}
        onChange={(e) => setHost(e.target.value)}
        disabled={client.connected}
        placeholder="Host"
        className="w-36 rounded border border-gray-300 bg-white px-2 py-1 text-xs outline-none focus:border-accent disabled:opacity-50 dark:border-surface-border dark:bg-surface dark:text-gray-200"
      />
      <input
        value={port}
        onChange={(e) => setPort(e.target.value.replace(/\D/g, ''))}
        disabled={client.connected}
        placeholder="Puerto"
        className="w-16 rounded border border-gray-300 bg-white px-2 py-1 text-xs outline-none focus:border-accent disabled:opacity-50 dark:border-surface-border dark:bg-surface dark:text-gray-200"
      />

      {client.connected ? (
        <button
          onClick={() => void disconnect()}
          className="rounded bg-red-600 px-3 py-1 text-xs font-medium text-white hover:bg-red-500"
        >
          Desconectar
        </button>
      ) : (
        <button
          onClick={() => void onConnect()}
          disabled={connecting || !host || !port}
          className="rounded bg-accent px-3 py-1 text-xs font-medium text-white hover:bg-accent-hover disabled:opacity-50"
        >
          {connecting ? 'Conectando…' : 'Conectar'}
        </button>
      )}

      <div className="flex-1" />

      <button
        onClick={resetDockLayout}
        title="Restaurar distribución de paneles"
        className="rounded px-2 py-1 text-xs text-gray-500 hover:bg-gray-200 dark:text-gray-400 dark:hover:bg-surface"
      >
        ⟲ Layout
      </button>
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
