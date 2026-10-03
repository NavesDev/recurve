import { PERMISSIONS } from '../shared/constants/permissions';
import { ROUTES } from '../shared/constants/routes';
import type { NavEntry } from '../shared/layout/AppShell';

export const userNav: NavEntry = { to: ROUTES.users, label: 'Operadores', icon: 'users', permission: PERMISSIONS.MANAGE_USERS };
