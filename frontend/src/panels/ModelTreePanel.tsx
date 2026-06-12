import { useState } from 'react';
import { getApi } from '../api/client';
import type { ModelNodeDto } from '../api/types';
import ContextMenu, { type MenuItem } from '../components/ContextMenu';
import { useConnectionStore } from '../stores/connection';
import { useDialogStore } from '../stores/dialogs';
import { log } from '../stores/log';
import { useModelStore } from '../stores/model';
import { useMonitorStore } from '../stores/monitor';

const FC_COLOR: Record<string, string> = {
  ST: 'bg-green-500/15 text-green-600 dark:text-green-400',
  MX: 'bg-blue-500/15 text-blue-600 dark:text-blue-400',
  CO: 'bg-red-500/15 text-red-600 dark:text-red-400',
  CF: 'bg-purple-500/15 text-purple-600 dark:text-purple-400',
  DC: 'bg-gray-500/15 text-gray-500 dark:text-gray-400',
  SP: 'bg-orange-500/15 text-orange-600 dark:text-orange-400',
  SG: 'bg-orange-500/15 text-orange-600 dark:text-orange-400',
  SE: 'bg-orange-500/15 text-orange-600 dark:text-orange-400',
  BL: 'bg-yellow-500/15 text-yellow-600 dark:text-yellow-400',
  RP: 'bg-cyan-500/15 text-cyan-600 dark:text-cyan-400',
  BR: 'bg-cyan-500/15 text-cyan-600 dark:text-cyan-400',
};

const KIND_ICON: Record<string, string> = {
  LD: '▣',
  LN: '◈',
  DO: '◇',
  DA: '·',
  CDA: '⊞',
  ARRAY: '[]',
};

function TreeNode({
  node,
  depth,
  onMenu,
}: {
  node: ModelNodeDto;
  depth: number;
  onMenu: (e: React.MouseEvent, node: ModelNodeDto) => void;
}) {
  const [open, setOpen] = useState(depth < 1);
  const liveValue = useModelStore((s) => s.values.get(node.ref));
  const hasChildren = !!node.children?.length;
  const value = liveValue?.value ?? node.value;
  const draggable = !!node.fc && (node.kind === 'DO' || node.kind === 'DA' || node.kind === 'CDA');

  return (
    <div>
      <div
        className="flex cursor-pointer items-center gap-1 rounded px-1 py-[1px] hover:bg-gray-100 dark:hover:bg-surface-raised"
        style={{ paddingLeft: depth * 14 + 4 }}
        onClick={() => hasChildren && setOpen(!open)}
        onContextMenu={(e) => onMenu(e, node)}
        draggable={draggable}
        onDragStart={(e) => {
          e.dataTransfer.setData(
            'application/x-iednav-node',
            JSON.stringify({ ref: node.ref, fc: node.fc }),
          );
          e.dataTransfer.effectAllowed = 'copy';
        }}
        title={node.ref}
      >
        <span className="w-3 text-[10px] text-gray-400">
          {hasChildren ? (open ? '▾' : '▸') : ''}
        </span>
        <span className="text-[10px] text-gray-400">{KIND_ICON[node.kind] ?? '·'}</span>
        <span className="text-gray-800 dark:text-gray-200">{node.name}</span>
        {node.fc && node.kind !== 'DA' && (
          <span
            className={`rounded px-1 text-[9px] font-semibold ${FC_COLOR[node.fc] ?? 'bg-gray-500/15 text-gray-500'}`}
          >
            {node.fc}
          </span>
        )}
        {value !== undefined && (
          <span className="ml-1 truncate font-mono text-[11px] text-accent dark:text-accent-hover">
            = {value}
          </span>
        )}
      </div>
      {open &&
        node.children?.map((c) => (
          <TreeNode key={c.ref + (c.fc ?? '')} node={c} depth={depth + 1} onMenu={onMenu} />
        ))}
    </div>
  );
}

export default function ModelTreePanel() {
  const model = useModelStore((s) => s.model);
  const loading = useModelStore((s) => s.loading);
  const connected = useConnectionStore((s) => s.client.connected);
  const [filter, setFilter] = useState('');
  const [menu, setMenu] = useState<{ x: number; y: number; node: ModelNodeDto } | null>(null);

  const openMenu = (e: React.MouseEvent, node: ModelNodeDto) => {
    e.preventDefault();
    e.stopPropagation();
    setMenu({ x: e.clientX, y: e.clientY, node });
  };

  const buildMenuItems = (node: ModelNodeDto): MenuItem[] => {
    const openDialog = useDialogStore.getState().open;
    const items: MenuItem[] = [];

    if (node.fc) {
      items.push({
        label: 'Leer valor',
        onClick: async () => {
          try {
            const r = await getApi().read(node.ref, node.fc!);
            log.info(`[READ] ${node.ref} [${node.fc}] = ${r.value}`);
          } catch (err) {
            log.error(`[READ] ${node.ref}: ${(err as Error).message}`);
          }
        },
      });
      items.push({
        label: 'Añadir al monitor',
        onClick: () => useMonitorStore.getState().add(node.ref, node.fc!),
      });
    }

    if (node.kind === 'DA' && node.fc && !['ST', 'MX'].includes(node.fc)) {
      items.push({ label: 'Escribir valor…', onClick: () => openDialog('write', node) });
    }

    if (node.fc === 'CO') {
      items.push({ separator: true, label: '', onClick: () => {} });
      items.push({ label: 'Operar (control)…', onClick: () => openDialog('control', node), danger: true });
      items.push({ label: 'Cancelar SELECT (SBO)…', onClick: () => openDialog('cancelSbo', node) });
    }

    if (node.kind === 'DO') {
      items.push({ separator: true, label: '', onClick: () => {} });
      items.push({
        label: 'Bloquear valor (blkEna=true)',
        onClick: async () => {
          try {
            await getApi().blocking(node.ref, true);
            log.info(`Bloqueado: ${node.ref}`);
          } catch (err) {
            log.error(`[BL] ${(err as Error).message}`);
          }
        },
      });
      items.push({
        label: 'Desbloquear valor (blkEna=false)',
        onClick: async () => {
          try {
            await getApi().blocking(node.ref, false);
            log.info(`Desbloqueado: ${node.ref}`);
          } catch (err) {
            log.error(`[BL] ${(err as Error).message}`);
          }
        },
      });
    }

    items.push({ separator: true, label: '', onClick: () => {} });
    items.push({ label: '¿Qué es esto? (IEC 61850)', onClick: () => openDialog('dictionary', node) });
    return items;
  };

  if (!connected) {
    return (
      <div className="flex h-full items-center justify-center bg-white p-4 text-center text-xs text-gray-400 dark:bg-surface">
        Sin conexión.
        <br />
        Conéctese a un IED para explorar su modelo.
      </div>
    );
  }

  if (loading || !model) {
    return (
      <div className="flex h-full items-center justify-center bg-white text-xs text-gray-400 dark:bg-surface">
        {loading ? 'Cargando modelo…' : 'Modelo no disponible'}
      </div>
    );
  }

  const filterTree = (nodes: ModelNodeDto[]): ModelNodeDto[] => {
    if (!filter) return nodes;
    const f = filter.toLowerCase();
    const walk = (n: ModelNodeDto): ModelNodeDto | null => {
      const children = n.children?.map(walk).filter((c): c is ModelNodeDto => !!c) ?? [];
      if (n.name.toLowerCase().includes(f) || n.ref.toLowerCase().includes(f) || children.length) {
        return { ...n, children };
      }
      return null;
    };
    return nodes.map(walk).filter((n): n is ModelNodeDto => !!n);
  };

  return (
    <div className="flex h-full flex-col bg-white dark:bg-surface">
      <div className="border-b border-gray-200 p-1.5 dark:border-surface-border">
        <input
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          placeholder="Filtrar (nombre o referencia)…"
          className="w-full rounded border border-gray-300 bg-transparent px-2 py-1 text-xs outline-none focus:border-accent dark:border-surface-border dark:text-gray-200"
        />
      </div>
      <div className="flex-1 overflow-auto py-1">
        <div className="px-2 pb-1 text-[11px] font-semibold text-gray-500 dark:text-gray-400">
          {model.iedName}
        </div>
        {filterTree(model.logicalDevices).map((ld) => (
          <TreeNode key={ld.ref} node={ld} depth={0} onMenu={openMenu} />
        ))}
      </div>
      {menu && (
        <ContextMenu
          x={menu.x}
          y={menu.y}
          items={buildMenuItems(menu.node)}
          onClose={() => setMenu(null)}
        />
      )}
    </div>
  );
}
