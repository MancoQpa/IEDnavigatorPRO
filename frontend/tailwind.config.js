/** @type {import('tailwindcss').Config} */
export default {
  darkMode: 'class',
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        // Acento azul de la marca (coherente con el Swing FlatLaf pulido)
        accent: {
          DEFAULT: '#2675bf',
          hover: '#3b8be0',
        },
        surface: {
          DEFAULT: '#1e1f22',
          raised: '#2b2d30',
          border: '#393b40',
        },
      },
      fontFamily: {
        mono: ['"Cascadia Code"', '"JetBrains Mono"', 'Consolas', 'monospace'],
      },
    },
  },
  plugins: [],
};
