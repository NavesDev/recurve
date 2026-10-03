/** Server-shaped payloads for component tests. */
export const plans = {
  pro: {
    id: 'plan-pro', name: 'Profissional', description: 'Times de até 10 pessoas', active: true,
    createdAt: '2026-01-10T12:00:00Z',
    prices: [
      { id: 'price-pro-m', price: 129.9, currency: 'BRL', interval: 'MONTHLY', active: true, createdAt: '2026-01-10T12:00:00Z' },
      { id: 'price-pro-old', price: 99.9, currency: 'BRL', interval: 'MONTHLY', active: false, createdAt: '2025-06-01T12:00:00Z' },
    ],
  },
  legacy: {
    id: 'plan-legacy', name: 'Legado 2023', description: null, active: false, createdAt: '2023-01-01T12:00:00Z',
    prices: [{ id: 'price-legacy', price: 79, currency: 'BRL', interval: 'MONTHLY', active: true, createdAt: '2023-01-01T12:00:00Z' }],
  },
};

export function page<T>(items: T[], total = items.length, pageNumber = 0, size = 20) {
  return { items, page: pageNumber, size, total };
}

export const subscribers = {
  marina: {
    id: 'sub-marina', name: 'Marina Albuquerque', email: 'marina@ateliermb.com.br', document: '52998224725',
    status: 'ACTIVE', planId: 'plan-pro', planPriceId: 'price-pro-m', price: 129.9, currency: 'BRL', interval: 'MONTHLY',
    startedAt: '2026-03-12T12:00:00Z', nextBillingAt: '2026-10-12T12:00:00Z', canceledAt: null, createdAt: '2026-03-12T12:00:00Z',
  },
  lote: {
    id: 'sub-lote', name: 'Lote 42 Serviços', email: 'pagamentos@lote42.com.br', document: '11222333000181',
    status: 'PAST_DUE', planId: 'plan-pro', planPriceId: 'price-pro-m', price: 129.9, currency: 'BRL', interval: 'MONTHLY',
    startedAt: '2025-08-23T12:00:00Z', nextBillingAt: '2026-09-23T12:00:00Z', canceledAt: null, createdAt: '2025-08-23T12:00:00Z',
  },
  paula: {
    id: 'sub-paula', name: 'Paula Reis', email: 'paula.reis@outlook.com', document: '52998224725',
    status: 'CANCELED', planId: 'plan-pro', planPriceId: 'price-pro-m', price: 129.9, currency: 'BRL', interval: 'MONTHLY',
    startedAt: '2025-12-11T12:00:00Z', nextBillingAt: '2026-09-11T12:00:00Z', canceledAt: '2026-09-01T12:00:00Z', createdAt: '2025-12-11T12:00:00Z',
  },
};

export const payments = {
  pending: {
    id: 'pay-pending', subscriberId: 'sub-marina', amount: 129.9, currency: 'BRL', status: 'PENDING',
    dueAt: '2026-10-12T12:00:00Z', paidAt: null, refundedAt: null, externalId: 'pay_123', invoiceUrl: 'https://sandbox.asaas.com/i/123',
    createdAt: '2026-10-01T12:00:00Z',
  },
  unsent: {
    id: 'pay-unsent', subscriberId: 'sub-lote', amount: 129.9, currency: 'BRL', status: 'PENDING',
    dueAt: '2026-09-23T12:00:00Z', paidAt: null, refundedAt: null, externalId: null, invoiceUrl: null,
    createdAt: '2026-09-20T12:00:00Z',
  },
  paid: {
    id: 'pay-paid', subscriberId: 'sub-marina', amount: 129.9, currency: 'BRL', status: 'PAID',
    dueAt: '2026-09-12T12:00:00Z', paidAt: '2026-09-10T15:00:00Z', refundedAt: null, externalId: 'pay_122', invoiceUrl: 'https://sandbox.asaas.com/i/122',
    createdAt: '2026-09-01T12:00:00Z',
  },
};

export const users = {
  joana: {
    id: 'me', name: 'Joana Martins', email: 'joana@recurve.app', active: true, createdAt: '2026-01-01T12:00:00Z',
    permissions: ['MANAGE_USERS', 'MANAGE_PLANS', 'MANAGE_SUBSCRIBERS', 'MANAGE_PAYMENTS', 'MANAGE_SYSTEM'],
  },
  diego: {
    id: 'user-diego', name: 'Diego Póvoa', email: 'diego@recurve.app', active: true, createdAt: '2026-02-01T12:00:00Z',
    permissions: ['MANAGE_PLANS', 'VIEW_SUBSCRIBERS', 'MANAGE_PAYMENTS'],
  },
  caio: {
    id: 'user-caio', name: 'Caio Bastos', email: 'caio@recurve.app', active: false, createdAt: '2025-07-01T12:00:00Z',
    permissions: [],
  },
};
