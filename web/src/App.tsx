import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createBrowserRouter, Link, RouterProvider, type RouteObject } from 'react-router';
import { AuthBridge, authRoutes, can, StatusScreen, useMe } from './auth';
import { OverviewPage, overviewNav } from './overview';
import { ApiError } from './shared/api/ApiError';
import { paymentNav } from './payment';
import { planNav, planRoutes } from './plan';
import { ROUTES } from './shared/constants/routes';
import { ToastProvider } from './shared/design/Toast';
import { Shell } from './Shell';
import { subscriberNav } from './subscriber';
import { systemNav } from './system';
import { userNav } from './user';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 0,
      refetchOnWindowFocus: false,
      // Network and server failures get one more try; a 4xx is an answer.
      retry: (failures, error) => failures < 1 && !(error instanceof ApiError && error.status >= 400 && error.status < 500),
    },
  },
});

function Overview() {
  const { data: me } = useMe();
  const shortcuts = [planNav, subscriberNav, paymentNav, userNav, systemNav]
    .filter((entry) => !entry.permission || can(me, entry.permission));
  return <OverviewPage shortcuts={shortcuts} />;
}

const NotFound = () => (
  <StatusScreen code="404" title="Página não encontrada" text="O endereço não existe ou mudou."
    action={<Link to={ROUTES.home}>Voltar ao início</Link>} />
);

const routes: RouteObject[] = [
  ...authRoutes,
  {
    path: ROUTES.home,
    element: <Shell />,
    children: [
      { index: true, element: <Overview />, handle: { section: overviewNav.label } },
      ...planRoutes,
      { path: '*', element: <NotFound /> },
    ],
  },
];

const router = createBrowserRouter(routes);

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <AuthBridge>
        <ToastProvider>
          <RouterProvider router={router} />
        </ToastProvider>
      </AuthBridge>
    </QueryClientProvider>
  );
}
