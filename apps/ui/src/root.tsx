import { component$, isDev } from '@qwik.dev/core'
import { QwikCityProvider, RouterOutlet } from '@qwik.dev/router'

export default component$(() => {
  return (
    <QwikCityProvider>
      <head>
        <meta charset="utf-8" />
      </head>
      <body lang="en">
        <RouterOutlet />
      </body>
    </QwikCityProvider>
  )
})
