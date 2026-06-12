import type { IDockviewPanelProps } from 'dockview';
import DatasetPanel from '../panels/DatasetPanel';
import DevicePanel from '../panels/DevicePanel';
import GooseMapPanel from '../panels/GooseMapPanel';
import GoosePanel from '../panels/GoosePanel';
import SclComparePanel from '../panels/SclComparePanel';
import SvPanel from '../panels/SvPanel';
import LogPanel from '../panels/LogPanel';
import ModelTreePanel from '../panels/ModelTreePanel';
import MonitorPanel from '../panels/MonitorPanel';
import ReportsPanel from '../panels/ReportsPanel';
import ServerPanel from '../panels/ServerPanel';
import SettingGroupsPanel from '../panels/SettingGroupsPanel';
import WelcomePanel from '../panels/WelcomePanel';

/**
 * Registro de componentes de panel para dockview.
 * Los IDs son estables: se serializan en el layout persistido.
 */
export const panelComponents: Record<string, React.FC<IDockviewPanelProps>> = {
  modelTree: () => <ModelTreePanel />,
  welcome: () => <WelcomePanel />,
  log: () => <LogPanel />,
  monitor: () => <MonitorPanel />,
  reports: () => <ReportsPanel />,
  datasets: () => <DatasetPanel />,
  settingGroups: () => <SettingGroupsPanel />,
  device: () => <DevicePanel />,
  server: () => <ServerPanel />,
  goose: () => <GoosePanel />,
  sv: () => <SvPanel />,
  gooseMap: () => <GooseMapPanel />,
  sclCompare: () => <SclComparePanel />,
};

export interface PanelDef {
  id: string;
  component: keyof typeof panelComponents;
  title: string;
}

export const PANEL_DEFS: Record<string, PanelDef> = {
  modelTree: { id: 'modelTree', component: 'modelTree', title: 'Modelo IED' },
  welcome: { id: 'welcome', component: 'welcome', title: 'Inicio' },
  log: { id: 'log', component: 'log', title: 'Registro' },
  monitor: { id: 'monitor', component: 'monitor', title: 'Monitor' },
  reports: { id: 'reports', component: 'reports', title: 'Reports' },
  datasets: { id: 'datasets', component: 'datasets', title: 'DataSets' },
  settingGroups: { id: 'settingGroups', component: 'settingGroups', title: 'Setting Groups' },
  device: { id: 'device', component: 'device', title: 'Dispositivo' },
  server: { id: 'server', component: 'server', title: 'Servidor' },
  goose: { id: 'goose', component: 'goose', title: 'GOOSE' },
  sv: { id: 'sv', component: 'sv', title: 'Sampled Values' },
  gooseMap: { id: 'gooseMap', component: 'gooseMap', title: 'Mapa GOOSE' },
  sclCompare: { id: 'sclCompare', component: 'sclCompare', title: 'Comparar SCL' },
};
