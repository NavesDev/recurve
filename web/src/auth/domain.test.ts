import { describe, expect, it } from 'vitest';
import { can, type Me } from './domain';

const me: Me = { id: '1', name: 'Ada', email: 'ada@recurve.local', permissions: ['VIEW_PLANS'] };

describe('can', () => {
  it('is yes for a permission the operator holds', () => {
    expect(can(me, 'VIEW_PLANS')).toBe(true);
  });

  it('is no for one they do not', () => {
    expect(can(me, 'MANAGE_PLANS')).toBe(false);
  });

  it('is no while the operator is unknown (fail-closed)', () => {
    expect(can(undefined, 'VIEW_PLANS')).toBe(false);
    expect(can(null, 'VIEW_PLANS')).toBe(false);
  });
});
