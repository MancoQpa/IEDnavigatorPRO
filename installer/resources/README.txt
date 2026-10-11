=====================================================
 IED Navigator PRO __VERSION__ — LÉAME (instrucciones)
 Desarrollado por Emilio Medina — Paraguay
=====================================================

USO EXCLUSIVAMENTE EDUCATIVO
---------------------------
Herramienta para aprendizaje y exploración del estándar IEC 61850. NO es apta
para pruebas FAT/SAT, comisionamiento ni maniobras en instalaciones en servicio.
El desarrollador no garantiza el desempeño ni la idoneidad para ningún propósito;
el uso es bajo exclusiva responsabilidad del usuario.


QUÉ NECESITÁS INSTALAR (y qué NO)
---------------------------------
1) Java  ->  NO hace falta instalar nada.
   El paquete incluye su propio runtime de Java (carpeta "jre"). La aplicación
   lo usa automáticamente.

2) Npcap ->  SÍ hay que instalarlo por separado (una sola vez).
   Es el driver que permite capturar y publicar mensajes GOOSE y Sampled Values
   en la red. Sin Npcap, la aplicación ABRE Y FUNCIONA igual para MMS
   (cliente/servidor, lectura, escritura y control); solo quedan deshabilitados
   GOOSE y Sampled Values, y el panel GOOSE lo indica en su lista de interfaces.

   ¿Por qué no viene incluido?
   La licencia gratuita de Npcap NO permite redistribuirlo dentro de
   instaladores de terceros. Por eso, en lugar de incluirlo, te dejamos el
   enlace de descarga oficial (enlazar sí está permitido).

   Descarga oficial de Npcap:
       https://npcap.com/#download
   Al instalarlo:
     - marcá la opción "WinPcap API-compatible Mode" si aparece;
     - dejá DESMARCADA la opción
       "Restrict Npcap driver's access to Administrators only".
       Si la marcás, GOOSE y Sampled Values solo funcionan ejecutando
       IED Navigator PRO como administrador. Si ya la marcaste, volvé a
       ejecutar el instalador de Npcap y desmarcala.


PASOS DE INSTALACIÓN
--------------------
1. Extraé TODA esta carpeta a un destino FIJO, por ejemplo C:\IEDNavigatorPRO
   IMPORTANTE: no ejecutes INSTALAR.bat desde adentro del ZIP. Windows lo abre
   en una carpeta temporal que después borra, y el acceso directo queda
   apuntando a una ruta que ya no existe ("instalé y no arranca").
2. Doble clic en INSTALAR.bat.
   Verifica el paquete y el runtime Java incluido, y crea un acceso directo en
   el Escritorio. No instala nada en el sistema y NO requiere administrador.
3. Instalá Npcap desde el enlace de arriba (solo si vas a usar GOOSE/SV).
4. Iniciá la aplicación con el ícono del Escritorio o con IEDNavigatorPRO.exe

Una vez instalado, no muevas ni renombres la carpeta: el acceso directo apunta
a esta ubicación.


REQUISITOS
----------
- Windows 10 u 11 de 64 bits.
- Administrador: NO hace falta para instalar ni para el uso normal (MMS,
  simulador, GOOSE y Sampled Values). Solo lo pide:
    - el instalador de Npcap, una vez;
    - la herramienta "Liberar Puerto", para terminar un programa que ocupe el
      puerto 102 (algunas aplicaciones de fabricantes de IED corren en segundo
      plano y se adueñan de ese puerto). Para eso, y solo para eso, iniciá la
      aplicación con clic derecho > Ejecutar como administrador;
    - GOOSE / SV, únicamente si Npcap se instaló con la opción "Restrict Npcap
      driver's access to Administrators only" (ver arriba).


LICENCIA Y CÓDIGO FUENTE
------------------------
IED Navigator PRO es software libre bajo la Licencia Pública General GNU v3
(GPLv3) — ver el archivo LICENSE incluido. El código fuente completo está en:

       https://github.com/MancoQpa/IEDnavigatorPRO

Los avisos y licencias de todos los componentes de terceros (iec61850bean,
libiec61850, pcap4j, FlatLaf, JNA, SLF4J, etc.) están en THIRD-PARTY-NOTICES.txt


ENLACES ÚTILES
--------------
- Descargas del programa (Releases): https://github.com/MancoQpa/IEDnavigatorPRO/releases
- Npcap (driver GOOSE/SV):           https://npcap.com/#download
