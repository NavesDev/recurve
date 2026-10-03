import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { sessionStore } from '../shared/api/session';
import { renderWithApp, renderWithRoutes } from '../test/render';
import { server } from '../test/server';
import { AuthBridge } from './components/AuthBridge';
import { Can } from './components/Can';
import { RequireAuth, RequirePermission } from './components/RequireAuth';
import { LoginPage } from './pages/LoginPage';

const later = new Date(Date.now() + 3_600_000).toISOString();
const ada = { id: '1', name: 'Ada Lovelace', email: 'ada@recurve.local', permissions: ['VIEW_PLANS'] };

function signedIn() {
  sessionStore.set({ token: 'token-1', expiresAt: later });
}

function meAnswers(body: Record<string, unknown>, status = 200) {
  server.use(http.get('/api/me', () => HttpResponse.json(body, { status })));
}

describe('<Can>', () => {
  it('shows what the operator may do', async () => {
    signedIn();
    meAnswers(ada);
    renderWithApp(<><Can permission="VIEW_PLANS">ver</Can><Can permission="MANAGE_PLANS">gerenciar</Can></>);

    expect(await screen.findByText('ver')).toBeInTheDocument();
    expect(screen.queryByText('gerenciar')).not.toBeInTheDocument();
  });

  it('shows nothing while permissions are unknown or unreadable (fail-closed)', async () => {
    signedIn();
    meAnswers({ message: 'boom' }, 503);
    renderWithApp(<Can permission="VIEW_PLANS">ver</Can>);

    await waitFor(() => expect(screen.queryByText('ver')).not.toBeInTheDocument());
  });
});

describe('<RequireAuth>', () => {
  it('sends a visitor without a session to sign in, remembering where they were going', async () => {
    const { router } = renderWithRoutes([
      { path: '/plans', element: <RequireAuth><p>planos</p></RequireAuth> },
      { path: '/login', element: <p>entrar</p> },
    ], '/plans?page=2');

    expect(await screen.findByText('entrar')).toBeInTheDocument();
    expect(router.state.location.search).toBe(`?next=${encodeURIComponent('/plans?page=2')}`);
  });

  it('lets a signed-in operator through', async () => {
    signedIn();
    meAnswers(ada);
    renderWithApp(<RequireAuth><p>planos</p></RequireAuth>);

    expect(await screen.findByText('planos')).toBeInTheDocument();
  });
});

describe('<RequirePermission>', () => {
  it('answers 403 to an operator without the permission', async () => {
    signedIn();
    meAnswers(ada);
    renderWithApp(<RequirePermission permission="MANAGE_USERS"><p>operadores</p></RequirePermission>);

    expect(await screen.findByRole('heading', { name: 'Sem permissão' })).toBeInTheDocument();
    expect(screen.queryByText('operadores')).not.toBeInTheDocument();
  });

  it('lets an operator with the permission through', async () => {
    signedIn();
    meAnswers(ada);
    renderWithApp(<RequirePermission permission="VIEW_PLANS"><p>planos</p></RequirePermission>);

    expect(await screen.findByText('planos')).toBeInTheDocument();
  });
});

describe('<LoginPage>', () => {
  it('signs in and goes where the operator was headed', async () => {
    server.use(http.post('/api/auth/token', async ({ request }) => {
      const body = (await request.json()) as { email: string; password: string };
      return body.password === 's3cret-password'
        ? HttpResponse.json({ token: 'token-1', expiresAt: later })
        : HttpResponse.json({ message: 'Authentication required' }, { status: 401 });
    }));
    meAnswers(ada);
    const { router } = renderWithRoutes([
      { path: '/login', element: <LoginPage /> },
      { path: '/plans', element: <p>planos</p> },
    ], `/login?next=${encodeURIComponent('/plans')}`);

    await userEvent.type(screen.getByLabelText('E-mail'), 'ada@recurve.local');
    await userEvent.type(screen.getByLabelText('Senha'), 's3cret-password');
    await userEvent.click(screen.getByRole('button', { name: 'Entrar' }));

    expect(await screen.findByText('planos')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/plans');
    expect(sessionStore.get()?.token).toBe('token-1');
  });

  it('says the credentials were wrong without saying which', async () => {
    server.use(http.post('/api/auth/token', () => HttpResponse.json({ message: 'Authentication required' }, { status: 401 })));
    renderWithApp(<LoginPage />, { at: '/login' });

    await userEvent.type(screen.getByLabelText('E-mail'), 'ada@recurve.local');
    await userEvent.type(screen.getByLabelText('Senha'), 'wrong');
    await userEvent.click(screen.getByRole('button', { name: 'Entrar' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('E-mail ou senha incorretos.');
    expect(sessionStore.get()).toBeNull();
  });

  it('asks for both fields before calling the server', async () => {
    renderWithApp(<LoginPage />, { at: '/login' });

    await userEvent.click(screen.getByRole('button', { name: 'Entrar' }));

    expect(await screen.findByText('Informe o e-mail.')).toBeInTheDocument();
    expect(screen.getByText('Informe a senha.')).toBeInTheDocument();
  });

  it('never sends the operator to another site after signing in', async () => {
    server.use(http.post('/api/auth/token', () => HttpResponse.json({ token: 'token-1', expiresAt: later })));
    meAnswers(ada);
    const { router } = renderWithRoutes([
      { path: '/login', element: <LoginPage /> },
      { path: '/', element: <p>início</p> },
    ], `/login?next=${encodeURIComponent('//evil.example/x')}`);

    await userEvent.type(screen.getByLabelText('E-mail'), 'ada@recurve.local');
    await userEvent.type(screen.getByLabelText('Senha'), 'x');
    await userEvent.click(screen.getByRole('button', { name: 'Entrar' }));

    expect(await screen.findByText('início')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/');
  });
});

describe('<AuthBridge>', () => {
  it('forgets every cached answer when the session ends', async () => {
    signedIn();
    meAnswers(ada);
    const { queryClient } = renderWithApp(<AuthBridge><Can permission="VIEW_PLANS">ver</Can></AuthBridge>);
    await screen.findByText('ver');

    sessionStore.clear();

    await waitFor(() => expect(queryClient.getQueryCache().getAll()).toHaveLength(0));
  });
});
