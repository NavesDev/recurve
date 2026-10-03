import { lazy } from 'react';
import type { RouteObject } from 'react-router';
import { RequirePermission } from '../auth';
import { PERMISSIONS } from '../shared/constants/permissions';
import { ROUTE_PATTERNS } from '../shared/constants/routes';
import { subscriberNav } from './constants';

// Each screen is its own chunk, loaded when first opened.
const SubscriberDetailPage = lazy(() => import('./pages/SubscriberDetailPage').then((module) => ({ default: module.SubscriberDetailPage })));
const SubscriberEditPage = lazy(() => import('./pages/SubscriberFormPage').then((module) => ({ default: module.SubscriberEditPage })));
const SubscriberNewPage = lazy(() => import('./pages/SubscriberFormPage').then((module) => ({ default: module.SubscriberNewPage })));
const SubscriberListPage = lazy(() => import('./pages/SubscriberListPage').then((module) => ({ default: module.SubscriberListPage })));

const handle = { section: subscriberNav.label };
const view = PERMISSIONS.VIEW_SUBSCRIBERS;
const manage = PERMISSIONS.MANAGE_SUBSCRIBERS;

export const subscriberRoutes: RouteObject[] = [
  { path: ROUTE_PATTERNS.subscribers, handle, element: <RequirePermission permission={view}><SubscriberListPage /></RequirePermission> },
  { path: ROUTE_PATTERNS.subscriberNew, handle, element: <RequirePermission permission={manage}><SubscriberNewPage /></RequirePermission> },
  { path: ROUTE_PATTERNS.subscriberDetail, handle, element: <RequirePermission permission={view}><SubscriberDetailPage /></RequirePermission> },
  { path: ROUTE_PATTERNS.subscriberEdit, handle, element: <RequirePermission permission={manage}><SubscriberEditPage /></RequirePermission> },
];
