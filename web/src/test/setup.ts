// Shared setup of Vitest. Component tests opt into jsdom with `// @vitest-environment jsdom` on their
// first line; plain unit tests stay in Node. Everything DOM-specific below runs only in jsdom.
import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { server } from './server';

const inDom = typeof window !== 'undefined';

if (inDom) {
  // Mantine reads these browser APIs, which jsdom does not have.
  window.matchMedia ??= (query: string) =>
    ({
      matches: false,
      media: query,
      onchange: null,
      addEventListener: () => {},
      removeEventListener: () => {},
      addListener: () => {},
      removeListener: () => {},
      dispatchEvent: () => false,
    }) as MediaQueryList;
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
}

// Any request without a handler fails the test: tests never reach a real server.
beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));
afterEach(() => {
  server.resetHandlers();
  if (inDom) {
    cleanup();
    document.cookie.split(';').forEach((cookie) => {
      document.cookie = `${cookie.split('=')[0]?.trim()}=; max-age=0; path=/`;
    });
  }
});
afterAll(() => server.close());
