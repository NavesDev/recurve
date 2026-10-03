import type { RouteObject } from 'react-router';
import { RequirePermission } from '../auth';
import { PERMISSIONS } from '../shared/constants/permissions';
import { ROUTE_PATTERNS } from '../shared/constants/routes';
import { paymentNav } from './constants';
import { PaymentListPage } from './pages/PaymentListPage';

export const paymentRoutes: RouteObject[] = [
  {
    path: ROUTE_PATTERNS.payments, handle: { section: paymentNav.label },
    element: <RequirePermission permission={PERMISSIONS.VIEW_PAYMENTS}><PaymentListPage /></RequirePermission>,
  },
];
