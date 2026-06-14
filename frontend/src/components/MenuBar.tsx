import { useState } from 'react';
import { getApi } from '../api/client';
import { guardarCid, obtenerCid, saveBlob } from '../api/cid';
import { pickSclFile } from '../api/pickFile';
import { useConnectionStore } from '../stores/connection';
import { useDialogStore } from '../stores/dialogs';
import { useGooseStore } from '../stores/goose';
import { log, useLogStore } from '../stores/log';
import { useServerStore } from '../stores/server';
import { useUiStore } from '../stores/ui';
import Modal from './Modal';

type OpenMenu = 'archivo' | 'herramientas' | 'ayuda' | null;

function MenuItem({
  label,
  onClick,
  disabled,
}: {
  label: string;
  onClick: () => void;
  disabled?: boolean;
}) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      className="block w-full whitespace-nowrap px-3 py-1.5 text-left text-xs text-gray-700 hover:bg-accent hover:text-white disabled:opacity-40 disabled:hover:bg-transparent disabled:hover:text-gray-700 dark:text-gray-200 dark:disabled:hover:text-gray-200"
    >
      {label}
    </button>
  );
}

function MenuSep() {
  return <div className="my-1 h-px bg-gray-200 dark:bg-surface-border" />;
}

function PortCheckDialog({ onClose }: { onClose: () => void }) {
  const [port, setPort] = useState('102');
  const [result, setResult] = useState<{ ok: boolean; text: string } | null>(null);
  const [busy, setBusy] = useState(false);

  const check = async () => {
    setBusy(true);
    setResult(null);
    try {
      const res = await getApi().portCheck(Number(port));
      setResult(
        res.free
          ? { ok: true, text: `El puerto ${res.port} está libre y puede usarse.` }
          : {
              ok: false,
              text: `El puerto ${res.port} está ocupado${res.error ? ` (${res.error})` : ''}. Pruebe 49151 para pruebas o ejecute como administrador.`,
            },
      );
    } catch (e) {
      setResult({ ok: false, text: (e as Error).message });
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal title="Verificar puerto TCP" onClose={onClose} width={380}>
      <div className="flex flex-col gap-3 text-xs">
        <label className="flex items-center gap-2">
          <span className="text-gray-500 dark:text-gray-400">Puerto:</span>
          <input
            value={port}
            onChange={(e) => setPort(e.target.value.replace(/\D/g, ''))}
            className="w-24 rounded border border-gray-300 bg-white px-2 py-1 outline-none focus:border-accent dark:border-surface-border dark:bg-surface dark:text-gray-200"
          />
          <span className="text-[10px] text-gray-400">(102=MMS, 49151=pruebas)</span>
        </label>
        <button
          onClick={() => void check()}
          disabled={busy || !port}
          className="w-full rounded bg-accent px-3 py-1.5 font-medium text-white hover:bg-accent-hover disabled:opacity-50"
        >
          {busy ? 'Verificando…' : 'Verificar'}
        </button>
        {result && (
          <div className={result.ok ? 'text-green-500' : 'text-red-400'}>{result.text}</div>
        )}
        <div className="text-[10px] text-gray-400">
          El puerto 102 (MMS estándar) suele requerir permisos de administrador en Windows.
        </div>
      </div>
    </Modal>
  );
}

/** Barra de menú: Archivo · Herramientas · Ayuda (réplica de createMenuBar original). */
export default function MenuBar() {
  const [open, setOpen] = useState<OpenMenu>(null);
  const [portDialog, setPortDialog] = useState(false);
  const clearLog = useLogStore((s) => s.clear);
  const mode = useUiStore((s) => s.mode);
  const connected = useConnectionStore((s) => s.client.connected);
  const iedName = useConnectionStore((s) => s.client.iedName);
  const openDialog = useDialogStore((s) => s.open);

  /* ── Archivo ── */

  const loadScl = async () => {
    const path = await pickSclFile('Cargar SCL/CID');
    if (!path) return;
    if (mode === 'servidor') {
      const store = useServerStore.getState();
      store.setSclPath(path);
      try {
        await store.parse();
        if (useServerStore.getState().ieds.length === 1) await store.load();
      } catch {
        /* ya logueado */
      }
    } else {
      // En modo cliente el original carga el SCL para los GoCBs (GOOSE)
      try {
        await useGooseStore.getState().loadScl(path, 0);
      } catch {
        /* ya logueado */
      }
    }
  };

  const exitApp = async () => {
    if ('__TAURI_INTERNALS__' in window) {
      const { getCurrentWindow } = await import('@tauri-apps/api/window');
      await getCurrentWindow().close();
    } else {
      window.close();
    }
  };

  /* ── Herramientas ── */

  const generarReporteHtml = async () => {
    if (!connected) {
      log.warn('Conéctese a un IED (o inicie el simulador) para generar el reporte del modelo');
      return;
    }
    const includeValues = window.confirm(
      '¿Incluir los valores actuales de los atributos en el reporte?',
    );
    try {
      const html = await getApi().exportModelHtml(includeValues);
      saveBlob(
        new Blob([html], { type: 'text/html;charset=utf-8' }),
        `modelo_${iedName ?? 'ied'}_${new Date().toISOString().slice(0, 10)}.html`,
      );
      log.info('Reporte HTML del modelo generado');
    } catch (e) {
      log.error(`Error generando reporte: ${(e as Error).message}`);
    }
  };

  const item = (action: () => void) => () => {
    setOpen(null);
    action();
  };

  const menuBtn = (id: Exclude<OpenMenu, null>, label: string, content: React.ReactNode) => (
    <div className="relative" key={id}>
      <button
        onClick={() => setOpen(open === id ? null : id)}
        onMouseEnter={() => open && setOpen(id)}
        className={`px-2.5 py-1 text-xs ${
          open === id
            ? 'bg-accent text-white'
            : 'text-gray-700 hover:bg-gray-200 dark:text-gray-200 dark:hover:bg-surface'
        }`}
      >
        {label}
      </button>
      {open === id && (
        <div className="absolute left-0 top-full z-50 min-w-56 rounded-b border border-gray-200 bg-white py-1 shadow-lg dark:border-surface-border dark:bg-surface-raised">
          {content}
        </div>
      )}
    </div>
  );

  return (
    <>
      <div className="flex h-7 shrink-0 items-center border-b border-gray-200 bg-gray-50 px-1 dark:border-surface-border dark:bg-surface-raised">
        {menuBtn(
          'archivo',
          'Archivo',
          <>
            <MenuItem label="Cargar SCL/CID..." onClick={item(() => void loadScl())} />
            <MenuSep />
            <MenuItem label="Salir" onClick={item(() => void exitApp())} />
          </>,
        )}
        {menuBtn(
          'herramientas',
          'Herramientas',
          <>
            <MenuItem
              label="Obtener CID del IED..."
              onClick={item(() => void obtenerCid(connected))}
              disabled={!connected}
            />
            <MenuItem label="Guardar CID..." onClick={item(guardarCid)} />
            <MenuSep />
            <MenuItem
              label="Generar reporte HTML del modelo..."
              onClick={item(() => void generarReporteHtml())}
              disabled={!connected}
            />
            <MenuSep />
            <MenuItem label="Verificar Puerto..." onClick={item(() => setPortDialog(true))} />
            <MenuItem label="Limpiar Log" onClick={item(clearLog)} />
          </>,
        )}
        {menuBtn(
          'ayuda',
          'Ayuda',
          <>
            <MenuItem label="Acerca de..." onClick={item(() => openDialog('about'))} />
            <MenuItem
              label="Leyenda de íconos y colores..."
              onClick={item(() => openDialog('legend'))}
            />
          </>,
        )}
      </div>
      {open && <div className="fixed inset-0 z-40" onMouseDown={() => setOpen(null)} />}
      {portDialog && <PortCheckDialog onClose={() => setPortDialog(false)} />}
    </>
  );
}
