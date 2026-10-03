import { PERMISSIONS } from '../shared/constants/permissions';
import { ROUTES } from '../shared/constants/routes';
import type { NavEntry } from '../shared/layout/AppShell';

export const systemNav: NavEntry = { to: ROUTES.system, label: 'Sistema', icon: 'system', permission: PERMISSIONS.MANAGE_SYSTEM };
