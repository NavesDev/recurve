import type { RouteObject } from 'react-router';
import { RequirePermission } from '../auth';
import { PERMISSIONS } from '../shared/constants/permissions';
import { ROUTE_PATTERNS } from '../shared/constants/routes';
import { planNav } from './constants';
import { PlanDetailPage } from './pages/PlanDetailPage';
import { PlanEditPage, PlanNewPage } from './pages/PlanFormPage';
import { PlanListPage } from './pages/PlanListPage';

const handle = { section: planNav.label };

export const planRoutes: RouteObject[] = [
  { path: ROUTE_PATTERNS.plans, handle, element: <RequirePermission permission={PERMISSIONS.VIEW_PLANS}><PlanListPage /></RequirePermission> },
  { path: ROUTE_PATTERNS.planNew, handle, element: <RequirePermission permission={PERMISSIONS.MANAGE_PLANS}><PlanNewPage /></RequirePermission> },
  { path: ROUTE_PATTERNS.planDetail, handle, element: <RequirePermission permission={PERMISSIONS.VIEW_PLANS}><PlanDetailPage /></RequirePermission> },
  { path: ROUTE_PATTERNS.planEdit, handle, element: <RequirePermission permission={PERMISSIONS.MANAGE_PLANS}><PlanEditPage /></RequirePermission> },
];
