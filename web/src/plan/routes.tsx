import { lazy } from 'react';
import type { RouteObject } from 'react-router';
import { RequirePermission } from '../auth';
import { PERMISSIONS } from '../shared/constants/permissions';
import { ROUTE_PATTERNS } from '../shared/constants/routes';
import { planNav } from './constants';

// Each screen is its own chunk, loaded when first opened.
const PlanDetailPage = lazy(() => import('./pages/PlanDetailPage').then((module) => ({ default: module.PlanDetailPage })));
const PlanEditPage = lazy(() => import('./pages/PlanFormPage').then((module) => ({ default: module.PlanEditPage })));
const PlanNewPage = lazy(() => import('./pages/PlanFormPage').then((module) => ({ default: module.PlanNewPage })));
const PlanListPage = lazy(() => import('./pages/PlanListPage').then((module) => ({ default: module.PlanListPage })));

const handle = { section: planNav.label };

export const planRoutes: RouteObject[] = [
  { path: ROUTE_PATTERNS.plans, handle, element: <RequirePermission permission={PERMISSIONS.VIEW_PLANS}><PlanListPage /></RequirePermission> },
  { path: ROUTE_PATTERNS.planNew, handle, element: <RequirePermission permission={PERMISSIONS.MANAGE_PLANS}><PlanNewPage /></RequirePermission> },
  { path: ROUTE_PATTERNS.planDetail, handle, element: <RequirePermission permission={PERMISSIONS.VIEW_PLANS}><PlanDetailPage /></RequirePermission> },
  { path: ROUTE_PATTERNS.planEdit, handle, element: <RequirePermission permission={PERMISSIONS.MANAGE_PLANS}><PlanEditPage /></RequirePermission> },
];
