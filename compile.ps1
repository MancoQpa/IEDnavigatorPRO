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

# Se compila en una carpeta NUEVA y, solo si compila, reemplaza a classes\. Antes se compilaba
# encima de classes\ sin limpiarla: lo que alguna vez se compilo quedaba ahi y build_installer
# lo copiaba al paquete. El 2026-10-19 el paquete llevaba 101 clases de com\iedexplorer (el
# paquete viejo, de 2026-06), clases Test*.class sueltas y clases de codigo ya borrado
# (p. ej. IEC61850Client$LecturaModelo). Si la compilacion falla, classes\ queda como estaba.
$NEWDIR = "$ROOT\classes.nuevo"
if (Test-Path $NEWDIR) { Remove-Item $NEWDIR -Recurse -Force }
New-Item -ItemType Directory -Path $NEWDIR | Out-Null

# Build classpath
$jars = Get-ChildItem -Path $LIBDIR -Filter '*.jar' | ForEach-Object { $_.FullName }
$CP = $jars -join ';'

# Collect .java files from com.iednavigator only (com.iedexplorer is the old renamed package)
# Se excluye bridge\: era para el frontend Tauri (descartado); requiere Javalin/Jackson que no estan en lib\
$sources = Get-ChildItem -Path "$SRCDIR\com\iednavigator" -Recurse -Filter '*.java' |
    Where-Object { $_.FullName -notmatch '\\bridge\\' } | ForEach-Object { $_.FullName }

# com\beanit\: parches que sombrean clases de iec61850bean-1.9.0 (ver Fc.java).
# Tienen que compilarse a classes\, que va antes que lib\*.jar en el classpath de ejecucion.
if (Test-Path "$SRCDIR\com\beanit") {
    $sources += Get-ChildItem -Path "$SRCDIR\com\beanit" -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }
}

Write-Host "Compiling Java files..."
Write-Host "JAVA_HOME: $env:JAVA_HOME"
Write-Host "Classpath has $($jars.Count) jars"
Write-Host "Source files: $($sources.Count)"

# Write @argfile to avoid command-line length limits (ASCII sin BOM — javac lo requiere)
$argfile = "$env:TEMP\ied_sources.txt"
$sources | ForEach-Object { '"' + ($_ -replace '\\', '/') + '"' } | Out-File -FilePath $argfile -Encoding ascii

& $JAVAC -d $NEWDIR -cp $CP -encoding UTF-8 --release 11 "@$argfile" 2>&1
$javacExit = $LASTEXITCODE

Remove-Item $argfile -ErrorAction SilentlyContinue

if ($javacExit -eq 0) {
    # Copiar recursos al classpath de ejecucion. El .bat/.exe corren con
    # classpath "classes;lib\*.jar" (sin src\main\resources), asi que los bundles de
    # traduccion tienen que estar en classes\ o la app arranca sin textos traducidos.
    $RESDIR = "$ROOT\src\main\resources"
    if (Test-Path $RESDIR) {
        Copy-Item -Path "$RESDIR\*" -Destination $NEWDIR -Recurse -Force
        Write-Host "Resources copied to classes\"
    }
    # Reemplazar classes\ por la carpeta nueva. Si algo la tiene tomada (una instancia
    # corriendo desde classes\, un explorador abierto ahi), se avisa y no se mezcla nada.
    try {
        if (Test-Path $CLASSDIR) { Remove-Item $CLASSDIR -Recurse -Force -ErrorAction Stop }
        Rename-Item $NEWDIR "classes" -ErrorAction Stop
    } catch {
        Write-Host "ERROR: no se pudo reemplazar classes\ ($($_.Exception.Message))." -ForegroundColor Red
        Write-Host "Cerrar lo que la este usando y volver a compilar. Lo compilado quedo en classes.nuevo\."
        exit 1
    }
    Write-Host "Compilation successful!"
    exit 0
} else {
    Remove-Item $NEWDIR -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host "Compilation FAILED with exit code $javacExit"
    exit $javacExit
}
