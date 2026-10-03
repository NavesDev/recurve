import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import type { ReactElement } from 'react';
import { createMemoryRouter, RouterProvider, type RouteObject } from 'react-router';
import { ToastProvider } from '../shared/design/Toast';

interface Options {
  /** Where the browser starts. */
  at?: string;
  /** Extra routes beside the one under test, e.g. where a redirect lands. */
  routes?: RouteObject[];
  /** The path the element is mounted on; defaults to `at` without its query. */
  path?: string;
}

export function testQueryClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
}

/** Renders inside the app's providers and a memory router. Answers the router to inspect navigation. */
export function renderWithApp(element: ReactElement, { at = '/', routes = [], path }: Options = {}) {
  const queryClient = testQueryClient();
  const router = createMemoryRouter(
    [{ path: path ?? at.split('?')[0], element }, ...routes],
    { initialEntries: [at] },
  );
  const view = render(
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <RouterProvider router={router} />
      </ToastProvider>
    </QueryClientProvider>,
  );
  return { ...view, router, queryClient };
}

export function renderWithRoutes(routes: RouteObject[], at: string) {
  const queryClient = testQueryClient();
  const router = createMemoryRouter(routes, { initialEntries: [at] });
  const view = render(
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <RouterProvider router={router} />
      </ToastProvider>
    </QueryClientProvider>,
  );
  return { ...view, router, queryClient };
}

export type { ReactElement };
