import { lazy } from 'react';
import type { RouteObject } from 'react-router';
import { RequirePermission } from '../auth';
import { PERMISSIONS } from '../shared/constants/permissions';
import { ROUTE_PATTERNS } from '../shared/constants/routes';
import { userNav } from './constants';

// Each screen is its own chunk, loaded when first opened.
const UserEditPage = lazy(() => import('./pages/UserFormPage').then((module) => ({ default: module.UserEditPage })));
const UserNewPage = lazy(() => import('./pages/UserFormPage').then((module) => ({ default: module.UserNewPage })));
const UserListPage = lazy(() => import('./pages/UserListPage').then((module) => ({ default: module.UserListPage })));

const handle = { section: userNav.label };
const guard = (element: React.ReactNode) => <RequirePermission permission={PERMISSIONS.MANAGE_USERS}>{element}</RequirePermission>;

/** BR-01: seeing operators is managing them; every route needs MANAGE_USERS. */
export const userRoutes: RouteObject[] = [
  { path: ROUTE_PATTERNS.users, handle, element: guard(<UserListPage />) },
  { path: ROUTE_PATTERNS.userNew, handle, element: guard(<UserNewPage />) },
  { path: ROUTE_PATTERNS.userEdit, handle, element: guard(<UserEditPage />) },
];
