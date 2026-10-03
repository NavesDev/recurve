import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { signInAs } from '../test/auth';
import { page, payments, plans, subscribers } from '../test/fixtures';
import { renderWithRoutes } from '../test/render';
import { server } from '../test/server';
import { subscriberRoutes } from './routes';

const routes = [...subscriberRoutes, { path: '/login', element: <p>entrar</p> }];

function plansAnswer() {
  server.use(http.get('/api/plans', () => HttpResponse.json(page([plans.pro, plans.legacy]))));
}

describe('subscriber listing (FR-03.4, FR-06.3)', () => {
  it('lists subscribers with their plan by name and their status', async () => {
    signInAs(['VIEW_SUBSCRIBERS']);
    plansAnswer();
    server.use(http.get('/api/subscribers', () => HttpResponse.json(page([subscribers.marina, subscribers.lote]))));
    renderWithRoutes(routes, '/subscribers');

    const table = await screen.findByRole('table', { name: 'Assinantes' });
    await within(table).findByText('Marina Albuquerque');
    const rows = within(table).getAllByRole('row');
    await within(rows[1] as HTMLElement).findByText('Profissional');
    expect(within(rows[1] as HTMLElement).getByText('Ativo')).toBeInTheDocument();
    expect(within(rows[2] as HTMLElement).getByText('Atrasado')).toBeInTheDocument();
  });

  it('filters by several statuses at once', async () => {
    signInAs(['VIEW_SUBSCRIBERS']);
    plansAnswer();
    const asked: string[] = [];
    server.use(http.get('/api/subscribers', ({ request }) => {
      asked.push(decodeURIComponent(new URL(request.url).search));
      return HttpResponse.json(page([subscribers.lote]));
    }));
    renderWithRoutes(routes, '/subscribers');
    await screen.findByText('Lote 42 Serviços');

    await userEvent.click(screen.getByRole('button', { name: 'Atrasado' }));
    await userEvent.click(screen.getByRole('button', { name: 'Cancelado' }));

    await waitFor(() => expect(asked.at(-1)).toContain('filter=status:PAST_DUE,CANCELED'));
    expect(screen.getByRole('button', { name: 'Atrasado' })).toHaveAttribute('aria-pressed', 'true');
  });
});

describe('subscriber form (FR-03.1, FR-03.7)', () => {
  it('registers a subscriber on an active price', async () => {
    signInAs(['MANAGE_SUBSCRIBERS', 'VIEW_SUBSCRIBERS']);
    plansAnswer();
    let sent: unknown;
    server.use(
      http.post('/api/subscribers', async ({ request }) => {
        sent = await request.json();
        return HttpResponse.json(subscribers.marina, { status: 201 });
      }),
      http.get('/api/subscribers/sub-marina', () => HttpResponse.json(subscribers.marina)),
      http.get('/api/payments', () => HttpResponse.json(page([]))),
    );
    const { router } = renderWithRoutes(routes, '/subscribers/new');

    await userEvent.type(await screen.findByLabelText('Nome'), 'Marina Albuquerque');
    await userEvent.type(screen.getByLabelText('E-mail'), 'marina@ateliermb.com.br');
    const document = screen.getByLabelText('CPF ou CNPJ');
    await userEvent.type(document, '52998224725');
    expect(document).toHaveValue('529.982.247-25');
    const price = await screen.findByLabelText('Plano e preço');
    await waitFor(() => expect(within(price).getAllByRole('option').length).toBeGreaterThan(1));
    // An inactive plan's prices are not offered (FR-02.4).
    expect(within(price).queryByRole('option', { name: /Legado/ })).not.toBeInTheDocument();
    await userEvent.selectOptions(price, 'price-pro-m');
    await userEvent.click(screen.getByRole('button', { name: 'Cadastrar assinante' }));

    await waitFor(() => expect(router.state.location.pathname).toBe('/subscribers/sub-marina'));
    expect(sent).toEqual({ name: 'Marina Albuquerque', email: 'marina@ateliermb.com.br', document: '52998224725', planPriceId: 'price-pro-m' });
  });

  it('explains a refusal the server decides (BR-02)', async () => {
    signInAs(['MANAGE_SUBSCRIBERS', 'VIEW_SUBSCRIBERS']);
    plansAnswer();
    server.use(http.post('/api/subscribers', () => HttpResponse.json(
      { status: 422, message: 'Email marina@ateliermb.com.br is already in use by a subscriber', fieldErrors: [] }, { status: 422 })));
    renderWithRoutes(routes, '/subscribers/new');

    await userEvent.type(await screen.findByLabelText('Nome'), 'Marina');
    await userEvent.type(screen.getByLabelText('E-mail'), 'marina@ateliermb.com.br');
    await userEvent.type(screen.getByLabelText('CPF ou CNPJ'), '52998224725');
    const price = await screen.findByLabelText('Plano e preço');
    await waitFor(() => expect(within(price).getAllByRole('option').length).toBeGreaterThan(1));
    await userEvent.selectOptions(price, 'price-pro-m');
    await userEvent.click(screen.getByRole('button', { name: 'Cadastrar assinante' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Este e-mail já está em uso por outro assinante.');
  });
});

describe('subscriber detail (FR-03.3, FR-04.1)', () => {
  it('offers editing and canceling only while the subscriber is not canceled', async () => {
    signInAs(['MANAGE_SUBSCRIBERS', 'VIEW_SUBSCRIBERS', 'VIEW_PAYMENTS']);
    plansAnswer();
    server.use(
      http.get('/api/subscribers/sub-paula', () => HttpResponse.json(subscribers.paula)),
      http.get('/api/payments', () => HttpResponse.json(page([]))),
    );
    renderWithRoutes(routes, '/subscribers/sub-paula');

    expect(await screen.findByRole('heading', { name: 'Paula Reis' })).toBeInTheDocument();
    expect(screen.getByText('Cancelado')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Editar' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cancelar assinatura' })).not.toBeInTheDocument();
  });

  it('cancels after confirming', async () => {
    signInAs(['MANAGE_SUBSCRIBERS', 'VIEW_SUBSCRIBERS']);
    plansAnswer();
    let canceled = false;
    server.use(
      http.get('/api/subscribers/sub-marina', () => HttpResponse.json(canceled ? { ...subscribers.marina, status: 'CANCELED' } : subscribers.marina)),
      http.delete('/api/subscribers/sub-marina', () => {
        canceled = true;
        return HttpResponse.json({ ...subscribers.marina, status: 'CANCELED' });
      }),
    );
    renderWithRoutes(routes, '/subscribers/sub-marina');

    await userEvent.click(await screen.findByRole('button', { name: 'Cancelar assinatura' }));
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Cancelar assinatura' }));

    expect(await screen.findByText('Cancelado')).toBeInTheDocument();
  });

  it('generates the cycle\'s charge and shows where the customer pays it', async () => {
    signInAs(['VIEW_SUBSCRIBERS', 'MANAGE_PAYMENTS', 'VIEW_PAYMENTS']);
    plansAnswer();
    let sent: unknown;
    server.use(
      http.get('/api/subscribers/sub-marina', () => HttpResponse.json(subscribers.marina)),
      http.get('/api/payments', () => HttpResponse.json(page(sent ? [payments.pending] : []))),
      http.post('/api/payments', async ({ request }) => {
        sent = await request.json();
        return HttpResponse.json(payments.pending, { status: 201 });
      }),
    );
    renderWithRoutes(routes, '/subscribers/sub-marina');

    await userEvent.click(await screen.findByRole('button', { name: 'Gerar cobrança' }));
    const dialog = await screen.findByRole('dialog', { name: 'Cobrança gerada' });

    expect(sent).toEqual({ subscriberId: 'sub-marina' });
    expect(within(dialog).getByRole('link', { name: /abrir fatura/i })).toHaveAttribute('href', 'https://sandbox.asaas.com/i/123');
    expect(await screen.findByRole('table', { name: 'Cobranças' })).toBeInTheDocument();
  });

  it('hides charging from who cannot manage payments', async () => {
    signInAs(['VIEW_SUBSCRIBERS']);
    plansAnswer();
    server.use(http.get('/api/subscribers/sub-marina', () => HttpResponse.json(subscribers.marina)));
    renderWithRoutes(routes, '/subscribers/sub-marina');

    await screen.findByRole('heading', { name: 'Marina Albuquerque' });
    expect(screen.queryByRole('button', { name: 'Gerar cobrança' })).not.toBeInTheDocument();
    // Without VIEW_PAYMENTS the charges are not even asked for (MSW would fail an undeclared request).
    expect(screen.queryByRole('table', { name: 'Cobranças' })).not.toBeInTheDocument();
  });
});
