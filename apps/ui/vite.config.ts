import { defineConfig } from 'vite'
import { qwikVite } from '@qwik.dev/core/optimizer'
import tsconfigPaths from 'vite-tsconfig-paths'
import { qwikRouter } from '@qwik.dev/router/vite'

export default defineConfig({
  plugins: [qwikRouter(), qwikVite(), tsconfigPaths()],
})
