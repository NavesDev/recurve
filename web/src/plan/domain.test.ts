import { describe, expect, it } from 'vitest';
import {
  activePrices, canAddPrice, canDeactivatePrice, canReplacePrice, freeIntervals, priceSummary, type Plan, type Price,
} from './domain';

const price = (overrides: Partial<Price>): Price => ({
  id: 'p', price: 49.9, currency: 'BRL', interval: 'MONTHLY', active: true, createdAt: '2026-01-01T00:00:00Z', ...overrides,
});

const plan = (overrides: Partial<Plan> = {}): Plan => ({
  id: '1', name: 'Pro', description: null, active: true, createdAt: '2026-01-01T00:00:00Z',
  prices: [
    price({ id: 'm1', price: 39.9, active: false, createdAt: '2025-01-01T00:00:00Z' }),
    price({ id: 'm2' }),
    price({ id: 'y1', price: 499, interval: 'YEARLY' }),
  ],
  ...overrides,
});

const plain = (text: string) => text.replace(/\u00a0/g, ' ');

describe('plan rules', () => {
  it('lists the active prices, monthly first', () => {
    expect(activePrices(plan()).map((p) => p.id)).toEqual(['m2', 'y1']);
  });

  it('summarizes what a plan charges (BR-03)', () => {
    expect(plain(priceSummary(plan()))).toBe('R$ 49,90/mês · R$ 499,00/ano');
    expect(priceSummary(plan({ prices: [] }))).toBe('Sem preço ativo');
  });

  it('knows which cycles still have no active price (BR-03: at most one per cycle)', () => {
    expect(freeIntervals(plan())).toEqual([]);
    expect(freeIntervals(plan({ prices: [price({ id: 'm2' })] }))).toEqual(['YEARLY']);
  });

  it('takes a new price only while the plan is active and a cycle is free (FR-02.4)', () => {
    expect(canAddPrice(plan({ prices: [price({})] }))).toBe(true);
    expect(canAddPrice(plan())).toBe(false);
    expect(canAddPrice(plan({ active: false, prices: [] }))).toBe(false);
  });

  it('replaces only an active price of an active plan (FR-02.7)', () => {
    expect(canReplacePrice(plan(), price({ active: true }))).toBe(true);
    expect(canReplacePrice(plan(), price({ active: false }))).toBe(false);
    expect(canReplacePrice(plan({ active: false }), price({ active: true }))).toBe(false);
  });

  it('deactivates only an active price (FR-02.3)', () => {
    expect(canDeactivatePrice(price({ active: true }))).toBe(true);
    expect(canDeactivatePrice(price({ active: false }))).toBe(false);
  });
});
