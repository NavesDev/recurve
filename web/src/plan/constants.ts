import type { ListingSpec } from '../shared/api/listing';
import { PERMISSIONS } from '../shared/constants/permissions';
import { ROUTES } from '../shared/constants/routes';
import type { NavEntry } from '../shared/layout/AppShell';
import type { BillingInterval } from './domain';

export const planNav: NavEntry = { to: ROUTES.plans, label: 'Planos', icon: 'plans', permission: PERMISSIONS.VIEW_PLANS };

/** FR-06.2: what the plan listing offers. */
export const PLAN_LISTING: ListingSpec = {
  defaultSort: 'name.keyword',
  sorts: ['name.keyword', 'createdAt'],
  filters: ['activeIntervals', 'active'],
};

export const INTERVAL_ORDER: readonly BillingInterval[] = ['MONTHLY', 'YEARLY'];

export const INTERVAL_LABEL: Record<BillingInterval, string> = { MONTHLY: 'Mensal', YEARLY: 'Anual' };

/** Written after an amount, as in "R$ 49,90/mês". */
export const INTERVAL_UNIT: Record<BillingInterval, string> = { MONTHLY: 'mês', YEARLY: 'ano' };

/** NFR-06: the gateway charges in BRL only. */
export const PLAN_CURRENCY = 'BRL';

export const NAME_MAX_LENGTH = 120;
export const DESCRIPTION_MAX_LENGTH = 500;

/** How many plans a select or a name lookup loads: the server's page limit. */
export const PLAN_LOOKUP_SIZE = 100;
