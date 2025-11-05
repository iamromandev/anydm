import { render } from '@builder.io/qwik'
import Root from '@/root'

const container: HTMLElement | null = document.getElementById('app')
if (container) {
  render(container, <Root />)
}
