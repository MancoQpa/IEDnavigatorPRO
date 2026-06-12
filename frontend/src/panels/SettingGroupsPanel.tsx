import { useEffect, useState } from 'react';
import { getApi } from '../api/client';
import type { SettingGroupInfo } from '../api/types';
import { useConnectionStore } from '../stores/connection';
import { log } from '../stores/log';

/** Setting Groups (SGCB): grupo activo por LD y SelectActiveSG. */
export default function SettingGroupsPanel() {
  const connected = useConnectionStore((s) => s.client.connected);
  const [groups, setGroups] = useState<SettingGroupInfo[]>([]);
  const [loading, setLoading] = useState(false);

  const refresh = async () => {
    setLoading(true);
    try {
      const r = await getApi().settingGroups();
      setGroups(r.settingGroups);
    } catch (e) {
      log.error(`Error leyendo Setting Groups: ${(e as Error).message}`);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (connected) void refresh();
    else setGroups([]);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [connected]);

  if (!connected) {
    return (
      <div className="flex h-full items-center justify-center bg-white p-4 text-xs text-gray-400 dark:bg-surface">
        Sin conexión.
      </div>
    );
  }

  const select = async (ld: string, group: number) => {
    if (
      !window.confirm(
        `¿Activar el grupo de ajustes ${group} en ${ld}?\n\n` +
          'ADVERTENCIA: cambia el comportamiento de la protección en tiempo real.',
      )
    ) {
      return;
    }
    try {
      const r = await getApi().selectSettingGroup(ld, group);
      log.info(`Setting Group activo en ${ld}: ${r.actSG}/${r.numOfSGs}`);
      await refresh();
    } catch (e) {
      log.error(`Error en SelectActiveSG ${ld}: ${(e as Error).message}`);
    }
  };

  return (
    <div className="flex h-full flex-col bg-white dark:bg-surface">
      <div className="flex items-center gap-2 border-b border-gray-200 p-1.5 text-xs dark:border-surface-border">
        <span className="font-medium text-gray-600 dark:text-gray-300">
          Setting Group Control Blocks
        </span>
        <div className="flex-1" />
        <button
          onClick={() => void refresh()}
          disabled={loading}
          className="rounded border border-gray-300 px-2 py-1 hover:bg-gray-100 disabled:opacity-40 dark:border-surface-border dark:text-gray-300 dark:hover:bg-surface-raised"
        >
          {loading ? 'Actualizando…' : 'Actualizar'}
        </button>
      </div>

      {groups.length === 0 ? (
        <div className="flex flex-1 items-center justify-center p-4 text-center text-xs text-gray-400">
          El IED no expone Setting Groups (SGCB).
        </div>
      ) : (
        <div className="flex-1 overflow-auto p-2">
          {groups.map((g) => (
            <div
              key={g.ld}
              className="mb-2 rounded border border-gray-200 p-2 dark:border-surface-border"
            >
              <div className="mb-1.5 flex items-baseline gap-2 text-xs">
                <span className="font-mono font-medium text-gray-700 dark:text-gray-200">{g.ld}</span>
                <span className="text-gray-400">
                  grupo activo {g.actSG} de {g.numOfSGs}
                </span>
              </div>
              <div className="flex flex-wrap gap-1">
                {Array.from({ length: g.numOfSGs }, (_, i) => i + 1).map((n) => (
                  <button
                    key={n}
                    onClick={() => void select(g.ld, n)}
                    disabled={n === g.actSG}
                    className={`rounded px-2.5 py-1 text-xs ${
                      n === g.actSG
                        ? 'bg-accent font-semibold text-white'
                        : 'border border-gray-300 text-gray-600 hover:bg-gray-100 dark:border-surface-border dark:text-gray-300 dark:hover:bg-surface-raised'
                    }`}
                  >
                    SG {n}
                  </button>
                ))}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
