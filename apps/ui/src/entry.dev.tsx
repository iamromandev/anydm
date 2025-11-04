import { render } from '@builder.io/qwik';
import Root from './root';

const container: HTMLElement = document.getElementById('app');
if (container) {
  render(container, <Root />);
} else {
  console.error('❌ #app container not found in HTML');
}
