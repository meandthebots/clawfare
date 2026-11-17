import { fileURLToPath, URL } from "node:url";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import stylex from "@stylexjs/unplugin";
import { viteSingleFile } from "vite-plugin-singlefile";

// Kotlin JVM, exported HTML reports, and authenticated Sites all consume the
// SAME self-contained, deterministic production document.
export default defineConfig({
  root: fileURLToPath(new URL(".", import.meta.url)),
  plugins: [
    stylex.vite({ useCSSLayers: true, runtimeInjection: false }),
    react(),
    viteSingleFile(),
  ],
  build: {
    target: "es2022",
    outDir: "../build/frontend/web",
    emptyOutDir: true,
    cssCodeSplit: false,
    assetsInlineLimit: 100000000,
  },
  server: {
    proxy: { "/api": "http://127.0.0.1:8787" },
  },
});
