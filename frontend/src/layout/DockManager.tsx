import {
  DockviewReact,
  type DockviewApi,
  type DockviewReadyEvent,
} from 'dockview';
import 'dockview/dist/styles/dockview.css';
import { useCallback, useRef } from 'react';
import { useThemeStore } from '../stores/theme';
import { PANEL_DEFS, panelComponents } from './panelRegistry';

const LAYOUT_KEY = 'iednav.dockLayout.v6';

function buildDefaultLayout(api: DockviewApi) {
  const welcome = api.addPanel({
    id: PANEL_DEFS.welcome.id,
    component: PANEL_DEFS.welcome.component,
    title: PANEL_DEFS.welcome.title,
  });
  for (const key of ['monitor', 'reports', 'datasets', 'settingGroups', 'device', 'server', 'goose', 'sv', 'gooseMap', 'sclCompare'] as const) {
    api.addPanel({
      id: PANEL_DEFS[key].id,
      component: PANEL_DEFS[key].component,
      title: PANEL_DEFS[key].title,
      position: { referencePanel: welcome, direction: 'within' },
    });
  }
  welcome.api.setActive();
  api.addPanel({
    id: PANEL_DEFS.modelTree.id,
    component: PANEL_DEFS.modelTree.component,
    title: PANEL_DEFS.modelTree.title,
    position: { referencePanel: welcome, direction: 'left' },
    initialWidth: 320,
  });
  api.addPanel({
    id: PANEL_DEFS.log.id,
    component: PANEL_DEFS.log.component,
    title: PANEL_DEFS.log.title,
    position: { referencePanel: welcome, direction: 'below' },
    initialHeight: 180,
  });
}

/** Restaura el layout guardado; si falla (panel renombrado, corrupto) usa el default. */
export function resetDockLayout() {
  localStorage.removeItem(LAYOUT_KEY);
  location.reload();
}

export default function DockManager() {
  const theme = useThemeStore((s) => s.theme);
  const apiRef = useRef<DockviewApi | null>(null);
  const saveTimer = useRef<number | undefined>(undefined);

  const onReady = useCallback((event: DockviewReadyEvent) => {
    const api = event.api;
    apiRef.current = api;

    const saved = localStorage.getItem(LAYOUT_KEY);
    let restored = false;
    if (saved) {
      try {
        api.fromJSON(JSON.parse(saved));
        restored = true;
      } catch {
        localStorage.removeItem(LAYOUT_KEY);
      }
    }
    if (!restored) {
      buildDefaultLayout(api);
    }

    api.onDidLayoutChange(() => {
      window.clearTimeout(saveTimer.current);
      saveTimer.current = window.setTimeout(() => {
        try {
          localStorage.setItem(LAYOUT_KEY, JSON.stringify(api.toJSON()));
        } catch {
          /* quota */
        }
      }, 500);
    });
  }, []);

  return (
    <div className="h-full w-full">
      <DockviewReact
        className={theme === 'dark' ? 'dockview-theme-dark' : 'dockview-theme-light'}
        components={panelComponents}
        onReady={onReady}
      />
    </div>
  );
}
