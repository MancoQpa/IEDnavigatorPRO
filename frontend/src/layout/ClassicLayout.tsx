import { useCallback, useRef, useState } from 'react';
import { ClientCard, ServerCard } from '../components/ModeCards';
import DataModelPanel from '../panels/DataModelPanel';
import DatasetPanel from '../panels/DatasetPanel';
import DevicePanel from '../panels/DevicePanel';
import GooseMapPanel from '../panels/GooseMapPanel';
import GoosePanel from '../panels/GoosePanel';
import LogPanel from '../panels/LogPanel';
import ModelTreePanel from '../panels/ModelTreePanel';
import MonitorPanel from '../panels/MonitorPanel';
import ProtectionSettingsPanel from '../panels/ProtectionSettingsPanel';
import ReportsPanel from '../panels/ReportsPanel';
import SclComparePanel from '../panels/SclComparePanel';
import SettingGroupsPanel from '../panels/SettingGroupsPanel';
import SvPanel from '../panels/SvPanel';
import { useUiStore } from '../stores/ui';

// Pestañas en el mismo orden que la GUI clásica
const TABS = [
  { id: 'monitor', title: 'Monitor', el: <MonitorPanel /> },
  { id: 'reports', title: 'Reports', el: <ReportsPanel /> },
  { id: 'goose', title: 'GOOSE', el: <GoosePanel /> },
  { id: 'settingGroups', title: 'Setting Groups', el: <SettingGroupsPanel /> },
  { id: 'ajustesSp', title: 'Ajustes (SP)', el: <ProtectionSettingsPanel /> },
  { id: 'datasets', title: 'Dataset', el: <DatasetPanel /> },
  { id: 'dataModel', title: 'Data Model', el: <DataModelPanel /> },
  { id: 'sclCompare', title: 'Comparar SCL', el: <SclComparePanel /> },
  { id: 'gooseMap', title: 'Mapa GOOSE', el: <GooseMapPanel /> },
  { id: 'sv', title: 'SV (SMV)', el: <SvPanel /> },
  { id: 'device', title: 'Dispositivo', el: <DevicePanel /> },
] as const;

function loadWidth(key: string, fallback: number): number {
  const v = Number(localStorage.getItem(key));
  return v >= 180 && v <= 800 ? v : fallback;
}

/** Divisor vertical arrastrable. */
function Splitter({ onDrag }: { onDrag: (dx: number) => void }) {
  const onMouseDown = useCallback(
    (e: React.MouseEvent) => {
      e.preventDefault();
      let lastX = e.clientX;
      const move = (ev: MouseEvent) => {
        onDrag(ev.clientX - lastX);
        lastX = ev.clientX;
      };
      const up = () => {
        window.removeEventListener('mousemove', move);
        window.removeEventListener('mouseup', up);
        document.body.style.cursor = '';
      };
      window.addEventListener('mousemove', move);
      window.addEventListener('mouseup', up);
      document.body.style.cursor = 'col-resize';
    },
    [onDrag],
  );
  return (
    <div
      onMouseDown={onMouseDown}
      className="w-1 shrink-0 cursor-col-resize bg-gray-200 hover:bg-accent dark:bg-surface-border dark:hover:bg-accent"
    />
  );
}

function SectionHeader({ title }: { title: string }) {
  return (
    <div className="border-b border-gray-200 px-2 py-1 text-[11px] font-medium uppercase tracking-wide text-gray-400 dark:border-surface-border">
      {title}
    </div>
  );
}

/**
 * Distribución fija heredada de la GUI Swing clásica:
 * columna izquierda (panel de modo + Log) · árbol «Modelo de Datos» · pestañas.
 */
export default function ClassicLayout() {
  const mode = useUiStore((s) => s.mode);
  const visibleTabs = TABS.filter((t) => !(t.id === 'reports' && mode === 'servidor'));
  const [leftW, setLeftW] = useState(() => loadWidth('iednav.classic.leftW', 300));
  const [treeW, setTreeW] = useState(() => loadWidth('iednav.classic.treeW', 350));
  const [tab, setTab] = useState(
    () => localStorage.getItem('iednav.classic.tab') ?? 'monitor',
  );
  const saveTimer = useRef<number | undefined>(undefined);

  const persist = (key: string, value: number) => {
    window.clearTimeout(saveTimer.current);
    saveTimer.current = window.setTimeout(() => localStorage.setItem(key, String(value)), 300);
  };

  const selectTab = (id: string) => {
    setTab(id);
    localStorage.setItem('iednav.classic.tab', id);
  };

  return (
    <div className="flex h-full min-h-0">
      {/* ── Columna izquierda: panel de modo + Log ── */}
      <div style={{ width: leftW }} className="flex shrink-0 flex-col bg-gray-50 dark:bg-surface-raised">
        {mode === 'servidor' ? <ServerCard /> : <ClientCard />}
        <div className="min-h-0 flex-1 border-t border-gray-200 dark:border-surface-border">
          <LogPanel />
        </div>
      </div>
      <Splitter
        onDrag={(dx) =>
          setLeftW((w) => {
            const next = Math.min(560, Math.max(220, w + dx));
            persist('iednav.classic.leftW', next);
            return next;
          })
        }
      />

      {/* ── Centro: Modelo de Datos ── */}
      <div style={{ width: treeW }} className="flex shrink-0 flex-col bg-white dark:bg-surface">
        <SectionHeader title="Modelo de Datos" />
        <div className="min-h-0 flex-1">
          <ModelTreePanel />
        </div>
      </div>
      <Splitter
        onDrag={(dx) =>
          setTreeW((w) => {
            const next = Math.min(700, Math.max(220, w + dx));
            persist('iednav.classic.treeW', next);
            return next;
          })
        }
      />

      {/* ── Derecha: pestañas ── */}
      <div className="flex min-w-0 flex-1 flex-col bg-white dark:bg-surface">
        <div className="flex shrink-0 items-center gap-1 overflow-x-auto border-b border-gray-200 px-1 dark:border-surface-border">
          {visibleTabs.map((t) => (
            <button
              key={t.id}
              onClick={() => selectTab(t.id)}
              className={`whitespace-nowrap border-b-2 px-3 py-1.5 text-xs ${
                tab === t.id
                  ? 'border-accent font-semibold text-gray-900 dark:text-gray-100'
                  : 'border-transparent text-gray-500 hover:text-gray-800 dark:text-gray-400 dark:hover:text-gray-200'
              }`}
            >
              {t.title}
            </button>
          ))}
        </div>
        <div className="relative min-h-0 flex-1">
          {visibleTabs.map((t) => (
            <div key={t.id} className="absolute inset-0" style={{ display: tab === t.id ? undefined : 'none' }}>
              {t.el}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
