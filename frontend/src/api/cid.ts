import { getApi } from './client';
import { log } from '../stores/log';

/** CID descargado del IED (Obtener CID → Guardar CID, como en el original). */
let fetchedCid: { name: string; blob: Blob } | null = null;

export function saveBlob(blob: Blob, filename: string) {
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = filename;
  a.click();
  URL.revokeObjectURL(a.href);
}

export async function obtenerCid(connected: boolean) {
  if (!connected) {
    log.warn('Conéctese a un IED antes de obtener su CID');
    return;
  }
  try {
    log.info('Buscando ficheros SCL/CID en el IED…');
    const r = await getApi().findSclFiles();
    if (!r.files.length) {
      log.warn('El IED no expone ficheros SCL/CID (puede no soportar servicios de fichero MMS)');
      return;
    }
    const path = r.files[0];
    const blob = await getApi().downloadIedFile(path);
    fetchedCid = { name: path.substring(path.lastIndexOf('/') + 1), blob };
    log.info(`CID obtenido del IED: ${path} (${blob.size} bytes). Use «Guardar CID...» para guardarlo.`);
  } catch (e) {
    log.error(`Error obteniendo CID: ${(e as Error).message}`);
  }
}

export function guardarCid() {
  if (!fetchedCid) {
    log.warn('Primero use «Obtener CID del IED...»');
    return;
  }
  saveBlob(fetchedCid.blob, fetchedCid.name);
  log.info(`CID guardado: ${fetchedCid.name}`);
}
