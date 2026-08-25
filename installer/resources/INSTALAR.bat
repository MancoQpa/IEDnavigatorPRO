@echo off
setlocal enabledelayedexpansion
title IED Navigator PRO __VERSION__ - Instalacion
color 1F
cd /d "%~dp0"

set "OK_JRE=0"
set "OK_APP=0"
set "OK_NPCAP=0"
set "OK_LNK=0"
set "ABORTAR=0"

cls
echo.
echo  ================================================================
echo    IED Navigator PRO __VERSION__
echo    Instalacion  -  Emilio Medina
echo  ================================================================
echo.
echo    Esto NO instala nada en el sistema: solo revisa el paquete y
echo    crea un acceso directo. La aplicacion corre desde esta carpeta.
echo.
echo  ----------------------------------------------------------------
echo.

:: ---- 1/4  El paquete esta extraido y completo ----------------------
echo    [1/4]  Revisando el paquete...

:: Ejecutar desde adentro del ZIP es la causa mas comun de "instale y no
:: arranca": Windows extrae a una carpeta temporal que despues borra, y el
:: acceso directo queda apuntando a una ruta que ya no existe.
echo %~dp0 | findstr /i /c:"\Temp\Temp" >nul
if not errorlevel 1 (
    echo           [ X ]  Se esta ejecutando desde DENTRO del archivo ZIP.
    echo.
    echo                  Windows lo abrio en una carpeta temporal que va a
    echo                  borrar al cerrar. Cierre esta ventana, extraiga el
    echo                  ZIP completo a una carpeta real ^(por ejemplo
    echo                  C:\IEDNavigatorPRO^) y ejecute INSTALAR.bat de ahi.
    set "ABORTAR=1"
    goto :resultado
)

set "FALTA="
if not exist "%~dp0IEDNavigatorPRO.exe" set "FALTA=!FALTA! IEDNavigatorPRO.exe"
if not exist "%~dp0classes"            set "FALTA=!FALTA! classes\"
if not exist "%~dp0lib"                set "FALTA=!FALTA! lib\"
if defined FALTA (
    echo           [ X ]  El paquete esta incompleto. Falta:!FALTA!
    echo.
    echo                  Vuelva a extraer el ZIP COMPLETO ^(no solo algunos
    echo                  archivos^) y ejecute INSTALAR.bat de nuevo.
    set "ABORTAR=1"
    goto :resultado
)
set "OK_APP=1"
echo           [ OK ]  Paquete completo.
echo.

:: ---- 2/4  Runtime Java incluido -----------------------------------
echo    [2/4]  Revisando el runtime de Java incluido...
if exist "%~dp0jre\bin\javaw.exe" (
    set "OK_JRE=1"
    echo           [ OK ]  Java propio encontrado. No hace falta instalar Java.
) else (
    echo           [ X ]  Falta la carpeta jre\ del paquete.
    echo.
    echo                  Vuelva a extraer el ZIP COMPLETO y ejecute
    echo                  INSTALAR.bat de nuevo.
    set "ABORTAR=1"
    goto :resultado
)
echo.

:: ---- 3/4  Npcap (opcional) ----------------------------------------
:: Npcap NO se distribuye con el paquete: su licencia gratuita no permite
:: redistribuirlo dentro de instaladores de terceros (ver README.txt).
echo    [3/4]  Revisando Npcap ^(captura GOOSE / Sampled Values^)...
if exist "%SystemRoot%\System32\Npcap\NPFInstall.exe" set "OK_NPCAP=1"
if exist "%SystemRoot%\System32\wpcap.dll"            set "OK_NPCAP=1"
reg query "HKLM\SOFTWARE\Npcap"             >nul 2>&1 && set "OK_NPCAP=1"
reg query "HKLM\SOFTWARE\WOW6432Node\Npcap" >nul 2>&1 && set "OK_NPCAP=1"
if "!OK_NPCAP!"=="1" (
    echo           [ OK ]  Npcap instalado. GOOSE / SV disponibles.
) else (
    echo           [ - ]  Npcap NO instalado ^(es opcional^).
    echo                  La aplicacion abre y funciona igual para MMS.
    echo                  Al final se explica como habilitar GOOSE / SV.
)
echo.

:: ---- 4/4  Acceso directo ------------------------------------------
echo    [4/4]  Creando el acceso directo en el Escritorio...
set "SC=%USERPROFILE%\Desktop\IED Navigator PRO.lnk"
powershell -NoProfile -Command "$s=(New-Object -ComObject WScript.Shell).CreateShortcut('%SC%'); $s.TargetPath='%~dp0IEDNavigatorPRO.exe'; $s.WorkingDirectory='%~dp0'; $s.IconLocation='%~dp0IEDNavigatorPRO.ico,0'; $s.Description='IED Navigator PRO __VERSION__ - IEC 61850 Explorer'; $s.Save()" >nul 2>&1
if exist "!SC!" (
    set "OK_LNK=1"
    echo           [ OK ]  Acceso directo creado.
) else (
    echo           [ - ]  No se pudo crear el acceso directo.
    echo                  No es grave: se puede iniciar la aplicacion con
    echo                  IEDNavigatorPRO.exe, en esta misma carpeta.
)

:resultado
echo.
echo  ================================================================
if "!ABORTAR!"=="1" (
    echo    INSTALACION NO COMPLETADA
    echo  ================================================================
    echo.
    echo    Corrija lo indicado arriba con [ X ] y vuelva a ejecutar
    echo    INSTALAR.bat. No se creo ningun acceso directo.
    echo.
    echo.
    pause
    exit /b 1
)

echo    LISTO PARA USAR
echo  ================================================================
echo.
if "!OK_LNK!"=="1" (
    echo    Inicie la aplicacion con el icono "IED Navigator PRO" del
    echo    Escritorio, o con IEDNavigatorPRO.exe en esta carpeta.
) else (
    echo    Inicie la aplicacion con IEDNavigatorPRO.exe, en esta carpeta.
)
echo.
echo    IMPORTANTE: no mueva ni renombre esta carpeta. El acceso directo
echo    apunta aca; si la carpeta cambia de lugar, deja de funcionar.
echo.

if "!OK_NPCAP!"=="0" (
    echo  ----------------------------------------------------------------
    echo    FALTA Npcap  -  solo afecta a GOOSE / Sampled Values
    echo  ----------------------------------------------------------------
    echo.
    echo    Que SI funciona sin Npcap:
    echo      cliente MMS, conexion a IEDs, arbol del modelo de datos,
    echo      lectura y escritura, control SBO, reportes, comparacion SCL.
    echo.
    echo    Que NO funciona sin Npcap:
    echo      captura y publicacion GOOSE en Capa 2, y Sampled Values.
    echo.
    echo    Para habilitarlo ^(una sola vez^):
    echo      1. Descargue Npcap de   https://npcap.com/#download
    echo      2. Al instalarlo, marque "WinPcap API-compatible Mode".
    echo      3. Vuelva a abrir IED Navigator PRO.
    echo.
    echo    Npcap no puede venir dentro de este paquete: su licencia
    echo    gratuita no permite redistribuirlo. Ver README.txt.
    echo.
)

echo  ----------------------------------------------------------------
echo    Uso exclusivamente educativo. No apto para comisionamiento ni
echo    maniobras en instalaciones en servicio. Ver README.txt.
echo  ----------------------------------------------------------------
echo.
pause
exit /b 0
