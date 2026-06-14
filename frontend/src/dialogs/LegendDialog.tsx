import type React from 'react';
import Modal from '../components/Modal';
import {
  BreakerIcon,
  DaIcon,
  DoIcon,
  LdIcon,
  lnIconFor,
  type BreakerState,
} from '../components/TreeIcons';

/* Réplica completa de showLegendDialog() de la GUI original (iconos y colores exactos). */

const NODE_TYPES: Array<[React.ReactElement, string]> = [
  [<LdIcon key="ld" />, 'LD (Dispositivo Lógico)'],
  [<DoIcon key="do" />, 'DO (Objeto de Datos)'],
  [<DaIcon key="da" />, 'DA/BDA (Atributo de Dato)'],
];

const LN_GROUPS: Array<[React.ReactElement, string]> = [
  [lnIconFor('XCBR'), 'Grupo X — Equipos de corte: XCBR (disyuntor)'],
  [lnIconFor('XSWI'), 'Grupo X/C — Seccionadores y control: XSWI, CSWI'],
  [lnIconFor('CILO'), 'Grupo C — Control: CILO, CPOW, CPDM'],
  [lnIconFor('MMXU'), 'Grupo M — Medición: MMXU, MSQI, MHAI'],
  [lnIconFor('MMTR'), 'Grupo M — Energía/demanda: MMTR, MSTA'],
  [lnIconFor('PDIS'), 'Grupo P — Protección: PDIS, PDIF, PTRC, PDIR'],
  [lnIconFor('RREC'), 'Grupo R — Protección relacionada: RREC, RPSB, RSYN'],
  [lnIconFor('ATCC'), 'Grupo A — Control automático: ATCC, ARCO, ARIS'],
  [lnIconFor('LLN0'), 'Grupo L — Sistema: LLN0, LPHD'],
  [lnIconFor('GGIO'), 'Grupo G — Genérico: GAPC, GGIO'],
  [lnIconFor('STMP'), 'Grupo S — Supervisión/sensores: STMP, SARC, SIMG'],
  [lnIconFor('TCTR'), 'Grupo T — Transformadores: TCTR, TVTR'],
  [lnIconFor('IHMI'), 'Grupo I — Interfaz: IHMI, ITCI, ITMI'],
  [lnIconFor('ZAXN'), 'Grupo Z — Otros equipos: ZAXN, ZBAT'],
  [lnIconFor('QXYZ'), 'Sin clasificar — LN personalizado no reconocido'],
];

const BREAKER_STATES: Array<[BreakerState, string, string]> = [
  ['on', 'Cerrado / ON (valor 2)', 'Disyuntor cerrado'],
  ['off', 'Abierto / OFF (valor 1)', 'Disyuntor abierto'],
  ['intermediate', 'Intermedio / Transitorio (valor 0 o 3)', 'Posición indeterminada'],
];

const TEXT_COLORS: Array<[string, string]> = [
  ['rgb(0,150,0)', 'Valor activo: ON, OK, CLOSED, TRUE'],
  ['rgb(200,0,0)', 'Valor inactivo: OFF, ALARM, OPEN, FALSE'],
  ['rgb(255,140,0)', 'Advertencia: INTERMEDIATE, BAD, WARNING, TEST'],
  ['rgb(120,80,180)', 'Bloqueado (FC=BL, blkEna=true)'],
  ['rgb(0,100,200)', 'En Watchlist (monitoreo activo)'],
];

export const FC_RGB: Record<string, string> = {
  ST: 'rgb(21,101,192)',
  MX: 'rgb(0,105,92)',
  CO: 'rgb(183,28,28)',
  CF: 'rgb(74,20,140)',
  DC: 'rgb(55,71,79)',
  SP: 'rgb(230,81,0)',
  SG: 'rgb(245,127,23)',
  SE: 'rgb(130,119,23)',
  BL: 'rgb(78,52,46)',
  EX: 'rgb(84,110,122)',
  OR: 'rgb(27,94,32)',
  RP: 'rgb(0,96,100)',
  BR: 'rgb(1,87,155)',
  GO: 'rgb(136,14,79)',
};

const FC_DESC: Array<[string, string]> = [
  ['ST', 'Status — Estado: stVal, q, t. Solo lectura'],
  ['MX', 'Measurands — Mediciones analógicas'],
  ['CO', 'Control — Comandos: Oper, SBOw, Cancel'],
  ['CF', 'Configuration — Parámetros del IED'],
  ['DC', 'Description — Placa: vendor, model, serial'],
  ['SP', 'Setting — Ajustes operativos (setpoints)'],
  ['SG', 'Setting Group — Selector grupo activo'],
  ['SE', 'Setting Group Edit — Edición grupo inactivo'],
  ['BL', 'Blocking — Bloqueo (blkEna)'],
  ['EX', 'Extended — Atributos propietarios'],
  ['OR', 'Operate Received — Confirmación de mando'],
  ['RP', 'Unbuffered Report (URCB)'],
  ['BR', 'Buffered Report (BRCB)'],
  ['GO', 'GOOSE — Control de publicación'],
];

const CDC_SECTIONS: Array<[string, Array<[string, string]>]> = [
  [
    'Estado binario',
    [
      ['SPS', 'Single Point Status — stVal BOOL. Uso: ON/OFF (alarmas)'],
      ['DPS', 'Double Point Status — stVal {off|intermediate|on|bad}. Uso: disyuntores'],
      ['ACT', 'Protection Activation — general BOOL + phsA/B/C. Uso: disparo'],
      ['ACD', 'Directional Protection Activation — dirGeneral {forward|backward}'],
    ],
  ],
  [
    'Estado entero/enumerado',
    [
      ['INS', 'Integer Status — stVal INT32. Uso: posición de tap, contadores'],
      ['ENS', 'Enumerated Status — stVal enum. Uso: estados nombrados'],
      ['BCR', 'Binary Counter Reading — actVal INT64. Uso: energía activa/reactiva (kWh/kVArh)'],
    ],
  ],
  [
    'Medición analógica',
    [
      ['MV', 'Measured Value — mag.f FLOAT32. Uso: frecuencia, temperatura, potencia'],
      ['CMV', 'Complex Measured Value — fasorial monofásico (magnitud + ángulo)'],
      ['WYE', 'Three Phase Y — phsA/phsB/phsC CMV. Uso: tensiones/corrientes de fase'],
      ['DEL', 'Three Phase Δ — phsAB/phsBC/phsCA CMV. Uso: tensiones de línea'],
      ['SEQ', 'Sequence Components — c1/c2/c0 CMV. Uso: componentes simétricas'],
      ['HMV', 'Harmonic Measured Value — array de armónicos. Uso: THD (H1–H50)'],
    ],
  ],
  [
    'Control (comandables)',
    [
      ['SPC', 'Single Point Controllable — stVal BOOL + Oper'],
      ['DPC', 'Double Point Controllable — stVal DPS + Oper. Uso: disyuntores/seccionadores'],
      ['APC', 'Analogue Point Controllable — setMag FLOAT32 + Oper. Uso: setpoint analógico'],
      ['BSC', 'Binary Controlled Step — valWTr + Oper (RAISE/LOWER)'],
    ],
  ],
  [
    'Ajustes (FC = SP/SG/SE)',
    [
      ['ING', 'Integer Setting — setVal INT32. Uso: retardos (ms), contadores'],
      ['SPG', 'Single Point Setting — setVal BOOL. Uso: habilitación de funciones'],
      ['ASG', 'Analogue Setting — setVal FLOAT32. Uso: umbrales de protección'],
      ['ENG', 'Enumerated Setting — setVal enum. Uso: modo de operación'],
    ],
  ],
  [
    'Placa del equipo (FC = DC)',
    [
      ['DPL', 'Device Name Plate — vendor, model, hwRev, swRev, serNum, location'],
      ['LPL', 'LN Name Plate — vendor, swRev, d (descripción)'],
    ],
  ],
];

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div>
      <div className="mb-1.5 border-b border-gray-200 pb-1 text-[11px] font-bold uppercase tracking-wide text-gray-700 dark:border-surface-border dark:text-gray-200">
        {title}
      </div>
      {children}
    </div>
  );
}

export default function LegendDialog({ onClose }: { onClose: () => void }) {
  return (
    <Modal title="Leyenda de íconos y colores" onClose={onClose} width={560}>
      <div className="flex max-h-[68vh] flex-col gap-4 overflow-auto pr-1 text-xs">
        <Section title="Tipos de nodo en el árbol">
          <table className="w-full">
            <tbody>
              {NODE_TYPES.map(([icon, desc]) => (
                <tr key={desc}>
                  <td className="w-6 py-0.5">{icon}</td>
                  <td className="py-0.5 text-gray-600 dark:text-gray-300">{desc}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Section>

        <Section title="Nodos Lógicos (LN) por grupo — IEC 61850-7-4">
          <table className="w-full">
            <tbody>
              {LN_GROUPS.map(([icon, desc]) => (
                <tr key={desc}>
                  <td className="w-6 py-0.5">{icon}</td>
                  <td className="py-0.5 text-gray-600 dark:text-gray-300">{desc}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Section>

        <Section title="Estados de disyuntor (DA stVal)">
          <table className="w-full">
            <tbody>
              {BREAKER_STATES.map(([state, label, desc]) => (
                <tr key={state}>
                  <td className="w-5 py-0.5"><BreakerIcon state={state} size={14} /></td>
                  <td className="py-0.5 pr-3 font-medium text-gray-700 dark:text-gray-200">{label}</td>
                  <td className="py-0.5 text-gray-500 dark:text-gray-400">{desc}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Section>

        <Section title="Colores de texto en el árbol">
          <table className="w-full">
            <tbody>
              {TEXT_COLORS.map(([color, desc]) => (
                <tr key={desc}>
                  <td className="w-8 py-0.5">
                    <span
                      className="inline-block h-3 w-3 rounded-sm align-middle"
                      style={{ backgroundColor: color }}
                    />
                  </td>
                  <td className="py-0.5 text-gray-600 dark:text-gray-300">{desc}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Section>

        <Section title="Functional Constraints (FC) — IEC 61850-7-2">
          <table className="w-full">
            <tbody>
              {FC_DESC.map(([fc, desc]) => (
                <tr key={fc}>
                  <td className="w-10 py-0.5">
                    <span
                      className="rounded px-1.5 py-0.5 text-[10px] font-bold text-white"
                      style={{ backgroundColor: FC_RGB[fc] }}
                    >
                      {fc}
                    </span>
                  </td>
                  <td className="py-0.5 pl-2 text-gray-600 dark:text-gray-300">{desc}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Section>

        <Section title="Common Data Classes (CDC) — IEC 61850-7-3">
          <div className="flex flex-col gap-2.5">
            {CDC_SECTIONS.map(([group, entries]) => (
              <div key={group}>
                <div className="mb-0.5 font-semibold text-gray-600 dark:text-gray-300">{group}:</div>
                <table className="w-full">
                  <tbody>
                    {entries.map(([cdc, desc]) => (
                      <tr key={cdc}>
                        <td className="w-12 py-0.5 align-top font-mono font-bold text-accent">{cdc}</td>
                        <td className="py-0.5 text-gray-600 dark:text-gray-300">{desc}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ))}
          </div>
        </Section>
      </div>
    </Modal>
  );
}
