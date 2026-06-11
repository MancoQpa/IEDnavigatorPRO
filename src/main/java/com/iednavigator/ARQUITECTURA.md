# Arquitectura de componentes — IEDNavigator
## `com.iednavigator` — interfaces entre clases

---

## Mapa de dependencias

```
IEDNavigatorApp  (orquestador principal)
│
├── IEC61850Client          protocolo MMS, polling de valores, reportes
├── IEC61850Server          simulación de IED desde SCL
│
├── ConnectionManager       ciclo de vida de conexión/servidor
│     └── Context ──────── implementado por IEDNavigatorApp
│
├── PollingManager          lectura periódica de valores del árbol
│     └── Context ──────── implementado por IEDNavigatorApp
│
├── MonitorManager          tabla Activity Monitor (watchlist)
│     └── Context ──────── implementado por IEDNavigatorApp
│
├── GoosePanel              todo lo relacionado con GOOSE
│     └── Context ──────── implementado por IEDNavigatorApp
│
├── ReportsPanel            tabla de RCBs (URCB/BRCB) y reportes recibidos
│     └── constructor args  (no usa Context, usa lambdas)
│
├── DatasetPanel            browser de DataSets del modelo
│     └── constructor args
│
├── DataModelPanel          árbol detallado del modelo IEC 61850
│     └── constructor args
│
├── SettingGroupsPanel      control de Setting Groups (SGCB)
│     └── constructor args
│
├── SclFileProcessor        parsing de archivos SCL (static util)
│     └── sin estado — solo métodos estáticos
│
├── ModelTreeBuilder        construcción y actualización del árbol Swing (static util)
│     └── sin estado — solo métodos estáticos
│
├── IconFactory             íconos Swing para el árbol (estáticos con caché)
├── Iec61850Dictionary      diccionario normativo de nombres IEC 61850
├── ValueDialogs            diálogos de lectura/escritura de valores (static util)
└── PortUtils               herramientas de diagnóstico de puertos TCP
```

---

## Patrón de integración

Hay dos patrones usados en el proyecto:

### 1. Patrón Context (para managers con estado)
Los componentes que mantienen estado y necesitan acceder a muchos servicios de la app
usan una interfaz `Context` interna. `IEDNavigatorApp` la implementa con una clase
anónima en `create<X>Context()`.

**Ventaja**: el componente no importa `IEDNavigatorApp` directamente.
**Componentes que lo usan**: `ConnectionManager`, `PollingManager`, `MonitorManager`, `GoosePanel`.

### 2. Patrón constructor con lambdas (para paneles UI)
Los paneles que solo construyen una `JPanel` y reaccionan a eventos reciben
sus dependencias como `Supplier<T>` y `Consumer<T>` en el constructor.

**Ventaja**: API mínima, sin acoplamiento en tiempo de compilación.
**Componentes que lo usan**: `ReportsPanel`, `DatasetPanel`, `DataModelPanel`, `SettingGroupsPanel`.

---

## Interfaces detalladas

### `ConnectionManager.Context`
**Propósito**: todo lo que `ConnectionManager` necesita para gestionar el ciclo de vida
de cliente MMS y servidor IED, incluyendo callbacks al terminar cada operación.

```java
// Logging y UI general
void log(String msg);
void updateStatus(boolean active, String msg);
Component parentWindow();
ExecutorService backgroundExecutor();

// Estado compartido cliente/servidor
IEC61850Client getClient();       void setClient(IEC61850Client c);
IEC61850Server getServer();       void setServer(IEC61850Server s);
boolean isConnected();            void setConnected(boolean v);
boolean isServerRunning();        void setServerRunning(boolean v);

// Estado del archivo SCL cargado
File getLoadedSclFile();          void setLoadedSclFile(File f);
String getLoadedIedName();        void setLoadedIedName(String n);
String[] getLoadedIedNameplate(); void setLoadedIedNameplate(String[] np);
List<SclGoCB> getSclGoCBs();

// Switches de modo en la UI
void switchUiToServerMode();
void switchUiToClientMode();

// Callbacks post-operación
void onConnected(String host, int port, String localIp);
void onDisconnected();
void onServerStarted(String localIp, int port);
void onServerStopped();

// Construcción de modelo post-conexión
void displayServerModel();
void displayClientModel();
void refreshGooseControlBlocks();
void autoSelectGooseInterface(String localIp);

// SCL parsing (delega a lógica SCL en IEDNavigatorApp/GoosePanel)
void parseGoCBsFromScl(File f);
void parseGoCBsFromScl(File f, int iedIndex);
int showIEDSelectionDialog(List<String> iedNames, String fileName);

// Control de polling (para detener antes de desconectar)
void stopPolling();

// Acceso a campos de la UI (host, puertos, etiquetas, botones)
String getTfHost();
String getTfClientPort();
String getTfServerPort();
void setLblFileName(String text);
void setStatusIndicatorConnecting();
void setBtnConnectEnabled(boolean v);
void setBtnConnectText(String text);
void setBtnStartStopText(String text);
```

**Quién la implementa**: `IEDNavigatorApp.createConnectionContext()` (línea ~2143)

---

### `PollingManager.Context`
**Propósito**: lo que necesita el loop de polling para leer valores y actualizar el árbol.
API mínima — solo estado de conexión, árbol Swing, y callbacks de refresco.

```java
void log(String msg);
boolean isConnected();
IEC61850Client getClient();

// Árbol de modelo (para iterar nodos y refrescar valores)
Set<String> getWatchlist();
DefaultMutableTreeNode getRootNode();
JTree getModelTree();
DefaultTreeModel getTreeModel();

// Formato de enums SCL (delega a GoosePanel.formatEnumValue)
String formatEnumValue(ModelNode node, String rawValue);

// Callback post-polling (actualiza tabla Activity Monitor)
void updateMonitorValues();

// Configuración
int getPollingInterval();                  // valor del spinner en ms
ExecutorService backgroundExecutor();
```

**Quién la implementa**: `IEDNavigatorApp.createPollingContext()` (línea ~2228)

---

### `MonitorManager.Context`
**Propósito**: acceso a los datos compartidos del monitor y widgets Swing necesarios
para renderizar la tabla de Activity Monitor.

```java
void log(String msg);

// Datos del monitor
Map<String, MonitorItem> getMonitorItems();
Set<String> getWatchlist();

// Widgets Swing (MonitorManager los manipula directamente)
JTree getModelTree();
JTable getMonitorTable();
DefaultTableModel getMonitorTableModel();
JComboBox<String> getMonitorFcFilter();
JTextField getMonitorNameFilter();
TableRowSorter<DefaultTableModel> getMonitorSorter();
JLabel getMonitorCountLabel();

// Auxiliares
String formatEnumValue(ModelNode node, String rawValue);
void updateWatchlistLabel();
ServerModel getServerModel();
```

**Quién la implementa**: `IEDNavigatorApp.createMonitorContext()` (línea ~1237)

**Nota**: Este contexto expone widgets Swing directamente (JTable, JTree, etc.).
Es el contexto más acoplado a la UI — refleja que MonitorManager manipula
la tabla en tiempo real, no a través de un modelo de datos separado.

---

### `GoosePanel.Context`
**Propósito**: todo lo que GoosePanel necesita para GOOSE pub/sub, parsing SCL de GoCBs,
y sincronización bidireccional con el modelo del servidor.

```java
void log(String msg);
IEC61850Server getServer();
IEC61850Client getClient();
boolean isConnected();
boolean isServerRunning();
boolean isServerMode();
ExecutorService backgroundExecutor();
Component parentWindow();

// Árbol (para resaltar nodo tras cambio de valor)
void updateSingleNodeInTree(String modelRef);
void updateServerMonitorValues();
String formatReference(String ref);

// Diálogos
int showIEDSelectionDialog(List<String> iedNames, String fileName);

// Estado SCL (GoCBs, DataSets, enums — leídos del parsing SCL)
String getLoadedIedName();        void setLoadedIedName(String name);
String[] getLoadedIedNameplate(); void setLoadedIedNameplate(String[] np);
File getLoadedSclFile();          void setLoadedSclFile(File f);
List<SclGoCB> getSclGoCBs();      void setSclGoCBs(List<SclGoCB> gcbs);
List<SclDataSet> getSclDataSets();void setSclDataSets(List<SclDataSet> datasets);

// Mapas de enums SCL (para formateo correcto de valores GOOSE)
void setSclEnumMaps(
    Map<String, LinkedHashMap<Integer, String>> enumTypes,
    Map<String, String> daEnumType,
    Map<String, Map<String, String>> lnTypeDoTypes,
    Map<String, String> lnClassToLnType);
Map<String, LinkedHashMap<Integer, String>> getSclEnumTypes();
Map<String, String> getSclDaEnumType();
Map<String, Map<String, String>> getSclLnTypeDoTypes();
Map<String, String> getSclLnClassToLnType();

// Callback al terminar el parsing SCL (refresca tabs dependientes)
void onSclLoaded();
```

**Quién la implementa**: `IEDNavigatorApp.createGooseContext()` (línea ~869)

**Nota**: Es el contexto más grande (26 métodos) porque GoosePanel hace parsing SCL
propio para GoCBs, publica/suscribe GOOSE, y sincroniza con el modelo del servidor.
La sincronización bidireccional GOOSE↔modelo está en `IEDNavigatorApp` (~líneas 1611–1722)
porque requiere acceso tanto a `GoosePanel` como a los métodos del árbol.

---

### API pública de `GoosePanel`
Métodos que `IEDNavigatorApp` llama directamente (sección GOOSE-MODEL SYNC):

```java
GoosePanel(Context ctx)

// Acceso al publisher activo (para sync de valores modelo → GOOSE)
GoosePublisher getGoosePublisher();
Map<Integer, GoosePublisher> getActivePublishers();

// Logging en el área de texto GOOSE
void logGoose(String message);

// Inferencia de tipo GOOSE desde nombre de FCDA (usada en TREE POPUP)
GoosePublisher.DataValue.Type inferDataType(String memberName);

// Helpers de conversión (usados en GOOSE-MODEL SYNC section)
GoosePublisher.DataValue convertBdaToPublisherValue(BasicDataAttribute bda,
    GoosePublisher.DataValue.Type targetType);
String convertPublisherValueToString(GoosePublisher.DataValue dv);

// Formato de enums SCL (llamado desde PollingManager.Context y MonitorManager.Context)
String formatEnumValue(ModelNode node, String rawValue);

// Construcción del panel Swing
JPanel createPanel();

// Selección de interfaz de red (llamada desde ConnectionManager.Context.autoSelectGooseInterface)
void autoSelectInterface(String localIp);

// Refresh de GoCBs tras cargar modelo (llamado desde ConnectionManager.Context.refreshGooseControlBlocks)
void refreshGooseControlBlocks();
```

---

### API pública de `IEC61850Client`

```java
// Ciclo de vida
boolean connect(String host, int port) throws IOException;
void disconnect();
boolean isConnected();
String getHost();
int getPort();
ClientAssociation getAssociation();

// Modelo
ServerModel getServerModel();

// Lectura/escritura
String readValue(String reference, Fc fc) throws IOException;
void readNodeValues(FcModelNode node) throws IOException;
void writeValue(String reference, Fc fc, String value) throws IOException;
Map<String, String> readDeviceNameplate();

// Formateo
String formatValue(ModelNode node);
String getValueType(ModelNode node);

// Control
boolean operate(FcModelNode controlNode, boolean value) throws IOException;

// Caché de valores (para polling y monitor)
CachedValue getCachedValue(String reference);

// Reportes
void setValueChangeListener(ValueChangeListener listener);
void enableReporting(Rcb rcb, ReportListener listener) throws IOException;
void disableReporting(Rcb rcb) throws IOException;

// Archivos IED (para Get CID)
List<FileInformation> listFiles(String directory) throws IOException;
List<String> findSclFiles() throws IOException;
byte[] downloadFile(String filename) throws IOException;
File downloadAndSaveSclFile(String remotePath, File localDir) throws IOException;

// Setting Groups
int[] readSGCBValues(String ldName);
void selectActiveSG(String ldName, int groupNumber) throws IOException;

// Blocking
FcModelNode findBlkEnaNode(String doReference);
void setBlocking(FcModelNode blkEnaNode, boolean block) throws IOException;

// Interfaces (listener)
interface ValueChangeListener { void onValueChange(String ref, String value, String fc); }
interface ReportListener      { void onReport(Report report); }
```

---

### API pública de `IEC61850Server`

```java
// Ciclo de vida
boolean loadSclFile(String sclPath) throws IOException;
boolean loadSclFile(String sclPath, int iedIndex);
boolean loadSclFileWithIED(String sclPath, int iedIndex);
boolean loadSclStream(InputStream is);
boolean start(int port);
void stop();
boolean isRunning();
int getPort();

// Modelo
ServerModel getServerModel();
int getIEDCount();
List<String> getAvailableIEDs(String sclPath);

// Valores
boolean setDataValue(String nodeRef, String value);
BasicDataAttribute getAttribute(String reference);
Set<String> getAttributeReferences();

// Interface (listener)
interface ServerListener {
    void onServerStarted();
    void onServerStopped();
    void onClientConnected(String clientAddr);
    void onClientDisconnected(String clientAddr);
    void onError(String msg);
}
void setServerListener(ServerListener listener);
```

---

### Utilitarios estáticos (sin estado, sin Context)

#### `SclFileProcessor`
```java
// Parsing de archivos SCL (ICD/CID/SCD)
static SclParsingResult parseFirstIED(File sclFile, Consumer<String> log);
static SclParsingResult parseIEDByIndex(File sclFile, int iedIndex, Consumer<String> log);

// Resultado del parsing
static class SclParsingResult {
    ServerModel model;
    String iedName;
    String[] nameplateFields;
    List<SclGoCB> goCBs;
    List<SclDataSet> dataSets;
    Map<String, LinkedHashMap<Integer, String>> enumTypes;
    Map<String, String> daEnumType;
    Map<String, Map<String, String>> lnTypeDoTypes;
    Map<String, String> lnClassToLnType;
}
```

#### `ModelTreeBuilder`
```java
// Construcción inicial del árbol
static void buildTree(DefaultMutableTreeNode root, ServerModel model,
    Map<String, Icon> iconCache, Consumer<String> log);

// Actualizaciones de valores
static void updateTreeValues(DefaultMutableTreeNode root,
    DefaultTreeModel treeModel, IEC61850Client client);
static void updateNodeValue(DefaultMutableTreeNode node,
    DefaultTreeModel treeModel, IEC61850Client client);
static void updateSingleNodeInTree(JTree tree, DefaultTreeModel treeModel,
    String modelRef, IEC61850Client client, IEC61850Server server);
static void updateTreeNodeRecursive(DefaultMutableTreeNode node,
    DefaultTreeModel treeModel);   // para servidor (sin client)

// Utilidades
static void clearModel(DefaultMutableTreeNode root, DefaultTreeModel treeModel);
static int countNodes(ServerModel model);

// Navegación FCDA
static void navigateToFcdaInModel(JTree tree, String fcdaName,
    ServerModel model, String iedName);
static DefaultMutableTreeNode findNodeByFcda(JTree tree, String fcdaName);
```

#### `ReportsPanel` (constructor con lambdas)
```java
ReportsPanel(
    Component parent,
    Consumer<String> log,
    Supplier<ServerModel> modelSupplier,
    Supplier<IEC61850Client> clientSupplier,
    ExecutorService backgroundExecutor,
    Consumer<String> onNodeUpdate   // ref → updateSingleNodeInTree(ref)
)
JPanel createPanel();
```

#### `DatasetPanel` (constructor con lambdas)
```java
DatasetPanel(
    Component parent,
    Consumer<String> log,
    Supplier<ServerModel> modelSupplier,
    Consumer<String> onNavigate     // ref → navega al nodo en el árbol
)
JPanel createPanel();
```

#### `DataModelPanel` (constructor con lambdas)
```java
DataModelPanel(
    Component parent,
    Consumer<String> log,
    Supplier<ServerModel> modelSupplier,
    Map<String, Icon> iconCache
)
JPanel createPanel();
```

#### `SettingGroupsPanel` (constructor con lambdas)
```java
SettingGroupsPanel(
    Component parent,
    Consumer<String> log,
    Supplier<ServerModel> modelSupplier,
    Supplier<IEC61850Client> clientSupplier,
    ExecutorService backgroundExecutor
)
JPanel createPanel();
```

---

## Flujos críticos

### Conexión MMS (cliente)
```
IEDNavigatorApp.toggleConnection()
  → ConnectionManager.connect()
    → IEC61850Client.connect(host, port)         // bloquea en background thread
    → ctx.onConnected(host, port, localIp)        // callback a IEDNavigatorApp
      → IEDNavigatorApp.onConnected()
        → ctx.displayClientModel()
          → ModelTreeBuilder.buildTree(...)
        → ctx.autoSelectGooseInterface(localIp)
          → GoosePanel.autoSelectInterface(localIp)
        → refreshGooseControlBlocks()
```

### Polling de valores
```
PollingManager.start()
  → scheduleAtFixedRate:
    → IEC61850Client.readNodeValues(node) por cada nodo en watchlist
    → ModelTreeBuilder.updateTreeValues(root, treeModel, client)
    → ctx.updateMonitorValues()
      → MonitorManager.refreshMonitorTable()
```

### Recepción de GOOSE y sincronización al modelo
```
GooseSubscriber → GoosePanel (callback GOOSE recibido)
  → GoosePanel actualiza tabla gooseTable

(Sentido inverso: cambio en modelo → GOOSE)
IEDNavigatorApp.setSelectedNodeValue(ref, value)
  → IEC61850Server.setDataValue(ref, value)
  → IEDNavigatorApp.updateGoosePublisherValues()
    → GoosePanel.getGoosePublisher().updateMember(...)
    → GoosePanel.getGoosePublisher().publish()
```

### Carga de SCL
```
IEDNavigatorApp.selectSclFile()
  → ConnectionManager.loadScl(file)
    → ctx.parseGoCBsFromScl(file)
      → SclFileProcessor.parseFirstIED(file, log)
      → ctx.setLoadedIedName(...) / setSclGoCBs(...) / ...
      → GoosePanel.ctx.onSclLoaded()
        → GoosePanel.refreshGooseControlBlocks()
        → GoosePanel.buildGoCBCombo()
```

---

## Consideraciones de diseño

**Estado compartido entre componentes**

El estado central de la app (cliente, servidor, modelo, nombre IED, GoCBs) vive en
`IEDNavigatorApp` como campos privados. Los componentes lo acceden solo a través de
sus `Context`. Esto evita referencias cruzadas directas entre `ConnectionManager`,
`PollingManager`, `GoosePanel`, etc.

**Excepción**: `GoosePanel` expone sus publishers directamente con getters
(`getGoosePublisher()`, `getActivePublishers()`) para la sección GOOSE-MODEL SYNC
que permanece en `IEDNavigatorApp`. No es una violación del patrón — es un contrato
explícito documentado en el comentario `// Public accessors for GOOSE-MODEL SYNC`.

**Thread safety**

Los callbacks de `Context` que tocan la UI usan `SwingUtilities.invokeLater()` internamente
(dentro de la implementación en `IEDNavigatorApp`). Los `Manager` no necesitan preocuparse
por el hilo actual al llamar a `ctx.log()`, `ctx.updateStatus()`, etc.

El `backgroundExecutor` (único hilo) serializa todas las operaciones de red (MMS connect,
read, write, enable/disableReporting). Las operaciones de polling usan un
`ScheduledExecutorService` separado en `PollingManager`.

**Formateo de enums SCL**

El formateo de valores que depende de los enums del SCL (`formatEnumValue`) está implementado
en `GoosePanel` (porque allí vive el parsing SCL completo) pero es consumido también por
`PollingManager` y `MonitorManager` a través de sus respectivos `Context`. Esto crea un
acoplamiento indirecto: ambos managers delegan a `GoosePanel.formatEnumValue` sin saberlo.
