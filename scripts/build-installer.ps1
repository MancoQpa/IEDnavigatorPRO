# Construye el instalador NSIS completo de IEDNavigator PRO (Tauri 2 + sidecar Java).
#
# Pasos:
#   1. mvn package -Pbridge          -> bridge.jar (fat jar headless)
#   2. jlink                          -> runtime Java minimo (~60 MB)
#   3. staging en src-tauri/resources -> bridge/bridge.jar, bridge/native/iec61850.dll, runtime/
#   4. smoke test                     -> runtime\bin\java -jar bridge.jar (espera BRIDGE_READY)
#   5. npm run tauri build            -> instalador NSIS en src-tauri/target/release/bundle/nsis
#
# Uso: powershell -ExecutionPolicy Bypass -File scripts\build-installer.ps1 [-SkipMaven] [-SkipTauri]
#
# ── FIRMA DE CODIGO (paso manual) ─────────────────────────────────────────────
# El instalador y el exe NO van firmados. Para firmar con un certificado EV/OV:
#
#   signtool sign /fd SHA256 /tr http://timestamp.digicert.com /td SHA256 `
#     /f certificado.pfx /p <password> `
#     "frontend\src-tauri\target\release\IEDNavigator PRO.exe" `
#     "frontend\src-tauri\target\release\bundle\nsis\IEDNavigator PRO_1.0.0_x64-setup.exe"
#
# Alternativa integrada: configurar en tauri.conf.json bundle.windows
#   "certificateThumbprint", "digestAlgorithm": "sha256",
#   "timestampUrl": "http://timestamp.digicert.com"
# y Tauri firma automaticamente durante el build.
# ──────────────────────────────────────────────────────────────────────────────

param(
    [switch]$SkipMaven,
    [switch]$SkipTauri
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$frontend = Join-Path $repo 'frontend'
$resources = Join-Path $frontend 'src-tauri\resources'
$bridgeJar = Join-Path $repo 'target\ied-navigator-bridge-jar-with-dependencies.jar'
$mvn = 'C:\Users\admin\tools\apache-maven-3.9.9\bin\mvn.cmd'

if (-not $env:JAVA_HOME) { throw 'JAVA_HOME no definido (se necesita JDK con jlink)' }
$jlink = Join-Path $env:JAVA_HOME 'bin\jlink.exe'
if (-not (Test-Path $jlink)) { throw "jlink no encontrado en $jlink" }

# ── 1. Bridge jar ──
if (-not $SkipMaven) {
    Write-Host '== Compilando bridge (mvn -Pbridge) ==' -ForegroundColor Cyan
    & $mvn -q -f (Join-Path $repo 'pom.xml') package -Pbridge -DskipTests
    if ($LASTEXITCODE -ne 0) { throw 'mvn package fallo' }
}
if (-not (Test-Path $bridgeJar)) { throw "No existe $bridgeJar" }

# ── 2. Runtime jlink ──
$runtimeDir = Join-Path $resources 'runtime'
Write-Host '== Generando runtime jlink ==' -ForegroundColor Cyan
if (Test-Path $runtimeDir) { Remove-Item -Recurse -Force $runtimeDir }
New-Item -ItemType Directory -Force -Path $resources | Out-Null

# Modulos para Javalin/Jetty + Jackson + pcap4j/JNA + iec61850bean
$modules = @(
    'java.base', 'java.logging', 'java.xml', 'java.naming',
    'java.management', 'java.sql', 'java.desktop',
    'jdk.unsupported', 'jdk.crypto.ec'
) -join ','

& $jlink --add-modules $modules `
    --strip-debug --no-header-files --no-man-pages --compress zip-6 `
    --output $runtimeDir
if ($LASTEXITCODE -ne 0) { throw 'jlink fallo' }
$size = [math]::Round(((Get-ChildItem -Recurse $runtimeDir | Measure-Object Length -Sum).Sum / 1MB), 1)
Write-Host "OK runtime ($size MB)"

# ── 3. Staging bridge ──
Write-Host '== Staging bridge ==' -ForegroundColor Cyan
$bridgeDir = Join-Path $resources 'bridge'
$nativeDir = Join-Path $bridgeDir 'native'
New-Item -ItemType Directory -Force -Path $nativeDir | Out-Null
Copy-Item $bridgeJar (Join-Path $bridgeDir 'bridge.jar') -Force
Copy-Item (Join-Path $repo 'lib\iec61850.dll') $nativeDir -Force
Write-Host 'OK bridge.jar + iec61850.dll'

# ── 4. Smoke test del runtime ──
Write-Host '== Smoke test runtime + bridge ==' -ForegroundColor Cyan
$javaExe = Join-Path $runtimeDir 'bin\java.exe'
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $javaExe
$psi.Arguments = "-Djna.library.path=`"$nativeDir`" -jar `"$(Join-Path $bridgeDir 'bridge.jar')`" --port 0 --token smoke"
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.UseShellExecute = $false
$proc = [System.Diagnostics.Process]::Start($psi)
$ready = $false
$deadline = (Get-Date).AddSeconds(30)
while (-not $proc.HasExited -and (Get-Date) -lt $deadline) {
    $line = $proc.StandardOutput.ReadLine()
    if ($null -eq $line) { break }
    if ($line -match 'BRIDGE_READY port=(\d+)') {
        Write-Host "OK bridge arranca con runtime jlink (puerto $($Matches[1]))"
        $ready = $true
        break
    }
}
if (-not $proc.HasExited) { $proc.Kill() }
if (-not $ready) {
    Write-Host $proc.StandardError.ReadToEnd()
    throw 'Smoke test fallo: el bridge no emitio BRIDGE_READY con el runtime jlink'
}

# ── 5. Tauri build ──
if (-not $SkipTauri) {
    Write-Host '== Tauri build (NSIS) ==' -ForegroundColor Cyan
    $env:Path = "$env:Path;C:\Users\admin\.cargo\bin"
    Push-Location $frontend
    try {
        npm run tauri build
        if ($LASTEXITCODE -ne 0) { throw 'tauri build fallo' }
    } finally {
        Pop-Location
    }
    $bundle = Join-Path $frontend 'src-tauri\target\release\bundle\nsis'
    Write-Host "== Instalador generado en $bundle ==" -ForegroundColor Green
    Get-ChildItem $bundle -Filter *.exe | ForEach-Object {
        Write-Host ("   {0}  ({1:N1} MB)" -f $_.Name, ($_.Length / 1MB))
    }
    Write-Host 'Recuerda: la firma de codigo es un paso manual (ver cabecera de este script).' -ForegroundColor Yellow
}
