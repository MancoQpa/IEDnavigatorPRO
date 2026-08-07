<#
    Arma un instalador de IEDNavigator PRO a partir del estado actual del repo.

    La plantilla (jre\, .exe, .ico) se reutiliza de un release anterior porque no
    depende de la version. Lo que cambia en cada build es classes\, lib\ y los textos
    de version. Se compila SIEMPRE antes de empaquetar y se verifica que los recursos
    (bundles i18n) hayan llegado a classes\: empaquetar un classes\ desactualizado fue
    el fallo que se detecto al armar la v4.10.

    Uso:
        .\build_installer.ps1 -Version 4.11
        .\build_installer.ps1 -Version 4.11 -Template installer\output\IEDNavigatorPRO_v4.10_Setup
#>
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$Template = "",
    [switch]$SkipCompile
)

$ErrorActionPreference = "Stop"
$ROOT = $PSScriptRoot
$OUTDIR = Join-Path $ROOT "installer\output"
$NAME = "IEDNavigatorPRO_v${Version}_Setup"
$DEST = Join-Path $OUTDIR $NAME

function Fail($msg) { Write-Host "ERROR: $msg" -ForegroundColor Red; exit 1 }
function Step($msg) { Write-Host "`n== $msg" -ForegroundColor Cyan }

# ── Plantilla ────────────────────────────────────────────────────────────────
if (-not $Template) {
    # el release mas reciente que tenga jre\ sirve de plantilla
    $cand = Get-ChildItem $OUTDIR -Directory -ErrorAction SilentlyContinue |
            Where-Object { Test-Path (Join-Path $_.FullName "jre") } |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $cand) { Fail "No se encontro ninguna plantilla con jre\ en $OUTDIR" }
    $Template = $cand.FullName
} elseif (-not [System.IO.Path]::IsPathRooted($Template)) {
    $Template = Join-Path $ROOT $Template
}
if (-not (Test-Path (Join-Path $Template "jre"))) { Fail "La plantilla no tiene jre\: $Template" }
Write-Host "Plantilla : $Template"
Write-Host "Destino   : $DEST"

# ── Compilar ─────────────────────────────────────────────────────────────────
if (-not $SkipCompile) {
    Step "Compilando"
    & (Join-Path $ROOT "compile.ps1")
    if ($LASTEXITCODE -ne 0) { Fail "La compilacion fallo" }
}

# ── Verificar que classes\ este al dia con el fuente ─────────────────────────
Step "Verificando classes\"
$srcI18n = Join-Path $ROOT "src\main\resources\i18n"
$clsI18n = Join-Path $ROOT "classes\i18n"
if (-not (Test-Path $clsI18n)) { Fail "Falta classes\i18n — compile.ps1 no copio los recursos" }
foreach ($f in Get-ChildItem $srcI18n -Filter *.properties) {
    $a = (Get-Content $f.FullName -Encoding utf8 | Where-Object { $_ -match '^[a-zA-Z]' }).Count
    $c = Join-Path $clsI18n $f.Name
    if (-not (Test-Path $c)) { Fail "Falta $($f.Name) en classes\i18n" }
    $b = (Get-Content $c -Encoding utf8 | Where-Object { $_ -match '^[a-zA-Z]' }).Count
    if ($a -ne $b) { Fail "$($f.Name): fuente=$a claves, classes=$b. classes\ quedo desactualizado." }
    Write-Host ("  {0,-24} {1} claves  OK" -f $f.Name, $a)
}

# ── Armar el paquete ─────────────────────────────────────────────────────────
Step "Copiando plantilla"
if (Test-Path $DEST) { Remove-Item $DEST -Recurse -Force -Confirm:$false }
New-Item -ItemType Directory -Path $DEST -Force | Out-Null
# genericos de la plantilla (no dependen de la version)
foreach ($item in @("jre", "IEDNavigatorPRO.exe", "IEDNavigatorPRO.ico", "IEDNavigatorPRO.bat")) {
    $src = Join-Path $Template $item
    if (Test-Path $src) { Copy-Item $src -Destination $DEST -Recurse -Force }
}
# textos de version: se toman de la plantilla y se actualizan mas abajo
foreach ($item in @("INSTALAR.bat", "README.txt", "LEAME.txt")) {
    Copy-Item (Join-Path $Template $item) -Destination $DEST -Force
}
# legales: SIEMPRE desde el repo, nunca de la plantilla
Copy-Item (Join-Path $ROOT "LICENSE") -Destination $DEST -Force
Copy-Item (Join-Path $ROOT "THIRD-PARTY-NOTICES.txt") -Destination $DEST -Force

Step "Copiando binarios recien compilados"
Copy-Item (Join-Path $ROOT "classes") -Destination $DEST -Recurse -Force
Copy-Item (Join-Path $ROOT "lib")     -Destination $DEST -Recurse -Force

# nada de logs de sesiones previas dentro del paquete.
# Se compara la extension exacta en vez de usar -Filter "*.log": el filtro de Windows
# usa semantica de nombre corto y tambien matchea jre\legal\java.logging, que es un
# DIRECTORIO con los avisos de licencia del runtime.
Get-ChildItem $DEST -Recurse -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Extension -eq ".log" } |
    Remove-Item -Force -Confirm:$false -ErrorAction SilentlyContinue

# ── Actualizar la version en los textos ──────────────────────────────────────
Step "Actualizando textos de version"
$oldVer = [regex]::Match((Split-Path $Template -Leaf), 'v(\d+\.\d+)').Groups[1].Value
if (-not $oldVer) { Fail "No se pudo deducir la version de la plantilla" }
Write-Host "  $oldVer -> $Version"
foreach ($f in @("INSTALAR.bat", "README.txt", "LEAME.txt")) {
    $p = Join-Path $DEST $f
    $t = Get-Content $p -Raw -Encoding utf8
    $t = $t -replace [regex]::Escape("v$oldVer"), "v$Version"
    Set-Content $p $t -Encoding utf8 -NoNewline
}

# ── Comprimir ────────────────────────────────────────────────────────────────
Step "Comprimiendo"
$zip = Join-Path $OUTDIR "$NAME.zip"
if (Test-Path $zip) { Remove-Item $zip -Force -Confirm:$false }
Compress-Archive -Path $DEST -DestinationPath $zip -CompressionLevel Optimal

# ── Resumen ──────────────────────────────────────────────────────────────────
Step "Listo"
$sz = (Get-ChildItem $DEST -Recurse -File | Measure-Object Length -Sum).Sum
"{0,-22} {1,10:N1} MB" -f "carpeta", ($sz / 1MB)
"{0,-22} {1,10:N1} MB" -f "zip", ((Get-Item $zip).Length / 1MB)
"{0,-22} {1,10}" -f "clases", (Get-ChildItem (Join-Path $DEST "classes") -Recurse -Filter *.class).Count
"{0,-22} {1,10}" -f "jars", (Get-ChildItem (Join-Path $DEST "lib") -Filter *.jar).Count
Write-Host "`n  $zip"
