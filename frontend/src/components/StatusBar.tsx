import { useEffect, useState } from 'react';
import { getApi } from '../api/client';
import { useConnectionStore } from '../stores/connection';

function Dot({ color }: { color: string }) {
  return <span className={`inline-block h-2 w-2 rounded-full ${color}`} />;
}

function Clock() {
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const t = window.setInterval(() => setNow(new Date()), 1000);
    return () => window.clearInterval(t);
  }, []);
  const hh = String(now.getHours()).padStart(2, '0');
  const mm = String(now.getMinutes()).padStart(2, '0');
  const ss = String(now.getSeconds()).padStart(2, '0');
  return <span>{`${hh}:${mm}:${ss}`}</span>;
}

/**
 * Barra de estado: «Listo» + placa del IED (FC=DC) + reloj,
 * réplica de createStatusBar() del original.
 */
export default function StatusBar() {
  const { bridgeReady, bridgePort, wsState, client, systemInfo } = useConnectionStore();
  const [iedInfo, setIedInfo] = useState('');

  useEffect(() => {
    let cancelled = false;
    if (!client.connected) {
      setIedInfo('');
      return;
    }
    (async () => {
      try {
        const np = await getApi().nameplate();
        if (cancelled) return;
        const vendor = np['vendor'] ?? np['phy.vendor'] ?? '?';
        const tipo = np['phy.model'] ?? np['d'] ?? '?';
        const cfg = np['configRev'] ?? '';
        let text = `IED: ${client.iedName ?? '?'}  |  Fabricante: ${vendor}  |  Tipo: ${tipo}`;
        if (cfg) text += `  cfg:${cfg}`;
        setIedInfo(text);
      } catch {
        if (!cancelled) setIedInfo(`IED: ${client.iedName ?? '?'}`);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [client.connected, client.iedName]);

  return (
    <div className="flex h-6 shrink-0 items-center gap-4 border-t border-gray-200 bg-gray-50 px-3 text-[11px] text-gray-500 dark:border-surface-border dark:bg-surface-raised dark:text-gray-400">
      <span className="font-medium text-gray-600 dark:text-gray-300">Listo</span>
      <span className="flex items-center gap-1.5" title={`Backend ${bridgeReady ? `127.0.0.1:${bridgePort}` : 'no disponible'}`}>
        <Dot color={bridgeReady ? 'bg-green-500' : 'bg-red-500'} />
        <Dot
          color={
            wsState === 'open' ? 'bg-green-500' : wsState === 'connecting' ? 'bg-yellow-500' : 'bg-red-500'
          }
        />
        <Dot color={client.connected ? 'bg-green-500' : 'bg-gray-400'} />
      </span>
      <span className="truncate font-mono text-[11px] text-[rgb(40,80,160)] dark:text-sky-400">
        {iedInfo || (client.connected ? `IED: ${client.iedName ?? '?'}` : '')}
      </span>
      <div className="flex-1" />
      {systemInfo && !systemInfo.npcapAvailable && (
        <span className="text-yellow-600 dark:text-yellow-400">
          Npcap no detectado — GOOSE/SV deshabilitados
        </span>
      )}
      <Clock />
    </div>
  );
}
