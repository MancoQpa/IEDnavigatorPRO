# Genera el arte del instalador NSIS a partir de la imagen de marca.
# - icon-source.png   : imagen cuadrada con margen (entrada para `tauri icon`)
# - header.bmp        : cabecera NSIS 150x57
# - sidebar.bmp       : banner lateral NSIS 164x314
# Uso: powershell -ExecutionPolicy Bypass -File scripts\make-installer-art.ps1 [ruta-imagen]

param(
    [string]$Source = "C:\Users\admin\Pictures\IEDNavigator.png"
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$repo = Split-Path -Parent $PSScriptRoot
$outDir = Join-Path $repo "frontend\src-tauri\installer"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$src = [System.Drawing.Image]::FromFile($Source)
Write-Host "Imagen origen: $($src.Width)x$($src.Height)"

# Fondo oscuro de la marca (azul noche del propio logo)
$bg = [System.Drawing.Color]::FromArgb(255, 11, 19, 43)

function New-Canvas([int]$w, [int]$h) {
    $bmp = New-Object System.Drawing.Bitmap($w, $h)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.Clear($bg)
    return @($bmp, $g)
}

function Draw-Contain($g, $img, [int]$w, [int]$h, [double]$scaleFactor) {
    $scale = [Math]::Min($w / $img.Width, $h / $img.Height) * $scaleFactor
    $dw = [int]($img.Width * $scale)
    $dh = [int]($img.Height * $scale)
    $x = [int](($w - $dw) / 2)
    $y = [int](($h - $dh) / 2)
    $g.DrawImage($img, $x, $y, $dw, $dh)
}

# ── icon-source.png: lienzo cuadrado 1024x1024 (entrada para `tauri icon`) ──
$c = New-Canvas 1024 1024
Draw-Contain $c[1] $src 1024 1024 0.92
$c[0].Save((Join-Path $outDir "icon-source.png"), [System.Drawing.Imaging.ImageFormat]::Png)
$c[1].Dispose(); $c[0].Dispose()
Write-Host "OK icon-source.png (1024x1024)"

# ── header.bmp: cabecera NSIS 150x57 ──
$c = New-Canvas 150 57
Draw-Contain $c[1] $src 150 57 0.9
$c[0].Save((Join-Path $outDir "header.bmp"), [System.Drawing.Imaging.ImageFormat]::Bmp)
$c[1].Dispose(); $c[0].Dispose()
Write-Host "OK header.bmp (150x57)"

# ── sidebar.bmp: banner lateral NSIS 164x314 (logo arriba, fondo de marca) ──
$c = New-Canvas 164 314
$g = $c[1]
$scale = [Math]::Min(140 / $src.Width, 140 / $src.Height)
$dw = [int]($src.Width * $scale)
$dh = [int]($src.Height * $scale)
$g.DrawImage($src, [int]((164 - $dw) / 2), 24, $dw, $dh)
$c[0].Save((Join-Path $outDir "sidebar.bmp"), [System.Drawing.Imaging.ImageFormat]::Bmp)
$g.Dispose(); $c[0].Dispose()
Write-Host "OK sidebar.bmp (164x314)"

$src.Dispose()
Write-Host "Arte del instalador generado en $outDir"
