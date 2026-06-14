import { useDialogStore } from '../stores/dialogs';
import AboutDialog from './AboutDialog';
import CancelSboDialog from './CancelSboDialog';
import ControlDialog from './ControlDialog';
import DictionaryDialog from './DictionaryDialog';
import LegendDialog from './LegendDialog';
import ServerValueEditDialog from './ServerValueEditDialog';
import ValueEditDialog from './ValueEditDialog';

/** Renderiza el diálogo activo (store global de diálogos). */
export default function DialogHost() {
  const kind = useDialogStore((s) => s.kind);
  const node = useDialogStore((s) => s.node);
  const close = useDialogStore((s) => s.close);

  if (!kind) return null;

  // Diálogos sin nodo asociado
  if (kind === 'legend') return <LegendDialog onClose={close} />;
  if (kind === 'about') return <AboutDialog onClose={close} />;

  if (!node) return null;

  switch (kind) {
    case 'control':
      return <ControlDialog node={node} onClose={close} />;
    case 'cancelSbo':
      return <CancelSboDialog node={node} onClose={close} />;
    case 'write':
      return <ValueEditDialog node={node} onClose={close} />;
    case 'serverWrite':
      return <ServerValueEditDialog node={node} onClose={close} />;
    case 'dictionary':
      return <DictionaryDialog node={node} onClose={close} />;
  }
}
