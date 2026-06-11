$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.2.10-hotspot'
$JAVA = "$env:JAVA_HOME\bin\java.exe"
$LIBDIR = 'C:\Users\admin\Documents\proyectos IA\iec61850_java_explorer\lib'
$CLASSDIR = 'C:\Users\admin\Documents\proyectos IA\iec61850_java_explorer\classes'

# Build classpath
$jars = Get-ChildItem -Path $LIBDIR -Filter '*.jar' | ForEach-Object { $_.FullName }
$CP = "$CLASSDIR;" + ($jars -join ';')

Write-Host "Starting IED Scout Java..."
& $JAVA --enable-native-access=ALL-UNNAMED -cp $CP com.iednavigator.IEDNavigatorApp 2>&1
