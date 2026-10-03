import { lazy } from 'react';
import type { RouteObject } from 'react-router';
import { RequirePermission } from '../auth';
import { PERMISSIONS } from '../shared/constants/permissions';
import { ROUTE_PATTERNS } from '../shared/constants/routes';
import { paymentNav } from './constants';

// Each screen is its own chunk, loaded when first opened.
const PaymentListPage = lazy(() => import('./pages/PaymentListPage').then((module) => ({ default: module.PaymentListPage })));

export const paymentRoutes: RouteObject[] = [
  {
    path: ROUTE_PATTERNS.payments, handle: { section: paymentNav.label },
    element: <RequirePermission permission={PERMISSIONS.VIEW_PAYMENTS}><PaymentListPage /></RequirePermission>,
  },
];
