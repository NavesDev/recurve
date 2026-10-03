import { PERMISSIONS } from '../shared/constants/permissions';
import { ROUTES } from '../shared/constants/routes';
import type { NavEntry } from '../shared/layout/AppShell';

export const paymentNav: NavEntry = { to: ROUTES.payments, label: 'Pagamentos', icon: 'payments', permission: PERMISSIONS.VIEW_PAYMENTS };
