Add-Type -AssemblyName System.Drawing
$bmp = New-Object System.Drawing.Bitmap(512,512)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = 'AntiAlias'
$g.Clear([System.Drawing.Color]::FromArgb(0,0,0,0))
$brush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255,38,117,191))
$g.FillEllipse($brush, 16, 16, 480, 480)
$font = New-Object System.Drawing.Font('Segoe UI', 150, [System.Drawing.FontStyle]::Bold)
$white = [System.Drawing.Brushes]::White
$fmt = New-Object System.Drawing.StringFormat
$fmt.Alignment = 'Center'
$fmt.LineAlignment = 'Center'
$g.DrawString('IED', $font, $white, (New-Object System.Drawing.RectangleF(0,0,512,512)), $fmt)
$g.Dispose()
$bmp.Save("$PSScriptRoot\app-icon.png", [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
Write-Host 'icono generado'
