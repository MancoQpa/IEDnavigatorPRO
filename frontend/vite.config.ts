import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Config pensada para Tauri: puerto fijo y sin limpiar pantalla (logs de Rust visibles)
export default defineConfig({
  plugins: [react()],
  clearScreen: false,
  server: {
    port: 5173,
    strictPort: true,
  },
  build: {
    target: 'es2022',
  },
});
