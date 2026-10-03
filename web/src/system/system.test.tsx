import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { signInAs } from '../test/auth';
import { renderWithRoutes } from '../test/render';
import { server } from '../test/server';
import { systemRoutes } from './routes';

const routes = [...systemRoutes, { path: '/login', element: <p>entrar</p> }];

describe('system (FR-01.5)', () => {
  it('rebuilds every index in turn and tells how many documents each has', async () => {
    signInAs(['MANAGE_SYSTEM']);
    const order: string[] = [];
    for (const [entity, indexed] of [['plans', 4], ['subscribers', 1284], ['users', 5], ['payments', 24817]] as const) {
      server.use(http.post(`/api/${entity}/reindex`, () => {
        order.push(entity);
        return HttpResponse.json({ indexed });
      }));
    }
    renderWithRoutes(routes, '/system');

    await userEvent.click(await screen.findByRole('button', { name: 'Reindexar tudo' }));

    await waitFor(() => expect(order).toEqual(['plans', 'subscribers', 'users', 'payments']));
    expect(await screen.findByText('24.817 documentos indexados')).toBeInTheDocument();
    expect(screen.getByText('1.284 documentos indexados')).toBeInTheDocument();
  });

  it('reports a failed rebuild and still runs the rest', async () => {
    signInAs(['MANAGE_SYSTEM']);
    server.use(
      http.post('/api/plans/reindex', () => HttpResponse.json({ message: 'A backing service is unavailable' }, { status: 503 })),
      http.post('/api/subscribers/reindex', () => HttpResponse.json({ indexed: 1 })),
      http.post('/api/users/reindex', () => HttpResponse.json({ indexed: 1 })),
      http.post('/api/payments/reindex', () => HttpResponse.json({ indexed: 1 })),
    );
    renderWithRoutes(routes, '/system');

    await userEvent.click(await screen.findByRole('button', { name: 'Reindexar tudo' }));

    expect(await screen.findByText('O servidor está indisponível no momento. Tente de novo em instantes.')).toBeInTheDocument();
    expect(await screen.findAllByText('1 documento indexado')).toHaveLength(3);
  });

  it('is closed without MANAGE_SYSTEM', async () => {
    signInAs(['MANAGE_USERS']);
    renderWithRoutes(routes, '/system');

    expect(await screen.findByRole('heading', { name: 'Sem permissão' })).toBeInTheDocument();
  });
});
