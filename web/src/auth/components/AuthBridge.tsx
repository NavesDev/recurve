import { useQueryClient } from '@tanstack/react-query';
import { useEffect, type ReactNode } from 'react';
import { onForbidden } from '../../shared/api/http';
import { sessionStore } from '../../shared/api/session';
import { meKey } from '../api';

/**
 * Keeps the cache honest about who is signed in: when the session ends
 * (sign-out, a 401, expiry) every cached answer is dropped, so the next
 * operator in this tab sees nothing of the last one; a 403 rereads the
 * permissions, since they may have been revoked.
 */
export function AuthBridge({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();

  useEffect(() => {
    const stopSession = sessionStore.subscribe(() => {
      if (!sessionStore.get()) queryClient.clear();
    });
    const stopForbidden = onForbidden(() => {
      void queryClient.invalidateQueries({ queryKey: meKey });
    });
    return () => {
      stopSession();
      stopForbidden();
    };
  }, [queryClient]);

  return children;
}
