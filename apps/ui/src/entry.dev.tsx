import { render } from '@builder.io/qwik'
import Root from '@/root'

const container = document.getElementById('app')
if (container) {
  render(container, <Root />)
}
