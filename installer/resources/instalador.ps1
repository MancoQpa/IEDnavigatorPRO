# Instalador de IED Navigator PRO, con ventana.
#
# Lo lanza INSTALAR.bat. Si esto falla por lo que sea, el .bat sigue con su
# version de consola: el instalador nunca depende de que esta ventana funcione.
#
# No instala nada en el sistema. Verifica que el paquete este completo y crea un
# acceso directo en el escritorio. La aplicacion corre desde su carpeta.

$ErrorActionPreference = "Stop"
$VERSION = "__VERSION__"
$RAIZ    = Split-Path -Parent $MyInvocation.MyCommand.Path

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()

# ── Paleta ───────────────────────────────────────────────────────────────────
$cFondo   = [System.Drawing.Color]::FromArgb(250, 250, 252)
$cBanda   = [System.Drawing.Color]::FromArgb(23, 42, 69)
$cTexto   = [System.Drawing.Color]::FromArgb(33, 37, 41)
$cSuave   = [System.Drawing.Color]::FromArgb(108, 117, 125)
$cOk      = [System.Drawing.Color]::FromArgb(25, 135, 84)
$cAviso   = [System.Drawing.Color]::FromArgb(180, 120, 10)
$cError   = [System.Drawing.Color]::FromArgb(190, 40, 50)

function Fuente($tam, $estilo = [System.Drawing.FontStyle]::Regular) {
    New-Object System.Drawing.Font("Segoe UI", $tam, $estilo)
}

# ── Ventana ──────────────────────────────────────────────────────────────────
$frm = New-Object System.Windows.Forms.Form
$frm.Text = "IED Navigator PRO $VERSION - Instalacion"
$frm.Size = New-Object System.Drawing.Size(620, 470)
$frm.StartPosition = "CenterScreen"
$frm.FormBorderStyle = "FixedDialog"
$frm.MaximizeBox = $false
$frm.BackColor = $cFondo
$ico = Join-Path $RAIZ "IEDNavigatorPRO.ico"
if (Test-Path $ico) { try { $frm.Icon = New-Object System.Drawing.Icon($ico) } catch {} }

# Banda superior
$banda = New-Object System.Windows.Forms.Panel
$banda.Size = New-Object System.Drawing.Size(620, 84)
$banda.Location = New-Object System.Drawing.Point(0, 0)
$banda.BackColor = $cBanda
$frm.Controls.Add($banda)

$lblT = New-Object System.Windows.Forms.Label
$lblT.Text = "IED Navigator PRO"
$lblT.Font = Fuente 17 ([System.Drawing.FontStyle]::Bold)
$lblT.ForeColor = [System.Drawing.Color]::White
$lblT.Location = New-Object System.Drawing.Point(24, 16)
$lblT.AutoSize = $true
$banda.Controls.Add($lblT)

$lblV = New-Object System.Windows.Forms.Label
$lblV.Text = "$VERSION   -   Explorador IEC 61850"
$lblV.Font = Fuente 9.5
$lblV.ForeColor = [System.Drawing.Color]::FromArgb(170, 190, 215)
$lblV.Location = New-Object System.Drawing.Point(26, 50)
$lblV.AutoSize = $true
$banda.Controls.Add($lblV)

# Texto explicativo
$lblQue = New-Object System.Windows.Forms.Label
$lblQue.Text = "Esta instalacion no modifica el sistema. Verifica que el paquete este completo" + [Environment]::NewLine +
               "y crea un acceso directo en el escritorio. La aplicacion corre desde esta carpeta."
$lblQue.Font = Fuente 9
$lblQue.ForeColor = $cSuave
$lblQue.Location = New-Object System.Drawing.Point(26, 100)
$lblQue.Size = New-Object System.Drawing.Size(560, 36)
$frm.Controls.Add($lblQue)

# Lista de pasos
$lista = New-Object System.Windows.Forms.Panel
$lista.Location = New-Object System.Drawing.Point(26, 146)
$lista.Size = New-Object System.Drawing.Size(560, 180)
$lista.BackColor = [System.Drawing.Color]::White
$lista.BorderStyle = "FixedSingle"
$frm.Controls.Add($lista)

$pasos = @(
    @{ n = "Paquete completo"; d = "Archivos de la aplicacion y bibliotecas" },
    @{ n = "Java incluido";    d = "No hace falta instalar Java aparte" },
    @{ n = "Npcap";            d = "Opcional: solo para GOOSE y Sampled Values" },
    @{ n = "Acceso directo";   d = "En el escritorio" }
)
$iconos = @(); $titulos = @(); $detalles = @()
for ($i = 0; $i -lt $pasos.Count; $i++) {
    $y = 14 + ($i * 42)

    $ic = New-Object System.Windows.Forms.Label
    $ic.Text = [char]0x25CB          # circulo vacio
    $ic.Font = Fuente 12
    $ic.ForeColor = $cSuave
    $ic.Location = New-Object System.Drawing.Point(16, $y)
    $ic.Size = New-Object System.Drawing.Size(26, 24)
    $lista.Controls.Add($ic); $iconos += $ic

    $tt = New-Object System.Windows.Forms.Label
    $tt.Text = $pasos[$i].n
    $tt.Font = Fuente 10 ([System.Drawing.FontStyle]::Bold)
    $tt.ForeColor = $cTexto
    $tt.Location = New-Object System.Drawing.Point(46, $y)
    $tt.Size = New-Object System.Drawing.Size(240, 20)
    $lista.Controls.Add($tt); $titulos += $tt

    $dd = New-Object System.Windows.Forms.Label
    $dd.Text = $pasos[$i].d
    $dd.Font = Fuente 8.5
    $dd.ForeColor = $cSuave
    $dd.Location = New-Object System.Drawing.Point(46, ($y + 19))
    $dd.Size = New-Object System.Drawing.Size(490, 18)
    $lista.Controls.Add($dd); $detalles += $dd
}

# Barra de progreso
$barra = New-Object System.Windows.Forms.ProgressBar
$barra.Location = New-Object System.Drawing.Point(26, 338)
$barra.Size = New-Object System.Drawing.Size(560, 8)
$barra.Style = "Continuous"
$barra.Maximum = 4
$frm.Controls.Add($barra)

# Mensaje final
$lblFin = New-Object System.Windows.Forms.Label
$lblFin.Font = Fuente 9.5 ([System.Drawing.FontStyle]::Bold)
$lblFin.Location = New-Object System.Drawing.Point(26, 356)
$lblFin.Size = New-Object System.Drawing.Size(560, 40)
$frm.Controls.Add($lblFin)

# Botones
$btnInst = New-Object System.Windows.Forms.Button
$btnInst.Text = "Instalar"
$btnInst.Font = Fuente 10
$btnInst.Size = New-Object System.Drawing.Size(120, 34)
$btnInst.Location = New-Object System.Drawing.Point(346, 396)
$frm.Controls.Add($btnInst)

$btnDesinst = New-Object System.Windows.Forms.Button
$btnDesinst.Text = "Desinstalar"
$btnDesinst.Font = Fuente 10
$btnDesinst.Size = New-Object System.Drawing.Size(120, 34)
$btnDesinst.Location = New-Object System.Drawing.Point(26, 396)
$btnDesinst.Enabled = $false
$frm.Controls.Add($btnDesinst)

$btnNpcap = New-Object System.Windows.Forms.Button
$btnNpcap.Text = "Instalar Npcap"
$btnNpcap.Font = Fuente 10
$btnNpcap.Size = New-Object System.Drawing.Size(140, 34)
$btnNpcap.Location = New-Object System.Drawing.Point(152, 396)
$btnNpcap.Visible = $false
$frm.Controls.Add($btnNpcap)

$btnSalir = New-Object System.Windows.Forms.Button
$btnSalir.Text = "Cerrar"
$btnSalir.Font = Fuente 10
$btnSalir.Size = New-Object System.Drawing.Size(110, 34)
$btnSalir.Location = New-Object System.Drawing.Point(476, 396)
$btnSalir.Add_Click({ $frm.Close() })
$frm.Controls.Add($btnSalir)

# ── Npcap ───────────────────────────────────────────────────────────────────
# No se empaqueta: su licencia gratuita prohibe redistribuirlo dentro de
# instaladores de terceros. Se ofrece traerlo del sitio oficial a pedido.
function HayNpcap {
    if (Test-Path "$env:SystemRoot\System32\Npcap\NPFInstall.exe") { return $true }
    if (Test-Path "$env:SystemRoot\System32\wpcap.dll")            { return $true }
    foreach ($k in @("HKLM:\SOFTWARE\Npcap", "HKLM:\SOFTWARE\WOW6432Node\Npcap")) {
        if (Test-Path $k) { return $true }
    }
    return $false
}

$NPCAP_WEB = "https://npcap.com/#download"

# Busca en la pagina oficial el enlace al instalador. No se fija una version en
# el codigo porque quedaria vieja: se lee la que el sitio publique.
function UrlInstaladorNpcap {
    try {
        $r = Invoke-WebRequest -Uri "https://npcap.com/" -UseBasicParsing -TimeoutSec 20
        # El sitio publica el enlace como ruta relativa sin barra inicial
        # --dist/npcap-1.88.exe--, asi que se busca el nombre del archivo y se
        # arma la URL. Verificado contra npcap.com el 2026-09-07.
        $m = [regex]::Match($r.Content, 'dist/(npcap-[0-9.]+\.exe)')
        if ($m.Success) { return "https://npcap.com/dist/" + $m.Groups[1].Value }
    } catch { }
    return $null
}

# ── Estado de la instalacion ────────────────────────────────────────────────
# Toda la huella de la instalacion es este acceso directo. Se comprueba ademas
# que apunte a ESTA carpeta: si apunta a otra, hay una version distinta
# instalada y conviene decirlo en vez de pisarla en silencio.
$lnk = Join-Path ([Environment]::GetFolderPath("Desktop")) "IED Navigator PRO.lnk"

function EstadoInstalacion {
    if (-not (Test-Path $lnk)) { return "no" }
    try {
        $sh = New-Object -ComObject WScript.Shell
        $destino = $sh.CreateShortcut($lnk).TargetPath
        $mio = Join-Path $RAIZ "IEDNavigatorPRO.exe"
        if ($destino -eq $mio) { return "aqui" } else { return "otra" }
    } catch { return "aqui" }
}

function RefrescarEstado {
    switch (EstadoInstalacion) {
        "aqui" {
            $lblFin.Text = "Ya instalado desde esta carpeta."
            $lblFin.ForeColor = $cOk
            $btnInst.Text = "Reinstalar"
            $btnDesinst.Enabled = $true
        }
        "otra" {
            $lblFin.Text = "Hay un acceso directo que apunta a otra carpeta. Al instalar se reemplaza."
            $lblFin.ForeColor = $cAviso
            $btnInst.Text = "Instalar"
            $btnDesinst.Enabled = $true
        }
        default {
            $lblFin.Text = ""
            $btnInst.Text = "Instalar"
            $btnDesinst.Enabled = $false
        }
    }
}

# ── Marcar un paso ───────────────────────────────────────────────────────────
function Marcar($i, $estado, $detalle) {
    switch ($estado) {
        "ok"    { $iconos[$i].Text = [char]0x2713; $iconos[$i].ForeColor = $cOk }
        "aviso" { $iconos[$i].Text = [char]0x0021; $iconos[$i].ForeColor = $cAviso }
        "error" { $iconos[$i].Text = [char]0x2717; $iconos[$i].ForeColor = $cError }
    }
    if ($detalle) { $detalles[$i].Text = $detalle }
    $frm.Refresh()
    Start-Sleep -Milliseconds 220
}

# ── La instalacion ───────────────────────────────────────────────────────────
$btnInst.Add_Click({
    $btnInst.Enabled = $false
    $barra.Value = 0
    $lblFin.Text = ""

    # 1. Paquete completo, y no ejecutandose desde dentro del ZIP
    if ($RAIZ -match '\\Temp\\.*Temp\\' -or $RAIZ -match '\\AppData\\Local\\Temp\\') {
        Marcar 0 "error" "Se esta ejecutando desde dentro del ZIP. Extraiga la carpeta completa primero."
        $lblFin.Text = "Instalacion cancelada. Extraiga el ZIP a una carpeta real y repita."
        $lblFin.ForeColor = $cError
        $btnInst.Enabled = $true
        return
    }
    $falta = @()
    foreach ($f in @("IEDNavigatorPRO.exe", "classes", "lib")) {
        if (-not (Test-Path (Join-Path $RAIZ $f))) { $falta += $f }
    }
    if ($falta.Count -gt 0) {
        Marcar 0 "error" ("Faltan en el paquete: " + ($falta -join ", "))
        $lblFin.Text = "El paquete esta incompleto. Vuelva a descargarlo y extraerlo entero."
        $lblFin.ForeColor = $cError
        $btnInst.Enabled = $true
        return
    }
    Marcar 0 "ok" "Aplicacion y bibliotecas presentes"
    $barra.Value = 1

    # 2. Java incluido
    if (Test-Path (Join-Path $RAIZ "jre\bin\javaw.exe")) {
        Marcar 1 "ok" "Java incluido en el paquete"
    } else {
        Marcar 1 "error" "Falta la carpeta jre\ del paquete"
        $lblFin.Text = "El paquete esta incompleto: falta Java. Vuelva a descargarlo."
        $lblFin.ForeColor = $cError
        $btnInst.Enabled = $true
        return
    }
    $barra.Value = 2

    # 3. Npcap: opcional, informativo
    if (HayNpcap) {
        Marcar 2 "ok" "Detectado: GOOSE y Sampled Values disponibles"
        $btnNpcap.Visible = $false
    } else {
        Marcar 2 "aviso" "No detectado. MMS funciona igual; GOOSE y SV quedan deshabilitados."
        $btnNpcap.Visible = $true
    }
    $barra.Value = 3

    # 4. Acceso directo
    try {
        $sh  = New-Object -ComObject WScript.Shell
        $s   = $sh.CreateShortcut($lnk)
        $s.TargetPath       = Join-Path $RAIZ "IEDNavigatorPRO.exe"
        $s.WorkingDirectory = $RAIZ
        if (Test-Path $ico) { $s.IconLocation = "$ico,0" }
        $s.Description      = "IED Navigator PRO $VERSION - Explorador IEC 61850"
        $s.Save()
        if (Test-Path $lnk) {
            Marcar 3 "ok" "Creado en el escritorio"
        } else {
            Marcar 3 "aviso" "No se pudo crear. Abra IEDNavigatorPRO.exe desde esta carpeta."
        }
    } catch {
        Marcar 3 "aviso" "No se pudo crear. Abra IEDNavigatorPRO.exe desde esta carpeta."
    }
    $barra.Value = 4

    if ($npcap) {
        $lblFin.Text = "Instalacion completa. Ya puede abrir IED Navigator PRO."
    } else {
        $lblFin.Text = "Instalacion completa. Sin Npcap: MMS funciona, GOOSE y SV no."
    }
    $lblFin.ForeColor = $cOk
    $btnSalir.Text = "Finalizar"
})

$btnNpcap.Add_Click({
    $r = [System.Windows.Forms.MessageBox]::Show(
        "Npcap habilita GOOSE y Sampled Values. No viene en este paquete porque su" + [Environment]::NewLine +
        "licencia no permite redistribuirlo." + [Environment]::NewLine + [Environment]::NewLine +
        "Se va a descargar el instalador oficial desde npcap.com y ejecutarlo." + [Environment]::NewLine +
        "Windows va a pedir permiso de administrador: lo pide Npcap, no esta ventana." + [Environment]::NewLine + [Environment]::NewLine +
        "Hace falta conexion a internet. Continuar?",
        "Instalar Npcap",
        [System.Windows.Forms.MessageBoxButtons]::OKCancel,
        [System.Windows.Forms.MessageBoxIcon]::Information)
    if ($r -ne [System.Windows.Forms.DialogResult]::OK) { return }

    $btnNpcap.Enabled = $false
    $detalles[2].Text = "Buscando el instalador en npcap.com..."
    $frm.Refresh()

    $url = UrlInstaladorNpcap
    if (-not $url) {
        $detalles[2].Text = "No se pudo contactar npcap.com. Se abre la pagina para descargarlo a mano."
        Start-Process $NPCAP_WEB
        $btnNpcap.Enabled = $true
        return
    }

    $destino = Join-Path $env:TEMP ([System.IO.Path]::GetFileName($url))
    try {
        $detalles[2].Text = "Descargando " + [System.IO.Path]::GetFileName($url) + "..."
        $frm.Refresh()
        Invoke-WebRequest -Uri $url -OutFile $destino -UseBasicParsing -TimeoutSec 120
    } catch {
        $detalles[2].Text = "Fallo la descarga. Se abre la pagina para bajarlo a mano."
        Start-Process $NPCAP_WEB
        $btnNpcap.Enabled = $true
        return
    }

    if (-not (Test-Path $destino) -or (Get-Item $destino).Length -lt 200000) {
        $detalles[2].Text = "La descarga quedo incompleta. Se abre la pagina oficial."
        Start-Process $NPCAP_WEB
        $btnNpcap.Enabled = $true
        return
    }

    try {
        $detalles[2].Text = "Ejecutando el instalador de Npcap. Siga sus pasos y vuelva aca."
        $frm.Refresh()
        $pr = Start-Process -FilePath $destino -PassThru
        $pr.WaitForExit()
    } catch {
        $detalles[2].Text = "No se pudo ejecutar el instalador. Esta en: " + $destino
        $btnNpcap.Enabled = $true
        return
    }

    if (HayNpcap) {
        Marcar 2 "ok" "Instalado: GOOSE y Sampled Values disponibles"
        $btnNpcap.Visible = $false
        $lblFin.Text = "Instalacion completa, con GOOSE y Sampled Values."
        $lblFin.ForeColor = $cOk
    } else {
        $detalles[2].Text = "Npcap sigue sin detectarse. Puede hacer falta reiniciar Windows."
        $btnNpcap.Enabled = $true
    }
})

$btnDesinst.Add_Click({
    $r = [System.Windows.Forms.MessageBox]::Show(
        "Se quitara el acceso directo del escritorio." + [Environment]::NewLine + [Environment]::NewLine +
        "La carpeta del programa y sus archivos no se tocan: la instalacion no" + [Environment]::NewLine +
        "copia nada al sistema, asi que esto es todo lo que deja.",
        "Desinstalar IED Navigator PRO",
        [System.Windows.Forms.MessageBoxButtons]::OKCancel,
        [System.Windows.Forms.MessageBoxIcon]::Question)
    if ($r -ne [System.Windows.Forms.DialogResult]::OK) { return }
    try {
        if (Test-Path $lnk) { Remove-Item $lnk -Force }
        for ($i = 0; $i -lt 4; $i++) {
            $iconos[$i].Text = [char]0x25CB
            $iconos[$i].ForeColor = $cSuave
            $detalles[$i].Text = $pasos[$i].d
        }
        $barra.Value = 0
        $btnInst.Enabled = $true
        RefrescarEstado
        $lblFin.Text = "Desinstalado. Se quito el acceso directo del escritorio."
        $lblFin.ForeColor = $cTexto
    } catch {
        $lblFin.Text = "No se pudo quitar el acceso directo: " + $_.Exception.Message
        $lblFin.ForeColor = $cError
    }
})

RefrescarEstado

# La consola que lanzo esto ya cumplio su papel: avisar mientras PowerShell
# cargaba. Se la esconde para que el usuario quede solo con la ventana, en vez
# de tener un recuadro negro detras durante toda la instalacion.
try {
    Add-Type -Name Consola -Namespace Win32 -MemberDefinition @'
[DllImport("kernel32.dll")] public static extern IntPtr GetConsoleWindow();
[DllImport("user32.dll")]   public static extern bool ShowWindow(IntPtr h, int n);
'@
    $hc = [Win32.Consola]::GetConsoleWindow()
    if ($hc -ne [IntPtr]::Zero) { [void][Win32.Consola]::ShowWindow($hc, 0) }  # 0 = SW_HIDE
} catch { }

$frm.Add_Shown({ $frm.Activate() })
[void]$frm.ShowDialog()
