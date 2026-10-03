import { PERMISSIONS } from '../shared/constants/permissions';
import { ROUTES } from '../shared/constants/routes';
import type { NavEntry } from '../shared/layout/AppShell';

export const systemNav: NavEntry = { to: ROUTES.system, label: 'Sistema', icon: 'system', permission: PERMISSIONS.MANAGE_SYSTEM };

export type IndexName = 'plans' | 'subscribers' | 'users' | 'payments';

/** FR-01.5: the search indexes, rebuilt in this order by "reindex all". */
export const INDEXES: readonly { key: IndexName; label: string; hint: string }[] = [
  { key: 'plans', label: 'Planos', hint: 'Nome e preços' },
  { key: 'subscribers', label: 'Assinantes', hint: 'Cliente, e-mail e plano' },
  { key: 'users', label: 'Operadores', hint: 'Usuários do painel' },
  { key: 'payments', label: 'Pagamentos', hint: 'Cobranças e seus status' },
];
