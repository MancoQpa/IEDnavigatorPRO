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

# ── Verificar i18n con el banco permanente ───────────────────────────────────
#
# Hasta la v4.12 este paso contaba las claves de cada bundle y comparaba el numero contra
# el fuente. Ese conteo deja pasar el caso en que un bundle pierde una clave y gana otra:
# da el mismo total y no son las mismas claves. test\TestI18n.java compara por CONJUNTO, y
# ademas cubre lo que el conteo no miraba: apostrofes sin doblar en las claves que pasan
# por MessageFormat (el defecto del 27-07), placeholders que no coinciden con la base,
# valores vacios, claves citadas en el fuente que no existen en el bundle, y que classes\
# tenga los mismos VALORES que el fuente y no solo la misma cantidad.
#
# Se corre DOS veces: sobre el repo antes de copiar, y sobre el paquete ya armado, que es
# lo que efectivamente se descarga. Empaquetar un classes\ desactualizado fue el fallo de
# la v4.10, y ahi la intencion estaba bien: lo que fallo fue la copia.
$bancoSrc = Join-Path $ROOT "test\TestI18n.java"
$bancoOut = Join-Path ([System.IO.Path]::GetTempPath()) "iednav_i18n_$PID"

function LimpiarBanco {
    if (Test-Path $bancoOut) {
        Remove-Item $bancoOut -Recurse -Force -Confirm:$false -ErrorAction SilentlyContinue
    }
}

# Corre el banco contra un classes\i18n dado. El fuente de referencia es siempre el del repo.
function Banco($clsI18nDir, $etiqueta) {
    Write-Host "  contra $etiqueta"
    & java -cp $bancoOut TestI18n -q (Join-Path $ROOT "src\main\resources\i18n") $clsI18nDir (Join-Path $ROOT "src\main\java")
    if ($LASTEXITCODE -ne 0) {
        LimpiarBanco
        Fail "El banco de i18n fallo sobre $etiqueta. No se empaqueta."
    }
}

Step "Verificando i18n"
if (-not (Test-Path $bancoSrc)) { Fail "Falta test\TestI18n.java — el banco de i18n es parte del armado" }
if (-not (Test-Path (Join-Path $ROOT "classes\i18n"))) { Fail "Falta classes\i18n — compile.ps1 no copio los recursos" }
New-Item -ItemType Directory -Path $bancoOut -Force | Out-Null
# El banco no depende de nada del proyecto (solo JDK), asi que compila sin classpath.
& javac -encoding UTF-8 -d $bancoOut $bancoSrc
if ($LASTEXITCODE -ne 0) { LimpiarBanco; Fail "No compilo test\TestI18n.java" }
Banco (Join-Path $ROOT "classes\i18n") "el repo"

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

# El paquete es lo que se descarga: se verifica el bundle que va adentro, no el del repo.
Step "Verificando i18n del paquete"
Banco (Join-Path $DEST "classes\i18n") "el paquete"
LimpiarBanco

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
