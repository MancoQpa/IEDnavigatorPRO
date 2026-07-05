# Vocabulario y estilo de Emilio Medina

Diccionario vivo de terminología, jerga técnica y convenciones de estilo que usa
Emilio Medina (autor de IEDNavigator PRO) al describir tareas, escribir commits y
documentar el código. Generado analizando el historial de git y el código del
repositorio. **Este archivo se lee como contexto en cada sesión** — amplíalo a
medida que aparezcan términos nuevos, en vez de crear uno paralelo.

## Cómo usar este archivo

- Cuando Emilio use una palabra o abreviatura de esta lista, interprétala con el
  significado de aquí sin pedir aclaración.
- Cuando aparezca un término técnico nuevo y recurrente (2+ veces) en una
  conversación, propone añadirlo aquí.
- Al escribir commits, mensajes o documentación para este proyecto, imita el
  estilo descrito en la sección "Estilo de escritura".

---

## Jerga y abreviaturas del dominio IEC 61850 (tal como las usa)

| Término | Significado / uso |
|---|---|
| GoCB | GOOSE Control Block. Usa "GoCBs" (plural) y "GoCBs del Modelo" para los que vienen del SCL. |
| GooseMap / Mapa GOOSE | Panel/funcionalidad que mapea publicadores↔suscriptores GOOSE (ExtRef/LGOS). |
| FCDA | Functional Constrained Data Attribute — miembro de un DataSet. |
| BDA | Basic Data Attribute — atributo hijo dentro de un DA/DO al expandir. |
| stVal / stNum / sqNum | Atributos de estado y secuencia de GOOSE/status; los nombra tal cual, sin traducir. |
| orCat / origin | Campo "originator category" del control; recuerda casos puntuales como `orCat=3` vs `orCat=0 (not-supported)`. |
| ctlModel | Modelo de control: 1=status-only, 2=direct-with-enhanced?, 4=sbo-enhanced-security ("SBO enhanced"). Lo escribe como `ctlModel=4`. |
| SBO / SBO reforzado / SBO enhanced | Select-Before-Operate; "SBO enhanced" = ctlModel 4 (select-with-value). |
| addCause | Campo de causa adicional en LastApplError; lo cita en frases tipo "addCause 8 (Blocked-by-Mode)". |
| Blocked-by-Mode / Blocked-by-switching-hierarchy | Causas de rechazo de control que menciona por nombre exacto, no traducidas. |
| LastApplError | DO de error de aplicación; nota importante suya: "es a nivel de LN, no del DO de control" (bug que encontró y documentó). |
| RCB / URCB / BRCB | Report Control Block (Unbuffered/Buffered). |
| SG / SGCB / Setting Groups | Grupos de ajustes; el panel lo llama "Ajustes" en la UI. |
| DataSet | Siempre en inglés, nunca "conjunto de datos". |
| SCL / ICD / CID / SCD | Archivos de configuración; los nombra siempre en mayúsculas, sin explicar (asume que se sabe). |
| maniobra | Su palabra para "operación de control" sobre un interruptor/switch (operate). "Maniobra confirmada", "maniobra real". |
| Modo Test | Se refiere al checkbox de modo de prueba del diálogo de control; lo escribe con mayúscula inicial. |
| retrieveModel() | Llamada MMS de iec61850bean; suele indicar sus limitaciones conocidas (IEDs grandes, DataSets faltantes). |
| AccessPoint | Unidad de modelo SCL por IED; tema recurrente: "un IED puede tener 2+ AccessPoints que hay que fusionar". |
| heartbeat | GOOSE heartbeat (retransmisión periódica); nunca lo traduce. |
| coalesce / COALESCE_MS | Término que usa para agrupar/filtrar mensajes rápidos repetidos. |
| bridge | El backend headless Java que conecta el frontend React con el motor MMS/GOOSE. |
| sidecar | El proceso Java empaquetado (jlink) que lanza Tauri. |
| paridad (1:1) | Su criterio de aceptación recurrente: el frontend nuevo debe comportarse igual que el Swing original. "Paridad GOOSE/Dataset/GooseMap". |
| réplica exacta / réplica de X | Forma de documentar un componente nuevo que imita a uno viejo (`/** Réplica de showAboutDialog() de la GUI original. */`). |
| fiel al comportamiento original | Frase de justificación cuando replica un comportamiento aunque parezca raro. |

## Marcas/IEDs reales que menciona

- **NARI PCS-9611S** — IED de referencia con comportamientos particulares (ctlModel=4, no expone LastApplError en el modelo).
- **Siemens** — otra marca mencionada por rechazar `updateDataSets()`.

Cuando mencione una marca de IED sin más contexto, asume que se refiere a un caso real de prueba/depuración, no hipotético.

## Estilo de escritura (commits y docs)

- Formato de commit: **Conventional Commits en español** con scope:
  `feat(control): ...`, `fix(bridge): ...`, `style(ui): ...`, `perf(bridge): ...`, `docs(licencia): ...`, `chore: ...`
- El asunto suele terminar con una versión entre paréntesis cuando aplica: `(v4.5)`, `v1.1.0`.
- Cuerpo del commit en estructura de diagnóstico, casi siempre en este orden:
  1. **Problema / Causa (raíz)** — describe el síntoma observado y por qué ocurre, a veces "Causa raíz definitiva:" cuando corrige un fix anterior incompleto.
  2. **Cambios** — lista con guiones, un ítem por archivo/método tocado, formato `Archivo.método(): qué hace`.
  3. A veces cierra con una nota de fidelidad ("Igual al comportamiento del Swing original", "fiel al...").
- Le gusta anotar el nombre exacto de método + archivo entre backticks o sin ellos: `verifyControlFeedback()`, `fillControlStructure()`.
- Usa em-dash (—) para separar cláusulas dentro de un mismo renglón, con frecuencia.
- Comentarios de código casi siempre en español, incluso cuando el código/identificadores están en inglés (mezcla habitual: `// Fusionar todos los AccessPoints de este IED en un único ServerModel`).
- Nombres de método/clase en inglés (`buildModelRefFromFCDA`, `syncPublisherToServerModel`), pero texto de UI (botones, diálogos, logs) en español: `btnPublicarTodos`, "Publicar Todos", "Habilitar Todos".
- Le gusta agrupar botones de acción masiva con el patrón "Todos" (`Expandir Todo`, `Colapsar Todo`, `Habilitar Todos`, `Publicar Todos`, `Detener Todos`).
- Prefijos de sección en comentarios: `// Métodos públicos`, `// Métodos privados —`, usados para separar bloques dentro de una clase.
- Disclaimers recurrentes: proyecto marcado como **"uso exclusivamente educativo"**, "No apta para pruebas FAT/SAT, comisionamiento ni maniobras en instalaciones en servicio."
- Licencia: cuida mucho la procedencia GPLv3 de iec61850bean/libiec61850 y qué se puede/no puede redistribuir (p. ej. Npcap).

## Preferencias de arquitectura/código

- Prefiere **decomposición explícita documentada** (ver `ARQUITECTURA.md`): patrón `Context` (interfaz interna) para managers con estado, patrón "constructor con lambdas" (`Supplier<T>`/`Consumer<T>`) para paneles UI sin estado propio.
- Marca TODOs de refactor con formato propio: `// TODO-REFACTOR F1: ...` (fases numeradas).
- Cuando extrae código de un archivo monolítico deja constancia: `/** Extraído de IEDExplorerApp.java — métodos createXxxIcon + initIcons. */`.
- Versiona cambios visuales como "pulido" (`style(ui): pulido visual FlatLaf`) y aclara cuando algo es "solo cosmético, sin cambios de layout ni funcionalidad".

## Notas para completar

Esta sección es intencionalmente breve porque se generó a partir de solo ~27
commits y del código existente — el histórico real de conversaciones con Emilio
no estaba disponible en esta sesión. Ampliar con:
- Expresiones/muletillas que use en el chat (aún no observadas, solo en código/commits).
- Convenciones de nombres de rama.
- Vocabulario de negocio/comercial (instalador, distribución, versión) si surge.
