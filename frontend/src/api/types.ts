// Tipos compartidos con el bridge Java (REST + WS)

export interface BridgeInfo {
  port: number;
  token: string;
}

export interface NetworkInterfaceInfo {
  name: string;
  description: string | null;
}

export interface SystemInfo {
  app: string;
  version: string;
  javaVersion: string;
  os: string;
  npcapAvailable: boolean;
  interfaces: NetworkInterfaceInfo[];
  nativeDllAvailable: boolean;
}

export type NodeKind = 'LD' | 'LN' | 'DO' | 'DA' | 'CDA' | 'ARRAY' | 'NODE';

export interface ModelNodeDto {
  name: string;
  ref: string;
  kind: NodeKind;
  fc?: string;
  type?: string;
  value?: string;
  children?: ModelNodeDto[];
}

export interface ModelDto {
  iedName: string;
  logicalDevices: ModelNodeDto[];
}

export interface ClientStatus {
  connected: boolean;
  host?: string;
  port?: number;
  iedName?: string;
}

export interface ValueChange {
  ref: string;
  fc: string | null;
  value: string;
  type: string;
  ts: number;
}

export interface ControlInfo {
  operRef: string;
  ctlModel: number;
  ctlModelName: string;
  ctlValType: string;
  sbo: boolean;
  blockable: boolean;
}

export interface ControlResultDto {
  success: boolean;
  ctlModel: number;
  ctlModelName: string;
  error?: string;
  lastApplError?: string;
}

export interface DictEntry {
  token: string;
  kind: string;
  kindLabel: string;
  nameEs: string;
  nameEn: string;
  description: string;
  standard: string;
  example?: string;
  lnClass?: string;
}

export interface RcbInfo {
  ref: string;
  name: string;
  type: 'URCB' | 'BRCB';
  rptId: string;
  datSet: string;
  rptEna: boolean;
  enabledByBridge: boolean;
  trgOps: string;
  intgPd: number;
  bufTm: number;
  confRev: number;
  resv?: boolean;
}

export interface ReportEntry {
  ref: string;
  fc: string;
  value: string;
  reason: string;
}

export interface ReportEvent {
  rptId: string;
  sqNum: number | null;
  dataSetRef: string;
  bufOvfl: boolean | null;
  confRev: number | null;
  timeOfEntry: number | null;
  ts: number;
  entries: ReportEntry[];
}

export interface DataSetMember {
  ref: string;
  fc: string;
}

export interface DataSetInfo {
  ref: string;
  deletable: boolean;
  members: DataSetMember[];
}

export interface DataSetValue {
  ref: string;
  fc: string;
  value: string;
  type: string;
}

export interface SettingGroupInfo {
  ld: string;
  actSG: number;
  numOfSGs: number;
}

export interface IedFileInfo {
  name: string;
  size: number;
  lastModified: number | null;
}

export interface ServerStatus {
  running: boolean;
  modelLoaded: boolean;
  port?: number;
  sclPath?: string;
  iedName?: string;
  iedIndex?: number;
}

// ── GOOSE / SV (Fase 5) ──

export interface PcapInterfaceInfo {
  name: string;
  description: string | null;
  addresses: string[];
}

export interface NetInterfaces {
  npcapAvailable: boolean;
  interfaces: PcapInterfaceInfo[];
}

export interface GooseDataValue {
  index: number;
  name: string;
  type: string;
  value: string | null;
}

export interface GoCBInfo {
  index: number;
  ldInst: string;
  cbName: string;
  goId: string | null;
  datSet: string | null;
  appId: string | null;
  confRev: number;
  macAddress: string | null;
  vlanId: number;
  minTime: number;
  maxTime: number;
  publishing: boolean;
  stNum?: number;
  sqNum?: number;
  dataValues: GooseDataValue[];
}

export interface GooseUdpStatus {
  receiving: boolean;
  sending: boolean;
  port: number;
  targetIp?: string;
  sentCount?: number;
  receivedCount?: number;
}

export interface GooseState {
  sclPath?: string;
  iedName?: string;
  subscribing: boolean;
  subscriberInterface?: string;
  udp?: GooseUdpStatus;
  gocbs: GoCBInfo[];
}

export interface GooseMessageEntry {
  index: number;
  type: string;
  value: string | null;
  name?: string;
}

export interface GooseMessageEvent {
  source: 'local' | 'network' | 'udp';
  gcbIndex?: number;
  gocbRef: string;
  goId: string;
  datSet: string;
  appId: number;
  stNum: number;
  sqNum: number;
  confRev: number;
  test?: boolean;
  srcMac: string;
  dstMac: string;
  entries: GooseMessageEntry[];
  /** Marca de tiempo del envelope WS (añadida en el cliente). */
  ts?: number;
}

export interface SvSample {
  index: number;
  name: string;
  value: number;
  quality: number;
}

export interface SvMessage {
  svId: string;
  smpCnt: number;
  confRev: number;
  appId: number;
  smpRate?: number;
  ts: number;
  samples: SvSample[];
}

export interface SvStatus {
  running: boolean;
  nativeAvailable: boolean;
  interfaceId?: string;
  appId?: number;
  asduCount?: number;
  sampleCount?: number;
}

// ── Utilidades SCL ──

export interface SclDifference {
  category: string;
  element: string;
  valueA: string | null;
  valueB: string | null;
  status: string;
}

export interface SclCompareResult {
  fileA: string;
  fileB: string;
  total: number;
  byCategory: Record<string, number>;
  differences: SclDifference[];
}

export interface GooseMapPublisher {
  iedName: string;
  ldInst: string;
  cbName: string;
  datSet: string;
  appId: string;
  mac: string;
  appidHex: string;
  members: string[];
  subscriberCount: number;
}

export interface GooseMapSubscription {
  subscriberIed: string;
  pubIed: string;
  pubLd: string;
  pubCb: string;
  pubRef: string;
  dataRef: string;
  target: string;
  via: string;
  resolved: boolean;
}

export interface GooseMapResult {
  file: string;
  iedNames: string[];
  publishers: GooseMapPublisher[];
  subscriptions: GooseMapSubscription[];
}

export interface WsEnvelope<T = unknown> {
  type: string;
  seq: number;
  ts: number;
  payload: T;
}
