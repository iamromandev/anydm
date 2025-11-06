import { component$, type PropsOf } from '@qwik.dev/core'
import './input.css'

type InputProps = PropsOf<'input'> & {
  error?: string
}

export const Input = component$<InputProps>(({ id, name, error, ...props }) => {
  const inputId = id || name

  return (
    <>
      <input id={inputId} {...props} class="input" aria-invalid={!!error} />
      {error && <div>{error}</div>}
    </>
  )
})
