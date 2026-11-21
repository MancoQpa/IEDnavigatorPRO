package com.beanit.iec61850bean;

import com.beanit.asn1bean.ber.types.BerBitString;
import com.beanit.iec61850bean.internal.mms.asn1.Data;

/**
 * ReasonCode de un reporte (IEC 61850-7-2 / 8-1).
 *
 * <p><b>Esta clase sombrea deliberadamente a com.beanit.iec61850bean.BdaReasonForInclusion de
 * iec61850bean-1.9.0.</b> Es la fuente original reconstruida desde el jar, con un cambio: al
 * <i>enviar</i> un reporte, el motivo va con 6 bits en vez de 7.
 *
 * <p>La biblioteca codificaba siempre 7 bits (relleno 1: {@code 84 02 01 04} para GI). La
 * herramienta comercial (versión 5.10), conectada como cliente al simulador, cortaba la asociación en cuanto recibía el
 * primer reporte (GI o integridad), 1 ms después. Su propio simulador, con el mismo CID, manda
 * 6 bits ({@code 84 02 02 04}); con un proxy que cambió solo ese largo, la herramienta comercial aceptó el
 * reporte (2026-11-15). Los 6 bits son reserved, data-change, quality-change, data-update,
 * integrity y general-interrogation; el séptimo, application-trigger (Ed2), solo se manda
 * cuando está encendido, que el servidor no hace.
 *
 * <p>El tamaño de la clase sigue en 7: {@link BdaBitString#setValueFromMmsDataObj} rechaza un
 * bit-string más largo que el tipo, y hay IED que mandan 7 bits. Así el cliente acepta los
 * dos largos ({@code test/TestMotivoReporte}).
 */
public final class BdaReasonForInclusion extends BdaBitString {

  /** Bits que se mandan si application-trigger está apagado (Ed1 y la mayoría de los IED). */
  static final int BITS_SIN_APP_TRIGGER = 6;

  public BdaReasonForInclusion(ObjectReference objectReference) {
    super(objectReference, null, null, 7, false, false);
    basicType = BdaType.REASON_FOR_INCLUSION;
    setDefault();
  }

  @Override
  public BdaReasonForInclusion copy() {
    BdaReasonForInclusion copy = new BdaReasonForInclusion(objectReference);

    byte[] valueCopy = new byte[value.length];
    System.arraycopy(value, 0, valueCopy, 0, value.length);
    copy.setValue(valueCopy);

    if (mirror == null) {
      copy.mirror = this;
    } else {
      copy.mirror = mirror;
    }
    return copy;
  }

  /** El cambio: 6 bits salvo que haga falta el séptimo (application-trigger). */
  @Override
  Data getMmsDataObj() {
    if (isApplicationTrigger()) return super.getMmsDataObj();
    Data data = new Data();
    data.setBitString(new BerBitString(new byte[] {(byte) (value[0] & 0xFC)}, BITS_SIN_APP_TRIGGER));
    return data;
  }

  public boolean isDataChange() {
    return (value[0] & 0x40) == 0x40;
  }

  public void setDataChange(boolean dataChange) {
    if (dataChange) {
      value[0] = (byte) (value[0] | 0x40);
    } else {
      value[0] = (byte) (value[0] & 0xbf);
    }
  }

  public boolean isQualityChange() {
    return (value[0] & 0x20) == 0x20;
  }

  public void setQualityChange(boolean qualityChange) {
    if (qualityChange) {
      value[0] = (byte) (value[0] | 0x20);
    } else {
      value[0] = (byte) (value[0] & 0xdf);
    }
  }

  public boolean isDataUpdate() {
    return (value[0] & 0x10) == 0x10;
  }

  public void setDataUpdate(boolean dataUpdate) {
    if (dataUpdate) {
      value[0] = (byte) (value[0] | 0x10);
    } else {
      value[0] = (byte) (value[0] & 0xef);
    }
  }

  public boolean isIntegrity() {
    return (value[0] & 0x08) == 0x08;
  }

  public void setIntegrity(boolean integrity) {
    if (integrity) {
      value[0] = (byte) (value[0] | 0x08);
    } else {
      value[0] = (byte) (value[0] & 0xf7);
    }
  }

  public boolean isGeneralInterrogation() {
    return (value[0] & 0x04) == 0x04;
  }

  public void setGeneralInterrogation(boolean generalInterrogation) {
    if (generalInterrogation) {
      value[0] = (byte) (value[0] | 0x04);
    } else {
      value[0] = (byte) (value[0] & 0xfb);
    }
  }

  public boolean isApplicationTrigger() {
    return (value[0] & 0x02) == 0x02;
  }

  public void setApplicationTrigger(boolean applicationTrigger) {
    if (applicationTrigger) {
      value[0] = (byte) (value[0] | 0x02);
    } else {
      value[0] = (byte) (value[0] & 0xfd);
    }
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    boolean first = true;
    if (isDataChange()) {
      if (!first) sb.append(",");
      first = false;
      sb.append("data-change");
    }
    if (isDataUpdate()) {
      if (!first) sb.append(",");
      first = false;
      sb.append("data-update");
    }
    if (isQualityChange()) {
      if (!first) sb.append(",");
      first = false;
      sb.append("quality-change");
    }
    if (isIntegrity()) {
      if (!first) sb.append(",");
      first = false;
      sb.append("integrity");
    }
    if (isGeneralInterrogation()) {
      if (!first) sb.append(",");
      first = false;
      sb.append("general-interrogation");
    }
    if (isApplicationTrigger()) {
      if (!first) sb.append(",");
      sb.append("application-trigger");
    }
    return sb.toString();
  }
}
