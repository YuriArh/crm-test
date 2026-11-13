import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { api, unwrap } from '../api/client';
import { ApiError } from '../api/errors';
import type { CreateRequest, RequestCard, UpdateRequest } from '../api/types';
import { PAGE_SIZE, type ListParams } from './listParams';
import { shouldPoll } from './statuses';

export const POLL_INTERVAL_MS = 2_500;

export const requestKeys = {
  all: ['requests'] as const,
  lists: () => [...requestKeys.all, 'list'] as const,
  list: (params: ListParams) => [...requestKeys.lists(), params] as const,
  card: (id: string) => [...requestKeys.all, 'card', id] as const,
};

export function useRequestList(params: ListParams) {
  return useQuery({
    queryKey: requestKeys.list(params),
    queryFn: () =>
      unwrap(
        api.GET('/api/requests', {
          params: {
            query: {
              businessStatus: params.businessStatus,
              deliveryStatus: params.deliveryStatus,
              overdue: params.overdue,
              page: params.page - 1,
              size: PAGE_SIZE,
              sort: params.sort,
            },
          },
        }),
      ),
    placeholderData: keepPreviousData,
  });
}

export function fetchCard(id: string): Promise<RequestCard> {
  return unwrap(api.GET('/api/requests/{id}', { params: { path: { id } } }));
}

/** The card refreshes itself every few seconds until its outcome is final. */
export function useRequestCard(id: string) {
  return useQuery({
    queryKey: requestKeys.card(id),
    queryFn: () => fetchCard(id),
    refetchInterval: (query) => (shouldPoll(query.state.data) ? POLL_INTERVAL_MS : false),
  });
}

export interface CreateResult {
  card: RequestCard;
  /** The server answered with the stored response of an earlier attempt with the same key. */
  replayed: boolean;
}

export function useCreateRequest() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ body, idempotencyKey }: { body: CreateRequest; idempotencyKey: string }) => {
      const { data, error, response } = await api.POST('/api/requests', {
        params: { header: { 'Idempotency-Key': idempotencyKey } },
        body,
      });
      if (!response.ok || !data) {
        throw ApiError.from(response.status, error);
      }
      return {
        card: data,
        replayed: response.headers.get('Idempotent-Replayed') === 'true',
      } satisfies CreateResult;
    },
    onSuccess: ({ card, replayed }) => {
      if (replayed) {
        console.debug(`Create of ${card.number} was replayed for a repeated Idempotency-Key`);
      }
      queryClient.setQueryData(requestKeys.card(card.id), card);
      void queryClient.invalidateQueries({ queryKey: requestKeys.lists() });
    },
  });
}

export function useUpdateRequest(id: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: UpdateRequest) =>
      unwrap(api.PATCH('/api/requests/{id}', { params: { path: { id } }, body })),
    onSuccess: (card) => {
      queryClient.setQueryData(requestKeys.card(id), card);
      void queryClient.invalidateQueries({ queryKey: requestKeys.lists() });
    },
    onError: (error) => {
      if (error instanceof ApiError && error.current) {
        queryClient.setQueryData(requestKeys.card(id), error.current);
      }
    },
  });
}

export function useOperators(enabled: boolean) {
  return useQuery({
    queryKey: ['operators'],
    queryFn: () => unwrap(api.GET('/api/users/operators')),
    enabled,
    staleTime: 60_000,
  });
}
