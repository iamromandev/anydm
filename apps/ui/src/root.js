import { jsx as _jsx, jsxs as _jsxs } from '@builder.io/qwik/jsx-runtime';
import { component$ } from '@builder.io/qwik';
export default component$(() => {
  return _jsxs('div', {
    style: {
      minHeight: '100vh',
      display: 'flex',
      flexDirection: 'column',
      justifyContent: 'center',
      alignItems: 'center',
      padding: '1rem',
      fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif',
      color: '#1e293b',
      background: '#f8fafc',
    },
    children: [
      _jsx('h1', {
        style: { fontSize: '2.25rem', fontWeight: '700', margin: '0 0 0.5rem' },
        children: 'Hello, World! \uD83C\uDF0D',
      }),
      _jsxs('p', {
        style: { fontSize: '1.125rem', color: '#64748b', textAlign: 'center' },
        children: [
          'From ',
          _jsx('strong', { children: 'Qwik' }),
          ' + ',
          _jsx('strong', { children: 'Bun' }),
          ' in the',
          ' ',
          _jsx('code', { children: 'anydm' }),
          ' monorepo',
        ],
      }),
    ],
  });
});
