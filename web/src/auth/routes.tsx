import type { RouteObject } from 'react-router';
import { ROUTE_PATTERNS } from '../shared/constants/routes';
import { LoginPage } from './pages/LoginPage';

export const authRoutes: RouteObject[] = [{ path: ROUTE_PATTERNS.login, element: <LoginPage /> }];
