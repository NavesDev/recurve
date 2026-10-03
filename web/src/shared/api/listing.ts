import { DEFAULT_PAGE_SIZE, PAGE_SIZES } from '../constants/listing';

/** One listing request, as FR-06 and FR-07 shape it. */
export interface Listing {
  q: string;
  /** field → values; values of one field combine with OR, fields with AND. */
  filters: Record<string, string[]>;
  /** `field` or `field:asc|desc`. */
  sort: string;
  page: number;
  size: number;
}

/** What a listing offers: the fields the server accepts for it. */
export interface ListingSpec {
  defaultSort: string;
  sorts: readonly string[];
  filters: readonly string[];
}

/** The page envelope the server answers (FR-07.2). */
export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

/** The listing as query parameters — the same shape for the API and the browser's URL. */
export function toSearchParams(listing: Listing): URLSearchParams {
  const params = new URLSearchParams();
  if (listing.q.trim()) params.set('q', listing.q.trim());
  for (const [field, values] of Object.entries(listing.filters)) {
    if (values.length) params.append('filter', `${field}:${values.join(',')}`);
  }
  params.set('sort', listing.sort);
  params.set('page', String(listing.page));
  params.set('size', String(listing.size));
  return params;
}

function validSort(sort: string | null, spec: ListingSpec): string {
  if (!sort) return spec.defaultSort;
  const [field, direction, ...rest] = sort.split(':');
  const knownDirection = direction === undefined || direction === 'asc' || direction === 'desc';
  return field && spec.sorts.includes(field) && knownDirection && rest.length === 0 ? sort : spec.defaultSort;
}

function nonNegative(value: string | null): number {
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed >= 0 ? parsed : 0;
}

/**
 * The listing a URL asks for. Fail-closed: a field the listing does not
 * offer, a page out of range or a size not on offer is dropped for the
 * default, so a hand-edited URL never reaches the server as a 400.
 */
export function readListing(params: URLSearchParams, spec: ListingSpec): Listing {
  const filters: Record<string, string[]> = {};
  for (const raw of params.getAll('filter')) {
    const separator = raw.indexOf(':');
    const field = raw.slice(0, separator);
    const values = raw.slice(separator + 1).split(',').filter(Boolean);
    if (separator > 0 && spec.filters.includes(field) && values.length) filters[field] = values;
  }
  const size = Number(params.get('size'));
  return {
    q: params.get('q') ?? '',
    filters,
    sort: validSort(params.get('sort'), spec),
    page: nonNegative(params.get('page')),
    size: (PAGE_SIZES as readonly number[]).includes(size) ? size : DEFAULT_PAGE_SIZE,
  };
}
