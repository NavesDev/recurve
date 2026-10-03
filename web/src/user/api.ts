import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { http } from '../shared/api/http';
import { toSearchParams, type Listing, type Page } from '../shared/api/listing';
import { QUERY_ROOTS } from '../shared/constants/query';
import type { NewUser, User, UserInput } from './domain';

export const userKeys = {
  all: [QUERY_ROOTS.users] as const,
  list: (listing: Listing) => [...userKeys.all, 'list', listing] as const,
  detail: (id: string) => [...userKeys.all, 'detail', id] as const,
};

export function useUserList(listing: Listing) {
  return useQuery({
    queryKey: userKeys.list(listing),
    queryFn: ({ signal }) => http.get<Page<User>>(`/api/users?${toSearchParams(listing)}`, { signal }),
    placeholderData: keepPreviousData,
  });
}

export function useUser(id: string) {
  return useQuery({
    queryKey: userKeys.detail(id),
    queryFn: ({ signal }) => http.get<User>(`/api/users/${encodeURIComponent(id)}`, { signal }),
  });
}

/** An operator may be editing themselves: their own permissions are reread too. */
function useInvalidate() {
  const queryClient = useQueryClient();
  return () => Promise.all([
    queryClient.invalidateQueries({ queryKey: userKeys.all }),
    queryClient.invalidateQueries({ queryKey: [QUERY_ROOTS.me] }),
  ]);
}

export function useCreateUser() {
  const invalidate = useInvalidate();
  return useMutation({ mutationFn: (user: NewUser) => http.post<User>('/api/users', user), onSuccess: invalidate });
}

export function useUpdateUser(id: string) {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: (user: UserInput) => http.put<User>(`/api/users/${encodeURIComponent(id)}`, user),
    onSuccess: invalidate,
  });
}

export function useDeactivateUser(id: string) {
  const invalidate = useInvalidate();
  return useMutation({ mutationFn: () => http.delete<User>(`/api/users/${encodeURIComponent(id)}`), onSuccess: invalidate });
}
