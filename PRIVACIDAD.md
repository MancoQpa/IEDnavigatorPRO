# Privacidad — IEDNavigator PRO

**Última actualización:** 2026-10-10

IEDNavigator PRO es una herramienta educativa, gratuita y de código abierto (GPL v3).
Esta página explica qué datos maneja la aplicación, dónde quedan y cómo borrarlos.

## En resumen

- **La aplicación no recopila datos de ningún tipo.**
- **La aplicación no envía datos de ningún tipo** al desarrollador ni a terceros. No tiene
  telemetría, analíticas, cuentas de usuario, publicidad ni búsqueda automática de
  actualizaciones.
- **La aplicación no sabe quién la usa.** No hay registro, inicio de sesión ni
  identificador de instalación.
- **Todo queda en la computadora de quien descargó e instaló la aplicación**, y se puede
  borrar en cualquier momento (ver *Cómo borrar tus datos*).

## Conexiones de red que hace la aplicación

La aplicación solo se conecta a lo que **vos le indicás**:

- **Cliente MMS:** a la dirección IP y el puerto del IED que ingresás.
- **Servidor / simulador:** escucha en el puerto que elegís, para que se conecten clientes
  de tu red.
- **GOOSE:** publica y captura tramas en la red local por la interfaz que
  seleccionás. El modo *GOOSE sobre UDP* envía las tramas por difusión (broadcast) a tu
  subred, en el puerto 62746.

Ninguna de estas conexiones va a servidores del desarrollador ni de terceros.

El código fuente incluye además un puente REST (`bridge/`) que la aplicación de escritorio
**no inicia**. Si alguien lo ejecuta por separado, escucha solo en `127.0.0.1` (el propio
equipo) y exige un token.

## Permisos de administrador: solo para liberar el puerto 102

La aplicación **no necesita** ejecutarse como administrador para su uso normal, incluido
GOOSE si Npcap se instaló con la opción recomendada (ver más abajo).

La única excepción es la herramienta **Liberar Puerto** del panel Servidor. El puerto 102
es el puerto estándar de MMS (IEC 61850), y **algunas aplicaciones propietarias de
fabricantes de IED se ejecutan en segundo plano y se adueñan de ese puerto**, aunque no
las estés usando. Mientras otro programa lo ocupe, el simulador de IED no puede escuchar
en él.

Qué hace la herramienta, paso a paso:

1. **Verificar Puerto** identifica qué programa ocupa el puerto, con los comandos de
   Windows `netstat -ano` y `tasklist`. Para esto no hace falta ser administrador.
2. **Liberar Puerto** te muestra ese programa y te pide **confirmación** antes de hacer
   nada.
3. Si confirmás, lo termina con el comando de Windows `taskkill /F /PID <número>`.

Esos programas suelen correr como servicios del sistema o con otra cuenta, y Windows solo
permite terminarlos a un administrador. **Por eso, y solo para esto**, hace falta iniciar
IEDNavigator PRO con *clic derecho → Ejecutar como administrador*. Para todo lo demás,
abrila normalmente.

Tené en cuenta que:

- Terminar el proceso **detiene esa aplicación del fabricante**. Volverá a iniciarse con
  el próximo reinicio de Windows o del servicio.
- Si no querés terminar nada, podés usar otro puerto para el simulador (por ejemplo
  `10102`), que no requiere permisos especiales.
- Todo ocurre en tu computadora: la herramienta no envía ni guarda información sobre los
  programas que encuentra. Solo te la muestra en pantalla.

### GOOSE: depende de cómo instalaste Npcap

GOOSE usa Npcap, un programa aparte. Su instalador ofrece la opción
**"Restrict Npcap driver's access to Administrators only"**:

- **Desmarcada (recomendado):** GOOSE funciona con un usuario común, sin
  ejecutar IEDNavigator PRO como administrador.
- **Marcada:** solo funciona si ejecutás IEDNavigator PRO como administrador. Es una
  restricción de Npcap, no de esta aplicación.

Si la marcaste, volvé a ejecutar el instalador de Npcap y dejala desmarcada. El instalador
de IEDNavigator PRO detecta esta opción y lo avisa.

## Qué guarda la aplicación en tu computadora

| Qué | Dónde | Qué contiene |
|---|---|---|
| Registros de sesión | carpeta `logs\` junto al programa; si no se puede escribir ahí, `%USERPROFILE%\.iednavigator\logs\` | lo que muestra el panel de registro: direcciones IP de los equipos a los que te conectaste, lecturas, órdenes enviadas y sus resultados. Se conservan las **últimas 30 sesiones**; las más viejas se borran solas. |
| Salida estándar | `iednavigator.log`, junto al programa | mensajes técnicos de la última ejecución. |
| Idioma elegido | registro de Windows, `HKEY_CURRENT_USER\Software\JavaSoft\Prefs\com\iednavigator` | solo el código del idioma (por ejemplo `es`). |
| Archivos que exportás | donde vos elijas | archivos CID, CSV del monitor, informes HTML del modelo. La aplicación solo los crea cuando se lo pedís. |

Estos datos **nunca salen de tu computadora** por iniciativa de la aplicación. Los
registros pueden contener direcciones IP y nombres de equipos de tu instalación: tenelo en
cuenta si los compartís, por ejemplo al reportar un problema.

## Cómo borrar tus datos

No hay datos tuyos en ningún servidor, así que no hace falta pedirle nada a nadie: el
borrado está completamente en tus manos. Seguí estos pasos en orden. Ninguno requiere
permisos de administrador.

### Paso 1 — Cerrar la aplicación

Cerrá todas las ventanas de IEDNavigator PRO. Si el programa está abierto, Windows no deja
borrar su carpeta.

Para confirmar que no quedó corriendo: abrí el **Administrador de tareas**
(`Ctrl` + `Shift` + `Esc`) y verificá que no aparezca *IEDNavigatorPRO.exe* ni *OpenJDK
Platform binary* (el Java que viene incluido con la aplicación). Si aparece alguno,
seleccionalo y hacé clic en **Finalizar tarea**.

> Si tenés otros programas Java abiertos, también pueden figurar como *OpenJDK Platform
> binary*. Ante la duda, cerrá la aplicación desde su ventana en lugar de finalizar tareas.

### Paso 2 — Ubicar la carpeta del programa

Es la carpeta donde extrajiste el ZIP (por ejemplo `C:\IEDNavigatorPRO_v4.13.4_Setup`).
Si no recordás dónde está:

1. Hacé clic derecho sobre el acceso directo **IEDNavigator PRO** del escritorio.
2. Elegí **Abrir ubicación del archivo**. Se abre el Explorador en la carpeta del
   programa.

### Paso 3 — Quitar el acceso directo

Ejecutá `INSTALAR.bat` desde esa carpeta y hacé clic en **Desinstalar**, o simplemente
borrá el acceso directo del escritorio. Las dos cosas hacen lo mismo.

> **Importante:** *Desinstalar* solo quita el acceso directo. **No borra** el programa, los
> registros ni la preferencia de idioma. Para eso están los pasos siguientes.

### Paso 4 — Borrar la carpeta del programa

Volvé a la carpeta del paso 2, subí un nivel y borrá la carpeta completa. Con eso se
eliminan:

- el programa;
- la carpeta `logs\`, con los registros de todas las sesiones;
- el archivo `iednavigator.log`.

Si Windows dice que el archivo está en uso, volvé al paso 1.

### Paso 5 — Borrar los registros de la carpeta de usuario (si existe)

Solo existe si la aplicación no pudo escribir en su propia carpeta (por ejemplo, si quedó
dentro de `Archivos de programa`).

1. Presioná `Windows` + `R`.
2. Escribí `%USERPROFILE%\.iednavigator` y presioná **Enter**.
3. Si se abre una carpeta, cerrala, subí un nivel y borrá la carpeta **`.iednavigator`**
   completa. Si Windows dice que no la encuentra, no existe y no hay nada que borrar.

### Paso 6 — Borrar la preferencia de idioma

Es el único dato que la aplicación guarda fuera de su carpeta: el idioma que elegiste,
por ejemplo `es`. No contiene nada más.

**Opción A — con el Editor del Registro:**

1. Presioná `Windows` + `R`, escribí `regedit` y presioná **Enter**. Aceptá el aviso de
   Windows.
2. Pegá esta ruta en la barra de direcciones del editor y presioná **Enter**:
   `HKEY_CURRENT_USER\Software\JavaSoft\Prefs\com\iednavigator`
3. Hacé clic derecho sobre la carpeta **`iednavigator`** del panel izquierdo y elegí
   **Eliminar**. Confirmá.

> Borrá **solo** la carpeta `iednavigator`. Las carpetas de alrededor pueden ser de otros
> programas Java.

**Opción B — con un comando:**

1. Presioná `Windows` + `R`, escribí `cmd` y presioná **Enter**.
2. Pegá este comando y presioná **Enter**:

   ```
   reg delete "HKCU\Software\JavaSoft\Prefs\com\iednavigator" /f
   ```

3. Si responde *La operación se completó correctamente*, quedó borrada. Si responde que no
   encuentra la clave, ya no existía.

### Paso 7 — Archivos que exportaste vos

Los archivos CID, CSV o HTML que guardaste desde la aplicación están donde los elegiste
guardar. La aplicación no lleva cuenta de ellos: buscalos y borralos como cualquier otro
archivo.

### Verificación

Después de los pasos 4 a 6, en tu computadora no queda nada de IEDNavigator PRO. Npcap,
si lo instalaste, es un programa aparte: se desinstala desde **Configuración →
Aplicaciones**.

## La página web

La página de presentación (`mancoqpa.github.io/IEDnavigatorPRO`) no usa cookies,
analíticas ni contadores de visitas. Sí intervienen dos servicios de terceros:

- **GitHub Pages**, que aloja la página y las descargas, y registra los accesos según la
  [Declaración de Privacidad de GitHub](https://docs.github.com/es/site-policy/privacy-policies/github-general-privacy-statement).
- **Google Fonts**, que entrega las tipografías: tu navegador le pide los archivos a
  Google, que ve tu dirección IP.

El enlace a Ko-fi es un enlace común: no carga nada de Ko-fi hasta que hacés clic.

## Uso de inteligencia artificial

El desarrollo de IEDNavigator PRO —código, documentación y traducciones— contó con
asistencia de herramientas de inteligencia artificial, revisada por el autor.

Aun así, **no se descarta la presencia de errores (bugs)** que la revisión y la
refactorización asistida por IA no hayan podido detectar o corregir. Por eso la
herramienta es de uso exclusivamente educativo y no debe usarse en instalaciones en
servicio. Si encontrás un error, podés reportarlo (ver *Contacto*).

La traducción al chino no fue revisada por un hablante nativo y puede contener errores.

La aplicación **no usa** inteligencia artificial durante su funcionamiento ni envía datos
a servicios de IA.

## Contacto

Para consultas sobre privacidad, abrí un *issue* en
[github.com/MancoQpa/IEDnavigatorPRO/issues](https://github.com/MancoQpa/IEDnavigatorPRO/issues).
Los *issues* son públicos: no incluyas direcciones IP, nombres de instalaciones ni otros
datos sensibles.

## Cambios a esta página

Si la aplicación empezara a manejar datos de otra forma, esta página se actualizará antes
de publicar esa versión, y la fecha de arriba lo indicará.
