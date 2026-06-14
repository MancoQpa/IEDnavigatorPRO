import { open } from '@tauri-apps/plugin-dialog';

/** Abre el diálogo nativo y devuelve la ruta del archivo SCL elegido (o null). */
export async function pickSclFile(title = 'Seleccionar archivo SCL'): Promise<string | null> {
  const sel = await open({
    title,
    multiple: false,
    filters: [
      { name: 'Archivos SCL', extensions: ['icd', 'cid', 'scd', 'iid', 'sed', 'ssd', 'xml'] },
      { name: 'Todos los archivos', extensions: ['*'] },
    ],
  });
  return typeof sel === 'string' ? sel : null;
}
