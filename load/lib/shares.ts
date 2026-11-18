// Which client works with which requests (docs/specs/load-test.md §5.1): the clients are split between the two
// initiators, and the requests of each initiator between its clients, so no request has two clients.

export type Initiator = 'north' | 'south';

export type Client = {
    initiator: Initiator;
    /** Number of this client among the clients of its initiator, from 0. */
    share: number;
    /** How many clients the initiator has. */
    shares: number;
};

/** Odd VUs work as the north initiator, even ones as the south initiator; vuId starts from 1. */
export function clientOf(vuId: number, vus: number): Client {
    const initiator: Initiator = vuId % 2 === 1 ? 'north' : 'south';
    return {
        initiator,
        share: (vuId - 1) >> 1,
        shares: initiator === 'north' ? Math.ceil(vus / 2) : Math.floor(vus / 2),
    };
}

/** The requests of one client: every `shares`-th one starting from `share`, in the order the server listed them. */
export function shareOf<T>(items: readonly T[], share: number, shares: number): T[] {
    return items.filter((_, position) => position % shares === share);
}
