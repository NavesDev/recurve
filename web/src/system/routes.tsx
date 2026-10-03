import type { RouteObject } from 'react-router';
import { RequirePermission } from '../auth';
import { PERMISSIONS } from '../shared/constants/permissions';
import { ROUTE_PATTERNS } from '../shared/constants/routes';
import { systemNav } from './constants';
import { SystemPage } from './pages/SystemPage';

export const systemRoutes: RouteObject[] = [
  {
    path: ROUTE_PATTERNS.system, handle: { section: systemNav.label },
    element: <RequirePermission permission={PERMISSIONS.MANAGE_SYSTEM}><SystemPage /></RequirePermission>,
  },
];
