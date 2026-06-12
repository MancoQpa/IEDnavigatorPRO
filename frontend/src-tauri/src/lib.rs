use std::io::{BufRead, BufReader};
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::Mutex;

use tauri::{Manager, RunEvent};

/// Información del handshake con el bridge Java (BRIDGE_READY port=N).
#[derive(Clone, serde::Serialize)]
pub struct BridgeInfo {
    pub port: u16,
    pub token: String,
}

#[derive(Default)]
struct BridgeState {
    info: Mutex<Option<BridgeInfo>>,
    child: Mutex<Option<Child>>,
}

/// El frontend la invoca en bucle hasta que el bridge esté listo.
#[tauri::command]
fn get_bridge_info(state: tauri::State<'_, BridgeState>) -> Option<BridgeInfo> {
    state.info.lock().unwrap().clone()
}

fn find_java() -> String {
    // 1) Runtime jlink empaquetado junto al exe (instalación comercial)
    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            let bundled = dir.join("runtime").join("bin").join("java.exe");
            if bundled.exists() {
                return bundled.to_string_lossy().into_owned();
            }
        }
    }
    // 2) JAVA_HOME
    if let Ok(home) = std::env::var("JAVA_HOME") {
        let p = PathBuf::from(home).join("bin").join("java.exe");
        if p.exists() {
            return p.to_string_lossy().into_owned();
        }
    }
    // 3) java del PATH
    "java".to_string()
}

fn find_bridge_jar() -> Option<PathBuf> {
    // 1) Override explícito (desarrollo / debugging)
    if let Ok(p) = std::env::var("IEDNAV_BRIDGE_JAR") {
        let pb = PathBuf::from(p);
        if pb.exists() {
            return Some(pb);
        }
    }
    // 2) Junto al ejecutable (instalación empaquetada)
    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            let bundled = dir.join("bridge").join("bridge.jar");
            if bundled.exists() {
                return Some(bundled);
            }
        }
    }
    // 3) Árbol de desarrollo: <repo>/target/... (cwd = frontend/src-tauri en `tauri dev`)
    for candidate in [
        "../../target/ied-navigator-bridge-jar-with-dependencies.jar",
        "../target/ied-navigator-bridge-jar-with-dependencies.jar",
    ] {
        let pb = PathBuf::from(candidate);
        if pb.exists() {
            return pb.canonicalize().ok().or(Some(pb));
        }
    }
    None
}

fn find_native_lib_dir() -> Option<PathBuf> {
    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            let bundled = dir.join("bridge").join("native");
            if bundled.exists() {
                return Some(bundled);
            }
        }
    }
    for candidate in ["../../lib", "../lib"] {
        let pb = PathBuf::from(candidate);
        if pb.join("iec61850.dll").exists() {
            return pb.canonicalize().ok().or(Some(pb));
        }
    }
    None
}

fn spawn_bridge(app: &tauri::AppHandle) {
    let app = app.clone();
    std::thread::spawn(move || {
        let jar = match find_bridge_jar() {
            Some(j) => j,
            None => {
                eprintln!("[tauri] bridge.jar no encontrado (compile con: mvn package -Pbridge)");
                return;
            }
        };
        let token = uuid::Uuid::new_v4().to_string();
        let java = find_java();

        let mut cmd = Command::new(&java);
        if let Some(native) = find_native_lib_dir() {
            cmd.arg(format!("-Djna.library.path={}", native.to_string_lossy()));
        }
        cmd.arg("-jar")
            .arg(&jar)
            .args(["--port", "0", "--token", &token, "--watchdog", "120"])
            .stdout(Stdio::piped())
            .stderr(Stdio::inherit());

        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            const CREATE_NO_WINDOW: u32 = 0x0800_0000;
            cmd.creation_flags(CREATE_NO_WINDOW);
        }

        let mut child = match cmd.spawn() {
            Ok(c) => c,
            Err(e) => {
                eprintln!("[tauri] no se pudo lanzar java ({java}): {e}");
                return;
            }
        };

        let stdout = child.stdout.take();
        {
            let state = app.state::<BridgeState>();
            *state.child.lock().unwrap() = Some(child);
        }

        if let Some(out) = stdout {
            let reader = BufReader::new(out);
            for line in reader.lines().map_while(Result::ok) {
                println!("[bridge] {line}");
                if let Some(rest) = line.strip_prefix("BRIDGE_READY port=") {
                    if let Ok(port) = rest.trim().parse::<u16>() {
                        let state = app.state::<BridgeState>();
                        *state.info.lock().unwrap() = Some(BridgeInfo {
                            port,
                            token: token.clone(),
                        });
                    }
                }
            }
        }
    });
}

fn kill_bridge(app: &tauri::AppHandle) {
    let state = app.state::<BridgeState>();
    let child = state.child.lock().unwrap().take();
    if let Some(mut child) = child {
        let _ = child.kill();
        let _ = child.wait();
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .manage(BridgeState::default())
        .invoke_handler(tauri::generate_handler![get_bridge_info])
        .setup(|app| {
            spawn_bridge(app.handle());
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("error iniciando IEDNavigator PRO")
        .run(|app_handle, event| {
            if let RunEvent::Exit = event {
                kill_bridge(app_handle);
            }
        });
}
