// @vitest-environment jsdom
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import { renderApp } from '@/test/renderApp';

const scheme = () => document.documentElement.getAttribute('data-mantine-color-scheme');

describe('ColorSchemeToggle', () => {
  afterEach(() => localStorage.clear());

  it('starts dark and switches to light and back, remembering the choice', async () => {
    renderApp('/login', { me: null });

    const toLight = await screen.findByRole('button', { name: 'Светлая тема' });
    expect(scheme()).toBe('dark');

    await userEvent.click(toLight);
    expect(scheme()).toBe('light');
    expect(localStorage.getItem('mantine-color-scheme-value')).toBe('light');

    await userEvent.click(screen.getByRole('button', { name: 'Тёмная тема' }));
    expect(scheme()).toBe('dark');
  });
});
