# IEDNavigator PRO

[🇪🇸 Español](README.md) · **🇬🇧 English** · [🇨🇳 中文](README.zh.md) · [🇧🇷 Português](README.pt.md) · [🇸🇦 العربية](README.ar.md)

[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/mancoqpa)

---

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
- Responds to MMS reads from standard external MMS clients.
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

### Verified equipment

Real devices the tool has been exercised against, and what was verified on each. Names are used
to identify the product (nominative use): they imply no declared compatibility, certification or
endorsement by any manufacturer.

| Device | What was verified |
| --- | --- |
| Siemens SIPROTEC 5 | Model discovery and **SBO with enhanced security operated in the field**, with position verification |
| NARI PCS-9611S | Model discovery and SBO control in the lab |
| ZIV 2IRX | Model discovery and **direct control** (`ctlModel` = 3) |
| ABB REC670 | Model discovery, report control blocks and pre-command condition checks |
| Ingeteam Ingepac EF-ZTO | Model discovery |
| Efacec | Model discovery |
| TPUS420 | Model discovery |

*Model discovery* means the tool retrieved and browsed the device's data model; it does not
imply anything was operated on it. The ABB REC670 and the Ingeteam are retired from service and
used as test-bench units: they support functional verification, not performance claims.

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
