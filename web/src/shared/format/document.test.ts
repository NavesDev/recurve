import { describe, expect, it } from 'vitest';
import { digitsOf, formatDocument } from './document';

describe('formatDocument', () => {
  it('masks a CPF', () => {
    expect(formatDocument('52998224725')).toBe('529.982.247-25');
  });

  it('masks a CNPJ', () => {
    expect(formatDocument('11222333000181')).toBe('11.222.333/0001-81');
  });

  it('masks what has been typed so far', () => {
    expect(formatDocument('5299822')).toBe('529.982.2');
    expect(formatDocument('112223330001')).toBe('11.222.333/0001');
  });

  it('ignores anything but digits', () => {
    expect(formatDocument('529.982.247-25')).toBe('529.982.247-25');
  });
});

describe('digitsOf', () => {
  it('keeps the digits only, which is what the server stores', () => {
    expect(digitsOf('11.222.333/0001-81')).toBe('11222333000181');
  });
});
