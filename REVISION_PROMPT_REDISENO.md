# Rediseño de la ventana principal: repaso contra el prompt

Fecha: 2026-11-15 · Rama `rediseno-ui` · Etapa 5. Compara el prompt del rediseño (versión del
15-11) con el código después de las etapas 1 a 4.

## Hecho

| Punto del prompt | Dónde quedó |
|---|---|
| 1. Título con IED, archivo o IP y versión | `actualizarTitulo()` |
| 1. Menús Archivo, Simulación/Conexión, Ver, Herramientas, Ayuda con atajos | ui8; Idioma como submenú de Herramientas; Atajos de teclado en Ayuda |
| 1. Preferencias de Ver guardadas entre sesiones | `view.*` (registro, agrupar, descripciones, densidad, propiedades) |
| 2. Barra de contexto de 48 px, grupo derecho de ancho fijo, nada se mueve al cambiar de modo | `BarraContexto` (ui9) |
| 2. Píldora de estado con la IP siempre visible | `TestCabeceraIp` en OK |
| 2b. Cancelar la conexión, etapa en la píldora | etapa 1 (`444a8d6`), `TestCancelarConexion` |
| 2b. "Leer todo" fuera del EDT y cancelable | etapa 1 |
| 2b. Confirmación al cambiar de modo, Cancelar por defecto | etapa 1 |
| 2b. Informe sobre el servidor de fondo | etapa 4, `INFORME_SERVIDOR_DE_FONDO.md`; se implementó la opción 0 (Archivo › Nueva ventana) |
| 3. Paneles fijos de Servidor/Cliente repartidos en barra, menú y monitor | ui10 |
| 3. Estado vacío del árbol con su botón | ui10; el botón dice Cancelar mientras se conecta |
| 3. Registro oculto por defecto, badge, contadores como filtro, Limpiar/Copiar/Exportar, ×N, ajuste de línea | ui10 y etapa 1 (Limpiar) |
| 4. Agrupar DO por FC (también los SDO), red de seguridad en Ver | ui11 y etapa 2 |
| 4. CDC en cada DO: del SCL, o deducido con asterisco y tooltip; nada si no se puede | etapa 2 (`baaaaa3`) |
| 4. Marcas DA/BDA/SDO correctas | etapa 2 |
| 4. Valores: enumerados `1 · on`, `0 · (no configurado)`, calidad solo si no es good, hora con ms, 1970 en ámbar, resalte al cambiar | ui11 y etapa 2 |
| 4. Descripción gris al final, siempre en LN/LD, en DO con hover o selección, apagable | ui11 |
| 4. Panel Propiedades; se elimina el Explorador del Data Model y el "Exportar XML" falso | etapa 3 (`c109be4`) |
| 4. Densidad, guías de indentación, hover, selección con barra de acento | ui11/ui14 (`ArbolUI`) |
| 4. Búsqueda Ctrl+F en segundo plano con debounce, breadcrumb | ui11 (`BuscadorArbol`) |
| 4. Monitorear con doble clic, Enter o menú contextual, línea de ayuda al pie | ui11 |
| 5. Cinco pestañas de 34 px, Registro a la derecha, Ajustes en split, Comparar SCL y Mapa GOOSE en ventanas únicas | ui12 |
| 6. Monitor: barra de 40 px, columnas, resalte ámbar que se desvanece, drop en toda la tabla | ui13 |
| 7. Colores, densidad, Segoe UI, términos IEC en inglés, contraste ≥ 4,5:1 | ui14 |
| 7. Monoespaciada solo para referencias, valores, registro y atajos | etapa 5: las cuatro `Font("Monospaced")` fijas que quedaban pasan a `Estilo.mono` |

## Apartado a propósito

| Punto | Qué se hizo y por qué |
|---|---|
| 4. Marca de tipo como texto `LD/LN/DO/DA` de 9 px | El árbol usa los íconos y colores de la leyenda: lo pidió el usuario el 2026-11-14 (ui17). |
| 7. Eliminar los `TitledBorder` | Se reestilaron (negrita de 12 px, sin línea) en la ui14 en vez de quitarlos. |
| 1. "Explorador del Data Model" en Herramientas | Eliminado (etapa 3); Ctrl+M pasa a Ver › Propiedades. |

## Pendiente

| Punto | Detalle |
|---|---|
| Textos fijos en diálogos | 45 diálogos de 7 clases todavía tienen texto en español escrito en el código (GOOSE 17, ConnectionManager 6, ajustes 12, Datasets 5, otros 5). El prompt exige `I18n.t()` para el texto nuevo; estos son anteriores. Conviene una tanda propia. |
| Zoom de Windows 125 % y 150 % | Sin probar. |
| Tema oscuro | Parcial. |
| Cancelar la elección de IED de un SCD | Deja el título con ese archivo aunque no se cargó (visto al probar la etapa 3; ya pasaba antes). |
| Recientes con el mismo nombre | Dos archivos con el mismo nombre en carpetas distintas se ven iguales en el menú (el tooltip trae la ruta). |
| Pruebas de interfaz en el repositorio | Siguen afuera (propuesta 6 de `PROPUESTA_MEJORAS.md`). |
