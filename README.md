# IEDNavigator PRO

Herramienta de escritorio en **Java** para exploración, simulación y análisis del protocolo
**IEC 61850**, orientada a la formación técnica en automatización de subestaciones.
Desarrollada por **Emilio Medina** (Paraguay). Software libre bajo **GPL v3**.

Es la versión evolucionada (núcleo activo) del proyecto IEDNavigator, con interfaz
**Java Swing + FlatLaf**.

> ⚠ **Uso exclusivamente educativo.** No apta para pruebas FAT/SAT, comisionamiento ni
> maniobras en instalaciones en servicio. El desarrollador no garantiza el desempeño ni la
> idoneidad para ningún propósito; el uso es bajo exclusiva responsabilidad del usuario.

---

## Características

### Cliente MMS
- Conexión MMS/ACSE a IEDs IEC 61850 (puerto estándar 102), con timeout configurable (5–60 s).
- Descubrimiento del modelo (Server → LD → LN → DO → DA).
- Lectura y escritura de valores por Restricción Funcional (ST, MX, CF, CO, SP, entre otras).
- Sondeo (polling) periódico configurable.
- Monitor de actividad con exportación CSV.
- Suscripción a reportes (URCB / BRCB).
- Setting Groups (SGCB) y panel de Ajustes de protección (SP).
- Construcción del modelo por reflexión para IEDs que rechazan el `retrieveModel` estándar.

### Control de maniobra
- Modelos de control (ctlModel): directo, SBO con seguridad normal y **SBO con seguridad
  reforzada** (select-with-value). Como `iec61850bean` 1.9.0 no implementa el
  select-with-value del modelo reforzado (ctlModel = 4), el intercambio `SBOw` → `Oper`
  (con el mismo `ctlNum`) está implementado manualmente conforme a IEC 61850-7-2 §20.
  Verificado contra un relé de protección **NARI PCS-9611S** real.
- Diálogo de maniobra con **SBO en dos pasos**: *Seleccionar (SBOw)* — con cuenta regresiva
  del temporizador de reserva (`sboTimeout`) —, *Ejecutar (OPER)* y *Cancelar SELECT*.
- Bandera `Test`, campo `Check` (`synchroChk` / `interlkChk`), identificador de operador
  (`orIdent`).
- **Verificación de posición post-operación**: tras un OPERATE aceptado, la herramienta lee
  el `stVal` del objeto controlado hasta confirmar (o no) la maniobra física.

### Modo Servidor / Simulador de IED
- Carga de archivos SCL (ICD / CID / SCD) e instanciación de un servidor IEC 61850.
- Responde a lecturas MMS de clientes externos (p. ej. IEDScout).
- Edición interactiva de valores desde la interfaz.
- Sincronización bidireccional entre el modelo de datos y los publicadores GOOSE.

### GOOSE (IEC 61850-8-1)
- Publicación y suscripción en Capa 2 (EtherType 0x88B8), con etiqueta VLAN 802.1Q.
- Esquema de retransmisión conforme a la norma (número de secuencia `sqNum` monótono).
- Puente **GOOSE-sobre-UDP** (puerto 62746) para redes enrutadas / Wi-Fi.
- GOOSE / Sampled Values nativo vía `libiec61850` (JNA), incluido en `lib/`.

### Utilidades SCL y diccionario
- Comparación de archivos SCL (diferencias por IED, LN, DataSet, GoCB, Report, comunicación).
- Análisis del mapa de suscripciones GOOSE (publicadores/suscriptores) desde SCD.
- Generación de reporte HTML del modelo del IED.
- Diccionario IEC 61850 integrado (descripciones de LN, CDC, FC, DO).

---

## Requisitos

- **Windows 10 u 11 de 64 bits.**
- **Java**: no se necesita instalarlo si se usa el instalador de la sección *Releases*
  (incluye su propio runtime, generado con `jlink`). Para compilar desde el código: **JDK 11
  o superior**.
- **Npcap** (solo para captura/publicación GOOSE Capa 2 y Sampled Values): se instala por
  separado desde <https://npcap.com/#download> (marcar *WinPcap API-compatible Mode*). Sin
  Npcap, el cliente MMS y el servidor funcionan con normalidad; solo GOOSE/SV Capa 2 lo
  requieren.

---

## Instalación y ejecución

### Opción A — Instalador (recomendado)
1. Descargar el instalador desde **[Releases](../../releases)** (paquete autocontenido,
   incluye el runtime de Java).
2. Extraer toda la carpeta (por ejemplo a `C:\IEDNavigatorPRO`).
3. Clic derecho en `INSTALAR.bat` → **Ejecutar como administrador**.
4. Instalar Npcap desde <https://npcap.com/#download> (solo si se usará GOOSE/SV).
5. Iniciar con el ícono del Escritorio o con `IEDNavigatorPRO.exe`.

### Opción B — Maven (desde el código)
```bash
mvn clean package -DskipTests
java --enable-native-access=ALL-UNNAMED -Djna.library.path=lib \
     -jar target/ied-navigator-1.0.0-jar-with-dependencies.jar
```

### Opción C — Script PowerShell
```powershell
.\compile.ps1     # compila a classes/ usando lib/*.jar
```
Luego ejecutar con el `jar` generado (Opción B) o con la clase principal
`com.iednavigator.IEDNavigatorApp` sobre `classes/` + `lib/*`.

---

## Uso rápido

**Conectar a un IED real**
1. Seleccionar el modo **Cliente** e ingresar IP y puerto (estándar: `102`).
2. Conectar y navegar el árbol del modelo.
3. Clic derecho sobre un nodo: leer, escribir, agregar al monitor, u operar (FC = CO).

**Simular un IED propio**
1. Seleccionar el modo **Servidor** y cargar un archivo `.icd` / `.cid` / `.scd`.
2. Iniciar el servidor y conectarse desde cualquier cliente MMS.

**Archivo de prueba incluido:** `test/test_ied.cid`

---

## Estructura del proyecto

```
src/main/java/com/iednavigator/
  IEDNavigatorApp.java        # GUI principal (Swing)
  IEC61850Client.java         # Cliente MMS/ACSE, descubrimiento, polling, control
  IEC61850Server.java         # Servidor/simulador de IED desde SCL
  GoosePublisher.java         # Publicación GOOSE (pcap4j)
  GooseSubscriber.java        # Suscripción GOOSE
  GooseUdpBridge.java         # Puente GOOSE sobre UDP
  ConnectionManager.java      # Gestión de conexión
  SclCompare.java             # Comparación de archivos SCL
  GooseMapAnalyzer.java       # Mapa de suscripciones GOOSE
  Iec61850Dictionary.java     # Diccionario IEC 61850
  native_lib/                 # Bindings JNA a iec61850.dll (GOOSE/SV nativo)
  [+ paneles y clases auxiliares]
lib/                          # Dependencias Java + iec61850.dll
test/test_ied.cid             # CID de prueba
```

---

## Dependencias

| Librería                    | Versión | Licencia               | Incluida |
|-----------------------------|---------|------------------------|----------|
| iec61850bean                | 1.9.0   | Apache 2.0             | Sí       |
| libiec61850 (iec61850.dll)  | —       | GPL v3                 | Sí       |
| asn1bean                    | 1.13.0  | Apache 2.0             | Sí       |
| pcap4j                      | 1.8.2   | MIT                    | Sí       |
| FlatLaf                     | 3.2     | Apache 2.0             | Sí       |
| JNA                         | 5.14.0  | Apache 2.0 / LGPL 2.1  | Sí       |
| SLF4J                       | 2.0.9   | MIT                    | Sí       |
| ANTLR                       | 2.7.7   | ANTLR 2 (tipo BSD)     | Sí       |
| Npcap                       | —       | Npcap License          | No (aparte) |

Los avisos y licencias completos están en
[THIRD-PARTY-NOTICES.txt](THIRD-PARTY-NOTICES.txt).

---

## Licencia

Se distribuye bajo la **GNU General Public License v3 (GPL v3)** — ver [LICENSE](LICENSE).
Esta condición deriva de la biblioteca nativa `libiec61850` (`iec61850.dll`, **GPL v3**), que
se incluye para las funciones GOOSE/SV nativas; las demás dependencias son permisivas
(Apache 2.0, MIT, LGPL/BSD). Podés usar, modificar y redistribuir el software siempre que
mantengas la misma licencia, incluyas el código fuente y conserves los avisos de copyright.

Npcap **no** se redistribuye con este proyecto (su licencia no lo permite); se enlaza al sitio
oficial de descarga, lo cual no constituye redistribución.

Copyright © Emilio Medina.

---

## Problemas conocidos y limitaciones

- La captura/publicación GOOSE en Capa 2 puede no funcionar en todas las interfaces de red en
  Windows y requiere Npcap con privilegios de administrador.
- Herramienta de laboratorio y formación: **no** validada para puesta en servicio productiva.
- La plataforma está orientada a Windows.
