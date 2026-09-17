import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: { port: 5173, strictPort: true },
  // Use one thread worker: fork workers time out on this Windows/Docker development machine.
  test: { pool: 'threads', maxWorkers: 1, environment: 'jsdom', setupFiles: './src/test/setup.ts', clearMocks: true },
});
