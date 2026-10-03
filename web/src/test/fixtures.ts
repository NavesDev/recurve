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
