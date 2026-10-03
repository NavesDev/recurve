import { describe, expect, it } from 'vitest';
import { formatMoney, parseMoney } from './money';

// Intl separates the symbol with a no-break space; the tests normalize it.
const plain = (text: string) => text.replace(/\u00a0/g, ' ');

describe('formatMoney', () => {
  it('writes BRL the Brazilian way', () => {
    expect(plain(formatMoney(1299.9, 'BRL'))).toBe('R$ 1.299,90');
  });

  it('always shows two decimal places (NFR-06)', () => {
    expect(plain(formatMoney(49, 'BRL'))).toBe('R$ 49,00');
  });

  it('writes another currency with its own code', () => {
    expect(plain(formatMoney(10, 'USD'))).toBe('US$ 10,00');
  });
});

describe('parseMoney', () => {
  it('reads an amount typed the Brazilian way', () => {
    expect(parseMoney('129,90')).toBe(129.9);
    expect(parseMoney('1.299,90')).toBe(1299.9);
    expect(parseMoney(' 49 ')).toBe(49);
    expect(parseMoney('49.9')).toBe(49.9);
  });

  it('refuses what is not an amount with at most two decimal places (NFR-06)', () => {
    expect(parseMoney('')).toBeNull();
    expect(parseMoney('abc')).toBeNull();
    expect(parseMoney('1,999')).toBeNull();
    expect(parseMoney('-5')).toBeNull();
  });
});
