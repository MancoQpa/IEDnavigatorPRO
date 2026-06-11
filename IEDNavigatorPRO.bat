@echo off
cd /d "%~dp0"
setlocal enabledelayedexpansion
set "JAVA=C:\Program Files\Eclipse Adoptium\jdk-25.0.2.10-hotspot\bin\java.exe"
if not exist "%JAVA%" set "JAVA=java"

set "CP=classes"
for %%j in (lib\*.jar) do set "CP=!CP!;%%j"

"%JAVA%" --enable-native-access=ALL-UNNAMED -Djna.library.path=lib -cp "%CP%" com.iednavigator.IEDNavigatorApp
if errorlevel 1 pause
