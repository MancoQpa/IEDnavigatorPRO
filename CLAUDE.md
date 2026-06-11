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
src/main/java/com/iedexplorer/
  IEDNavigatorApp.java       # Main GUI (~6600 lines) - Entry point, 3-panel layout
  IEC61850Client.java        # MMS client, model discovery, polling, control
  IEC61850Server.java        # IED simulation from SCL files
  GoosePublisher.java        # GOOSE message publishing via pcap4j
  GooseSubscriber.java       # GOOSE message capture and parsing
  GooseUdpBridge.java        # GOOSE encapsulation over UDP (unicast/broadcast/multicast)
  ListFc.java                # Functional Constraint listing utility
  SclParserTest.java         # SCL parser test utility
  native_lib/
    LibIec61850.java         # JNA interface to iec61850.dll
    NativeGooseSubscriber.java  # Native GOOSE subscriber via JNA
    NativeSVSubscriber.java     # Native Sampled Values subscriber via JNA
lib/                         # Pre-packaged JARs and iec61850.dll
installer/                   # Distribution packaging scripts and outputs
```

## Build & Run

```bash
# Maven build (primary)
mvn clean package -DskipTests

# Run
java --enable-native-access=ALL-UNNAMED -Djna.library.path=lib -jar target/ied-navigator-1.0.0-jar-with-dependencies.jar

# Alternative: batch scripts
build.bat        # Compiles
run.bat          # Runs
START.bat        # Smart startup with dependency checks
```

Maven artifact: `com.iednavigator:ied-navigator:1.0.0`
Main class: `com.iednavigator.IEDNavigatorApp`

## Architecture Notes

- **Single-package design**: All classes in `com.iednavigator` (no sub-packages except `native_lib`).
- **IEDNavigatorApp.java is monolithic**: Contains the entire Swing GUI, event handling, tab management, and coordinates client/server. This is the largest file (~6600 lines).
- **Listener/callback patterns**: Client and Server use listener interfaces to notify the GUI of events (connections, value changes, errors).
- **CachedValue**: Inner class in IEC61850Client for tracking attribute values with timestamps.
- **Dual native strategy**: Java-based pcap4j for basic GOOSE + JNA bindings to libiec61850.dll for advanced GOOSE/SV features.
- **Dependencies bundled in `lib/`**: JARs are committed to the repo alongside Maven; build scripts reference both.

## Key IEC 61850 Concepts in Code

- **SCL files** (ICD/CID/SCD): XML configuration files describing IED data models. Parsed by iec61850bean's `SclParser`.
- **Functional Constraints (FC)**: ST (status), MX (measurement), CO (control), CF (configuration), etc.
- **Data model hierarchy**: Server > LogicalDevice > LogicalNode > DataObject > DataAttribute.
- **Control operations**: Select-before-operate (SBO) and direct control for switches/breakers via `controlSwitch()`.
- **GOOSE**: Generic Object Oriented Substation Event - Layer 2 multicast messaging (EtherType 0x88B8).
- **Sampled Values (SMV)**: Streaming measurement data at Layer 2.

## Important Conventions

- Windows-first development: batch/PowerShell scripts, `iec61850.dll`, Npcap dependency.
- No unit tests in the project (tests are skipped in Maven build).
- Logging via SLF4J with simple backend; debug info printed to console.
- GUI uses color-coded tree nodes for state indication.
- CSV export available for monitored data.
- Multiple installer versions exist in `installer/output/` (v2.0 through v12).

## Common Tasks

- **Add new data type formatting**: Modify `formatValue()` / `formatQuality()` in `IEC61850Client.java`.
- **Modify GUI layout/tabs**: Edit `IEDNavigatorApp.java` (search for panel/tab creation methods).
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
3. Server model update triggers tree/monitor refresh via `updateServerTreeValues()` / `updateServerMonitorValues()`

### Key helper methods (in `IEDNavigatorApp.java`):
- `buildModelRefFromFCDA(member)` - Converts FCDA string (`ldInst/LN.DO.DA [FC]`) to iec61850bean reference (`IEDNameLDInst/LN.DO.DA`) using `loadedIedName`
- `extractFcFromMember(member)` - Extracts Functional Constraint from `[FC]` suffix
- `convertBdaToPublisherValue(bda, targetType)` - Converts BDA value to GoosePublisher DataValue type (forward)
- `convertPublisherValueToString(dv)` - Converts publisher DataValue to string for server model (reverse)
- `findDataSetForGoCB(gcb)` - Finds the SclDataSet matching a GoCB's datSet name
- `syncPublisherToServerModel(gcbIndex, dataIndex)` - Writes publisher value back to server model

The `loadedIedName` field stores the IED name from the SCL file, set during `parseGoCBsFromScl()`.

## Known Considerations

- GOOSE Layer 2 multicast has limitations on Windows (noted in documentation).
- Connection timeout is hardcoded at 10 seconds in the client.
- The main GUI file (IEDNavigatorApp.java) is very large and could benefit from decomposition.
- JNA library has two versions in lib/ (5.13.0 and 5.14.0) - potential classpath conflict.
