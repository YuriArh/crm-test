/** Query keys of the problems module; every screen builds its keys here so invalidation stays consistent. */
export const problemKeys = {
  all: ['problems'] as const,
  lists: ['problems', 'list'] as const,
  list: (params: object) => ['problems', 'list', params] as const,
  one: (id: string) => ['problems', 'one', id] as const,
  openCount: ['problems', 'openCount'] as const,
};
