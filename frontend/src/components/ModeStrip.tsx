import { useConnectionStore } from '../stores/connection';
import { useServerStore } from '../stores/server';
import { useUiStore } from '../stores/ui';

/**
 * Franja de modo como en la GUI clásica: selector Servidor/Cliente,
 * indicador de estado y tarjeta del IED activo a la derecha.
 */
export default function ModeStrip() {
  const mode = useUiStore((s) => s.mode);
  const setMode = useUiStore((s) => s.setMode);
  const client = useConnectionStore((s) => s.client);
  const server = useServerStore((s) => s.status);

  const active = mode === 'cliente' ? client.connected : server.running;
  const iedName = mode === 'cliente' ? client.iedName : server.iedName;
  const detail =
    mode === 'cliente'
      ? client.connected
        ? `${client.host}:${client.port}`
        : 'Sin conexión'
      : server.running
        ? `Puerto ${server.port}`
        : server.modelLoaded
          ? 'SCL cargado — detenido'
          : 'Sin modelo';

  return (
    <div className="flex h-12 shrink-0 items-center gap-4 border-b border-gray-200 bg-gray-50 px-3 text-xs dark:border-surface-border dark:bg-surface-raised">
      {/* Modo */}
      <fieldset className="flex items-center gap-3 rounded-md border border-gray-300 px-3 py-1.5 dark:border-surface-border">
        <legend className="px-1 text-[10px] text-gray-400">Modo</legend>
        {(['servidor', 'cliente'] as const).map((m) => {
          const locked = (client.connected || server.running) && mode !== m;
          return (
            <label
              key={m}
              className={`flex items-center gap-1.5 ${locked ? 'cursor-not-allowed opacity-40' : 'cursor-pointer'} text-gray-700 dark:text-gray-200`}
              title={locked ? 'Detenga la sesión activa antes de cambiar de modo' : undefined}
            >
              <input
                type="radio"
                name="appMode"
                checked={mode === m}
                disabled={locked}
                onChange={() => setMode(m)}
                className="accent-accent"
              />
              {m === 'servidor' ? 'Servidor' : 'Cliente'}
            </label>
          );
        })}
      </fieldset>

      {/* Estado */}
      <div className="flex items-center gap-2">
        <span
          className={`inline-block h-3.5 w-3.5 rounded-sm ${active ? 'bg-green-500' : 'bg-red-600'}`}
        />
        <span className="font-semibold text-gray-800 dark:text-gray-100">
          Modo {mode === 'servidor' ? 'Servidor' : 'Cliente'}
        </span>
        <span className="text-gray-400">·</span>
        <span className="text-gray-500 dark:text-gray-400">
          Conexión: <span className="font-mono text-accent dark:text-accent-hover">{detail}</span>
        </span>
      </div>

      <div className="flex-1" />

      {/* Tarjeta IED activo */}
      {iedName && (
        <div className="rounded-md border-2 border-accent/60 bg-accent/10 px-4 py-1 text-center">
          <div className="text-sm font-bold tracking-wide text-accent dark:text-accent-hover">
            {iedName}
          </div>
        </div>
      )}
    </div>
  );
}
