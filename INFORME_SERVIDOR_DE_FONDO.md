# ¿Puede seguir corriendo la simulación mientras se usa el Cliente?

Fecha: 2026-11-15 · Rama `rediseno-ui` (después de `c109be4`) · Etapa 4 del rediseño: solo
informe, nada implementado.

## Respuesta corta

Técnicamente sí: el servidor (`IEC61850Server`) no depende de la interfaz y no hay conflicto de
red (escucha en su puerto; el cliente usa un puerto efímero hacia el IED, o hacia el propio
simulador, que ya se probó). El problema está en la ventana: **modelo, árbol, monitor, panel
GOOSE y panel Propiedades son uno solo y los usan los dos modos**. Dejar el servidor corriendo
sin tocar eso produce errores silenciosos (abajo, con el lugar exacto del código).

Recomiendo, en este orden:

1. **Ahora, costo casi nulo:** *Archivo › Nueva ventana*, que abre otra instancia del programa.
   Una corre la simulación y la otra es el Cliente. El código no tiene bloqueo de instancia
   única, así que abrir el programa dos veces debería funcionar ya; no lo probé con las dos
   corriendo a la vez. Falta el menú y esa prueba.
2. **Si después hace falta en la misma ventana:** la opción A de abajo (servidor de fondo sin
   interfaz propia mientras se está en Cliente), unos 2 a 3 días con pruebas.
3. **No recomiendo** la opción B (los dos modos completos a la vez en una ventana): es rehacer
   el contexto de toda la aplicación.

## Lo que hoy se comparte entre los dos modos

| Pieza | Dónde | Qué pasaría con el servidor corriendo en modo Cliente |
|---|---|---|
| Un solo árbol y un solo `nodeMap` | `IEDNavigatorApp` (`rootNode`, `nodeMap`) | Muestra el modelo del IED remoto. Cuando un cliente externo escribe en el simulador, `onClientWrite` llama a `updateSingleNodeInTree(ref)`: si el IED remoto tiene la misma referencia (o si el Cliente está conectado al propio simulador) **se actualiza el nodo equivocado** |
| Un solo monitor | `monitorItems`, `watchlist` | `onClientWrite` llama a `updateServerMonitorValues()`, que lee **siempre** el modelo del servidor (`MonitorManager` → `ctx.getServerModel()`, sin mirar el modo): **pisaría los valores del IED remoto con los del simulador** |
| Un solo panel GOOSE y un solo combo de placa | `GoosePanel` | Al conectar, el Cliente elige la placa (`autoSelectGooseInterface`). Los publicadores del simulador usan ese mismo combo: **la simulación podría pasar a publicar por otra placa** sin que nadie lo pida |
| Un solo `currentMode` | 38 decisiones en `IEDNavigatorApp`, 8 en `ConnectionManager`, 6 en `GoosePanel` | Cada una supone que hay un solo modo activo: barra de contexto, menú Simulación/Conexión, título, estado vacío, interruptor virtual, modo test, reporte HTML, Guardar CID… |
| Panel Propiedades y renderer del árbol | `PanelPropiedades`, `ArbolCeldaRenderer` (`modoServidor`, `sinLeer`) | Leen el modo para decidir "no configurado" frente a "sin leer": con dos modos vivos hay que decidirlo por modelo, no por ventana |
| Interruptor virtual | `ventanasAparato` | Opera sobre el servidor. Puede seguir abierto, pero hay que decidir si se cierra o queda al cambiar a Cliente |

Lo que **no** es problema: el motor de mando, las fallas del interruptor virtual, los reports
y el GOOSE del simulador viven en `IEC61850Server` y su motor; siguen funcionando sin la
ventana.

## Opciones

### Opción 0 — Dos instancias (recomendada ahora)

- *Archivo › Nueva ventana* lanza el mismo ejecutable con `ProcessBuilder`.
- Cada instancia tiene su modelo, su árbol, su monitor y su registro de sesión.
- Compartido: las preferencias (`view.*`, recientes): el último que escribe gana. No rompe nada.
- Costo: medio día, con una prueba manual (simulación en una, cliente en otra, contra la propia
  simulación y contra un IED).
- Límite: dos ventanas en vez de una; dos procesos Java (unos 300 MB más de memoria).

### Opción A — Servidor de fondo en la misma ventana

- Al pasar a Cliente con la simulación activa, el diálogo ofrece una tercera opción: *Dejar la
  simulación corriendo*.
- Mientras se está en Cliente:
  - `onClientWrite` solo registra y propaga al GOOSE; no toca árbol ni monitor (que son del IED
    remoto);
  - `updateServerMonitorValues` no corre;
  - el Cliente no cambia la placa GOOSE si hay publicadores del simulador activos;
  - la barra de contexto muestra un aviso fijo: *Simulación activa de fondo · IP:puerto*, con
    un enlace para volver;
  - el interruptor virtual sigue abierto.
- Al volver a Servidor: se reconstruye el árbol desde el modelo del servidor (que no se
  perdió) y se reengancha el monitor.
- Costo: 2 a 3 días, con una prueba automática de que `onClientWrite` en Cliente no toca el
  monitor ni el árbol, y una prueba manual con el Cliente conectado al propio simulador.
- Riesgo: cada función nueva tiene que acordarse de esta regla. Mitigación: que las tres
  decisiones (árbol, monitor, placa) pasen por un solo método `servidorEnPrimerPlano()`.

### Opción B — Los dos modos completos a la vez

- Dos árboles, dos monitores, dos paneles GOOSE (o pestañas por modo), y un `Context` por modo
  en vez del único actual.
- Costo: semanas; toca casi todas las clases de interfaz.
- No lo recomiendo: la opción 0 da lo mismo para el usuario con una fracción del riesgo.

## Para decidir

- Si el uso es "simulo un IED y lo exploro con el Cliente", la opción 0 alcanza y además deja
  ver las dos cosas a la vez, lado a lado.
- Si el uso es "salto entre los dos modos sin cortar la simulación", hace falta la opción A.
