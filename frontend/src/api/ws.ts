import type { WsEnvelope } from './types';

type Handler = (envelope: WsEnvelope) => void;
export type WsState = 'connecting' | 'open' | 'closed';

/**
 * Socket de eventos push del bridge con reconexión automática
 * (backoff 0.5s → 5s) y suscripción por tipo de evento.
 */
export class BridgeSocket {
  private ws: WebSocket | null = null;
  private handlers = new Map<string, Set<Handler>>();
  private stateHandlers = new Set<(s: WsState) => void>();
  private retryMs = 500;
  private closedByUser = false;
  private pingTimer: number | undefined;

  private url: string;

  constructor(url: string) {
    this.url = url;
  }

  connect() {
    this.closedByUser = false;
    this.open();
  }

  private open() {
    this.notifyState('connecting');
    this.ws = new WebSocket(this.url);

    this.ws.onopen = () => {
      this.retryMs = 500;
      this.notifyState('open');
      // keepalive: cuenta como actividad para el watchdog del bridge
      this.pingTimer = window.setInterval(() => this.ws?.send('ping'), 10_000);
    };

    this.ws.onmessage = (e) => {
      try {
        const env = JSON.parse(e.data as string) as WsEnvelope;
        this.dispatch(env);
      } catch {
        /* mensaje no JSON */
      }
    };

    this.ws.onclose = () => {
      window.clearInterval(this.pingTimer);
      this.notifyState('closed');
      if (!this.closedByUser) {
        setTimeout(() => this.open(), this.retryMs);
        this.retryMs = Math.min(this.retryMs * 2, 5000);
      }
    };

    this.ws.onerror = () => {
      this.ws?.close();
    };
  }

  close() {
    this.closedByUser = true;
    window.clearInterval(this.pingTimer);
    this.ws?.close();
  }

  /** Suscribe un handler a un tipo de evento ('*' = todos). Devuelve unsubscribe. */
  on(type: string, handler: Handler): () => void {
    let set = this.handlers.get(type);
    if (!set) {
      set = new Set();
      this.handlers.set(type, set);
    }
    set.add(handler);
    return () => set!.delete(handler);
  }

  onState(handler: (s: WsState) => void): () => void {
    this.stateHandlers.add(handler);
    return () => this.stateHandlers.delete(handler);
  }

  private dispatch(env: WsEnvelope) {
    this.handlers.get(env.type)?.forEach((h) => h(env));
    this.handlers.get('*')?.forEach((h) => h(env));
  }

  private notifyState(s: WsState) {
    this.stateHandlers.forEach((h) => h(s));
  }
}

let socket: BridgeSocket | null = null;

export function setSocket(s: BridgeSocket) {
  socket = s;
}

export function getSocket(): BridgeSocket {
  if (!socket) throw new Error('BridgeSocket no inicializado');
  return socket;
}
