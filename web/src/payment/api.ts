import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { http } from '../shared/api/http';
import { toSearchParams, type Listing, type Page } from '../shared/api/listing';
import { QUERY_ROOTS } from '../shared/constants/query';
import { PAYMENT_LISTING } from './constants';
import type { Payer, Payment } from './domain';

export const paymentKeys = {
  all: [QUERY_ROOTS.payments] as const,
  list: (listing: Listing) => [...paymentKeys.all, 'list', listing] as const,
  payers: (ids: readonly string[]) => [...paymentKeys.all, 'payers', ids] as const,
};

export function usePaymentList(listing: Listing, enabled = true) {
  return useQuery({
    queryKey: paymentKeys.list(listing),
    queryFn: ({ signal }) => http.get<Page<Payment>>(`/api/payments?${toSearchParams(listing)}`, { signal }),
    placeholderData: keepPreviousData,
    enabled,
  });
}

/** One subscriber's charges, newest due first. */
export function subscriberPaymentsListing(subscriberId: string, page = 0): Listing {
  return { q: '', filters: { subscriberId: [subscriberId] }, sort: PAYMENT_LISTING.defaultSort, page, size: 10 };
}

/**
 * The names behind a page of charges: one request for the whole page
 * (`filter=id:a,b,c`), since the payment index keeps only the id — a
 * subscriber's name changes (FR-03.7). Off when the operator may not
 * view subscribers: then charges show without names.
 */
export function usePayers(ids: readonly string[], enabled: boolean) {
  const unique = [...new Set(ids)].sort();
  return useQuery({
    queryKey: paymentKeys.payers(unique),
    queryFn: async ({ signal }) => {
      const params = toSearchParams({ q: '', filters: { id: unique }, sort: 'startedAt:desc', page: 0, size: unique.length });
      const found = await http.get<Page<Payer>>(`/api/subscribers?${params}`, { signal });
      return new Map(found.items.map((payer) => [payer.id, payer]));
    },
    enabled: enabled && unique.length > 0,
    placeholderData: keepPreviousData,
  });
}

/** A charge changes its subscriber too (status, next billing date): both caches go. */
function useInvalidateAfterPayment() {
  const queryClient = useQueryClient();
  return () => Promise.all([
    queryClient.invalidateQueries({ queryKey: paymentKeys.all }),
    queryClient.invalidateQueries({ queryKey: [QUERY_ROOTS.subscribers] }),
  ]);
}

export function useRequestPayment() {
  const invalidate = useInvalidateAfterPayment();
  return useMutation({
    mutationFn: (subscriberId: string) => http.post<Payment>('/api/payments', { subscriberId }),
    onSettled: invalidate,
  });
}

export type PaymentAction = 'send' | 'sync' | 'confirm' | 'refund';

export function usePaymentAction() {
  const invalidate = useInvalidateAfterPayment();
  return useMutation({
    mutationFn: ({ payment, action }: { payment: Payment; action: PaymentAction }) =>
      http.post<Payment>(`/api/payments/${encodeURIComponent(payment.id)}/${action}`),
    onSettled: invalidate,
  });
}
