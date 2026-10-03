import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { sessionStore } from './shared/api/session';
import { Shell } from './Shell';
import { renderWithRoutes } from './test/render';
import { server } from './test/server';

const later = new Date(Date.now() + 3_600_000).toISOString();

function operator(permissions: string[]) {
  sessionStore.set({ token: 't', expiresAt: later });
  server.use(http.get('/api/me', () => HttpResponse.json({ id: '1', name: 'Joana Martins', email: 'joana@recurve.app', permissions })));
}

describe('<Shell>', () => {
  it('lists only the areas the operator may open', async () => {
    operator(['VIEW_PLANS', 'VIEW_PAYMENTS']);
    renderWithRoutes([{ path: '/', element: <Shell />, children: [{ index: true, element: <p>início</p> }] }], '/');

    const nav = await screen.findByRole('navigation', { name: 'Principal' });
    const links = within(nav).getAllByRole('link').map((link) => link.textContent);
    expect(links).toEqual(['Visão geral', 'Planos', 'Pagamentos']);
  });

  it('shows who is signed in and signs them out', async () => {
    operator([]);
    const { router } = renderWithRoutes([
      { path: '/', element: <Shell />, children: [{ index: true, element: <p>início</p> }] },
      { path: '/login', element: <p>entrar</p> },
    ], '/');

    expect(await screen.findByText('JM')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /sair/i }));

    expect(await screen.findByText('entrar')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/login');
    expect(sessionStore.get()).toBeNull();
  });
});
