import type { RouteObject } from 'react-router';
import { RequirePermission } from '../auth';
import { PERMISSIONS } from '../shared/constants/permissions';
import { ROUTE_PATTERNS } from '../shared/constants/routes';
import { subscriberNav } from './constants';
import { SubscriberDetailPage } from './pages/SubscriberDetailPage';
import { SubscriberEditPage, SubscriberNewPage } from './pages/SubscriberFormPage';
import { SubscriberListPage } from './pages/SubscriberListPage';

const handle = { section: subscriberNav.label };
const view = PERMISSIONS.VIEW_SUBSCRIBERS;
const manage = PERMISSIONS.MANAGE_SUBSCRIBERS;

export const subscriberRoutes: RouteObject[] = [
  { path: ROUTE_PATTERNS.subscribers, handle, element: <RequirePermission permission={view}><SubscriberListPage /></RequirePermission> },
  { path: ROUTE_PATTERNS.subscriberNew, handle, element: <RequirePermission permission={manage}><SubscriberNewPage /></RequirePermission> },
  { path: ROUTE_PATTERNS.subscriberDetail, handle, element: <RequirePermission permission={view}><SubscriberDetailPage /></RequirePermission> },
  { path: ROUTE_PATTERNS.subscriberEdit, handle, element: <RequirePermission permission={manage}><SubscriberEditPage /></RequirePermission> },
];
