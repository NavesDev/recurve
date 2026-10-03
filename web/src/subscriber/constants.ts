import { PERMISSIONS } from '../shared/constants/permissions';
import { ROUTES } from '../shared/constants/routes';
import type { NavEntry } from '../shared/layout/AppShell';

export const subscriberNav: NavEntry = {
  to: ROUTES.subscribers, label: 'Assinantes', icon: 'subscribers', permission: PERMISSIONS.VIEW_SUBSCRIBERS,
};
