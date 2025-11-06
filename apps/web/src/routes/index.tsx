// src/routes/index.tsx
import { component$, $, useSignal } from '@qwik.dev/core'
import { useNavigate } from '@qwik.dev/router'
import { Button } from '@/components/button'
import { Input } from '@/components/input'

export default component$(() => {
  const url = useSignal('')
  const nav = useNavigate()
  const isLoading = useSignal(false)

  const handleSubmit = $(async (e: Event) => {
    e.preventDefault()
    if (!url.value.trim()) return

    isLoading.value = true
    try {
      // Basic URL validation
      new URL(url.value)
      await nav(`/analyze?url=${encodeURIComponent(url.value)}`)
    } catch {
      alert('Please enter a valid URL with http:// or https://')
    } finally {
      isLoading.value = false
    }
  })

  return (
    <div class="min-h-screen bg-gradient-to-br from-blue-50 to-indigo-100 flex items-center justify-center p-4">
      <div class="max-w-md w-full space-y-8">
        {/* Header */}
        <div class="text-center">
          <h1 class="text-3xl font-bold text-gray-900 mb-2">URL Analyzer</h1>
          <p class="text-gray-600">
            Enter a URL to analyze website performance and SEO
          </p>
        </div>

        {/* URL Form */}
        <form onSubmit$={handleSubmit} class="space-y-4">
          <Input
            type="url"
            bind:value={url}
            placeholder="https://example.com"
            class="w-full px-4 py-3 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent"
            required
          />

          <Button
            type="submit"
            disabled={isLoading.value}
            class="w-full py-3 bg-blue-600 hover:bg-blue-700 text-white font-semibold rounded-lg transition-colors disabled:opacity-50"
          >
            {isLoading.value ? 'Analyzing...' : 'Analyze URL'}
          </Button>
        </form>

        {/* Quick Examples */}
        <div class="text-center">
          <p class="text-sm text-gray-500 mb-2">Try these examples:</p>
          <div class="flex flex-wrap justify-center gap-2">
            {['https://google.com', 'https://github.com'].map((example) => (
              <button
                key={example}
                type="button"
                onClick$={() => {
                  url.value = example
                  handleSubmit(new Event('submit'))
                }}
                class="text-sm text-blue-600 hover:text-blue-800 underline"
              >
                {example}
              </button>
            ))}
          </div>
        </div>
      </div>
    </div>
  )
})
