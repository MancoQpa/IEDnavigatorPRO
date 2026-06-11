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
