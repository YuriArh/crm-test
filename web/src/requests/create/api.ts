import { useMutation, useQueryClient } from '@tanstack/react-query';
import { requestKeys } from '@/requests/keys';
import { api, unwrap, type CreateRequest, type RequestCard } from '@/shared/api';

export type CreateVariables = {
  body: CreateRequest;
  /** From {@link IdempotencyKeys}: the same body in the same form always goes with the same key. */
  idempotencyKey: string;
};

/** `POST /api/requests`. A replay of an earlier create (`Idempotent-Replayed: true`) is a success too. */
export function createRequest({ body, idempotencyKey }: CreateVariables): Promise<RequestCard> {
  return unwrap(api.POST('/api/requests', { params: { header: { 'Idempotency-Key': idempotencyKey } }, body }));
}

/** Creates a request; the registry in the cache is stale afterwards. Never retried on its own. */
export function useCreateRequest() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: createRequest,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: requestKeys.lists }),
  });
}
