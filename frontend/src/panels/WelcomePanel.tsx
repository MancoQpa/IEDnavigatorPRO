import { useConnectionStore } from '../stores/connection';

function CapabilityBadge({ ok, label }: { ok: boolean; label: string }) {
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full px-3 py-1 text-xs font-medium ${
        ok
          ? 'bg-green-500/15 text-green-600 dark:text-green-400'
          : 'bg-yellow-500/15 text-yellow-700 dark:text-yellow-400'
      }`}
    >
      <span className={`h-2 w-2 rounded-full ${ok ? 'bg-green-500' : 'bg-yellow-500'}`} />
      {label}
    </span>
  );
}

export default function WelcomePanel() {
  const systemInfo = useConnectionStore((s) => s.systemInfo);

  return (
    <div className="flex h-full flex-col items-center justify-center gap-6 bg-white text-gray-800 dark:bg-surface dark:text-gray-200">
      <div className="text-center">
        <h1 className="text-3xl font-light tracking-tight">
          <span className="font-semibold text-accent">IED</span>Navigator{' '}
          <span className="text-accent">PRO</span>
        </h1>
        <p className="mt-2 text-sm text-gray-500 dark:text-gray-400">
          Explorador y simulador IEC 61850 — MMS · GOOSE · Sampled Values
        </p>
      </div>

      {systemInfo && (
        <div className="flex flex-wrap items-center justify-center gap-2">
          <CapabilityBadge ok={systemInfo.npcapAvailable} label="Npcap (GOOSE/SV)" />
          <CapabilityBadge ok={systemInfo.nativeDllAvailable} label="libiec61850.dll" />
          <CapabilityBadge ok label={`Java ${systemInfo.javaVersion}`} />
        </div>
      )}

      {systemInfo && !systemInfo.npcapAvailable && (
        <div className="max-w-md rounded-md border border-yellow-500/30 bg-yellow-500/10 px-4 py-3 text-center text-xs text-yellow-700 dark:text-yellow-400">
          <p className="font-medium">Npcap no detectado</p>
          <p className="mt-1">
            Las funciones GOOSE y Sampled Values en red requieren Npcap. Descárguelo desde{' '}
            <span className="font-mono select-all">https://npcap.com</span> e instálelo con la
            opción «WinPcap API-compatible mode». Después reinicie la aplicación.
          </p>
        </div>
      )}

      <div className="max-w-md text-center text-xs text-gray-400 dark:text-gray-500">
        Use la barra superior para conectarse a un IED, o abra el panel Servidor para
        simular uno a partir de un archivo SCL.
      </div>
    </div>
  );
}
