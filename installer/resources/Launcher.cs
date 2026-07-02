using System;
using System.Diagnostics;
using System.IO;
using System.Text;

class Launcher
{
    static int Main(string[] args)
    {
        string baseDir = AppDomain.CurrentDomain.BaseDirectory;
        string java = FindJava(baseDir);

        if (java == null)
        {
            System.Windows.Forms.MessageBox.Show(
                "Java no encontrado.\n\n" +
                "Instale Java 11 o superior desde:\nhttps://adoptium.net/",
                "IED Navigator PRO", System.Windows.Forms.MessageBoxButtons.OK,
                System.Windows.Forms.MessageBoxIcon.Error);
            return 1;
        }

        // Build classpath
        StringBuilder cp = new StringBuilder("classes");
        string libDir = Path.Combine(baseDir, "lib");
        if (Directory.Exists(libDir))
        {
            foreach (string jar in Directory.GetFiles(libDir, "*.jar"))
                cp.Append(";").Append(jar);
        }

        // Build arguments
        string arguments =
            "--enable-native-access=ALL-UNNAMED " +
            "-Djna.library.path=\"" + libDir + "\" " +
            "-cp \"" + cp.ToString() + "\" " +
            "com.iednavigator.IEDNavigatorApp";

        // Append any extra args passed to the exe (e.g. SCL file path)
        foreach (string a in args)
            arguments += " \"" + a + "\"";

        // Preferir javaw.exe: JVM sin ventana de consola. Asi la barra de tareas
        // muestra solo la ventana Swing (con su icono) y los logs no quedan a la
        // vista de otros usuarios; van a iednavigator.log junto al exe.
        string javaw = Path.Combine(Path.GetDirectoryName(java), "javaw.exe");
        if (File.Exists(javaw)) java = javaw;

        ProcessStartInfo psi = new ProcessStartInfo();
        psi.FileName = java;
        psi.Arguments = arguments;
        psi.WorkingDirectory = baseDir;
        psi.UseShellExecute = false;
        psi.CreateNoWindow = true;
        psi.RedirectStandardOutput = true;
        psi.RedirectStandardError = true;

        // Add lib to PATH for iec61850.dll
        string envPath = Environment.GetEnvironmentVariable("PATH") ?? "";
        psi.EnvironmentVariables["PATH"] = libDir + ";" + envPath;

        StreamWriter logw = null;
        try
        {
            logw = new StreamWriter(Path.Combine(baseDir, "iednavigator.log"), false);
            logw.AutoFlush = true;
        }
        catch (Exception) { /* sin log file (carpeta de solo lectura): se descarta la salida */ }

        try
        {
            Process proc = Process.Start(psi);
            proc.OutputDataReceived += delegate(object s, DataReceivedEventArgs e)
            {
                if (logw != null && e.Data != null) { lock (logw) logw.WriteLine(e.Data); }
            };
            proc.ErrorDataReceived += delegate(object s, DataReceivedEventArgs e)
            {
                if (logw != null && e.Data != null) { lock (logw) logw.WriteLine(e.Data); }
            };
            proc.BeginOutputReadLine();
            proc.BeginErrorReadLine();
            proc.WaitForExit();
            if (logw != null) logw.Close();
            return proc.ExitCode;
        }
        catch (Exception ex)
        {
            if (logw != null) logw.Close();
            System.Windows.Forms.MessageBox.Show(
                "Error al iniciar la aplicacion:\n" + ex.Message,
                "IED Navigator PRO", System.Windows.Forms.MessageBoxButtons.OK,
                System.Windows.Forms.MessageBoxIcon.Error);
            return 1;
        }
    }

    static string FindJava(string baseDir)
    {
        // 1. Bundled JRE
        string bundled = Path.Combine(baseDir, "jre", "bin", "java.exe");
        if (File.Exists(bundled)) return bundled;

        // 2. Known Adoptium path
        string adoptium = @"C:\Program Files\Eclipse Adoptium";
        if (Directory.Exists(adoptium))
        {
            string[] dirs = Directory.GetDirectories(adoptium);
            Array.Sort(dirs); Array.Reverse(dirs);
            foreach (string d in dirs)
            {
                string j = Path.Combine(d, "bin", "java.exe");
                if (File.Exists(j)) return j;
            }
        }

        // 3. JAVA_HOME
        string javaHome = Environment.GetEnvironmentVariable("JAVA_HOME");
        if (!string.IsNullOrEmpty(javaHome))
        {
            string j = Path.Combine(javaHome, "bin", "java.exe");
            if (File.Exists(j)) return j;
        }

        // 4. Common Java locations
        string pf = Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles);
        foreach (string vendor in new[] { "Java", "Eclipse Adoptium", "Microsoft" })
        {
            string vDir = Path.Combine(pf, vendor);
            if (Directory.Exists(vDir))
            {
                string[] dirs = Directory.GetDirectories(vDir);
                Array.Sort(dirs); Array.Reverse(dirs);
                foreach (string d in dirs)
                {
                    string j = Path.Combine(d, "bin", "java.exe");
                    if (File.Exists(j)) return j;
                }
            }
        }

        // 5. PATH
        string pathEnv = Environment.GetEnvironmentVariable("PATH") ?? "";
        foreach (string p in pathEnv.Split(';'))
        {
            if (string.IsNullOrWhiteSpace(p)) continue;
            string j = Path.Combine(p.Trim(), "java.exe");
            if (File.Exists(j)) return j;
        }

        return null;
    }
}
