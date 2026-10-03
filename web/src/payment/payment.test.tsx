import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { signInAs } from '../test/auth';
import { page, payments, subscribers } from '../test/fixtures';
import { renderWithRoutes } from '../test/render';
import { server } from '../test/server';
import { canConfirm, canRefund, canSend, canSync, type Payment } from './domain';
import { paymentRoutes } from './routes';

const routes = [...paymentRoutes, { path: '/login', element: <p>entrar</p> }];

describe('payment rules (FR-04)', () => {
  const as = (payment: object) => payment as Payment;

  it('resends only a pending charge the gateway never took (FR-04.7)', () => {
    expect(canSend(as(payments.unsent))).toBe(true);
    expect(canSend(as(payments.pending))).toBe(false);
  });

  it('confirms by hand a pending or failed charge, never a paid one (FR-04.5)', () => {
    expect(canConfirm(as(payments.pending))).toBe(true);
    expect(canConfirm(as({ ...payments.pending, status: 'FAILED' }))).toBe(true);
    expect(canConfirm(as(payments.paid))).toBe(false);
    expect(canConfirm(as({ ...payments.paid, status: 'REFUNDED' }))).toBe(false);
  });

  it('refunds only a paid charge (BR-08)', () => {
    expect(canRefund(as(payments.paid))).toBe(true);
    expect(canRefund(as(payments.pending))).toBe(false);
  });

  it('asks the gateway only about a charge it holds', () => {
    expect(canSync(as(payments.pending))).toBe(true);
    expect(canSync(as(payments.unsent))).toBe(false);
  });
});

function listAnswers() {
  server.use(
    http.get('/api/payments', () => HttpResponse.json(page([payments.pending, payments.unsent, payments.paid]))),
    http.get('/api/subscribers', ({ request }) => {
      const filter = new URL(request.url).searchParams.get('filter') ?? '';
      const ids = filter.replace('id:', '').split(',');
      return HttpResponse.json(page([subscribers.marina, subscribers.lote].filter((s) => ids.includes(s.id))));
    }),
  );
}

describe('payment listing (FR-04.6)', () => {
  it('names each charge\'s subscriber, asking for all of a page at once', async () => {
    signInAs(['VIEW_PAYMENTS', 'VIEW_SUBSCRIBERS']);
    listAnswers();
    renderWithRoutes(routes, '/payments');

    const table = await screen.findByRole('table', { name: 'Pagamentos' });
    expect(await within(table).findAllByText('Marina Albuquerque')).toHaveLength(2);
    expect(within(table).getByText('Lote 42 Serviços')).toBeInTheDocument();
    expect(within(table).getByText('Pago')).toBeInTheDocument();
  });

  it('offers each charge only the actions its state allows', async () => {
    signInAs(['MANAGE_PAYMENTS', 'VIEW_PAYMENTS', 'VIEW_SUBSCRIBERS']);
    listAnswers();
    renderWithRoutes(routes, '/payments');
    await screen.findByRole('table', { name: 'Pagamentos' });

    await userEvent.click(await screen.findByRole('button', { name: 'Ações da cobrança de Marina Albuquerque, vencimento 12/09/2026' }));
    expect(screen.getAllByRole('menuitem').map((item) => item.textContent)).toEqual(['Atualizar com o gateway', 'Reembolsar']);
    await userEvent.keyboard('{Escape}');

    await userEvent.click(screen.getByRole('button', { name: 'Ações da cobrança de Lote 42 Serviços, vencimento 23/09/2026' }));
    expect(screen.getAllByRole('menuitem').map((item) => item.textContent)).toEqual(['Reenviar ao gateway', 'Confirmar pagamento manual']);
  });

  it('refunds after confirming', async () => {
    signInAs(['MANAGE_PAYMENTS', 'VIEW_PAYMENTS', 'VIEW_SUBSCRIBERS']);
    listAnswers();
    let refunded = false;
    server.use(http.post('/api/payments/pay-paid/refund', () => {
      refunded = true;
      return HttpResponse.json({ ...payments.paid, status: 'REFUNDED' });
    }));
    renderWithRoutes(routes, '/payments');

    await userEvent.click(await screen.findByRole('button', { name: 'Ações da cobrança de Marina Albuquerque, vencimento 12/09/2026' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Reembolsar' }));
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Reembolsar' }));

    await waitFor(() => expect(refunded).toBe(true));
    expect(await screen.findByText('Cobrança reembolsada.')).toBeInTheDocument();
  });

  it('offers no action to who only views payments', async () => {
    signInAs(['VIEW_PAYMENTS', 'VIEW_SUBSCRIBERS']);
    listAnswers();
    renderWithRoutes(routes, '/payments');
    await screen.findByRole('table', { name: 'Pagamentos' });

    expect(screen.queryByRole('button', { name: /ações da cobrança/i })).not.toBeInTheDocument();
  });

  it('filters by status', async () => {
    signInAs(['VIEW_PAYMENTS', 'VIEW_SUBSCRIBERS']);
    const asked: string[] = [];
    listAnswers();
    server.use(http.get('/api/payments', ({ request }) => {
      asked.push(decodeURIComponent(new URL(request.url).search));
      return HttpResponse.json(page([payments.paid]));
    }));
    renderWithRoutes(routes, '/payments');
    await screen.findByRole('table', { name: 'Pagamentos' });

    await userEvent.click(screen.getByRole('button', { name: 'Falhou' }));

    await waitFor(() => expect(asked.at(-1)).toContain('filter=status:FAILED'));
  });
});
