$ErrorActionPreference = "Stop"
$ProjectRoot = 'C:\Users\admin\Documents\proyectos IA\iec61850_java_explorer'
$Version     = "3.6"
$TempDir     = "$ProjectRoot\temp_installer_v36"
$AppDir      = "$TempDir\IEDNavigator"
$OutputDir   = "$ProjectRoot\installer\output"
$OutputZip   = "$OutputDir\IEDNavigator_v${Version}_Setup.zip"

Write-Host "================================================" -ForegroundColor Cyan
Write-Host "  IED Navigator v$Version - Build Installer ZIP" -ForegroundColor Cyan
Write-Host "================================================" -ForegroundColor Cyan

# Step 1: Compile
Write-Host "[1/4] Compilando..." -ForegroundColor Yellow
& "$ProjectRoot\compile.ps1"
if ($LASTEXITCODE -ne 0) { Write-Host "ERROR: Compilacion fallida" -ForegroundColor Red; exit 1 }
$classCount = (Get-ChildItem "$ProjectRoot\classes" -Recurse -Filter '*.class').Count
Write-Host "OK - $classCount clases" -ForegroundColor Green

# Step 2: Create structure
Write-Host "[2/4] Creando estructura..." -ForegroundColor Yellow
Remove-Item -Path $TempDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$AppDir\classes" | Out-Null
New-Item -ItemType Directory -Force -Path "$AppDir\lib"     | Out-Null
New-Item -ItemType Directory -Force -Path $OutputDir        | Out-Null

Copy-Item -Path "$ProjectRoot\classes\*" -Destination "$AppDir\classes" -Recurse
Copy-Item -Path "$ProjectRoot\lib\*.jar" -Destination "$AppDir\lib"
Get-ChildItem "$ProjectRoot\lib\*.dll" -ErrorAction SilentlyContinue | ForEach-Object {
    Copy-Item $_.FullName "$AppDir\lib"
}
Write-Host "OK" -ForegroundColor Green

# Step 3: Create launcher scripts
Write-Host "[3/4] Creando scripts..." -ForegroundColor Yellow

# IEDNavigator.bat - minimal wrapper
@'
@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0IEDNavigator.ps1"
'@ | Out-File -FilePath "$AppDir\IEDNavigator.bat" -Encoding ASCII

# IEDNavigator.ps1 - real launcher with UAC elevation
$launcherPs1 = @'
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ScriptDir

$id = [Security.Principal.WindowsIdentity]::GetCurrent()
$pr = [Security.Principal.WindowsPrincipal]$id
if (-not $pr.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host 'Solicitando permisos de administrador...'
    Write-Host '(Requerido para puerto 102 y captura GOOSE)'
    $psi = New-Object System.Diagnostics.ProcessStartInfo 'powershell.exe'
    $psi.Arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$($MyInvocation.MyCommand.Path)`""
    $psi.Verb = 'runas'
    $psi.WorkingDirectory = $ScriptDir
    try { [System.Diagnostics.Process]::Start($psi) | Out-Null }
    catch { Write-Host "No se pudo elevar: $_" -ForegroundColor Yellow }
    exit
}

$JavaExe = $null
$candidates = @(
    "$env:JAVA_HOME\bin\java.exe"
) + (Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Filter 'java.exe' -Recurse -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName) `
  + (Get-Command java -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source)

foreach ($c in $candidates) {
    if ($c -and (Test-Path $c)) { $JavaExe = $c; break }
}
if (-not $JavaExe) {
    Add-Type -AssemblyName System.Windows.Forms
    [System.Windows.Forms.MessageBox]::Show('Java no encontrado. Instale Java 11+ desde https://adoptium.net','IEDNavigator')
    exit 1
}

$CP = (Get-ChildItem "$ScriptDir\lib\*.jar" | ForEach-Object { $_.FullName }) -join ';'
$CP = "$ScriptDir\classes;$CP"
$env:PATH = "$ScriptDir\lib;$env:PATH"

Write-Host "Iniciando IED Navigator v3.6..."
Start-Process -FilePath $JavaExe `
    -ArgumentList "--enable-native-access=ALL-UNNAMED","-Djna.library.path=`"$ScriptDir\lib`"","-cp","`"$CP`"","com.iednavigator.IEDNavigatorApp" `
    -WorkingDirectory $ScriptDir -NoNewWindow -Wait
'@
$launcherPs1 | Out-File -FilePath "$AppDir\IEDNavigator.ps1" -Encoding UTF8

# LEAME.txt
@"
IED Navigator v$Version
========================
Herramienta educativa para exploracion de IEDs IEC 61850.

REQUISITOS:
- Windows 10/11
- Java 11 o superior (https://adoptium.net)
- Npcap (https://npcap.com) para GOOSE y captura de red

EJECUTAR:
  Doble clic en IEDNavigator.bat
  (solicita permisos de administrador para puerto 102 y GOOSE)

NOVEDADES v${Version}:
- Fix definitivo de reportes URCB/BRCB:
  Usa association.enableReporting() en lugar de setRcbValues() para
  activar correctamente urcb.enable() en el servidor. Esto puebla
  chgRcbs en los BDAs del DataSet y hace que los reports lleguen
  al cliente.
- dataRef=false en CID de prueba (evita desalineamiento de indices
  en processReport() que mataba el ClientReceiver)
- extractNodeValue() recursivo en ReportsPanel para manejar
  FcDataObject (no solo BasicDataAttribute)
- Limpieza de logs de debug en servidor y cliente
"@ | Out-File -FilePath "$AppDir\LEAME.txt" -Encoding UTF8

Write-Host "OK" -ForegroundColor Green

# Step 4: ZIP
Write-Host "[4/4] Empaquetando ZIP..." -ForegroundColor Yellow
if (Test-Path $OutputZip) { Remove-Item $OutputZip }
Compress-Archive -Path "$TempDir\*" -DestinationPath $OutputZip
$sizeMB = [math]::Round((Get-Item $OutputZip).Length / 1MB, 1)
Write-Host "OK - $OutputZip ($sizeMB MB)" -ForegroundColor Green

Remove-Item -Path $TempDir -Recurse -Force
Write-Host ""
Write-Host "Listo: IEDNavigator_v${Version}_Setup.zip ($sizeMB MB)" -ForegroundColor Cyan
