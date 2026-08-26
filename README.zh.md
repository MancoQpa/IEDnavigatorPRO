# IEDNavigator PRO

[🇪🇸 Español](README.md) · [🇬🇧 English](README.en.md) · **🇨🇳 中文** · [🇧🇷 Português](README.pt.md) · [🇸🇦 العربية](README.ar.md)

[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/mancoqpa)

---

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
- 响应标准外部 MMS 客户端的读取请求。
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

### 已验证设备

工具实际测试过的设备，以及在每台设备上验证的内容。此处使用产品名称仅为标识产品（指称性使用），
不表示任何制造商声明的兼容性、认证或背书。

| 设备 | 验证内容 |
| --- | --- |
| Siemens SIPROTEC 5 7SJ85 | 模型发现，以及**现场执行的增强安全 SBO 操作**，含位置确认 |
| Siemens SIPROTEC 5 6MD85 | 试验台上的模型发现（31 个逻辑设备、117 个逻辑节点、2009 个数据对象）与控制模型读取 |
| NARI PCS-9611S | 模型发现与实验室 SBO 控制 |
| ZIV 2IRX | 模型发现与**直接控制**（`ctlModel` = 3） |
| ABB REC670 | 模型发现、报告控制块，以及命令前置条件检查 |
| Ingeteam Ingepac EF-ZTO | 模型发现 |
| Efacec TPU S420 | 模型发现 |

*模型发现*指工具成功获取并浏览了设备的数据模型，并不表示对该设备执行过任何操作。ABB REC670
与 Ingeteam 已退出运行，作为试验台使用：可用于功能验证，不可作为性能结论的依据。6MD85 在试验台上探索，仅接入辅助电源：无位置反馈，也无测量量。

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
