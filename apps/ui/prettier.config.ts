import type { Config } from 'prettier'

const config: Config = {
  singleQuote: true,
  semi: false,
  trailingComma: 'all',
  printWidth: 80,
  plugins: ['prettier-plugin-tailwindcss'],
}

export default config
