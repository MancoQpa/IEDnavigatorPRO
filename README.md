# IEDNavigator PRO

[🇪🇸 Español](#español) | [🇬🇧 English](#english)

Desktop tool for **IEC 61850** exploration, simulation and analysis, built in **Java**.
Developed by **Emilio Medina** (Paraguay). Free software under **GPL v3**.

---

<a name="español"></a>
## 🇪🇸 Español

Herramienta de escritorio en **Java** para exploración, simulación y análisis del protocolo **IEC 61850**, orientada a la formación técnica en automatización de subestaciones.
Desarrollada por **Emilio Medina** (Paraguay). Software libre bajo **GPL v3**.

Es la versión evolucionada (núcleo activo) del proyecto IEDNavigator, con interfaz **Java Swing + FlatLaf**.

> ⚠ **Uso exclusivamente educativo.** No apta para pruebas FAT/SAT, comisionamiento ni maniobras en instalaciones en servicio. El desarrollador no garantiza el desempeño ni la idoneidad para ningún propósito; el uso es bajo exclusiva responsabilidad del usuario.

### Características

#### Cliente MMS
- Conexión MMS/ACSE a IEDs IEC 61850 (puerto estándar 102), con timeout configurable (5–60 s).
- Descubrimiento del modelo (Server → LD → LN → DO → DA).
- Lectura y escritura de valores por Restricción Funcional (ST, MX, CF, CO, SP, entre otras).
- Sondeo (polling) periódico configurable.
- Monitor de actividad con exportación CSV.
- Suscripción a reportes (URCB / BRCB).
- Setting Groups (SGCB) y panel de Ajustes de protección (SP).
- Construcción del modelo por reflexión para IEDs que rechazan el `retrieveModel` estándar.

#### Control de maniobra
- Modelos de control (ctlModel): directo, SBO con seguridad normal y **SBO con seguridad reforzada** (select-with-value). Como `iec61850bean` 1.9.0 no implementa el select-with-value del modelo reforzado (ctlModel = 4), el intercambio `SBOw` → `Oper` (con el mismo `ctlNum`) está implementado manualmente conforme a IEC 61850-7-2 §20. Verificado contra un relé de protección **NARI PCS-9611S** real.
- Diálogo de maniobra con **SBO en dos pasos**: *Seleccionar (SBOw)* — con cuenta regresiva del temporizador de reserva (`sboTimeout`) —, *Ejecutar (OPER)* y *Cancelar SELECT*.
- Bandera `Test`, campo `Check` (`synchroChk` / `interlkChk`), identificador de operador (`orIdent`).
- **Verificación de posición post-operación**: tras un OPERATE aceptado, la herramienta lee el `stVal` del objeto controlado hasta confirmar (o no) la maniobra física.

#### Modo Servidor / Simulador de IED
- Carga de archivos SCL (ICD / CID / SCD) e instanciación de un servidor IEC 61850.
- Responde a lecturas MMS de clientes externos (p. ej. IEDScout).
- Edición interactiva de valores desde la interfaz.
- Sincronización bidireccional entre el modelo de datos y los publicadores GOOSE.

#### GOOSE (IEC 61850-8-1)
- Publicación y suscripción en Capa 2 (EtherType 0x88B8), con etiqueta VLAN 802.1Q.
- Esquema de retransmisión conforme a la norma (número de secuencia `sqNum` monótono).
- Puente **GOOSE-sobre-UDP** (puerto 62746) para redes enrutadas / Wi-Fi.
- GOOSE / Sampled Values nativo vía `libiec61850` (JNA), incluido en `lib/`.

#### Utilidades SCL y diccionario
- Comparación de archivos SCL (diferencias por IED, LN, DataSet, GoCB, Report, comunicación).
- Análisis del mapa de suscripciones GOOSE (publicadores/suscriptores) desde SCD.
- Generación de reporte HTML del modelo del IED.
- Diccionario IEC 61850 integrado (descripciones de LN, CDC, FC, DO).

### Requisitos
- **Windows 10 u 11 de 64 bits.**
- **Java**: no se necesita instalarlo si se usa el instalador de la sección *Releases* (incluye su propio runtime, generado con `jlink`). Para compilar desde el código: **JDK 11 o superior**.
- **Npcap** (solo para captura/publicación GOOSE Capa 2 y Sampled Values): se instala por separado desde <https://npcap.com/#download> (marcar *WinPcap API-compatible Mode*). Sin Npcap, el cliente MMS y el servidor funcionan con normalidad; solo GOOSE/SV Capa 2 lo requieren.

### Instalación y ejecución

**Opción A — Instalador (recomendado)**
1. Descargar el instalador desde [Releases](https://github.com/MancoQpa/IEDnavigatorPRO/releases) (paquete autocontenido, incluye el runtime de Java).
2. Extraer toda la carpeta (por ejemplo a `C:\IEDNavigatorPRO`).
3. Clic derecho en `INSTALAR.bat` → **Ejecutar como administrador**.
4. Instalar Npcap desde <https://npcap.com/#download> (solo si se usará GOOSE/SV).
5. Iniciar con el ícono del Escritorio o con `IEDNavigatorPRO.exe`.

**Opción B — Maven (desde el código)**
```
mvn clean package -DskipTests
java --enable-native-access=ALL-UNNAMED -Djna.library.path=lib \
     -jar target/ied-navigator-1.0.0-jar-with-dependencies.jar
```

**Opción C — Script PowerShell**
```
.\compile.ps1     # compila a classes/ usando lib/*.jar
```
Luego ejecutar con el `jar` generado (Opción B) o con la clase principal `com.iednavigator.IEDNavigatorApp` sobre `classes/` + `lib/*`.

### Uso rápido

**Conectar a un IED real**
1. Seleccionar el modo **Cliente** e ingresar IP y puerto (estándar: `102`).
2. Conectar y navegar el árbol del modelo.
3. Clic derecho sobre un nodo: leer, escribir, agregar al monitor, u operar (FC = CO).

**Simular un IED propio**
1. Seleccionar el modo **Servidor** y cargar un archivo `.icd` / `.cid` / `.scd`.
2. Iniciar el servidor y conectarse desde cualquier cliente MMS.

**Archivo de prueba incluido:** `test/test_ied.cid`

### Estructura del proyecto
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

### Dependencias

| Librería                   | Versión | Licencia              | Incluida    |
| --------------------------- | ------- | ---------------------- | ----------- |
| iec61850bean                | 1.9.0   | Apache 2.0             | Sí          |
| libiec61850 (iec61850.dll)  | —       | GPL v3                 | Sí          |
| asn1bean                    | 1.13.0  | Apache 2.0             | Sí          |
| pcap4j                      | 1.8.2   | MIT                    | Sí          |
| FlatLaf                     | 3.2     | Apache 2.0             | Sí          |
| JNA                         | 5.14.0  | Apache 2.0 / LGPL 2.1  | Sí          |
| SLF4J                       | 2.0.9   | MIT                    | Sí          |
| ANTLR                       | 2.7.7   | ANTLR 2 (tipo BSD)     | Sí          |
| Npcap                       | —       | Npcap License          | No (aparte) |

Los avisos y licencias completos están en [THIRD-PARTY-NOTICES.txt](https://github.com/MancoQpa/IEDnavigatorPRO/blob/main/THIRD-PARTY-NOTICES.txt).

### Licencia

Se distribuye bajo la **GNU General Public License v3 (GPL v3)** — ver [LICENSE](https://github.com/MancoQpa/IEDnavigatorPRO/blob/main/LICENSE). Esta condición deriva de la biblioteca nativa `libiec61850` (`iec61850.dll`, **GPL v3**), que se incluye para las funciones GOOSE/SV nativas; las demás dependencias son permisivas (Apache 2.0, MIT, LGPL/BSD). Podés usar, modificar y redistribuir el software siempre que mantengas la misma licencia, incluyas el código fuente y conserves los avisos de copyright.

Npcap **no** se redistribuye con este proyecto (su licencia no lo permite); se enlaza al sitio oficial de descarga, lo cual no constituye redistribución.

Copyright © Emilio Medina.

### Problemas conocidos y limitaciones
- La captura/publicación GOOSE en Capa 2 puede no funcionar en todas las interfaces de red en Windows y requiere Npcap con privilegios de administrador.
- Herramienta de laboratorio y formación: **no** validada para puesta en servicio productiva.
- La plataforma está orientada a Windows.

---

<a name="english"></a>
## 🇬🇧 English

Desktop tool written in **Java** for exploring, simulating and analyzing the **IEC 61850** protocol, aimed at technical training in substation automation.
Developed by **Emilio Medina** (Paraguay). Free software under **GPL v3**.

This is the evolved (actively maintained) version of the IEDNavigator project, with a **Java Swing + FlatLaf** interface.

> ⚠ **Educational use only.** Not suitable for FAT/SAT testing, commissioning, or switching operations on live installations. The developer provides no warranty of performance or fitness for any purpose; use is entirely at the user's own risk.

### Features

#### MMS Client
- MMS/ACSE connection to IEC 61850 IEDs (standard port 102), with configurable timeout (5–60 s).
- Model discovery (Server → LD → LN → DO → DA).
- Reading and writing values by Functional Constraint (ST, MX, CF, CO, SP, and others).
- Configurable periodic polling.
- Activity monitor with CSV export.
- Report subscription (URCB / BRCB).
- Setting Groups (SGCB) and protection settings panel (SP).
- Reflection-based model construction for IEDs that reject the standard `retrieveModel`.

#### Switching / Control Operations
- Control models (ctlModel): direct, SBO with normal security, and **SBO with enhanced security** (select-with-value). Since `iec61850bean` 1.9.0 does not implement select-with-value for the enhanced model (ctlModel = 4), the `SBOw` → `Oper` exchange (with matching `ctlNum`) is implemented manually per IEC 61850-7-2 §20. Verified against a real **NARI PCS-9611S** protection relay.
- Two-step SBO control dialog: *Select (SBOw)* — with a countdown of the reservation timer (`sboTimeout`) —, *Execute (OPER)*, and *Cancel SELECT*.
- `Test` flag, `Check` field (`synchroChk` / `interlkChk`), operator identifier (`orIdent`).
- **Post-operation position verification**: after an accepted OPERATE, the tool reads the `stVal` of the controlled object until the physical operation is confirmed (or not).

#### Server Mode / IED Simulator
- Loads SCL files (ICD / CID / SCD) and instantiates an IEC 61850 server.
- Responds to MMS reads from external clients (e.g. IEDScout).
- Interactive value editing from the interface.
- Bidirectional sync between the data model and GOOSE publishers.

#### GOOSE (IEC 61850-8-1)
- Layer 2 publish/subscribe (EtherType 0x88B8), with 802.1Q VLAN tagging.
- Standard-compliant retransmission scheme (monotonic `sqNum` sequence number).
- **GOOSE-over-UDP** bridge (port 62746) for routed networks / Wi-Fi.
- Native GOOSE / Sampled Values via `libiec61850` (JNA), included in `lib/`.

#### SCL Utilities and Dictionary
- SCL file comparison (differences by IED, LN, DataSet, GoCB, Report, communication).
- GOOSE subscription map analysis (publishers/subscribers) from SCD.
- HTML report generation of the IED model.
- Built-in IEC 61850 dictionary (LN, CDC, FC, DO descriptions).

### Requirements
- **64-bit Windows 10 or 11.**
- **Java**: not required if using the installer from the *Releases* section (it bundles its own runtime, built with `jlink`). To build from source: **JDK 11 or higher**.
- **Npcap** (only for Layer 2 GOOSE/Sampled Values capture and publishing): installed separately from <https://npcap.com/#download> (check *WinPcap API-compatible Mode*). Without Npcap, the MMS client and server work normally; only Layer 2 GOOSE/SV requires it.

### Installation and Setup

**Option A — Installer (recommended)**
1. Download the installer from [Releases](https://github.com/MancoQpa/IEDnavigatorPRO/releases) (self-contained package, includes the Java runtime).
2. Extract the entire folder (e.g. to `C:\IEDNavigatorPRO`).
3. Right-click `INSTALAR.bat` → **Run as administrator**.
4. Install Npcap from <https://npcap.com/#download> (only if GOOSE/SV will be used).
5. Launch via the Desktop icon or `IEDNavigatorPRO.exe`.

**Option B — Maven (from source)**
```
mvn clean package -DskipTests
java --enable-native-access=ALL-UNNAMED -Djna.library.path=lib \
     -jar target/ied-navigator-1.0.0-jar-with-dependencies.jar
```

**Option C — PowerShell script**
```
.\compile.ps1     # builds to classes/ using lib/*.jar
```
Then run using the generated `jar` (Option B) or the main class `com.iednavigator.IEDNavigatorApp` against `classes/` + `lib/*`.

### Quick Start

**Connect to a real IED**
1. Select **Client** mode and enter the IP and port (standard: `102`).
2. Connect and browse the model tree.
3. Right-click a node: read, write, add to monitor, or operate (FC = CO).

**Simulate your own IED**
1. Select **Server** mode and load an `.icd` / `.cid` / `.scd` file.
2. Start the server and connect from any MMS client.

**Included test file:** `test/test_ied.cid`

### Project Structure
```
src/main/java/com/iednavigator/
  IEDNavigatorApp.java        # Main GUI (Swing)
  IEC61850Client.java         # MMS/ACSE client, discovery, polling, control
  IEC61850Server.java         # IED server/simulator from SCL
  GoosePublisher.java         # GOOSE publishing (pcap4j)
  GooseSubscriber.java        # GOOSE subscription
  GooseUdpBridge.java         # GOOSE-over-UDP bridge
  ConnectionManager.java      # Connection management
  SclCompare.java             # SCL file comparison
  GooseMapAnalyzer.java       # GOOSE subscription map
  Iec61850Dictionary.java     # IEC 61850 dictionary
  native_lib/                 # JNA bindings to iec61850.dll (native GOOSE/SV)
  [+ panels and helper classes]
lib/                          # Java dependencies + iec61850.dll
test/test_ied.cid             # Test CID
```

### Dependencies

| Library                     | Version | License                | Bundled |
| ---------------------------- | ------- | ----------------------- | ------- |
| iec61850bean                 | 1.9.0   | Apache 2.0              | Yes     |
| libiec61850 (iec61850.dll)   | —       | GPL v3                  | Yes     |
| asn1bean                     | 1.13.0  | Apache 2.0              | Yes     |
| pcap4j                       | 1.8.2   | MIT                     | Yes     |
| FlatLaf                      | 3.2     | Apache 2.0              | Yes     |
| JNA                          | 5.14.0  | Apache 2.0 / LGPL 2.1   | Yes     |
| SLF4J                        | 2.0.9   | MIT                     | Yes     |
| ANTLR                        | 2.7.7   | ANTLR 2 (BSD-style)     | Yes     |
| Npcap                        | —       | Npcap License           | No (separate) |

Full notices and licenses are in [THIRD-PARTY-NOTICES.txt](https://github.com/MancoQpa/IEDnavigatorPRO/blob/main/THIRD-PARTY-NOTICES.txt).

### License

Distributed under the **GNU General Public License v3 (GPL v3)** — see [LICENSE](https://github.com/MancoQpa/IEDnavigatorPRO/blob/main/LICENSE). This requirement stems from the native `libiec61850` library (`iec61850.dll`, **GPL v3**), included for native GOOSE/SV functionality; the remaining dependencies are permissive (Apache 2.0, MIT, LGPL/BSD). You may use, modify, and redistribute the software as long as you keep the same license, include the source code, and preserve the copyright notices.

Npcap is **not** redistributed with this project (its license does not permit it); a link is provided to the official download site, which does not constitute redistribution.

Copyright © Emilio Medina.

### Known Issues and Limitations
- Layer 2 GOOSE capture/publishing may not work on all network interfaces on Windows and requires Npcap with administrator privileges.
- Lab and training tool: **not** validated for production commissioning.
- The platform is Windows-oriented.
