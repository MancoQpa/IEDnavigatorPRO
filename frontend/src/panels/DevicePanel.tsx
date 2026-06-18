import { useEffect, useState } from 'react';
import { save, ask } from '@tauri-apps/plugin-dialog';
import { openPath } from '@tauri-apps/plugin-opener';
import { getApi } from '../api/client';
import { useConnectionStore } from '../stores/connection';
import { log } from '../stores/log';

const NAMEPLATE_LABELS: Record<string, string> = {
  vendor: 'Fabricante',
  swRev: 'Versión SW',
  hwRev: 'Versión HW',
  configRev: 'Revisión config.',
  d: 'Descripción',
  'phy.vendor': 'Fabricante (físico)',
  'phy.model': 'Modelo',
  'phy.serNum': 'Nº de serie',
};

function saveBlob(blob: Blob, filename: string) {
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = filename;
  a.click();
  URL.revokeObjectURL(a.href);
}

/** Dispositivo: nameplate, ficheros SCL del IED (descarga CID) y export HTML del modelo. */
export default function DevicePanel() {
  const connected = useConnectionStore((s) => s.client.connected);
  const iedName = useConnectionStore((s) => s.client.iedName);

  const [nameplate, setNameplate] = useState<Record<string, string>>({});
  const [sclFiles, setSclFiles] = useState<string[]>([]);
  const [searching, setSearching] = useState(false);
  const [searched, setSearched] = useState(false);
  const [includeValues, setIncludeValues] = useState(false);
  const [exporting, setExporting] = useState(false);

  useEffect(() => {
    if (!connected) {
      setNameplate({});
      setSclFiles([]);
      setSearched(false);
      return;
    }
    getApi()
      .nameplate()
      .then(setNameplate)
      .catch((e) => log.warn(`Nameplate no disponible: ${(e as Error).message}`));
  }, [connected]);

  if (!connected) {
    return (
      <div className="flex h-full items-center justify-center bg-white p-4 text-xs text-gray-400 dark:bg-surface">
        Sin conexión.
      </div>
    );
  }

  const searchScl = async () => {
    setSearching(true);
    try {
      const r = await getApi().findSclFiles();
      setSclFiles(r.files);
      setSearched(true);
      log.info(`Búsqueda SCL en el IED: ${r.files.length} fichero(s)`);
    } catch (e) {
      log.error(`Error buscando ficheros SCL: ${(e as Error).message}`);
    } finally {
      setSearching(false);
    }
  };

  const download = async (path: string) => {
    try {
      const blob = await getApi().downloadIedFile(path);
      saveBlob(blob, path.substring(path.lastIndexOf('/') + 1));
      log.info(`Descargado: ${path} (${blob.size} bytes)`);
    } catch (e) {
      log.error(`Error descargando ${path}: ${(e as Error).message}`);
    }
  };

  const exportHtml = async () => {
    setExporting(true);
    try {
      const defaultName = `modelo_${iedName ?? 'ied'}_${new Date().toISOString().slice(0, 10)}.html`;
      const filePath = await save({
        title: 'Guardar reporte HTML',
        defaultPath: defaultName,
        filters: [
          { name: 'HTML', extensions: ['html'] },
          { name: 'Todos los archivos', extensions: ['*'] },
        ],
      });
      if (!filePath) { setExporting(false); return; }

      const html = await getApi().exportModelHtml(includeValues);
      // Escribir archivo vía fetch al filesystem nativo
      const encoder = new TextEncoder();
      const bytes = encoder.encode(html);
      const fs = await import('@tauri-apps/plugin-fs');
      await fs.writeFile(filePath, bytes);

      log.info(`Reporte HTML exportado: ${filePath}`);

      const openIt = await ask(`Reporte guardado en:\n${filePath}\n\n¿Desea abrirlo en el navegador?`, {
        title: 'Exportar HTML',
        kind: 'info',
        okLabel: 'Abrir',
        cancelLabel: 'Cerrar',
      });
      if (openIt) await openPath(filePath);
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      log.error(`Error exportando HTML: ${msg}`);
    } finally {
      setExporting(false);
    }
  };

  const npEntries = Object.entries(nameplate);

  return (
    <div className="h-full overflow-auto bg-white p-3 text-xs dark:bg-surface">
      {/* ── Nameplate ── */}
      <h3 className="mb-1.5 text-[11px] font-semibold uppercase tracking-wide text-gray-500">
        Placa de identificación (LLN0.NamPlt / LPHD.PhyNam)
      </h3>
      {npEntries.length === 0 ? (
        <div className="mb-4 text-gray-400">El IED no expone datos de placa (FC=DC).</div>
      ) : (
        <table className="mb-4 border-collapse">
          <tbody>
            {npEntries.map(([k, v]) => (
              <tr key={k} className="border-t border-gray-100 dark:border-surface-border/50">
                <td className="py-1 pr-6 text-gray-500">{NAMEPLATE_LABELS[k] ?? k}</td>
                <td className="py-1 font-medium text-gray-700 dark:text-gray-200">{v}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {/* ── Ficheros SCL ── */}
      <h3 className="mb-1.5 text-[11px] font-semibold uppercase tracking-wide text-gray-500">
        Ficheros de configuración (CID/ICD/SCD)
      </h3>
      <div className="mb-2">
        <button
          onClick={() => void searchScl()}
          disabled={searching}
          className="rounded border border-gray-300 px-2 py-1 hover:bg-gray-100 disabled:opacity-40 dark:border-surface-border dark:text-gray-300 dark:hover:bg-surface-raised"
        >
          {searching ? 'Buscando…' : 'Buscar en el IED'}
        </button>
      </div>
      {searched && sclFiles.length === 0 && (
        <div className="mb-4 text-gray-400">
          No se encontraron ficheros SCL (el IED puede no soportar servicios de fichero MMS).
        </div>
      )}
      {sclFiles.length > 0 && (
        <ul className="mb-4">
          {sclFiles.map((f) => (
            <li key={f} className="flex items-center gap-2 border-t border-gray-100 py-1 dark:border-surface-border/50">
              <span className="flex-1 font-mono text-[11px] text-gray-700 dark:text-gray-300">{f}</span>
              <button
                onClick={() => void download(f)}
                className="rounded bg-accent px-2 py-0.5 text-white hover:bg-accent-hover"
              >
                Descargar
              </button>
            </li>
          ))}
        </ul>
      )}

      {/* ── Export HTML ── */}
      <h3 className="mb-1.5 text-[11px] font-semibold uppercase tracking-wide text-gray-500">
        Reporte del modelo
      </h3>
      <div className="flex items-center gap-3">
        <label className="flex items-center gap-1.5 text-gray-500">
          <input
            type="checkbox"
            checked={includeValues}
            onChange={(e) => setIncludeValues(e.target.checked)}
          />
          Incluir valores actuales
        </label>
        <button
          onClick={() => void exportHtml()}
          disabled={exporting}
          className="rounded bg-accent px-3 py-1 font-medium text-white hover:bg-accent-hover disabled:opacity-40"
        >
          {exporting ? 'Generando…' : 'Exportar HTML'}
        </button>
      </div>
      <div className="mt-1 text-[11px] text-gray-400">
        Reporte autocontenido imprimible a PDF (Ctrl+P en el navegador).
      </div>
    </div>
  );
}
