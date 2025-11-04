import { component$ } from '@builder.io/qwik';

export default component$(() => {
  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        flexDirection: 'column',
        justifyContent: 'center',
        alignItems: 'center',
        padding: '1rem',
        fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif',
        color: '#1e293b',
        background: '#f8fafc',
      }}
    >
      <h1
        style={{ fontSize: '2.25rem', fontWeight: '700', margin: '0 0 0.5rem' }}
      >
        Hello, World! 🌍
      </h1>
      <p
        style={{ fontSize: '1.125rem', color: '#64748b', textAlign: 'center' }}
      >
        From <strong>Qwik</strong> + <strong>Bun</strong> in the{' '}
        <code>anydm</code> monorepo
      </p>
    </div>
  );
});
