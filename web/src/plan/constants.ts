import { PERMISSIONS } from '../shared/constants/permissions';
import { ROUTES } from '../shared/constants/routes';
import type { NavEntry } from '../shared/layout/AppShell';

export const planNav: NavEntry = { to: ROUTES.plans, label: 'Planos', icon: 'plans', permission: PERMISSIONS.VIEW_PLANS };
