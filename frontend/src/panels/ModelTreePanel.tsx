import { useEffect, useState } from 'react';
import { getApi } from '../api/client';
import type { ModelNodeDto } from '../api/types';
import ContextMenu, { type MenuItem } from '../components/ContextMenu';
import { TreeIcon } from '../components/TreeIcons';
import { FC_RGB } from '../dialogs/LegendDialog';
import { useConnectionStore } from '../stores/connection';
import { useDialogStore } from '../stores/dialogs';
import { log } from '../stores/log';
import { useModelStore } from '../stores/model';
import { useMonitorStore } from '../stores/monitor';
import { useServerStore } from '../stores/server';
import { useUiStore } from '../stores/ui';

const READONLY_FC = ['ST', 'MX'];

/* ── Colores de texto idénticos a la GUI original (ModelTreeCellRenderer) ── */

const ACTIVE_VALUES = ['on', 'ok', 'closed', 'true', 'good'];
const INACTIVE_VALUES = ['off', 'alarm', 'open', 'false'];
const WARNING_VALUES = ['intermediate', 'bad', 'warning', 'test', 'bad-state', 'invalid'];

/** Color de texto según el valor (como ModelTreeCellRenderer original). */
function valueColor(value: string | undefined): string | undefined {
  if (value === undefined) return undefined;
  const v = value.trim().toLowerCase();
  if (ACTIVE_VALUES.includes(v)) return 'rgb(0,150,0)';
  if (INACTIVE_VALUES.includes(v)) return 'rgb(200,0,0)';
  if (WARNING_VALUES.some((w) => v === w || v.includes(w))) return 'rgb(255,140,0)';
  return undefined;
}

function TreeNode({
  node,
  depth,
  onMenu,
  serverSource,
  isServerMode,
}: {
  node: ModelNodeDto;
  depth: number;
  onMenu: (e: React.MouseEvent, node: ModelNodeDto) => void;
  serverSource: boolean;
  isServerMode: boolean;
}) {
  const [open, setOpen] = useState(depth < 1);
  const liveValue = useModelStore((s) => s.values.get(node.ref));
  const connected = useConnectionStore((s) => s.client.connected);
  const openDialog = useDialogStore((s) => s.open);
  const inWatchlist = useMonitorStore((s) =>
    node.fc ? s.items.some((it) => it.ref === node.ref) : false,
  );
  const hasChildren = !!node.children?.length;

  const handleDoubleClick = (e: React.MouseEvent) => {
    e.stopPropagation();
    if (node.kind !== 'DA' && node.kind !== 'CDA') return;
    // En modo servidor todos los DA son editables (el servidor simula el IED)
    if (isServerMode) {
      openDialog('serverWrite', node);
    } else if (connected && node.fc && !READONLY_FC.includes(node.fc)) {
      openDialog('write', node);
    }
  };
  const value = liveValue?.value ?? node.value;
  const draggable = !!node.fc && (node.kind === 'DO' || node.kind === 'DA' || node.kind === 'CDA');
  const vColor = valueColor(value);
  const nameColor = inWatchlist ? 'rgb(0,100,200)' : node.fc === 'BL' ? 'rgb(120,80,180)' : undefined;

  return (
    <div>
      <div
        className="flex cursor-pointer items-center gap-1 rounded px-1 py-[1px] text-[12px] hover:bg-gray-100 dark:hover:bg-surface-raised"
        style={{ paddingLeft: depth * 14 + 4 }}
        onClick={() => hasChildren && setOpen(!open)}
        onDoubleClick={handleDoubleClick}
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
        <span className="w-3 shrink-0 text-[10px] text-gray-400">
          {hasChildren ? (open ? '▾' : '▸') : ''}
        </span>
        <TreeIcon node={node} value={value} />
        <span
          className={nameColor ? 'font-medium' : 'text-gray-800 dark:text-gray-200'}
          style={nameColor ? { color: nameColor } : undefined}
        >
          {inWatchlist ? '* ' : ''}
          {node.fc === 'BL' ? '🔒 ' : ''}
          {node.name}
        </span>
        {node.fc && node.kind !== 'DA' && (
          <span
            className="rounded px-1 text-[9px] font-bold text-white"
            style={{ backgroundColor: FC_RGB[node.fc] ?? 'rgb(96,125,139)' }}
          >
            {node.fc}
          </span>
        )}
        {value !== undefined && (
          <span
            className={`ml-1 truncate ${vColor ? 'font-bold' : 'text-gray-800 dark:text-gray-200'}`}
            style={vColor ? { color: vColor } : undefined}
          >
            = {value}
          </span>
        )}
      </div>
      {open &&
        node.children?.map((c) => (
          <TreeNode key={c.ref + (c.fc ?? '')} node={c} depth={depth + 1} onMenu={onMenu} serverSource={serverSource} isServerMode={isServerMode} />
        ))}
    </div>
  );
}

export default function ModelTreePanel() {
  const clientModel = useModelStore((s) => s.model);
  const loading = useModelStore((s) => s.loading);
  const connected = useConnectionStore((s) => s.client.connected);
  const serverModel = useServerStore((s) => s.model);
  const mode = useUiStore((s) => s.mode);
  const treeSearch = useUiStore((s) => s.treeSearch);
  const setTreeSearch = useUiStore((s) => s.setTreeSearch);
  const [filter, setFilter] = useState('');

  // Sync external treeSearch (from Dataset navigation) into local filter
  useEffect(() => {
    if (treeSearch) {
      setFilter(treeSearch);
      setTreeSearch(''); // consume it
    }
  }, [treeSearch, setTreeSearch]);
  const [menu, setMenu] = useState<{ x: number; y: number; node: ModelNodeDto } | null>(null);

  // Como en la GUI original: en modo servidor el árbol se puebla desde el
  // ServerModel al cargar el SCL (sin necesidad de cliente conectado).
  const serverSource = !connected && mode === 'servidor' && !!serverModel;
  const model = connected ? clientModel : serverSource ? serverModel : null;

  const openMenu = (e: React.MouseEvent, node: ModelNodeDto) => {
    e.preventDefault();
    e.stopPropagation();
    setMenu({ x: e.clientX, y: e.clientY, node });
  };

  /** Menú contextual modo servidor (réplica de buildServerPopupForNode). */
  const buildServerMenuItems = (node: ModelNodeDto): MenuItem[] => {
    const openDialog = useDialogStore.getState().open;
    const setValue = useServerStore.getState().setValue;
    const items: MenuItem[] = [];
    const type = (node.type ?? '').toUpperCase();
    const isLeaf = node.kind === 'DA' || node.kind === 'CDA';

    const setVal = (v: string) => async () => {
      try {
        await setValue(node.ref, v);
      } catch {
        /* ya logueado */
      }
    };

    if (isLeaf) {
      if (type.includes('BOOLEAN')) {
        items.push({ label: 'Establecer TRUE', onClick: setVal('true') });
        items.push({ label: 'Establecer FALSE', onClick: setVal('false') });
      } else if (type.includes('DBPOS') || type.includes('DOUBLE')) {
        items.push({ label: 'Establecer ON (cerrado)', onClick: setVal('on') });
        items.push({ label: 'Establecer OFF (abierto)', onClick: setVal('off') });
        items.push({ label: 'Establecer INTERMEDIATE', onClick: setVal('intermediate') });
        items.push({ label: 'Establecer BAD_STATE', onClick: setVal('bad') });
      } else if (type.includes('TAP')) {
        items.push({ label: 'Establecer STOP', onClick: setVal('stop') });
        items.push({ label: 'Establecer LOWER', onClick: setVal('lower') });
        items.push({ label: 'Establecer HIGHER', onClick: setVal('higher') });
        items.push({ label: 'Establecer RESERVED', onClick: setVal('reserved') });
      }
      items.push({
        label: 'Editar valor…',
        onClick: () => openDialog('serverWrite', node),
      });
      items.push({ separator: true, label: '', onClick: () => {} });
    }

    items.push({ label: '¿Qué es esto? (IEC 61850)', onClick: () => openDialog('dictionary', node) });
    items.push({ label: 'Leyenda de íconos y colores…', onClick: () => openDialog('legend') });
    return items;
  };

  /** Menú contextual modo cliente (igual que antes + leyenda). */
  const buildClientMenuItems = (node: ModelNodeDto): MenuItem[] => {
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
    items.push({ label: 'Leyenda de íconos y colores…', onClick: () => openDialog('legend') });
    return items;
  };

  if (!model) {
    return (
      <div className="flex h-full items-center justify-center bg-white p-4 text-center text-xs text-gray-400 dark:bg-surface">
        {loading ? (
          'Cargando modelo…'
        ) : mode === 'servidor' ? (
          <span>
            Sin modelo.
            <br />
            Cargue un archivo SCL/ICD/CID para ver el modelo del IED.
          </span>
        ) : (
          <span>
            Sin conexión.
            <br />
            Conéctese a un IED para explorar su modelo.
          </span>
        )}
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
          {serverSource && <span className="ml-1 font-normal text-gray-400">(modelo del simulador)</span>}
        </div>
        {filterTree(model.logicalDevices).map((ld) => (
          <TreeNode key={ld.ref} node={ld} depth={0} onMenu={openMenu} serverSource={serverSource} isServerMode={mode === 'servidor'} />
        ))}
      </div>
      {menu && (
        <ContextMenu
          x={menu.x}
          y={menu.y}
          items={mode === 'servidor' ? buildServerMenuItems(menu.node) : buildClientMenuItems(menu.node)}
          onClose={() => setMenu(null)}
        />
      )}
    </div>
  );
}
