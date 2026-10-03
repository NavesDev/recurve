import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { http } from '../shared/api/http';
import { toSearchParams, type Listing, type Page } from '../shared/api/listing';
import { QUERY_ROOTS } from '../shared/constants/query';
import type { NewSubscriber, Subscriber, SubscriberInput } from './domain';

export const subscriberKeys = {
  all: [QUERY_ROOTS.subscribers] as const,
  list: (listing: Listing) => [...subscriberKeys.all, 'list', listing] as const,
  detail: (id: string) => [...subscriberKeys.all, 'detail', id] as const,
};

export function useSubscriberList(listing: Listing) {
  return useQuery({
    queryKey: subscriberKeys.list(listing),
    queryFn: ({ signal }) => http.get<Page<Subscriber>>(`/api/subscribers?${toSearchParams(listing)}`, { signal }),
    placeholderData: keepPreviousData,
  });
}

export function useSubscriber(id: string) {
  return useQuery({
    queryKey: subscriberKeys.detail(id),
    queryFn: ({ signal }) => http.get<Subscriber>(`/api/subscribers/${encodeURIComponent(id)}`, { signal }),
  });
}

/** A subscriber change moves the plan's subscription count too (BR-10). */
function useInvalidate() {
  const queryClient = useQueryClient();
  return () => Promise.all([
    queryClient.invalidateQueries({ queryKey: subscriberKeys.all }),
    queryClient.invalidateQueries({ queryKey: [QUERY_ROOTS.plans] }),
  ]);
}

export function useCreateSubscriber() {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: (subscriber: NewSubscriber) => http.post<Subscriber>('/api/subscribers', subscriber),
    onSuccess: invalidate,
  });
}

export function useUpdateSubscriber(id: string) {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: (subscriber: SubscriberInput) => http.put<Subscriber>(`/api/subscribers/${encodeURIComponent(id)}`, subscriber),
    onSuccess: invalidate,
  });
}

export function useCancelSubscriber(id: string) {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: () => http.delete<Subscriber>(`/api/subscribers/${encodeURIComponent(id)}`),
    onSuccess: invalidate,
  });
}
