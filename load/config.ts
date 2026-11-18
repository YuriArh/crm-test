// The only place that reads __ENV (docs/specs/load-test.md §5.0).

export type Profile = {
    /** Concurrent clients of the run. */
    vus: number;
    /** Warm-up before the measured part, seconds. */
    warmupSeconds: number;
    /** Measured part, seconds. */
    runSeconds: number;
    /** Requests the seed creates. */
    seedSize: number;
    /** Concurrent clients of the seed. */
    seedVus: number;
};

const PROFILES: Record<string, Profile> = {
    // NF08: 30 s warm-up, 2 min run, 20 clients, 5000 records.
    full: { vus: 20, warmupSeconds: 30, runSeconds: 120, seedSize: 5000, seedVus: 10 },
    // A short pass over the whole chain to check the scripts; its numbers measure nothing.
    smoke: { vus: 2, warmupSeconds: 10, runSeconds: 20, seedSize: 200, seedVus: 2 },
};

const profileName = __ENV.PROFILE || 'full';
const profile = PROFILES[profileName];
if (profile === undefined) {
    throw new Error(`Unknown PROFILE "${profileName}", expected one of: ${Object.keys(PROFILES).join(', ')}`);
}

export const config = {
    profileName,
    profile,
    baseUrl: __ENV.BASE_URL || 'http://dispatch:8080',
    /** Marks the run in Idempotency-Keys and comment texts. The Makefile passes the start time. */
    runId: __ENV.RUN_ID || 'local',
    /** Password of the demo users (V5__demo_users.sql). */
    password: __ENV.LOAD_PASSWORD || 'demo1234',
};

/** The demo users the load uses (V5__demo_users.sql). */
export const USERS = {
    north: 'north.initiator',
    south: 'south.initiator',
    operator: 'north.operator',
} as const;

/** Id of north.operator, the assignee the seed sets. */
export const NORTH_OPERATOR_ID = '0195f000-0000-7000-8000-000000000102';
