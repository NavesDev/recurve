import { act, renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { describe, expect, it } from 'vitest';
import type { ListingSpec } from './listing';
import { useListing } from './useListing';

const spec: ListingSpec = { defaultSort: 'name.keyword', sorts: ['name.keyword'], filters: ['active'] };

function setup(at: string) {
  let router!: ReturnType<typeof createMemoryRouter>;
  const wrapper = ({ children }: { children: ReactNode }) => {
    router ??= createMemoryRouter([{ path: '/plans', element: children }], { initialEntries: [at] });
    return <RouterProvider router={router} />;
  };
  const hook = renderHook(() => useListing(spec), { wrapper });
  return { hook, router: () => router };
}

describe('useListing', () => {
  it('reads the listing from the URL', () => {
    const { hook } = setup('/plans?q=pro&page=2');
    expect(hook.result.current.listing).toMatchObject({ q: 'pro', page: 2, sort: 'name.keyword' });
  });

  it('writes a change into the URL and goes back to the first page', () => {
    const { hook, router } = setup('/plans?page=3');

    act(() => hook.result.current.setFilter('active', ['true']));

    expect(router().state.location.search).toContain('filter=active%3Atrue');
    expect(hook.result.current.listing.page).toBe(0);
  });

  it('keeps the page when only the page changes', () => {
    const { hook } = setup('/plans');

    act(() => hook.result.current.setPage(4));

    expect(hook.result.current.listing.page).toBe(4);
  });
});
