@echo off
echo Iniciando IEC 61850 Explorer...

if exist "target\iec61850-explorer-1.0.0-jar-with-dependencies.jar" (
    java -jar target\iec61850-explorer-1.0.0-jar-with-dependencies.jar
) else (
    echo.
    echo ERROR: Primero compile el proyecto con build.bat
    echo.
    pause
)
