import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // The backend runs on 8090 and has no CORS setup; the dev server forwards /api to it.
    proxy: { '/api': 'http://localhost:8090' },
  },
  test: {
    environment: 'jsdom',
    globals: true, // lets @testing-library/react clean up the DOM after each test
  },
});
