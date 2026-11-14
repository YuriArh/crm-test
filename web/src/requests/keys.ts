/** Query keys of the requests module; every screen builds its keys here so invalidation stays consistent. */
export const requestKeys = {
  all: ['requests'] as const,
  lists: ['requests', 'list'] as const,
  list: (params: object) => ['requests', 'list', params] as const,
  card: (id: string) => ['requests', 'card', id] as const,
  operators: ['users', 'operators'] as const,
};
