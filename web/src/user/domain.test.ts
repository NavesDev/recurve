import { describe, expect, it } from 'vitest';
import { accessSummary, levelOf, permissionsFrom, profilePermissions } from './domain';

describe('access by area (BR-01)', () => {
  it('reads the level of each area from the permissions', () => {
    const permissions = ['MANAGE_PLANS', 'VIEW_SUBSCRIBERS'] as const;
    expect(levelOf(permissions, 'plans')).toBe('manage');
    expect(levelOf(permissions, 'subscribers')).toBe('view');
    expect(levelOf(permissions, 'payments')).toBe('none');
    expect(levelOf(['VIEW_PLANS', 'MANAGE_PLANS'], 'plans')).toBe('manage');
  });

  it('writes the fewest permissions that grant the chosen levels: manage implies view', () => {
    expect(permissionsFrom({ plans: 'manage', subscribers: 'view', payments: 'none', users: 'none', system: 'manage' }))
      .toEqual(['MANAGE_PLANS', 'VIEW_SUBSCRIBERS', 'MANAGE_SYSTEM']);
  });

  it('summarizes access as the prototype does', () => {
    expect(accessSummary(profilePermissions('Administrador'))).toBe('Acesso total');
    expect(accessSummary(['VIEW_PLANS'])).toBe('1 de 5 áreas');
    expect(accessSummary([])).toBe('Sem acesso');
  });

  it('fills a profile, which is only a shortcut', () => {
    expect(profilePermissions('Financeiro')).toEqual(['MANAGE_PLANS', 'VIEW_SUBSCRIBERS', 'MANAGE_PAYMENTS']);
    expect(profilePermissions('Leitura')).toEqual(['VIEW_PLANS', 'VIEW_SUBSCRIBERS', 'VIEW_PAYMENTS']);
  });
});
