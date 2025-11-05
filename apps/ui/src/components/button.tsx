// src/components/button.tsx
import { component$, Slot, type QwikIntrinsicElements } from '@qwik.dev/core'

type ButtonProps = QwikIntrinsicElements['button'] & {
  variant?: 'primary' | 'secondary' | 'outline'
}

export const Button = component$<ButtonProps>(
  ({ variant = 'primary', ...props }) => {
    const baseClasses = 'px-4 py-2 rounded font-medium transition-colors'
    const variantClasses = {
      primary: 'bg-blue-600 text-white hover:bg-blue-700',
      secondary: 'bg-gray-600 text-white hover:bg-gray-700',
      outline: 'border border-gray-300 hover:bg-gray-50',
    }

    return (
      <button
        {...props}
        class={`${baseClasses} ${variantClasses[variant]} ${props.class}`}
      >
        <Slot />
      </button>
    )
  },
)
