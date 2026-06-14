import Modal from '../components/Modal';
import { useConnectionStore } from '../stores/connection';

/** Réplica de showAboutDialog() de la GUI original. */
export default function AboutDialog({ onClose }: { onClose: () => void }) {
  const systemInfo = useConnectionStore((s) => s.systemInfo);
  const nativeOk = systemInfo?.nativeDllAvailable ?? false;

  return (
    <Modal title="Acerca de..." onClose={onClose} width={400}>
      <div className="flex flex-col items-center gap-1 py-2 text-center text-xs">
        <div className="text-xl font-semibold text-gray-800 dark:text-gray-100">
          <span className="text-accent">IED</span> Navigator
        </div>
        <div className="text-[11px] font-medium text-gray-500 dark:text-gray-400">
          Version 2.0 - Hybrid Edition
        </div>

        <div className="my-2 h-px w-full bg-gray-200 dark:bg-surface-border" />

        <div className="font-semibold text-gray-700 dark:text-gray-200">IEC 61850 Explorer Tool</div>
        <div className="text-gray-500 dark:text-gray-400">
          Herramienta profesional para explorar, monitorear y configurar dispositivos IED
        </div>

        <div className="mt-2 w-full text-left">
          <div className="mb-1 font-semibold text-gray-700 dark:text-gray-200">Características:</div>
          <ul className="ml-4 list-disc text-gray-600 dark:text-gray-300">
            <li>Cliente/Servidor MMS</li>
            <li>Monitoreo en tiempo real</li>
            <li>Reports (URCB/BRCB)</li>
            <li>GOOSE Subscriber/Publisher</li>
            <li>Carga/descarga SCL/CID</li>
          </ul>
        </div>

        <div className="mt-2 w-full text-left">
          <span className="font-semibold text-gray-700 dark:text-gray-200">Estado libiec61850: </span>
          {nativeOk ? (
            <span className="font-bold text-green-600 dark:text-green-400">✓ disponible</span>
          ) : (
            <span className="font-bold text-red-500">✗ no disponible</span>
          )}
        </div>

        <div className="my-2 h-px w-full bg-gray-200 dark:bg-surface-border" />

        <div className="font-semibold text-gray-700 dark:text-gray-200">Desarrollado por:</div>
        <div className="text-gray-600 dark:text-gray-300">Emilio Medina</div>
        <div className="text-gray-500 dark:text-gray-400">Técnico Superior en Electrónica</div>
        <div className="text-gray-500 dark:text-gray-400">Paraguay</div>

        <div className="mt-2 text-[10px] text-gray-400">
          Bibliotecas: iec61850bean, libiec61850
          <br />© 2024 - Todos los derechos reservados
        </div>
      </div>
    </Modal>
  );
}
