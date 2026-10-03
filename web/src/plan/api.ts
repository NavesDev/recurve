import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { http } from '../shared/api/http';
import { toSearchParams, type Listing, type Page } from '../shared/api/listing';
import { PLAN_LISTING, PLAN_LOOKUP_SIZE } from './constants';
import type { Plan, PlanInput, Price, PriceInput } from './domain';

export const planKeys = {
  all: ['plans'] as const,
  list: (listing: Listing) => [...planKeys.all, 'list', listing] as const,
  lookup: () => [...planKeys.all, 'lookup'] as const,
  detail: (id: string) => [...planKeys.all, 'detail', id] as const,
};

export function usePlanList(listing: Listing) {
  return useQuery({
    queryKey: planKeys.list(listing),
    queryFn: ({ signal }) => http.get<Page<Plan>>(`/api/plans?${toSearchParams(listing)}`, { signal }),
    placeholderData: keepPreviousData,
  });
}

/** Every plan, for names and selects elsewhere. The server's page limit caps it. */
export function usePlanLookup() {
  return useQuery({
    queryKey: planKeys.lookup(),
    queryFn: ({ signal }) => http.get<Page<Plan>>(
      `/api/plans?${toSearchParams({ q: '', filters: {}, sort: PLAN_LISTING.defaultSort, page: 0, size: PLAN_LOOKUP_SIZE })}`,
      { signal },
    ),
    select: (page) => page.items,
  });
}

export function usePlan(id: string) {
  return useQuery({
    queryKey: planKeys.detail(id),
    queryFn: ({ signal }) => http.get<Plan>(`/api/plans/${encodeURIComponent(id)}`, { signal }),
  });
}

function useInvalidatePlans() {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries({ queryKey: planKeys.all });
}

export interface CreatedPlan {
  plan: Plan;
  /** The plan exists; only its first price failed. */
  priceError?: unknown;
}

/** FR-02.1 and, optionally, FR-02.2 in one step for the operator. */
export function useCreatePlan() {
  const invalidate = useInvalidatePlans();
  return useMutation({
    mutationFn: async ({ plan, firstPrice }: { plan: PlanInput; firstPrice: PriceInput | null }): Promise<CreatedPlan> => {
      const created = await http.post<Plan>('/api/plans', plan);
      if (!firstPrice) return { plan: created };
      try {
        await http.post<Price>(`/api/plans/${encodeURIComponent(created.id)}/prices`, firstPrice);
        return { plan: created };
      } catch (priceError) {
        return { plan: created, priceError };
      }
    },
    onSettled: invalidate,
  });
}

export function useUpdatePlan(id: string) {
  const invalidate = useInvalidatePlans();
  return useMutation({
    mutationFn: (plan: PlanInput) => http.put<Plan>(`/api/plans/${encodeURIComponent(id)}`, plan),
    onSuccess: invalidate,
  });
}

export function useDeactivatePlan(id: string) {
  const invalidate = useInvalidatePlans();
  return useMutation({
    mutationFn: () => http.delete<Plan>(`/api/plans/${encodeURIComponent(id)}`),
    onSuccess: invalidate,
  });
}

export function useAddPrice(planId: string) {
  const invalidate = useInvalidatePlans();
  return useMutation({
    mutationFn: (price: PriceInput) => http.post<Price>(`/api/plans/${encodeURIComponent(planId)}/prices`, price),
    onSuccess: invalidate,
  });
}

export function useReplacePrice() {
  const invalidate = useInvalidatePlans();
  return useMutation({
    mutationFn: ({ priceId, price }: { priceId: string; price: number }) =>
      http.post<Price>(`/api/prices/${encodeURIComponent(priceId)}/replace`, { price }),
    onSuccess: invalidate,
  });
}

export function useDeactivatePrice() {
  const invalidate = useInvalidatePlans();
  return useMutation({
    mutationFn: (priceId: string) => http.delete<Price>(`/api/prices/${encodeURIComponent(priceId)}`),
    onSuccess: invalidate,
  });
}
