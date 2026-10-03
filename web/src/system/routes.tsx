import { lazy } from 'react';
import type { RouteObject } from 'react-router';
import { RequirePermission } from '../auth';
import { PERMISSIONS } from '../shared/constants/permissions';
import { ROUTE_PATTERNS } from '../shared/constants/routes';
import { systemNav } from './constants';

// Each screen is its own chunk, loaded when first opened.
const SystemPage = lazy(() => import('./pages/SystemPage').then((module) => ({ default: module.SystemPage })));

export const systemRoutes: RouteObject[] = [
  {
    path: ROUTE_PATTERNS.system, handle: { section: systemNav.label },
    element: <RequirePermission permission={PERMISSIONS.MANAGE_SYSTEM}><SystemPage /></RequirePermission>,
  },
];
