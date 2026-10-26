import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';
import { viteSingleFile } from 'vite-plugin-singlefile';

// `npm run dev`   serves the app with hot reload; run the Java app with -Dui.url=http://127.0.0.1:5173
// `npm run build` writes dist/index.html, ONE file with all scripts and styles inlined. That single file is what
//                 Jolt can load today (serving a folder of separate assets is not implemented yet).
export default defineConfig({
  plugins: [react(), tailwindcss(), viteSingleFile()],
  server: { host: '127.0.0.1', port: 5173, strictPort: true },
});
