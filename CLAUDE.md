# CLAUDE.md - IEC 61850 Java Explorer (IEDNavigator)

## Project Overview

Java desktop application for IEC 61850 protocol exploration and IED (Intelligent Electronic Device) simulation. Developed by Emilio Medina.

Operates in two modes:
- **Client**: Connects to remote IEDs via MMS/ACSE, discovers models, reads/writes data, monitors values, controls switches/breakers.
- **Server**: Simulates IEDs from SCL files (ICD/CID/SCD), responds to MMS client requests.

Additionally supports GOOSE messaging (publish/subscribe), Sampled Values (SV/SMV), and a GOOSE-over-UDP bridge for routed networks.

## Tech Stack

- **Language**: Java 11+
- **Build**: Maven 3.6+ (`pom.xml`) + batch/PowerShell scripts for Windows
- **GUI**: Swing with FlatLaf 3.2 look-and-feel
- **IEC 61850 Protocol**: iec61850bean 1.9.0 (com.beanit)
- **Network Capture**: pcap4j 1.8.2 (requires Npcap on Windows)
- **Native Access**: JNA 5.14.0 + libiec61850.dll for GOOSE/SV at Layer 2
- **ASN.1**: asn1bean 1.13.0, jasn1 1.11.3
- **Logging**: SLF4J 2.0.9 + slf4j-simple

## Project Structure

```
src/main/java/com/iednavigator/
  IEDNavigatorApp.java       # Main window, entry point, wiring (~3700 lines)
  IEC61850Client.java        # MMS client: model discovery, reads, control (SBO/direct/cancel),
                             #   preflight, keep-alive, DataSet recovery
  IEC61850Server.java        # IED simulation from SCL files
  ConnectionManager.java     # Connect/disconnect, CID download and "Guardar CID"
  SclExporter.java           # Rebuilds a CID from the model the IED exposed over MMS
  ValorBda.java              # Correct text for any BasicDataAttribute (see below)
  GoosePanel.java, GooseModelSync.java, GoosePublisher.java, GooseSubscriber.java,
  GooseUdpBridge.java, GooseMap*.java
  ModelTreeBuilder.java, MonitorManager.java, PollingManager.java
  *Panel.java                # Reports, Dataset, DataModel, SettingGroups, ProtectionSettings, SclCompare
  SclReferenceUtils.java     # FCDA <-> model references, BDA <-> GOOSE value conversion
  I18n.java                  # Bundles in src/main/resources/i18n (es, en, pt, zh)
  SessionLog.java            # Per-session log file (see "Logs")
  Iec61850Dictionary.java    # Descriptions of LN classes, DOs, ServiceErrors, AddCauses
  ARQUITECTURA.md            # Architecture notes (written 2026-06; partly outdated)
  native_lib/                # JNA bindings to iec61850.dll (GOOSE/SV)
  bridge/                    # Headless REST bridge (BridgeMain, api/)
src/main/java/com/iedexplorer/  # OLD package, not compiled by compile.ps1. Do not edit.
test/                        # Standalone test programs (see "Tests")
lib/                         # Pre-packaged JARs and iec61850.dll
installer/                   # resources/ is versioned; output/ is gitignored
```

## Build & Run

```bash
# Maven build (primary)
mvn clean package -DskipTests

# Run
java --enable-native-access=ALL-UNNAMED -Djna.library.path=lib -jar target/ied-navigator-1.0.0-jar-with-dependencies.jar

# Day-to-day (what the project actually uses)
.\compile.ps1                              # compiles com.iednavigator into classes\
run.bat / dev.bat                          # runs from classes\
.\build_installer.ps1 -Version X.Y.Z       # builds the package the user runs (see below)
```

Maven artifact: `com.iednavigator:ied-navigator:1.0.0`
Main class: `com.iednavigator.IEDNavigatorApp`

## Architecture Notes

- **Package**: `com.iednavigator`, with sub-packages `native_lib` and `bridge`.
- **IEDNavigatorApp.java** was monolithic (~6600 lines) and has been split into panels and
  managers that receive a small `Context` interface from it; it is now ~3700 lines.
- **Listener/callback patterns**: Client and Server use listener interfaces to notify the GUI of events (connections, value changes, errors).
- **CachedValue**: Inner class in IEC61850Client for tracking attribute values with timestamps.
- **Dual native strategy**: Java-based pcap4j for basic GOOSE + JNA bindings to libiec61850.dll for advanced GOOSE/SV features.
- **Dependencies bundled in `lib/`**: JARs are committed to the repo alongside Maven; build scripts reference both.

## Key IEC 61850 Concepts in Code

- **SCL files** (ICD/CID/SCD): XML configuration files describing IED data models. Parsed by iec61850bean's `SclParser`.
- **Functional Constraints (FC)**: ST (status), MX (measurement), CO (control), CF (configuration), etc.
- **Data model hierarchy**: Server > LogicalDevice > LogicalNode > DataObject > DataAttribute.
- **Control operations**: `IEC61850Client.selectControl()` / `executeControl()` (SBO in two
  steps), `operateControl()` (direct), `cancelControl()`. `preflightControl()` reads the
  conditions that govern an order (Beh, Loc, CILO, blocks) before sending it.
- **GOOSE**: Generic Object Oriented Substation Event - Layer 2 multicast messaging (EtherType 0x88B8).
- **Sampled Values (SMV)**: Streaming measurement data at Layer 2.

## Important Conventions

- Windows-first development: batch/PowerShell scripts, `iec61850.dll`, Npcap dependency.
- Comments, log messages and the user-facing texts are in Spanish; this file is in English.
- Logging: see "Logs" below.
- GUI uses color-coded tree nodes for state indication.
- CSV export available for monitored data.
- Built installer packages land in `installer/output/`, which is **gitignored**. The
  installer sources that *are* versioned live in `installer/resources/`
  (`INSTALAR.bat`, `README.txt`, `LEAME.txt`); `build_installer.ps1` copies them from
  there, never from a previous release.

### Reading values: never `getValueString()`

`BasicDataAttribute.getValueString()` in iec61850bean 1.9.0 returns `null` for INT8U,
INT16, INT16U and INT64, and the raw bytes (`"[11, 0, 0, ...]"`) for FLOAT64. Use
`ValorBda.texto(bda)`, which reads each type with its own getter. Before it existed, those
values were blank in the tree and the monitor, and were dropped from exported CIDs.

### Texts and i18n

All user-facing text goes through `I18n.t(key, args...)`, with the key present in the four
bundles (`messages.properties`, `_en`, `_pt`, `_zh`). `build_installer.ps1` runs a parity
check and fails if a key is missing. `I18n.t` uses `MessageFormat`, which puts a thousands
separator in integers: pass ports, counts meant as identifiers and milliseconds as
`String.valueOf(n)` (a port showed as `10,102` until this was fixed). No apostrophes in
the texts: `MessageFormat` eats them.

### Tests

There is no JUnit suite. `test/` holds standalone programs with a `main` that print
`OK` / `FALLA` and end with `TODO OK`. Compile and run one with:

```
.\compile.ps1
javac -encoding UTF-8 -cp "classes;lib\*;test" -d test test\TestX.java
java  -cp "classes;lib\*;test" TestX
```

Several start a local `ServerSap` on a high port; `TestHeartbeat.AgujeroNegro` is a TCP
proxy that stops forwarding without closing, to reproduce a silent link loss.
Relevant ones: `TestHeartbeat`, `TestCtlModelLectura`, `TestEnumAncho`, `TestI18n`.

### Logs

- `<package>\logs\session-*.log` is the **authoritative record** of a session: everything
  shown in the GUI log panel, written to disk first. Look here first.
- `iednavigator.log` next to the executable only captures standard output.
- `build_installer.ps1` preserves `logs\` across rebuilds and keeps it out of the ZIP
  (those logs contain IPs and site names).

### Connection keep-alive

Some IEDs abort the MMS association after a period with no requests (measured: 90 s).
`IEC61850Client` sends a minimal read after 60 s of silence. Every request to the IED
must go through `mms()`, `leer()` or `escribir()` in that class: they record the time of
the last traffic, and the keep-alive only fires in silence because `ClientAssociation`
does not support concurrent requests.

## Releasing: order matters

`main` is not just the default branch — **pushing it publishes**. GitHub Pages serves
`index.html` from `main` at `/`, and release tags are cut from `main`. There is no
staging step.

Publish a release in this order, and do not reorder steps 3 and 4:

1. `.\build_installer.ps1 -Version X.Y.Z`
2. Verify the package: run `INSTALAR.bat` and launch `IEDNavigatorPRO.exe` from the
   built folder.
3. **Publish the GitHub release** with the ZIP as its asset.
4. **Only then** update the download link in `index.html` and push `main`.

Doing 4 before 3 leaves the landing page's download button pointing at an asset that
does not exist yet — a 404 on the main call to action, live, for as long as the release
stays a draft. This happened with v4.13.2.

### Testing a change the user will try

`compile.ps1` writes to `classes\`. **That is not what the user runs.** The desktop
shortcut points into `installer\output\<version>_Setup\`, which only changes when
`build_installer.ps1` runs.

So "it compiles" is never the end of a change the user is about to test. Repackage
first, then confirm the class timestamp inside the package is newer than the edit:

```bash
ls -l installer/output/<version>_Setup/classes/com/iednavigator/<Changed>.class
```

This cost four rounds of "I tested it and nothing changed" in the week of
2026-09-07 alone. The user is not testing the repo; they are testing the package.

`build_installer.ps1` renames the package folder before deleting it: if anything holds it
(the app running from there, an Explorer window, a console whose current directory is
inside), it stops without deleting anything. Close whatever holds it and rerun.

### Driving the GUI for a test

The GUI can be tested from PowerShell (screenshots with `System.Drawing`, clicks with
`user32` `SetCursorPos`/`mouse_event`, keys with `SendKeys`). Two things that block it:

- An **elevated window** in the foreground (e.g. the network adapter properties) makes
  `SetCursorPos` return `False` and swallows every click. Ask the user to close it.
- If the user is working on the same desktop, clicks can land in their windows. Stop and
  ask before continuing.

### Branching

- **Chores** (`.gitignore`, config): straight to `main`. A branch buys ceremony, not
  protection.
- **Code, installer, landing** (`src/`, `build_installer.ps1`, `installer/resources/`,
  `index.html`): via branch. A mistake here reaches users immediately.

### Untracking files that are already committed

`git rm --cached <path>` does not delete anything *on the branch where you run it*, but
when that commit reaches `main`, git applies it as a deletion of tracked files and
**removes the working-tree copies** — including on anyone else's next `git pull`. Back
up first, or restore afterwards without re-adding to the index:

```bash
git restore --source=<commit-before> --worktree -- <path>
```

## Common Tasks

- **Add new data type formatting**: `ValorBda.texto()` for the raw text, `formatValue()` in
  `IEC61850Client.java` for enum decoding, `GoosePanel.formatEnumValue()` for SCL enums.
- **Modify GUI layout/tabs**: the tab content lives in its own `*Panel.java`; the frame and
  the wiring are in `IEDNavigatorApp.java`.
- **Add new protocol features**: Extend `IEC61850Client.java` or `IEC61850Server.java`.
- **Modify GOOSE behavior**: Edit `GoosePublisher.java` / `GooseSubscriber.java`.
- **Native library integration**: Work in `native_lib/` package, update JNA interfaces in `LibIec61850.java`.

## GOOSE-DataModel Bidirectional Sync Architecture

### Data Model -> GOOSE (forward)
When a value is changed in the server data model (via GUI tree), the change propagates to GOOSE publishers:

1. `setSelectedNodeValue()` / context menu dialog calls `server.setDataValue(ref, value)`
2. For single publisher: `updateGoosePublisherValues()` syncs all FCDA members from server model
3. For multi-publishers (`activePublishers`): `propagateValueToPublishers()` does the same per-GoCB

### GOOSE -> Data Model (reverse)
When a value is changed from "GoCBs del Modelo" (right-click context menu), it propagates back to the server data model:

1. `changeGoCBState()` / `setPublisherDataValue()` updates publisher and publishes GOOSE
2. Then calls `syncPublisherToServerModel(gcbIndex, dataIndex)` to write back to server model
3. Server model update triggers the monitor refresh via `updateServerMonitorValues()`

### Key helper methods (now split between `GoosePanel.java`, `GooseModelSync.java` and `SclReferenceUtils.java`):
- `buildModelRefFromFCDA(member)` - Converts FCDA string (`ldInst/LN.DO.DA [FC]`) to iec61850bean reference (`IEDNameLDInst/LN.DO.DA`) using `loadedIedName`
- `extractFcFromMember(member)` - Extracts Functional Constraint from `[FC]` suffix
- `convertBdaToPublisherValue(bda, targetType)` - Converts BDA value to GoosePublisher DataValue type (forward)
- `convertPublisherValueToString(dv)` - Converts publisher DataValue to string for server model (reverse)
- `findDataSetForGoCB(gcb)` - Finds the SclDataSet matching a GoCB's datSet name
- `syncPublisherToServerModel(gcbIndex, dataIndex)` - Writes publisher value back to server model

The `loadedIedName` field stores the IED name from the SCL file, set during `parseGoCBsFromScl()`.

## Known Considerations

- GOOSE Layer 2 multicast has limitations on Windows (noted in documentation).
- The connection timeout is configurable from the client panel ("Timeout (s)", default 10).
- iec61850bean drops the GO functional constraint: GoCBs are not in the MMS model. They are
  only known when the IED serves its CID as a file, and a CID rebuilt by `SclExporter` has
  no `GSEControl`.
- Over MMS a setting (FC=SG) only returns the active group. Reading the others requires
  `SelectEditSG`, an action on the relay; `SclExporter` deliberately exports only the
  active group, marked with `sGroup`.
- `IEC61850Client.recuperarDataSetsTolerante()` calls three private methods of
  `ClientAssociation` by reflection. When upgrading iec61850bean, check they still exist.
- `classes\com\iedexplorer\` holds stale compiled classes of the old package (2026-06) and
  ends up in the package; it is not built anymore.
- Open items, measurements and the history behind these notes are in `BITACORA.md`
  (not versioned: it contains IPs and site names).
