import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { signInAs } from '../test/auth';
import { page, plans } from '../test/fixtures';
import { renderWithRoutes } from '../test/render';
import { server } from '../test/server';
import { planRoutes } from './routes';

const routes = [...planRoutes, { path: '/login', element: <p>entrar</p> }];
const plain = (text: string | null) => (text ?? '').replace(/\u00a0/g, ' ');

describe('plan listing (FR-02.5, FR-06.2)', () => {
  it('lists plans with what they charge and their state', async () => {
    signInAs(['VIEW_PLANS']);
    server.use(http.get('/api/plans', () => HttpResponse.json(page([plans.pro, plans.legacy]))));
    renderWithRoutes(routes, '/plans');

    const table = await screen.findByRole('table', { name: 'Planos' });
    await within(table).findByText('Profissional');
    const rows = within(table).getAllByRole('row');
    expect(plain(rows[1]?.textContent ?? '')).toContain('R$ 129,90/mês');
    expect(within(rows[1] as HTMLElement).getByText('Ativo')).toBeInTheDocument();
    expect(within(rows[2] as HTMLElement).getByText('Inativo')).toBeInTheDocument();
  });

  it('searches by name through the server', async () => {
    signInAs(['VIEW_PLANS']);
    const asked: string[] = [];
    server.use(http.get('/api/plans', ({ request }) => {
      asked.push(new URL(request.url).search);
      return HttpResponse.json(page([plans.pro]));
    }));
    const { router } = renderWithRoutes(routes, '/plans');
    await screen.findByText('Profissional');

    await userEvent.type(screen.getByRole('searchbox', { name: 'Buscar planos' }), 'prof');

    await waitFor(() => expect(asked.some((query) => query.includes('q=prof'))).toBe(true));
    expect(router.state.location.search).toContain('q=prof');
  });

  it('offers creating and row actions only to who manages plans', async () => {
    signInAs(['VIEW_PLANS']);
    server.use(http.get('/api/plans', () => HttpResponse.json(page([plans.pro]))));
    renderWithRoutes(routes, '/plans');
    await screen.findByText('Profissional');

    expect(screen.queryByRole('link', { name: 'Novo plano' })).not.toBeInTheDocument();
  });

  it('is closed to an operator without VIEW_PLANS', async () => {
    signInAs(['VIEW_SUBSCRIBERS']);
    renderWithRoutes(routes, '/plans');

    expect(await screen.findByRole('heading', { name: 'Sem permissão' })).toBeInTheDocument();
  });
});

describe('plan form (FR-02.1, FR-02.6)', () => {
  it('creates a plan with its first price and opens it', async () => {
    signInAs(['MANAGE_PLANS', 'VIEW_PLANS']);
    const bodies: unknown[] = [];
    server.use(
      http.post('/api/plans', async ({ request }) => {
        bodies.push(await request.json());
        return HttpResponse.json({ ...plans.pro, prices: [] }, { status: 201 });
      }),
      http.post('/api/plans/plan-pro/prices', async ({ request }) => {
        bodies.push(await request.json());
        return HttpResponse.json(plans.pro.prices[0], { status: 201 });
      }),
      http.get('/api/plans/plan-pro', () => HttpResponse.json(plans.pro)),
    );
    const { router } = renderWithRoutes(routes, '/plans/new');

    await userEvent.type(await screen.findByLabelText('Nome do plano'), 'Profissional');
    await userEvent.type(screen.getByLabelText('Preço inicial (R$)'), '129,90');
    await userEvent.click(screen.getByRole('radio', { name: 'Anual' }));
    await userEvent.click(screen.getByRole('button', { name: 'Criar plano' }));

    await waitFor(() => expect(router.state.location.pathname).toBe('/plans/plan-pro'));
    expect(bodies).toEqual([
      { name: 'Profissional', description: null },
      { price: 129.9, currency: 'BRL', interval: 'YEARLY' },
    ]);
  });

  it('shows the server\'s field errors on their fields', async () => {
    signInAs(['MANAGE_PLANS', 'VIEW_PLANS']);
    server.use(http.post('/api/plans', () => HttpResponse.json({
      status: 400, error: 'Bad Request', message: 'Request validation failed', timestamp: '',
      fieldErrors: [{ field: 'name', message: 'size must be between 0 and 120' }],
    }, { status: 400 })));
    renderWithRoutes(routes, '/plans/new');

    await userEvent.type(await screen.findByLabelText('Nome do plano'), 'X');
    await userEvent.click(screen.getByRole('button', { name: 'Criar plano' }));

    expect(await screen.findByLabelText('Nome do plano')).toHaveAccessibleDescription('size must be between 0 and 120');
  });

  it('asks for a name and a positive price before calling the server', async () => {
    signInAs(['MANAGE_PLANS', 'VIEW_PLANS']);
    renderWithRoutes(routes, '/plans/new');

    await userEvent.type(await screen.findByLabelText('Preço inicial (R$)'), '0');
    await userEvent.click(screen.getByRole('button', { name: 'Criar plano' }));

    expect(await screen.findByText('Informe um nome — aparece na fatura do assinante.')).toBeInTheDocument();
    expect(screen.getByText('O valor deve ser maior que zero.')).toBeInTheDocument();
  });

  it('edits name and description of an existing plan', async () => {
    signInAs(['MANAGE_PLANS', 'VIEW_PLANS']);
    let sent: unknown;
    server.use(
      http.get('/api/plans/plan-pro', () => HttpResponse.json(plans.pro)),
      http.put('/api/plans/plan-pro', async ({ request }) => {
        sent = await request.json();
        return HttpResponse.json({ ...plans.pro, name: 'Pro+' });
      }),
    );
    const { router } = renderWithRoutes(routes, '/plans/plan-pro/edit');

    const name = await screen.findByDisplayValue('Profissional');
    expect(screen.queryByLabelText('Preço inicial (R$)')).not.toBeInTheDocument();
    await userEvent.clear(name);
    await userEvent.type(name, 'Pro+');
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }));

    await waitFor(() => expect(router.state.location.pathname).toBe('/plans/plan-pro'));
    expect(sent).toEqual({ name: 'Pro+', description: 'Times de até 10 pessoas' });
  });
});

describe('plan detail (FR-02.2, FR-02.3, FR-02.4, FR-02.7)', () => {
  it('shows active prices and the history', async () => {
    signInAs(['VIEW_PLANS']);
    server.use(http.get('/api/plans/plan-pro', () => HttpResponse.json(plans.pro)));
    renderWithRoutes(routes, '/plans/plan-pro');

    const active = await screen.findByRole('table', { name: 'Preços ativos' });
    expect(plain(active.textContent)).toContain('R$ 129,90');
    const history = screen.getByRole('table', { name: 'Histórico de preços' });
    expect(plain(history.textContent)).toContain('R$ 99,90');
    expect(screen.queryByRole('button', { name: /substituir/i })).not.toBeInTheDocument();
  });

  it('replaces a price with a new amount', async () => {
    signInAs(['MANAGE_PLANS', 'VIEW_PLANS']);
    let sent: unknown;
    server.use(
      http.get('/api/plans/plan-pro', () => HttpResponse.json(plans.pro)),
      http.post('/api/prices/price-pro-m/replace', async ({ request }) => {
        sent = await request.json();
        return HttpResponse.json({ ...plans.pro.prices[0], id: 'price-new', price: 139.9 }, { status: 201 });
      }),
    );
    renderWithRoutes(routes, '/plans/plan-pro');

    await userEvent.click(await screen.findByRole('button', { name: 'Ações do preço Mensal' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Substituir valor' }));
    const dialog = screen.getByRole('dialog', { name: 'Substituir valor mensal' });
    await userEvent.type(within(dialog).getByLabelText('Novo valor (R$)'), '139,90');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Substituir' }));

    await waitFor(() => expect(sent).toEqual({ price: 139.9 }));
    expect(await screen.findByText('Preço substituído.')).toBeInTheDocument();
  });

  it('deactivates the plan after confirming', async () => {
    signInAs(['MANAGE_PLANS', 'VIEW_PLANS']);
    let deactivated = false;
    server.use(
      http.get('/api/plans/plan-pro', () => HttpResponse.json(deactivated ? { ...plans.pro, active: false } : plans.pro)),
      http.delete('/api/plans/plan-pro', () => {
        deactivated = true;
        return HttpResponse.json({ ...plans.pro, active: false });
      }),
    );
    renderWithRoutes(routes, '/plans/plan-pro');

    await userEvent.click(await screen.findByRole('button', { name: 'Desativar plano' }));
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Desativar plano' }));

    expect(await screen.findByText('Inativo')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Desativar plano' })).not.toBeInTheDocument();
  });

  it('says so when the plan does not exist', async () => {
    signInAs(['VIEW_PLANS']);
    server.use(http.get('/api/plans/nope', () => HttpResponse.json({ message: 'Plan nope not found' }, { status: 404 })));
    renderWithRoutes(routes, '/plans/nope');

    expect(await screen.findByRole('heading', { name: 'Plano não encontrado' })).toBeInTheDocument();
  });
});
