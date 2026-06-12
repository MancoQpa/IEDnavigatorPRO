import { useConnectionStore } from '../stores/connection';

function Dot({ color }: { color: string }) {
  return <span className={`inline-block h-2 w-2 rounded-full ${color}`} />;
}

export default function StatusBar() {
  const { bridgeReady, bridgePort, wsState, client, systemInfo } = useConnectionStore();

  return (
    <div className="flex h-6 shrink-0 items-center gap-4 border-t border-gray-200 bg-gray-50 px-3 text-[11px] text-gray-500 dark:border-surface-border dark:bg-surface-raised dark:text-gray-400">
      <span className="flex items-center gap-1.5">
        <Dot color={bridgeReady ? 'bg-green-500' : 'bg-red-500'} />
        Backend {bridgeReady ? `:${bridgePort}` : 'no disponible'}
      </span>
      <span className="flex items-center gap-1.5">
        <Dot
          color={
            wsState === 'open' ? 'bg-green-500' : wsState === 'connecting' ? 'bg-yellow-500' : 'bg-red-500'
          }
        />
        Eventos {wsState === 'open' ? 'en vivo' : wsState === 'connecting' ? 'conectando…' : 'sin conexión'}
      </span>
      <span className="flex items-center gap-1.5">
        <Dot color={client.connected ? 'bg-green-500' : 'bg-gray-400'} />
        {client.connected ? `IED ${client.host}:${client.port} (${client.iedName ?? '?'})` : 'Sin IED'}
      </span>
      <div className="flex-1" />
      {systemInfo && !systemInfo.npcapAvailable && (
        <span className="text-yellow-600 dark:text-yellow-400">
          Npcap no detectado — GOOSE/SV deshabilitados
        </span>
      )}
    </div>
  );
}
