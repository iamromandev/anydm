import { defineConfig } from 'vite'
import { qwikVite } from '@builder.io/qwik/optimizer'
import tsconfigPaths from 'vite-tsconfig-paths'

export default defineConfig({
  plugins: [tsconfigPaths(), qwikVite({ csr: true })],
})

// export default defineConfig((configEnv: ConfigEnv): UserConfig => {
//   const { command: mode } = configEnv;
//
//   return {
//     plugins: [qwikVite({ csr: true }), tsconfigPaths(), tailwindcss()],
//     define: {
//       __DEV__: mode === "serve",
//     },
//   };
// });
