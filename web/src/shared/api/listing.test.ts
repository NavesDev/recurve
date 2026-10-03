import { describe, expect, it } from 'vitest';
import { readListing, toSearchParams, type ListingSpec } from './listing';

const spec: ListingSpec = {
  defaultSort: 'startedAt:desc',
  sorts: ['startedAt', 'price'],
  filters: ['status', 'planId'],
};

describe('toSearchParams', () => {
  it('writes the server\'s listing contract (FR-06, FR-07)', () => {
    const params = toSearchParams({
      q: 'ana', filters: { status: ['ACTIVE', 'PAST_DUE'], planId: ['p1'] }, sort: 'price:asc', page: 2, size: 50,
    });

    expect(params.get('q')).toBe('ana');
    expect(params.getAll('filter')).toEqual(['status:ACTIVE,PAST_DUE', 'planId:p1']);
    expect(params.get('sort')).toBe('price:asc');
    expect(params.get('page')).toBe('2');
    expect(params.get('size')).toBe('50');
  });

  it('leaves out an empty search and empty filters', () => {
    const params = toSearchParams({ q: '  ', filters: { status: [] }, sort: 'price:asc', page: 0, size: 20 });

    expect(params.has('q')).toBe(false);
    expect(params.has('filter')).toBe(false);
  });
});

describe('readListing', () => {
  it('reads what toSearchParams wrote', () => {
    const listing = { q: 'ana', filters: { status: ['ACTIVE'] }, sort: 'price:desc', page: 1, size: 10 };

    expect(readListing(toSearchParams(listing), spec)).toEqual(listing);
  });

  it('falls back to the defaults on an empty URL', () => {
    expect(readListing(new URLSearchParams(), spec)).toEqual({
      q: '', filters: {}, sort: 'startedAt:desc', page: 0, size: 20,
    });
  });

  it('drops what the listing does not offer, rather than sending it (fail-closed)', () => {
    const listing = readListing(
      new URLSearchParams('filter=email:x&filter=status:ACTIVE&sort=email:asc&page=-3&size=999'),
      spec,
    );

    expect(listing.filters).toEqual({ status: ['ACTIVE'] });
    expect(listing.sort).toBe('startedAt:desc');
    expect(listing.page).toBe(0);
    expect(listing.size).toBe(20);
  });

  it('accepts a sort on an allowed field in either direction', () => {
    expect(readListing(new URLSearchParams('sort=price'), spec).sort).toBe('price');
    expect(readListing(new URLSearchParams('sort=price:desc'), spec).sort).toBe('price:desc');
    expect(readListing(new URLSearchParams('sort=price:sideways'), spec).sort).toBe('startedAt:desc');
  });
});
