import type {
  BridgeInfo,
  ClientStatus,
  ControlInfo,
  ControlResultDto,
  DataSetInfo,
  DataSetValue,
  DictEntry,
  GooseMapResult,
  GooseState,
  IedFileInfo,
  ModelDto,
  NetInterfaces,
  RcbInfo,
  SclCompareResult,
  ServerStatus,
  SettingGroupInfo,
  SvStatus,
  SystemInfo,
} from './types';

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

function isTauri(): boolean {
  return '__TAURI_INTERNALS__' in window;
}

/**
 * Resuelve puerto+token del bridge:
 * - En Tauri: pregunta al proceso Rust (que lanzó el sidecar Java) hasta que
 *   el handshake BRIDGE_READY haya ocurrido.
 * - En navegador (dev): query params ?bridgePort=&bridgeToken= o localStorage.
 */
export async function resolveBridgeInfo(): Promise<BridgeInfo> {
  if (isTauri()) {
    const { invoke } = await import('@tauri-apps/api/core');
    for (let i = 0; i < 240; i++) {
      const info = await invoke<BridgeInfo | null>('get_bridge_info');
      if (info) return info;
      await sleep(250);
    }
    throw new Error('El backend Java no respondió (timeout de handshake)');
  }
  const params = new URLSearchParams(location.search);
  const port = Number(params.get('bridgePort') ?? localStorage.getItem('bridgePort') ?? 0);
  const token = params.get('bridgeToken') ?? localStorage.getItem('bridgeToken') ?? '';
  if (port > 0) {
    localStorage.setItem('bridgePort', String(port));
    localStorage.setItem('bridgeToken', token);
    return { port, token };
  }
  throw new Error(
    'Modo navegador: arranque el bridge y abra con ?bridgePort=<n>&bridgeToken=<token>',
  );
}

/** Cliente REST del bridge. Instancia única inicializada en App. */
export class BridgeApi {
  readonly info: BridgeInfo;

  constructor(info: BridgeInfo) {
    this.info = info;
  }

  private get base(): string {
    return `http://127.0.0.1:${this.info.port}/api/v1`;
  }

  get wsUrl(): string {
    return `ws://127.0.0.1:${this.info.port}/ws?token=${encodeURIComponent(this.info.token)}`;
  }

  private async request<T>(method: string, path: string, body?: unknown): Promise<T> {
    const res = await fetch(this.base + path, {
      method,
      headers: {
        'X-Bridge-Token': this.info.token,
        ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
    if (!res.ok) {
      let message = `HTTP ${res.status}`;
      try {
        const err = (await res.json()) as { error?: string };
        if (err.error) message = err.error;
      } catch {
        /* respuesta no JSON */
      }
      throw new Error(message);
    }
    return (await res.json()) as T;
  }

  // ── Sistema ──
  systemInfo() {
    return this.request<SystemInfo>('GET', '/system/info');
  }
  ping() {
    return this.request<{ pong: number }>('POST', '/system/ping');
  }
  shutdown() {
    return this.request<{ status: string }>('POST', '/system/shutdown');
  }
  portCheck(port: number) {
    return this.request<{ port: number; free: boolean; error?: string }>(
      'GET', `/system/portcheck?port=${port}`,
    );
  }
  portRelease(port: number) {
    return this.request<{ port: number; released: boolean; pid?: number; message?: string }>(
      'POST', '/system/portrelease', { port },
    );
  }

  // ── Cliente IEC 61850 ──
  connect(host: string, port: number, timeoutMs?: number) {
    return this.request<ClientStatus>('POST', '/client/connect', { host, port, timeoutMs });
  }
  disconnect() {
    return this.request<ClientStatus>('POST', '/client/disconnect');
  }
  status() {
    return this.request<ClientStatus>('GET', '/client/status');
  }
  model() {
    return this.request<ModelDto>('GET', '/client/model');
  }
  read(ref: string, fc: string) {
    return this.request<{ ref: string; fc: string; value: string }>('POST', '/client/read', { ref, fc });
  }
  write(ref: string, fc: string, value: string) {
    return this.request<{ ref: string; fc: string; written: boolean }>('POST', '/client/write', { ref, fc, value });
  }
  setWatchlist(refs: string[], intervalMs: number) {
    return this.request<{ refs: string[]; intervalMs: number }>('PUT', '/client/watchlist', { refs, intervalMs });
  }
  nameplate() {
    return this.request<Record<string, string>>('GET', '/client/nameplate');
  }

  // ── Control SBO/direct ──
  controlInfo(ref: string) {
    return this.request<ControlInfo>('GET', `/client/control-info?ref=${encodeURIComponent(ref)}`);
  }
  operate(req: {
    ref: string;
    value: string;
    test?: boolean;
    orIdent?: string;
    synchroCheck?: boolean;
    interlockCheck?: boolean;
  }) {
    return this.request<ControlResultDto>('POST', '/client/operate', req);
  }
  cancelControl(ref: string, orIdent?: string) {
    return this.request<ControlResultDto>('POST', '/client/cancel', { ref, orIdent });
  }
  blocking(ref: string, block: boolean) {
    return this.request<{ ref: string; blocked: boolean }>('POST', '/client/blocking', { ref, block });
  }

  // ── Reports (RCBs) ──
  rcbs(refresh = false) {
    return this.request<{ rcbs: RcbInfo[] }>('GET', `/client/rcbs?refresh=${refresh}`);
  }
  enableRcb(ref: string) {
    return this.request<{ ref: string; enabled: boolean }>('POST', '/client/rcbs/enable', { ref });
  }
  disableRcb(ref: string) {
    return this.request<{ ref: string; enabled: boolean }>('POST', '/client/rcbs/disable', { ref });
  }

  // ── DataSets ──
  datasets() {
    return this.request<{ datasets: DataSetInfo[] }>('GET', '/client/datasets');
  }
  readDataSet(ref: string) {
    return this.request<{ ref: string; values: DataSetValue[] }>('POST', '/client/dataset/read', { ref });
  }

  // ── Setting Groups ──
  settingGroups() {
    return this.request<{ settingGroups: SettingGroupInfo[] }>('GET', '/client/sg');
  }
  selectSettingGroup(ld: string, group: number) {
    return this.request<SettingGroupInfo>('POST', '/client/sg/select', { ld, group });
  }

  // ── Ficheros del IED ──
  listIedFiles(dir = '') {
    return this.request<{ dir: string; files: IedFileInfo[] }>(
      'GET', `/client/files?dir=${encodeURIComponent(dir)}`,
    );
  }
  findSclFiles() {
    return this.request<{ files: string[] }>('GET', '/client/files/scl');
  }
  async downloadIedFile(path: string): Promise<Blob> {
    const res = await fetch(
      `${this.base}/client/files/download?path=${encodeURIComponent(path)}`,
      { headers: { 'X-Bridge-Token': this.info.token } },
    );
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return res.blob();
  }
  async exportModelHtml(includeValues: boolean): Promise<string> {
    const res = await fetch(`${this.base}/client/export/model-html?values=${includeValues}`, {
      headers: { 'X-Bridge-Token': this.info.token },
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return res.text();
  }

  // ── Servidor simulado ──
  serverParseScl(path: string) {
    return this.request<{ path: string; ieds: string[] }>('POST', '/server/scl/parse', { path });
  }
  serverLoad(path: string, iedIndex: number) {
    return this.request<ServerStatus>('POST', '/server/load', { path, iedIndex });
  }
  serverStart(port: number) {
    return this.request<ServerStatus>('POST', '/server/start', { port });
  }
  serverStop() {
    return this.request<ServerStatus>('POST', '/server/stop');
  }
  serverStatus() {
    return this.request<ServerStatus>('GET', '/server/status');
  }
  serverModel() {
    return this.request<ModelDto>('GET', '/server/model');
  }
  serverSetValue(ref: string, value: string) {
    return this.request<{ ref: string; value: string; written: boolean }>('POST', '/server/value', { ref, value });
  }

  // ── GOOSE / SV ──
  netInterfaces() {
    return this.request<NetInterfaces>('GET', '/net/interfaces');
  }
  gooseLoadScl(path: string, iedIndex: number) {
    return this.request<GooseState>('POST', '/goose/scl/load', { path, iedIndex });
  }
  gooseStatus() {
    return this.request<GooseState>('GET', '/goose/status');
  }
  goosePublish(index: number, interfaceName: string) {
    return this.request<GooseState>('POST', '/goose/publish', { index, interfaceName });
  }
  gooseStop(index: number) {
    return this.request<GooseState>('POST', '/goose/stop', { index });
  }
  gooseSetValue(index: number, dataIndex: number, value: string) {
    return this.request<GooseState>('POST', '/goose/value', { index, dataIndex, value });
  }
  gooseSubscribe(interfaceName: string) {
    return this.request<GooseState>('POST', '/goose/subscribe', { interfaceName });
  }
  gooseUnsubscribe() {
    return this.request<GooseState>('POST', '/goose/unsubscribe');
  }
  gooseUdpStart(receive: boolean, send: boolean, targetIp?: string) {
    return this.request<GooseState>('POST', '/goose/udp/start', { receive, send, targetIp });
  }
  gooseUdpStop() {
    return this.request<GooseState>('POST', '/goose/udp/stop');
  }
  svSubscribe(interfaceId: string, appId: number) {
    return this.request<SvStatus>('POST', '/sv/subscribe', { interfaceId, appId });
  }
  svUnsubscribe() {
    return this.request<SvStatus>('POST', '/sv/unsubscribe');
  }
  svStatus() {
    return this.request<SvStatus>('GET', '/sv/status');
  }

  // ── Utilidades SCL ──
  sclCompare(pathA: string, pathB: string, ignoreIedName: boolean) {
    return this.request<SclCompareResult>('POST', '/scl/compare', { pathA, pathB, ignoreIedName });
  }
  sclGooseMap(path: string) {
    return this.request<GooseMapResult>('POST', '/scl/goose-map', { path });
  }

  // ── Diccionario IEC 61850 ──
  dictionary(token: string) {
    return this.request<DictEntry>('GET', `/dictionary/${encodeURIComponent(token)}`);
  }
}

let api: BridgeApi | null = null;

export function setApi(instance: BridgeApi) {
  api = instance;
}

/** Acceso global al API (tras inicialización en App). */
export function getApi(): BridgeApi {
  if (!api) throw new Error('BridgeApi no inicializado');
  return api;
}
