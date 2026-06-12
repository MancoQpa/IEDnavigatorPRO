// Evita la consola en Windows en builds release
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    iednavigator_pro_lib::run()
}
