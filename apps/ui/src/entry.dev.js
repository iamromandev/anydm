import { jsx as _jsx } from '@builder.io/qwik/jsx-runtime';
import { render } from '@builder.io/qwik';
import Root from '@/root';
const container = document.getElementById('app');
if (container) {
  render(container, _jsx(Root, {}));
}
