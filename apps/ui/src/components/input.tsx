// src/components/input.tsx
import { component$, type QwikIntrinsicElements } from '@qwik.dev/core'

type InputProps = QwikIntrinsicElements['input']

export const Input = component$<InputProps>((props) => {
  return (
    <input
      {...props}
      class={`px-3 py-2 border border-gray-300 rounded focus:outline-none focus:ring-2 focus:ring-blue-500 ${props.class}`}
    />
  )
})
