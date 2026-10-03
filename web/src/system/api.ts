import { useMutation, useQueryClient } from '@tanstack/react-query';
import { http } from '../shared/api/http';
import type { IndexName } from './constants';

/** FR-01.5: rebuilds one index from the database. Every cached listing may change. */
export function useReindex() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (index: IndexName) => http.post<{ indexed: number }>(`/api/${index}/reindex`),
    onSuccess: () => queryClient.invalidateQueries(),
  });
}
