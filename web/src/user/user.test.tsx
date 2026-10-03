import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { signInAs } from '../test/auth';
import { page, users } from '../test/fixtures';
import { renderWithRoutes } from '../test/render';
import { server } from '../test/server';
import { userRoutes } from './routes';

const routes = [...userRoutes, { path: '/login', element: <p>entrar</p> }];

describe('operator listing (FR-01.4, FR-06.1)', () => {
  it('lists operators with their access and state', async () => {
    signInAs(['MANAGE_USERS']);
    server.use(http.get('/api/users', () => HttpResponse.json(page([users.joana, users.diego, users.caio]))));
    renderWithRoutes(routes, '/users');

    const table = await screen.findByRole('table', { name: 'Operadores' });
    await within(table).findByText('Diego Póvoa');
    const rows = within(table).getAllByRole('row');
    expect(within(rows[1] as HTMLElement).getByText('Acesso total')).toBeInTheDocument();
    expect(within(rows[2] as HTMLElement).getByText('3 de 5 áreas')).toBeInTheDocument();
    expect(within(rows[3] as HTMLElement).getByText('Desativado')).toBeInTheDocument();
  });

  it('is closed without MANAGE_USERS: seeing operators is managing them (BR-01)', async () => {
    signInAs(['VIEW_PLANS']);
    renderWithRoutes(routes, '/users');

    expect(await screen.findByRole('heading', { name: 'Sem permissão' })).toBeInTheDocument();
  });
});

describe('operator form (FR-01.1, FR-01.2)', () => {
  it('registers an operator from a profile adjusted area by area', async () => {
    signInAs(['MANAGE_USERS']);
    let sent: unknown;
    server.use(
      http.post('/api/users', async ({ request }) => {
        sent = await request.json();
        return HttpResponse.json({ ...users.diego, id: 'user-new' }, { status: 201 });
      }),
      http.get('/api/users', () => HttpResponse.json(page([]))),
    );
    const { router } = renderWithRoutes(routes, '/users/new');

    await userEvent.type(await screen.findByLabelText('Nome'), 'Diego Póvoa');
    await userEvent.type(screen.getByLabelText('E-mail de acesso'), 'diego@recurve.app');
    await userEvent.type(screen.getByLabelText('Senha inicial'), 's3cret-password');
    await userEvent.selectOptions(screen.getByLabelText('Perfil'), 'Financeiro');
    const subscribers = screen.getByRole('radiogroup', { name: 'Assinantes' });
    expect(within(subscribers).getByRole('radio', { name: 'Ver' })).toBeChecked();
    await userEvent.click(within(screen.getByRole('radiogroup', { name: 'Sistema' })).getByRole('radio', { name: 'Gerenciar' }));
    await userEvent.click(screen.getByRole('button', { name: 'Cadastrar operador' }));

    await waitFor(() => expect(router.state.location.pathname).toBe('/users'));
    expect(sent).toEqual({
      name: 'Diego Póvoa', email: 'diego@recurve.app', password: 's3cret-password',
      permissions: ['MANAGE_PLANS', 'VIEW_SUBSCRIBERS', 'MANAGE_PAYMENTS', 'MANAGE_SYSTEM'],
    });
  });

  it('refuses a password shorter than eight characters', async () => {
    signInAs(['MANAGE_USERS']);
    renderWithRoutes(routes, '/users/new');

    await userEvent.type(await screen.findByLabelText('Senha inicial'), 'short');
    await userEvent.click(screen.getByRole('button', { name: 'Cadastrar operador' }));

    expect(await screen.findByText('Ao menos 8 caracteres.')).toBeInTheDocument();
  });

  it('edits permissions without touching the password', async () => {
    signInAs(['MANAGE_USERS']);
    let sent: unknown;
    server.use(
      http.get('/api/users/user-diego', () => HttpResponse.json(users.diego)),
      http.put('/api/users/user-diego', async ({ request }) => {
        sent = await request.json();
        return HttpResponse.json(users.diego);
      }),
      http.get('/api/users', () => HttpResponse.json(page([]))),
    );
    renderWithRoutes(routes, '/users/user-diego/edit');

    await screen.findByDisplayValue('Diego Póvoa');
    expect(screen.queryByLabelText('Senha inicial')).not.toBeInTheDocument();
    await userEvent.click(within(screen.getByRole('radiogroup', { name: 'Pagamentos' })).getByRole('radio', { name: 'Nenhum' }));
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }));

    await waitFor(() => expect(sent).toEqual({
      name: 'Diego Póvoa', email: 'diego@recurve.app', permissions: ['MANAGE_PLANS', 'VIEW_SUBSCRIBERS'],
    }));
  });

  it('deactivates another operator after confirming (FR-01.3)', async () => {
    signInAs(['MANAGE_USERS']);
    let deactivated = false;
    server.use(
      http.get('/api/users/user-diego', () => HttpResponse.json(users.diego)),
      http.delete('/api/users/user-diego', () => {
        deactivated = true;
        return HttpResponse.json({ ...users.diego, active: false });
      }),
      http.get('/api/users', () => HttpResponse.json(page([]))),
    );
    renderWithRoutes(routes, '/users/user-diego/edit');

    await userEvent.click(await screen.findByRole('button', { name: 'Desativar operador' }));
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Desativar operador' }));

    await waitFor(() => expect(deactivated).toBe(true));
  });

  it('never offers to deactivate oneself', async () => {
    signInAs(['MANAGE_USERS']);
    server.use(http.get('/api/users/me', () => HttpResponse.json(users.joana)));
    renderWithRoutes(routes, '/users/me/edit');

    await screen.findByDisplayValue('Joana Martins');
    expect(screen.queryByRole('button', { name: 'Desativar operador' })).not.toBeInTheDocument();
  });
});
