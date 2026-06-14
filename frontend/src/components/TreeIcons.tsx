import type { ModelNodeDto } from '../api/types';

/* Réplica SVG 1:1 de IconFactory.java (iconos del árbol de la GUI original). */

type RGB = [number, number, number];

const rgb = ([r, g, b]: RGB) => `rgb(${r},${g},${b})`;
/** Como java.awt.Color.brighter()/darker() (factor 0.7). */
const brighter = (c: RGB): RGB => c.map((v) => Math.min(255, Math.round(Math.max(v, 3) / 0.7))) as RGB;
const darker = (c: RGB): RGB => c.map((v) => Math.round(v * 0.7)) as RGB;
const gid = (p: string, c: RGB) => `${p}${c[0]}_${c[1]}_${c[2]}`;

function Grad({ id, from, to }: { id: string; from: RGB; to: RGB }) {
  return (
    <linearGradient id={id} x1="1" y1="2" x2="15" y2="14" gradientUnits="userSpaceOnUse">
      <stop offset="0" stopColor={rgb(from)} />
      <stop offset="1" stopColor={rgb(to)} />
    </linearGradient>
  );
}

const LD_COLOR: RGB = [100, 100, 200];
const DO_COLOR: RGB = [150, 150, 200];
const DA_COLOR: RGB = [100, 180, 100];

/** Dispositivo lógico: rectángulo redondeado con gradiente y texto «LD». */
export function LdIcon({ size = 16 }: { size?: number }) {
  const id = gid('ld', LD_COLOR);
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" className="shrink-0">
      <defs>
        <Grad id={id} from={brighter(LD_COLOR)} to={LD_COLOR} />
      </defs>
      <rect x="1" y="2" width="14" height="12" rx="4" fill={`url(#${id})`} stroke={rgb(darker(LD_COLOR))} strokeWidth="0.9" />
      <text x="3" y="11" fontFamily="Arial" fontWeight="bold" fontSize="7" fill="#fff">LD</text>
    </svg>
  );
}

/** Nodo lógico: hexágono con gradiente y texto «LN». */
export function LnHexIcon({ color, size = 16 }: { color: RGB; size?: number }) {
  const id = gid('ln', color);
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" className="shrink-0">
      <defs>
        <Grad id={id} from={brighter(color)} to={darker(color)} />
      </defs>
      <polygon
        points="3,2 13,2 15,8 13,14 3,14 1,8"
        fill={`url(#${id})`}
        stroke={rgb(darker(darker(color)))}
        strokeWidth="0.8"
      />
      <text x="4" y="10.5" fontFamily="Arial" fontWeight="bold" fontSize="7" fill="rgba(255,255,255,0.85)">LN</text>
    </svg>
  );
}

/** Medidor analógico (grupos M, S, T). */
export function MeterIcon({ color, size = 16 }: { color: RGB; size?: number }) {
  const id = gid('mt', color);
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" className="shrink-0">
      <defs>
        <Grad id={id} from={[250, 250, 250]} to={[220, 220, 220]} />
      </defs>
      <rect x="1" y="1" width="14" height="14" rx="4" fill={`url(#${id})`} stroke={rgb(color)} strokeWidth="1.2" />
      <path d="M 13 9 A 5 5 0 0 0 3 9" fill="none" stroke={rgb(color)} strokeWidth="1.3" />
      <line x1="13" y1="9" x2="12" y2="9" stroke={rgb(color)} strokeWidth="1" />
      <line x1="8" y1="4" x2="8" y2="5" stroke={rgb(color)} strokeWidth="1" />
      <line x1="3" y1="9" x2="4" y2="9" stroke={rgb(color)} strokeWidth="1" />
      <line x1="8" y1="9" x2="12" y2="5" stroke={rgb(darker(color))} strokeWidth="1.5" strokeLinecap="round" />
      <circle cx="8" cy="9" r="2" fill={rgb(color)} />
    </svg>
  );
}

/** Escudo de protección (grupos P y R). */
export function ShieldIcon({ color, size = 16 }: { color: RGB; size?: number }) {
  const id = gid('sh', color);
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" className="shrink-0">
      <defs>
        <Grad id={id} from={brighter(color)} to={darker(color)} />
      </defs>
      <path
        d="M 2 2 L 14 2 L 14 9 Q 14 14 8 15 Q 2 14 2 9 Z"
        fill={`url(#${id})`}
        stroke={rgb(darker(darker(color)))}
        strokeWidth="1"
      />
      <line x1="8" y1="5" x2="8" y2="10" stroke="rgba(255,255,255,0.78)" strokeWidth="1.5" strokeLinecap="round" />
      <circle cx="8" cy="13" r="1" fill="rgba(255,255,255,0.78)" />
    </svg>
  );
}

/** Engranaje de 7 dientes (grupo A). */
function gearPoints(): string {
  const cx = 8;
  const cy = 8;
  const outer = 6.5;
  const inner = 4.5;
  const teeth = 7;
  const pts: string[] = [];
  for (let i = 0; i < teeth * 2; i++) {
    const angle = (i * Math.PI) / teeth - Math.PI / 2;
    const r = i % 2 === 0 ? outer : inner;
    pts.push(`${(cx + r * Math.cos(angle)).toFixed(2)},${(cy + r * Math.sin(angle)).toFixed(2)}`);
  }
  return pts.join(' ');
}
const GEAR_POINTS = gearPoints();

export function GearIcon({ color, size = 16 }: { color: RGB; size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" className="shrink-0">
      <polygon points={GEAR_POINTS} fill={rgb(color)} stroke={rgb(darker(color))} strokeWidth="0.8" />
      <circle cx="8" cy="8" r="2.5" fill="rgb(240,240,240)" stroke={rgb(darker(color))} strokeWidth="0.7" />
    </svg>
  );
}

/** Diamante (grupos L y G). */
export function DiamondIcon({ color, size = 16 }: { color: RGB; size?: number }) {
  const id = gid('di', color);
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" className="shrink-0">
      <defs>
        <Grad id={id} from={brighter(color)} to={darker(color)} />
      </defs>
      <polygon points="8,2 14,8 8,14 2,8" fill={`url(#${id})`} stroke={rgb(darker(color))} strokeWidth="0.9" />
      <line x1="5" y1="5" x2="10" y2="5" stroke="rgba(255,255,255,0.43)" strokeWidth="0.8" />
    </svg>
  );
}

/** Data Object: rectángulo claro con borde coloreado y líneas internas. */
export function DoIcon({ size = 16 }: { size?: number }) {
  const c = rgb(DO_COLOR);
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" className="shrink-0">
      <rect x="2" y="2" width="12" height="12" rx="3" fill="rgb(245,245,245)" stroke={c} strokeWidth="1.2" />
      <line x1="4" y1="6" x2="12" y2="6" stroke={c} strokeWidth="0.8" />
      <line x1="4" y1="9" x2="12" y2="9" stroke={c} strokeWidth="0.8" />
      <line x1="4" y1="12" x2="10" y2="12" stroke={c} strokeWidth="0.8" />
    </svg>
  );
}

/** Data Attribute: círculo verde con reflejo (12 px en el original). */
export function DaIcon({ size = 12 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 12 12" className="shrink-0">
      <circle cx="6" cy="6" r="5" fill={rgb(DA_COLOR)} stroke={rgb(darker(DA_COLOR))} strokeWidth="0.8" />
      <circle cx="4.5" cy="4.5" r="2.5" fill="rgba(255,255,255,0.35)" />
    </svg>
  );
}

export type BreakerState = 'on' | 'off' | 'intermediate';

/** Disyuntor: cerrado (verde), abierto (rojo) o intermedio (naranja). */
export function BreakerIcon({ state, size = 16 }: { state: BreakerState; size?: number }) {
  const color: RGB = state === 'on' ? [0, 180, 0] : state === 'off' ? [220, 50, 50] : [255, 165, 0];
  const bg: RGB = state === 'on' ? [200, 255, 200] : state === 'off' ? [255, 200, 200] : [255, 240, 200];
  const closed = state === 'on';
  const c = rgb(color);
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" className="shrink-0">
      <rect x="0.5" y="0.5" width="15" height="15" rx="4" fill={rgb(bg)} stroke={c} strokeWidth="1.5" />
      <line x1="2" y1="10" x2="5" y2="10" stroke={c} strokeWidth="2" />
      <line x1="11" y1="10" x2="14" y2="10" stroke={c} strokeWidth="2" />
      {closed ? (
        <line x1="5" y1="10" x2="11" y2="10" stroke={c} strokeWidth="2" />
      ) : (
        <line x1="5" y1="10" x2="10" y2="5" stroke={c} strokeWidth="2" />
      )}
      <circle cx="6" cy="10" r="2" fill={c} />
    </svg>
  );
}

/* ── Selección de icono (réplica de ModelTreeCellRenderer.getIconForNode/lnIcon) ── */

export function lnIconFor(name: string, size = 16): React.ReactElement {
  const cls = name.replace(/\d+$/, '').toUpperCase();
  if (cls.startsWith('XCBR')) return <LnHexIcon color={[200, 50, 50]} size={size} />;
  if (cls.startsWith('XSWI') || cls.startsWith('CSWI'))
    return <LnHexIcon color={[200, 100, 50]} size={size} />;
  if (cls.startsWith('MMTR') || cls.startsWith('MSTA'))
    return <MeterIcon color={[0, 150, 100]} size={size} />;
  if (cls.startsWith('CILO')) return <LnHexIcon color={[150, 100, 200]} size={size} />;
  switch (cls.charAt(0)) {
    case 'X':
      return <LnHexIcon color={[200, 100, 50]} size={size} />;
    case 'C':
      return <LnHexIcon color={[150, 100, 200]} size={size} />;
    case 'M':
      return <MeterIcon color={[0, 100, 200]} size={size} />;
    case 'P':
      return <ShieldIcon color={[180, 30, 30]} size={size} />;
    case 'R':
      return <ShieldIcon color={[130, 30, 170]} size={size} />;
    case 'A':
      return <GearIcon color={[0, 150, 170]} size={size} />;
    case 'L':
      return <DiamondIcon color={[70, 70, 70]} size={size} />;
    case 'G':
      return <DiamondIcon color={[90, 90, 90]} size={size} />;
    case 'S':
      return <MeterIcon color={[20, 140, 120]} size={size} />;
    case 'T':
      return <MeterIcon color={[140, 80, 0]} size={size} />;
    case 'I':
      return <LnHexIcon color={[50, 90, 200]} size={size} />;
    case 'Z':
      return <LnHexIcon color={[80, 80, 150]} size={size} />;
    default:
      return <LnHexIcon color={[100, 150, 100]} size={size} />;
  }
}

/** Estado de disyuntor a partir del valor de stVal (como el renderer original). */
function breakerStateFor(value: string): BreakerState {
  const v = value.toLowerCase();
  const label =
    v.includes('[') && v.includes(']') ? v.substring(v.indexOf('[') + 1, v.lastIndexOf(']')).trim() : v;
  if (label === 'on' || label === 'ok' || label === 'closed' || (!v.includes('[') && v === '2')) return 'on';
  if (label === 'off' || label === 'alarm' || label === 'open' || (!v.includes('[') && v === '1')) return 'off';
  return 'intermediate';
}

/** Icono del nodo del árbol, idéntico al original. */
export function TreeIcon({ node, value }: { node: ModelNodeDto; value?: string }) {
  const name = node.name.toUpperCase();
  switch (node.kind) {
    case 'LD':
      return <LdIcon />;
    case 'LN':
      return lnIconFor(node.name);
    case 'DO':
      if (name === 'POS' || name === 'BLKOPN' || name === 'BLKCLS')
        return <BreakerIcon state={value ? breakerStateFor(value) : 'intermediate'} />;
      return <DoIcon />;
    default:
      if (name === 'STVAL' && value !== undefined)
        return <BreakerIcon state={breakerStateFor(value)} />;
      return <DaIcon />;
  }
}
