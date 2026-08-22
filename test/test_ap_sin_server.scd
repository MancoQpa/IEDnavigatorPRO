<?xml version="1.0" encoding="UTF-8"?>
<!--
  Modelo sintetico para el hallazgo 5: DOS IEDs, y el primero con un AccessPoint
  que NO tiene seccion <Server> (declarado solo para GOOSE/SV, cosa legitima).

  Es la combinacion exacta que hace visible el defecto y que ningun archivo del
  corpus de fabricante tiene: hay archivos con AccessPoints sin servidor, y hay
  archivos con varios IEDs, pero ninguno con las dos cosas a la vez. Sin este
  archivo el arreglo quedaria sin banco.

  Contando AccessPoints sin mirar si tienen <Server>, al primer IED se le asignan
  dos modelos y al segundo ninguno: aparece un solo dispositivo, y con los Logical
  Devices del otro.
-->
<SCL xmlns="http://www.iec.ch/61850/2003/SCL">
  <Header id="BANCO_AP_SIN_SERVER"/>
    <IED name="IEDCONGOOSE">
      <AccessPoint name="GOOSE_ONLY"/>
      <AccessPoint name="S1"><Server>
        <Authentication/>
        <LDevice inst="LD1">
          <LN0 lnClass="LLN0" inst="" lnType="T_LLN0"/>
          <LN lnClass="LPHD" inst="1" lnType="T_LPHD"/>
        </LDevice>
      </Server></AccessPoint>
    </IED>
    <IED name="IEDSIMPLE">
      <AccessPoint name="S1"><Server>
        <Authentication/>
        <LDevice inst="LD2">
          <LN0 lnClass="LLN0" inst="" lnType="T_LLN0"/>
          <LN lnClass="LPHD" inst="1" lnType="T_LPHD"/>
        </LDevice>
      </Server></AccessPoint>
    </IED>
  <DataTypeTemplates>
    <LNodeType id="T_LLN0" lnClass="LLN0"><DO name="Mod" type="T_INC"/></LNodeType>
    <LNodeType id="T_LPHD" lnClass="LPHD"><DO name="PhyHealth" type="T_INS"/></LNodeType>
    <DOType id="T_INC" cdc="INC"><DA name="stVal" bType="Enum" type="T_MOD" fc="ST"/></DOType>
    <DOType id="T_INS" cdc="INS"><DA name="stVal" bType="INT32" fc="ST"/></DOType>
    <EnumType id="T_MOD"><EnumVal ord="1">on</EnumVal></EnumType>
  </DataTypeTemplates>
</SCL>
