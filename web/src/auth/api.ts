import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useSyncExternalStore } from 'react';
import { http } from '../shared/api/http';
import { sessionStore, type Session } from '../shared/api/session';
import { ME_STALE_TIME, QUERY_ROOTS } from '../shared/constants/query';
import type { IssuedToken, Me } from './domain';

export const meKey = [QUERY_ROOTS.me] as const;

export function useSession(): Session | null {
  return useSyncExternalStore(sessionStore.subscribe, sessionStore.get);
}

/** The signed-in operator. Cached for five minutes: every guard and button reads it. */
export function useMe() {
  const session = useSession();
  return useQuery({
    queryKey: meKey,
    queryFn: ({ signal }) => http.get<Me>('/api/me', { signal }),
    staleTime: ME_STALE_TIME,
    enabled: session !== null,
  });
}

export function useSignIn() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (credentials: { email: string; password: string }) =>
      http.post<IssuedToken>('/api/auth/token', credentials),
    onSuccess: async (issued) => {
      queryClient.clear();
      sessionStore.set(issued);
      await queryClient.fetchQuery({ queryKey: meKey, queryFn: () => http.get<Me>('/api/me'), staleTime: ME_STALE_TIME });
    },
  });
}

export function signOut() {
  sessionStore.clear();
}
