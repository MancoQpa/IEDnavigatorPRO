# IEDNavigator PRO

Plataforma de software abierto para **exploración, simulación y análisis del protocolo
IEC 61850**, orientada a la formación técnica en automatización de subestaciones.
Desarrollada por **Emilio Medina** (Paraguay).

Aplicación de escritorio en **Java (Swing + FlatLaf)** que integra en un único programa:

- **Cliente MMS**: conexión a IEDs reales, descubrimiento del modelo, lectura/escritura,
  y control de maniobra de interruptores (SBO reforzado con verificación de posición).
- **Servidor / simulador de IED** a partir de archivos SCL (ICD/CID/SCD).
- **GOOSE** (publicación y suscripción en Capa 2, con VLAN 802.1Q) y **puente GOOSE-sobre-UDP**.
- **Utilidades SCL**: comparación de archivos y mapa de suscripciones GOOSE.
- **Diccionario IEC 61850** educativo integrado.

> ⚠ **Uso exclusivamente educativo.** No apta para pruebas FAT/SAT, comisionamiento ni
> maniobras en instalaciones en servicio. El desarrollador no garantiza el desempeño ni la
> idoneidad para ningún propósito; el uso es bajo exclusiva responsabilidad del usuario.

## Requisitos

- **Windows 10/11 de 64 bits.**
- **Java**: no es necesario instalarlo. El instalador incluye su propio runtime (jlink).
- **Npcap** (necesario solo para captura/publicación GOOSE y SV): se instala **por separado**
  desde el sitio oficial — ver más abajo. No se incluye en el paquete por restricciones de
  su licencia.

## Instalación (para usuarios / alumnos)

1. Descargue el instalador desde la sección **[Releases](../../releases)**.
2. Extraiga **toda** la carpeta a un destino (por ejemplo `C:\IEDNavigatorPRO`).
3. Clic derecho en `INSTALAR.bat` → **Ejecutar como administrador**.
4. Instale **Npcap** desde el sitio oficial (marque *WinPcap API-compatible Mode*):
   **https://npcap.com/#download**
5. Inicie la aplicación con el ícono del Escritorio o con `IEDNavigatorPRO.exe`.

## Compilación desde el código fuente

```bash
mvn clean package -DskipTests
java --enable-native-access=ALL-UNNAMED -Djna.library.path=lib \
     -jar target/ied-navigator-1.0.0-jar-with-dependencies.jar
```

## Licencia

Este programa es software libre: se distribuye bajo la **Licencia Pública General GNU
versión 3 (GPLv3)** — ver el archivo [LICENSE](LICENSE). Esta condición deriva de las
bibliotecas de motor IEC 61850 que utiliza (iec61850bean y libiec61850), ambas GPLv3.

Los avisos y licencias de todos los componentes de terceros están en
[THIRD-PARTY-NOTICES.txt](THIRD-PARTY-NOTICES.txt).

Npcap **no** se redistribuye con este proyecto; su licencia no lo permite. Se enlaza al
sitio oficial de descarga, lo cual no constituye redistribución.
