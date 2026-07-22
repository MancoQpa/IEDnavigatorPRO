# IEDNavigator PRO

[🇪🇸 Español](#español) | [🇬🇧 English](#english) | [🇨🇳 中文](#中文) | [🇧🇷 Português](#português) | [🇸🇦 العربية](#العربية)

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

---

<a name="中文"></a>
## 🇨🇳 中文

基于 **Java** 开发的桌面工具，用于 **IEC 61850** 协议的浏览、仿真与分析，面向变电站自动化的技术培训。
由 **Emilio Medina**（巴拉圭）开发。基于 **GPL v3** 的自由软件。

本项目是 IEDNavigator 的进化版本（当前活跃维护的核心版本），采用 **Java Swing + FlatLaf** 界面。

> ⚠ **仅限教育用途。** 不适用于 FAT/SAT 测试、投运调试或在运行中变电站进行操作。开发者不对性能或适用性做任何保证；使用风险完全由用户自行承担。

### 功能特性

#### MMS 客户端
- 通过 MMS/ACSE 连接 IEC 61850 IED（标准端口 102），超时时间可配置（5–60 秒）。
- 模型发现（Server → LD → LN → DO → DA）。
- 按功能约束（ST、MX、CF、CO、SP 等）读写数值。
- 可配置的周期性轮询（polling）。
- 带 CSV 导出功能的活动监视器。
- 报告订阅（URCB / BRCB）。
- 定值组（SGCB）及保护定值面板（SP）。
- 针对拒绝标准 `retrieveModel` 的 IED，通过反射方式构建模型。

#### 操作控制
- 控制模型（ctlModel）：直接控制、普通安全 SBO，以及 **增强安全 SBO**（select-with-value）。由于 `iec61850bean` 1.9.0 未实现增强模型（ctlModel = 4）的 select-with-value，`SBOw` → `Oper` 的交互（使用相同的 `ctlNum`）已按照 IEC 61850-7-2 §20 手动实现，并在真实的 **NARI PCS-9611S** 保护装置上进行了验证。
- 两步式 SBO 操作对话框：*选择（SBOw）*——带预留计时器（`sboTimeout`）倒计时——、*执行（OPER）* 和 *取消 SELECT*。
- `Test` 标志位、`Check` 字段（`synchroChk` / `interlkChk`）、操作员标识（`orIdent`）。
- **操作后位置校验**：OPERATE 被接受后，工具会持续读取受控对象的 `stVal`，直至确认（或未确认）实际物理动作。

#### 服务端模式 / IED 仿真器
- 加载 SCL 文件（ICD / CID / SCD）并实例化一个 IEC 61850 服务端。
- 响应外部客户端（如 IEDScout）的 MMS 读取请求。
- 通过界面交互式编辑数值。
- 数据模型与 GOOSE 发布者之间的双向同步。

#### GOOSE（IEC 61850-8-1）
- 二层（Layer 2）发布/订阅（以太网类型 0x88B8），带 802.1Q VLAN 标签。
- 符合标准的重传机制（单调递增的序列号 `sqNum`）。
- **GOOSE-over-UDP** 桥接（端口 62746），适用于路由网络 / Wi-Fi。
- 通过 `libiec61850`（JNA）实现原生 GOOSE / 采样值（Sampled Values），已包含在 `lib/` 目录中。

#### SCL 工具与词典
- SCL 文件比较（按 IED、LN、DataSet、GoCB、Report、通信方式列出差异）。
- 从 SCD 文件分析 GOOSE 订阅关系图（发布者/订阅者）。
- 生成 IED 模型的 HTML 报告。
- 内置 IEC 61850 词典（LN、CDC、FC、DO 说明）。

### 系统要求
- **64 位 Windows 10 或 11。**
- **Java**：若使用 *Releases* 中的安装包则无需单独安装（内置由 `jlink` 生成的运行时）。若需从源码编译：需要 **JDK 11 或更高版本**。
- **Npcap**（仅用于二层 GOOSE / 采样值的捕获和发布）：需从 <https://npcap.com/#download> 单独安装（勾选 *WinPcap API-compatible Mode*）。若未安装 Npcap，MMS 客户端和服务端仍可正常工作；只有二层 GOOSE/SV 功能需要它。

### 安装与运行

**方式 A —— 安装程序（推荐）**
1. 从 [Releases](https://github.com/MancoQpa/IEDnavigatorPRO/releases) 下载安装包（自包含，内置 Java 运行时）。
2. 解压整个文件夹（例如解压到 `C:\IEDNavigatorPRO`）。
3. 右键点击 `INSTALAR.bat` → **以管理员身份运行**。
4. 从 <https://npcap.com/#download> 安装 Npcap（仅在需要使用 GOOSE/SV 时）。
5. 通过桌面图标或 `IEDNavigatorPRO.exe` 启动。

**方式 B —— Maven（从源码构建）**
```
mvn clean package -DskipTests
java --enable-native-access=ALL-UNNAMED -Djna.library.path=lib \
     -jar target/ied-navigator-1.0.0-jar-with-dependencies.jar
```

**方式 C —— PowerShell 脚本**
```
.\compile.ps1     # 使用 lib/*.jar 编译到 classes/ 目录
```
然后使用方式 B 生成的 `jar` 文件运行，或使用 `classes/` + `lib/*` 配合主类 `com.iednavigator.IEDNavigatorApp` 运行。

### 快速上手

**连接真实 IED**
1. 选择 **客户端（Cliente）** 模式，输入 IP 和端口（标准端口：`102`）。
2. 连接并浏览模型树。
3. 右键点击节点：读取、写入、加入监视器，或执行操作（FC = CO）。

**仿真自己的 IED**
1. 选择 **服务端（Servidor）** 模式，加载 `.icd` / `.cid` / `.scd` 文件。
2. 启动服务端，然后从任意 MMS 客户端连接。

**内置测试文件：** `test/test_ied.cid`

### 项目结构
```
src/main/java/com/iednavigator/
  IEDNavigatorApp.java        # 主界面（Swing）
  IEC61850Client.java         # MMS/ACSE 客户端，发现、轮询、控制
  IEC61850Server.java         # 基于 SCL 的 IED 服务端/仿真器
  GoosePublisher.java         # GOOSE 发布（pcap4j）
  GooseSubscriber.java        # GOOSE 订阅
  GooseUdpBridge.java         # GOOSE-over-UDP 桥接
  ConnectionManager.java      # 连接管理
  SclCompare.java             # SCL 文件比较
  GooseMapAnalyzer.java       # GOOSE 订阅关系图
  Iec61850Dictionary.java     # IEC 61850 词典
  native_lib/                 # JNA 绑定 iec61850.dll（原生 GOOSE/SV）
  [+ 面板及辅助类]
lib/                          # Java 依赖库 + iec61850.dll
test/test_ied.cid             # 测试用 CID 文件
```

### 依赖项

| 库                          | 版本    | 许可证                  | 是否内置 |
| ---------------------------- | ------- | ----------------------- | -------- |
| iec61850bean                 | 1.9.0   | Apache 2.0              | 是       |
| libiec61850 (iec61850.dll)   | —       | GPL v3                  | 是       |
| asn1bean                     | 1.13.0  | Apache 2.0              | 是       |
| pcap4j                       | 1.8.2   | MIT                     | 是       |
| FlatLaf                      | 3.2     | Apache 2.0              | 是       |
| JNA                          | 5.14.0  | Apache 2.0 / LGPL 2.1   | 是       |
| SLF4J                        | 2.0.9   | MIT                     | 是       |
| ANTLR                        | 2.7.7   | ANTLR 2（类 BSD）      | 是       |
| Npcap                        | —       | Npcap 许可证            | 否（单独安装） |

完整的声明和许可证内容见 [THIRD-PARTY-NOTICES.txt](https://github.com/MancoQpa/IEDnavigatorPRO/blob/main/THIRD-PARTY-NOTICES.txt)。

### 许可证

本项目基于 **GNU 通用公共许可证 v3（GPL v3）** 分发 —— 详见 [LICENSE](https://github.com/MancoQpa/IEDnavigatorPRO/blob/main/LICENSE)。该许可要求源于原生库 `libiec61850`（`iec61850.dll`，**GPL v3**），该库用于实现原生 GOOSE/SV 功能；其余依赖均为宽松许可证（Apache 2.0、MIT、LGPL/BSD）。您可以使用、修改和再分发本软件，前提是保持相同的许可证、包含源代码并保留版权声明。

Npcap **不** 随本项目一同分发（其许可证不允许此操作）；本项目仅提供指向官方下载网站的链接，这不构成再分发行为。

版权所有 © Emilio Medina。

### 已知问题与限制
- 二层 GOOSE 捕获/发布功能可能无法在 Windows 上所有网卡上正常工作，且需要以管理员权限运行的 Npcap。
- 本工具面向实验室与培训场景：**未** 经过生产环境投运验证。
- 本平台面向 Windows 系统。

---

<a name="português"></a>
## 🇧🇷 Português

Ferramenta de desktop em **Java** para exploração, simulação e análise do protocolo **IEC 61850**, voltada à capacitação técnica em automação de subestações.
Desenvolvida por **Emilio Medina** (Paraguai). Software livre sob **GPL v3**.

É a versão evoluída (núcleo ativamente mantido) do projeto IEDNavigator, com interface **Java Swing + FlatLaf**.

> ⚠ **Uso exclusivamente educacional.** Não é adequada para testes FAT/SAT, comissionamento ou manobras em instalações em operação. O desenvolvedor não garante o desempenho nem a adequação a qualquer finalidade; o uso é de exclusiva responsabilidade do usuário.

### Funcionalidades

#### Cliente MMS
- Conexão MMS/ACSE a IEDs IEC 61850 (porta padrão 102), com timeout configurável (5–60 s).
- Descoberta do modelo (Server → LD → LN → DO → DA).
- Leitura e escrita de valores por Restrição Funcional (ST, MX, CF, CO, SP, entre outras).
- Sondagem (polling) periódica configurável.
- Monitor de atividade com exportação para CSV.
- Assinatura de relatórios (URCB / BRCB).
- Setting Groups (SGCB) e painel de Ajustes de proteção (SP).
- Construção do modelo por reflexão para IEDs que rejeitam o `retrieveModel` padrão.

#### Controle de manobra
- Modelos de controle (ctlModel): direto, SBO com segurança normal e **SBO com segurança reforçada** (select-with-value). Como o `iec61850bean` 1.9.0 não implementa o select-with-value do modelo reforçado (ctlModel = 4), a troca `SBOw` → `Oper` (com o mesmo `ctlNum`) foi implementada manualmente conforme a IEC 61850-7-2 §20. Verificado em um relé de proteção **NARI PCS-9611S** real.
- Diálogo de manobra com **SBO em duas etapas**: *Selecionar (SBOw)* — com contagem regressiva do temporizador de reserva (`sboTimeout`) —, *Executar (OPER)* e *Cancelar SELECT*.
- Flag `Test`, campo `Check` (`synchroChk` / `interlkChk`), identificador do operador (`orIdent`).
- **Verificação de posição pós-operação**: após um OPERATE aceito, a ferramenta lê o `stVal` do objeto controlado até confirmar (ou não) a manobra física.

#### Modo Servidor / Simulador de IED
- Carregamento de arquivos SCL (ICD / CID / SCD) e instanciação de um servidor IEC 61850.
- Responde a leituras MMS de clientes externos (ex.: IEDScout).
- Edição interativa de valores pela interface.
- Sincronização bidirecional entre o modelo de dados e os publicadores GOOSE.

#### GOOSE (IEC 61850-8-1)
- Publicação e assinatura em Camada 2 (EtherType 0x88B8), com tag VLAN 802.1Q.
- Esquema de retransmissão conforme a norma (número de sequência `sqNum` monotônico).
- Ponte **GOOSE-sobre-UDP** (porta 62746) para redes roteadas / Wi-Fi.
- GOOSE / Sampled Values nativo via `libiec61850` (JNA), incluído em `lib/`.

#### Utilitários SCL e dicionário
- Comparação de arquivos SCL (diferenças por IED, LN, DataSet, GoCB, Report, comunicação).
- Análise do mapa de assinaturas GOOSE (publicadores/assinantes) a partir do SCD.
- Geração de relatório HTML do modelo do IED.
- Dicionário IEC 61850 integrado (descrições de LN, CDC, FC, DO).

### Requisitos
- **Windows 10 ou 11 de 64 bits.**
- **Java**: não é necessário instalar se for usado o instalador da seção *Releases* (inclui seu próprio runtime, gerado com `jlink`). Para compilar a partir do código: **JDK 11 ou superior**.
- **Npcap** (somente para captura/publicação GOOSE em Camada 2 e Sampled Values): instalado separadamente em <https://npcap.com/#download> (marcar *WinPcap API-compatible Mode*). Sem o Npcap, o cliente MMS e o servidor funcionam normalmente; apenas o GOOSE/SV em Camada 2 exige essa instalação.

### Instalação e execução

**Opção A — Instalador (recomendado)**
1. Baixar o instalador em [Releases](https://github.com/MancoQpa/IEDnavigatorPRO/releases) (pacote autocontido, inclui o runtime Java).
2. Extrair a pasta inteira (por exemplo, para `C:\IEDNavigatorPRO`).
3. Clique com o botão direito em `INSTALAR.bat` → **Executar como administrador**.
4. Instalar o Npcap em <https://npcap.com/#download> (somente se o GOOSE/SV for utilizado).
5. Iniciar pelo ícone da Área de Trabalho ou pelo `IEDNavigatorPRO.exe`.

**Opção B — Maven (a partir do código)**
```
mvn clean package -DskipTests
java --enable-native-access=ALL-UNNAMED -Djna.library.path=lib \
     -jar target/ied-navigator-1.0.0-jar-with-dependencies.jar
```

**Opção C — Script PowerShell**
```
.\compile.ps1     # compila para classes/ usando lib/*.jar
```
Depois, executar com o `jar` gerado (Opção B) ou com a classe principal `com.iednavigator.IEDNavigatorApp` sobre `classes/` + `lib/*`.

### Uso rápido

**Conectar a um IED real**
1. Selecionar o modo **Cliente** e informar IP e porta (padrão: `102`).
2. Conectar e navegar pela árvore do modelo.
3. Clique com o botão direito em um nó: ler, escrever, adicionar ao monitor, ou operar (FC = CO).

**Simular um IED próprio**
1. Selecionar o modo **Servidor** e carregar um arquivo `.icd` / `.cid` / `.scd`.
2. Iniciar o servidor e conectar-se a partir de qualquer cliente MMS.

**Arquivo de teste incluído:** `test/test_ied.cid`

### Estrutura do projeto
```
src/main/java/com/iednavigator/
  IEDNavigatorApp.java        # GUI principal (Swing)
  IEC61850Client.java         # Cliente MMS/ACSE, descoberta, polling, controle
  IEC61850Server.java         # Servidor/simulador de IED a partir de SCL
  GoosePublisher.java         # Publicação GOOSE (pcap4j)
  GooseSubscriber.java        # Assinatura GOOSE
  GooseUdpBridge.java         # Ponte GOOSE sobre UDP
  ConnectionManager.java      # Gestão de conexão
  SclCompare.java             # Comparação de arquivos SCL
  GooseMapAnalyzer.java       # Mapa de assinaturas GOOSE
  Iec61850Dictionary.java     # Dicionário IEC 61850
  native_lib/                 # Bindings JNA para iec61850.dll (GOOSE/SV nativo)
  [+ painéis e classes auxiliares]
lib/                          # Dependências Java + iec61850.dll
test/test_ied.cid             # CID de teste
```

### Dependências

| Biblioteca                  | Versão  | Licença                | Incluída |
| ---------------------------- | ------- | ----------------------- | -------- |
| iec61850bean                 | 1.9.0   | Apache 2.0              | Sim      |
| libiec61850 (iec61850.dll)   | —       | GPL v3                  | Sim      |
| asn1bean                     | 1.13.0  | Apache 2.0              | Sim      |
| pcap4j                       | 1.8.2   | MIT                     | Sim      |
| FlatLaf                      | 3.2     | Apache 2.0              | Sim      |
| JNA                          | 5.14.0  | Apache 2.0 / LGPL 2.1   | Sim      |
| SLF4J                        | 2.0.9   | MIT                     | Sim      |
| ANTLR                        | 2.7.7   | ANTLR 2 (tipo BSD)      | Sim      |
| Npcap                        | —       | Licença Npcap           | Não (separado) |

Os avisos e licenças completos estão em [THIRD-PARTY-NOTICES.txt](https://github.com/MancoQpa/IEDnavigatorPRO/blob/main/THIRD-PARTY-NOTICES.txt).

### Licença

Distribuído sob a **GNU General Public License v3 (GPL v3)** — ver [LICENSE](https://github.com/MancoQpa/IEDnavigatorPRO/blob/main/LICENSE). Essa condição decorre da biblioteca nativa `libiec61850` (`iec61850.dll`, **GPL v3**), incluída para as funções GOOSE/SV nativas; as demais dependências são permissivas (Apache 2.0, MIT, LGPL/BSD). Você pode usar, modificar e redistribuir o software desde que mantenha a mesma licença, inclua o código-fonte e preserve os avisos de copyright.

O Npcap **não** é redistribuído com este projeto (sua licença não permite); é fornecido um link para o site oficial de download, o que não constitui redistribuição.

Copyright © Emilio Medina.

### Problemas conhecidos e limitações
- A captura/publicação GOOSE em Camada 2 pode não funcionar em todas as interfaces de rede no Windows e requer o Npcap com privilégios de administrador.
- Ferramenta de laboratório e capacitação: **não** validada para colocação em operação produtiva.
- A plataforma é voltada para Windows.

---

<a name="العربية"></a>
## 🇸🇦 العربية

أداة سطح مكتب مطوَّرة بلغة **Java** لاستكشاف ومحاكاة وتحليل بروتوكول **IEC 61850**، موجَّهة للتدريب التقني في مجال أتمتة محطات التحويل الكهربائية.
طوَّرها **Emilio Medina** (باراغواي). برمجية حرة مرخَّصة بموجب **GPL v3**.

هذه هي النسخة المطوَّرة (النواة النشطة الصيانة) من مشروع IEDNavigator، بواجهة **Java Swing + FlatLaf**.

> ⚠ **للاستخدام التعليمي فقط.** غير مناسبة لاختبارات FAT/SAT أو التشغيل الفعلي أو إجراء المناورات على منشآت قيد التشغيل. لا يقدّم المطوِّر أي ضمان للأداء أو الملاءمة لأي غرض؛ ويقع الاستخدام بالكامل على مسؤولية المستخدم.

### الميزات

#### عميل MMS
- اتصال MMS/ACSE بأجهزة IED المتوافقة مع IEC 61850 (المنفذ القياسي 102)، مع مهلة زمنية قابلة للتهيئة (5–60 ثانية).
- اكتشاف النموذج (Server → LD → LN → DO → DA).
- قراءة وكتابة القيم حسب القيد الوظيفي (ST، MX، CF، CO، SP، وغيرها).
- استقصاء (polling) دوري قابل للتهيئة.
- شاشة مراقبة النشاط مع إمكانية التصدير إلى CSV.
- الاشتراك في التقارير (URCB / BRCB).
- مجموعات الإعدادات (SGCB) ولوحة إعدادات الحماية (SP).
- بناء النموذج عبر الانعكاس (reflection) للأجهزة التي ترفض أمر `retrieveModel` القياسي.

#### التحكم بالمناورة
- نماذج التحكم (ctlModel): مباشر، SBO بأمان عادي، و**SBO بأمان معزَّز** (select-with-value). نظرًا لأن `iec61850bean` الإصدار 1.9.0 لا يدعم آلية select-with-value الخاصة بالنموذج المعزَّز (ctlModel = 4)، فقد تم تنفيذ تبادل `SBOw` → `Oper` (بنفس قيمة `ctlNum`) يدويًا وفقًا للمعيار IEC 61850-7-2 §20. وتم التحقق منه على مرحّم حماية حقيقي من طراز **NARI PCS-9611S**.
- نافذة حوار للمناورة بخطوتين (SBO): *تحديد (SBOw)* — مع عدّ تنازلي لمؤقت الحجز (`sboTimeout`) —، ثم *تنفيذ (OPER)*، و*إلغاء التحديد (Cancelar SELECT)*.
- علامة `Test`، وحقل `Check` (`synchroChk` / `interlkChk`)، ومعرِّف المشغِّل (`orIdent`).
- **التحقق من الوضعية بعد التنفيذ**: بعد قبول أمر OPERATE، تقوم الأداة بقراءة قيمة `stVal` للكائن المتحكَّم فيه حتى تتأكد (أو لا تتأكد) من تنفيذ المناورة الفعلية.

#### وضع الخادم / محاكي IED
- تحميل ملفات SCL (ICD / CID / SCD) وإنشاء خادم IEC 61850.
- الاستجابة لطلبات القراءة عبر MMS من عملاء خارجيين (مثل IEDScout).
- تحرير القيم بشكل تفاعلي من الواجهة.
- مزامنة ثنائية الاتجاه بين نموذج البيانات وناشري GOOSE.

#### GOOSE (IEC 61850-8-1)
- نشر واشتراك على الطبقة الثانية (EtherType 0x88B8)، مع وسم VLAN حسب 802.1Q.
- آلية إعادة إرسال متوافقة مع المعيار (رقم تسلسلي `sqNum` تصاعدي).
- جسر **GOOSE عبر UDP** (المنفذ 62746) للشبكات الموجَّهة (routed) / شبكات Wi-Fi.
- دعم GOOSE / القيم المُعايَنة (Sampled Values) الأصلي عبر `libiec61850` (JNA)، المضمَّن في مجلد `lib/`.

#### أدوات SCL والقاموس
- مقارنة ملفات SCL (الفروقات حسب IED، LN، DataSet، GoCB، Report، والاتصال).
- تحليل خريطة اشتراكات GOOSE (الناشرون/المشتركون) انطلاقًا من ملف SCD.
- توليد تقرير HTML لنموذج الـ IED.
- قاموس IEC 61850 مدمج (وصف LN، CDC، FC، DO).

### المتطلبات
- **نظام Windows 10 أو 11 بإصدار 64 بت.**
- **Java**: غير مطلوب تثبيتها إذا تم استخدام المُثبِّت من قسم *Releases* (يتضمن بيئة تشغيل خاصة به تم إنشاؤها باستخدام `jlink`). للبناء من الشيفرة المصدرية: يلزم **JDK 11 أو أحدث**.
- **Npcap** (فقط لالتقاط/نشر GOOSE على الطبقة الثانية والقيم المُعايَنة): يتم تثبيتها بشكل منفصل من <https://npcap.com/#download> (مع تحديد خيار *WinPcap API-compatible Mode*). بدون Npcap، يعمل عميل MMS والخادم بشكل طبيعي؛ ويقتصر الاحتياج إليها على وظائف GOOSE/SV على الطبقة الثانية فقط.

### التثبيت والتشغيل

**الخيار أ — برنامج التثبيت (موصى به)**
1. تنزيل برنامج التثبيت من [Releases](https://github.com/MancoQpa/IEDnavigatorPRO/releases) (حزمة مستقلة تتضمن بيئة تشغيل Java).
2. استخراج المجلد بالكامل (مثلًا إلى `C:\IEDNavigatorPRO`).
3. النقر بزر الفأرة الأيمن على `INSTALAR.bat` ← **تشغيل كمسؤول**.
4. تثبيت Npcap من <https://npcap.com/#download> (فقط إذا كان سيتم استخدام GOOSE/SV).
5. التشغيل عبر أيقونة سطح المكتب أو عبر `IEDNavigatorPRO.exe`.

**الخيار ب — Maven (من الشيفرة المصدرية)**
```
mvn clean package -DskipTests
java --enable-native-access=ALL-UNNAMED -Djna.library.path=lib \
     -jar target/ied-navigator-1.0.0-jar-with-dependencies.jar
```

**الخيار ج — نص برمجي PowerShell**
```
.\compile.ps1     # يقوم بالبناء إلى classes/ باستخدام lib/*.jar
```
ثم يتم التشغيل باستخدام ملف `jar` الناتج (الخيار ب) أو باستخدام الصنف الرئيسي `com.iednavigator.IEDNavigatorApp` مع `classes/` + `lib/*`.

### بداية سريعة

**الاتصال بجهاز IED حقيقي**
1. اختيار وضع **العميل (Cliente)** وإدخال عنوان IP والمنفذ (القياسي: `102`).
2. الاتصال واستعراض شجرة النموذج.
3. النقر بزر الفأرة الأيمن على عقدة: قراءة، كتابة، إضافة إلى شاشة المراقبة، أو تنفيذ عملية (FC = CO).

**محاكاة جهاز IED خاص بك**
1. اختيار وضع **الخادم (Servidor)** وتحميل ملف بامتداد `.icd` / `.cid` / `.scd`.
2. تشغيل الخادم والاتصال به من أي عميل MMS.

**ملف الاختبار المضمَّن:** `test/test_ied.cid`

### بنية المشروع
```
src/main/java/com/iednavigator/
  IEDNavigatorApp.java        # الواجهة الرئيسية (Swing)
  IEC61850Client.java         # عميل MMS/ACSE، الاكتشاف، الاستقصاء، التحكم
  IEC61850Server.java         # خادم/محاكي IED انطلاقًا من SCL
  GoosePublisher.java         # نشر GOOSE (pcap4j)
  GooseSubscriber.java        # اشتراك GOOSE
  GooseUdpBridge.java         # جسر GOOSE عبر UDP
  ConnectionManager.java      # إدارة الاتصال
  SclCompare.java             # مقارنة ملفات SCL
  GooseMapAnalyzer.java       # خريطة اشتراكات GOOSE
  Iec61850Dictionary.java     # قاموس IEC 61850
  native_lib/                 # روابط JNA مع iec61850.dll (GOOSE/SV أصلي)
  [+ لوحات وأصناف مساعدة]
lib/                          # تبعيات Java + iec61850.dll
test/test_ied.cid             # ملف CID للاختبار
```

### التبعيات

| المكتبة                     | الإصدار | الترخيص                | مضمَّنة |
| ---------------------------- | ------- | ----------------------- | ------- |
| iec61850bean                 | 1.9.0   | Apache 2.0              | نعم     |
| libiec61850 (iec61850.dll)   | —       | GPL v3                  | نعم     |
| asn1bean                     | 1.13.0  | Apache 2.0              | نعم     |
| pcap4j                       | 1.8.2   | MIT                     | نعم     |
| FlatLaf                      | 3.2     | Apache 2.0              | نعم     |
| JNA                          | 5.14.0  | Apache 2.0 / LGPL 2.1   | نعم     |
| SLF4J                        | 2.0.9   | MIT                     | نعم     |
| ANTLR                        | 2.7.7   | ANTLR 2 (شبيه بـ BSD)   | نعم     |
| Npcap                        | —       | ترخيص Npcap             | لا (منفصل) |

الإشعارات والتراخيص الكاملة موجودة في [THIRD-PARTY-NOTICES.txt](https://github.com/MancoQpa/IEDnavigatorPRO/blob/main/THIRD-PARTY-NOTICES.txt).

### الترخيص

يتم توزيع هذا المشروع بموجب **رخصة جنو العمومية الإصدار 3 (GPL v3)** — انظر [LICENSE](https://github.com/MancoQpa/IEDnavigatorPRO/blob/main/LICENSE). وينشأ هذا الشرط من المكتبة الأصلية `libiec61850` (`iec61850.dll`، **GPL v3**)، المضمَّنة لتوفير وظائف GOOSE/SV الأصلية؛ أما بقية التبعيات فهي مرخَّصة بتراخيص متساهلة (Apache 2.0، MIT، LGPL/BSD). يمكنك استخدام البرمجية وتعديلها وإعادة توزيعها طالما حافظت على نفس الترخيص، وضمَّنت الشيفرة المصدرية، وحافظت على إشعارات حقوق النشر.

**لا** يتم إعادة توزيع Npcap مع هذا المشروع (لا يسمح ترخيصها بذلك)؛ ويُكتفى بتوفير رابط لموقع التنزيل الرسمي، وهو ما لا يشكِّل إعادة توزيع.

جميع الحقوق محفوظة © Emilio Medina.

### المشاكل المعروفة والقيود
- قد لا يعمل التقاط/نشر GOOSE على الطبقة الثانية على جميع واجهات الشبكة في نظام Windows، ويتطلب تشغيل Npcap بصلاحيات المسؤول.
- أداة مخصَّصة للمختبرات والتدريب: **لم يتم** التحقق من صلاحيتها للتشغيل الفعلي الإنتاجي.
- المنصة موجَّهة لنظام Windows.
