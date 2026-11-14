/// <reference types="vitest/config" />
import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In development the UI and the API share one origin through this proxy, so session and CSRF
// cookies work without CORS. DISPATCH_PORT is the one from .env (exported by the Makefile).
const dispatchUrl = process.env.DISPATCH_URL ?? `http://localhost:${process.env.DISPATCH_PORT ?? '8080'}`;

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: dispatchUrl, changeOrigin: false },
    },
  },
  test: {
    include: ['src/**/*.test.{ts,tsx}'],
    setupFiles: ['src/test/setup.ts'],
  },
});
