import { useCallback, useMemo } from 'react';
import { useSearchParams } from 'react-router';
import { readListing, toSearchParams, type Listing, type ListingSpec } from './listing';

/**
 * A table's search, filters, sort and page, kept in the URL: a listing can
 * be linked, reloaded and gone back to. Any change but the page returns to
 * the first page, where the new results start.
 */
export function useListing(spec: ListingSpec) {
  const [params, setParams] = useSearchParams();
  const listing = useMemo(() => readListing(params, spec), [params, spec]);

  const update = useCallback((patch: Partial<Listing>) => {
    const next = { ...listing, page: 0, ...patch };
    setParams(toSearchParams(next), { replace: true });
  }, [listing, setParams]);

  return {
    listing,
    setQuery: (q: string) => update({ q }),
    setFilter: (field: string, values: string[]) => update({ filters: { ...listing.filters, [field]: values } }),
    setSort: (sort: string) => update({ sort }),
    setPage: (page: number) => update({ page }),
    setSize: (size: number) => update({ size }),
  };
}
