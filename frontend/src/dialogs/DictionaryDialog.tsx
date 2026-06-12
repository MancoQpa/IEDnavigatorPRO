import { useEffect, useState } from 'react';
import { getApi } from '../api/client';
import type { DictEntry, ModelNodeDto } from '../api/types';
import Modal from '../components/Modal';

const KIND_COLOR: Record<string, string> = {
  LN: 'bg-blue-600',
  CDC: 'bg-green-700',
  FC: 'bg-purple-700',
  DO: 'bg-orange-600',
  DA: 'bg-slate-600',
  LD: 'bg-teal-700',
};

/** Diccionario educativo IEC 61850 ("¿Qué es esto?"). */
export default function DictionaryDialog({
  node,
  onClose,
}: {
  node: ModelNodeDto;
  onClose: () => void;
}) {
  const [entry, setEntry] = useState<DictEntry | null>(null);
  const [notFound, setNotFound] = useState(false);

  useEffect(() => {
    getApi()
      .dictionary(node.name)
      .then(setEntry)
      .catch(() => setNotFound(true));
  }, [node.name]);

  return (
    <Modal title={`IEC 61850 — ${node.name}`} onClose={onClose} width={480}>
      {notFound && (
        <div className="text-xs text-gray-500">
          Sin entrada en el diccionario para «{node.name}». Puede ser un nombre específico del
          fabricante.
        </div>
      )}
      {!entry && !notFound && <div className="text-xs text-gray-400">Buscando…</div>}
      {entry && (
        <div className="space-y-3 text-xs">
          <div className="flex items-center gap-2">
            <span className={`rounded px-1.5 py-0.5 text-[10px] font-bold text-white ${KIND_COLOR[entry.kind] ?? 'bg-gray-500'}`}>
              {entry.kind}
            </span>
            <span className="text-gray-500">{entry.kindLabel}</span>
          </div>
          <div>
            <div className="text-sm font-semibold text-gray-800 dark:text-gray-100">{entry.nameEs}</div>
            <div className="italic text-gray-500">{entry.nameEn}</div>
          </div>
          <p className="leading-relaxed text-gray-700 dark:text-gray-300">{entry.description}</p>
          {entry.example && (
            <div className="rounded bg-gray-100 p-2 font-mono text-[11px] text-gray-600 dark:bg-surface dark:text-gray-300">
              {entry.example}
            </div>
          )}
          <div className="text-[10px] text-gray-400">Referencia: {entry.standard}</div>
        </div>
      )}
    </Modal>
  );
}
