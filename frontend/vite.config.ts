import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const here = path.dirname(fileURLToPath(import.meta.url))

// https://vite.dev/config/
export default defineConfig({
  // Relative asset URLs so the app works from the jar's static/ dir at any context path.
  base: './',
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      // Dev: forward API calls to the local Spring Boot backend.
      '/api': 'http://localhost:8080',
    },
  },
  build: {
    // Emit directly into the Spring Boot static resources so the jar serves the UI at `/`.
    outDir: path.resolve(here, '../java-backend/src/main/resources/static'),
    // Never wipe the whole static dir: static/voice/* (LiveKit demo page) must survive builds.
    emptyOutDir: false,
  },
})
