$ROOT     = $PSScriptRoot
$SRCDIR   = "$ROOT\src\main\java"
$LIBDIR   = "$ROOT\lib"
$CLASSDIR = "$ROOT\classes"

# Auto-detectar JDK
$JAVAHOME = $null
if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\javac.exe")) {
    $JAVAHOME = $env:JAVA_HOME
} else {
    foreach ($base in @("C:\Program Files\Eclipse Adoptium","C:\Program Files\Java","C:\Program Files\Microsoft")) {
        if (Test-Path $base) {
            $hit = Get-ChildItem $base -Directory | Where-Object { Test-Path "$($_.FullName)\bin\javac.exe" } | Sort-Object Name -Descending | Select-Object -First 1
            if ($hit) { $JAVAHOME = $hit.FullName; break }
        }
    }
}
if (-not $JAVAHOME) {
    try { $jc = (Get-Command javac -ErrorAction Stop).Source; $JAVAHOME = Split-Path (Split-Path $jc) } catch {}
}
if (-not $JAVAHOME) {
    Write-Host "ERROR: JDK no encontrado. Instale JDK 11+ o defina JAVA_HOME." -ForegroundColor Red
    exit 1
}
$JAVAC = "$JAVAHOME\bin\javac.exe"

# Create classes dir if not exists
if (!(Test-Path $CLASSDIR)) { New-Item -ItemType Directory -Path $CLASSDIR }

# Build classpath
$jars = Get-ChildItem -Path $LIBDIR -Filter '*.jar' | ForEach-Object { $_.FullName }
$CP = $jars -join ';'

# Collect .java files from com.iednavigator only (com.iedexplorer is the old renamed package)
$sources = Get-ChildItem -Path "$SRCDIR\com\iednavigator" -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }

Write-Host "Compiling Java files..."
Write-Host "JAVA_HOME: $env:JAVA_HOME"
Write-Host "Classpath has $($jars.Count) jars"
Write-Host "Source files: $($sources.Count)"

# Write @argfile to avoid command-line length limits (ASCII sin BOM — javac lo requiere)
$argfile = "$env:TEMP\ied_sources.txt"
$sources | ForEach-Object { '"' + ($_ -replace '\\', '/') + '"' } | Out-File -FilePath $argfile -Encoding ascii

& $JAVAC -d $CLASSDIR -cp $CP -encoding UTF-8 --release 11 "@$argfile" 2>&1

Remove-Item $argfile -ErrorAction SilentlyContinue

if ($LASTEXITCODE -eq 0) {
    Write-Host "Compilation successful!"
} else {
    Write-Host "Compilation FAILED with exit code $LASTEXITCODE"
}
