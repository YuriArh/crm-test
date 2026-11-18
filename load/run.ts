// The load run (docs/specs/load-test.md §5): clients work in a closed loop — the next request goes out when the
// previous one is answered — doing list → card → create → update, so the four operations are equally frequent.
// The first PROFILE.warmupSeconds are tagged phase:warmup, the rest phase:run; thresholds and the report use phase:run.
// Run: make load-run (needs the data set of make load-seed).
import exec from 'k6/execution';
import http from 'k6/http';
import type { Options } from 'k6/options';

import { config, USERS } from './config.ts';
import { allRequests, createRequest, getCard, listRequests, patchRequest, systemInfo } from './lib/api.ts';
import { requestContent, runKey } from './lib/content.ts';
import { signedIn, type Session } from './lib/session.ts';
import { clientOf, shareOf, type Initiator } from './lib/shares.ts';

const { profile } = config;

/** Business results the registry list is filtered by (the subject field of the load, spec §2). */
const STATUSES = ['SUCCEEDED', 'REJECTED'] as const;
type Status = (typeof STATUSES)[number];

const PAGE_SIZE = 20;

// What counts as a failed request for http_req_failed.
http.setResponseCallback(http.expectedStatuses(200, 201));

export const options: Options = {
    scenarios: {
        load: {
            executor: 'constant-vus',
            vus: profile.vus,
            duration: `${profile.warmupSeconds + profile.runSeconds}s`,
        },
    },
    thresholds: {
        // NF08: p95 of reads ≤ 2 s, of changes ≤ 3 s; errors are not left out of the sample.
        'http_req_duration{phase:run,kind:read}': ['p(95)<2000'],
        'http_req_duration{phase:run,kind:write}': ['p(95)<3000'],
        'http_req_failed{phase:run,kind:read}': ['rate<0.01'],
        'http_req_failed{phase:run,kind:write}': ['rate<0.01'],
        // No limits: k6 puts a submetric into the summary only when it has a threshold.
        'http_req_duration{phase:run,op:list}': [],
        'http_req_duration{phase:run,op:card}': [],
        'http_req_duration{phase:run,op:create}': [],
        'http_req_duration{phase:run,op:update}': [],
    },
    summaryTrendStats: ['count', 'avg', 'med', 'p(95)', 'p(99)', 'max'],
    setupTimeout: '5m',
};

type SetupData = {
    serverTime: string;
    /** Ids of the data set's requests per initiator, in the order of their numbers. */
    requests: Record<Initiator, string[]>;
    /** Pages of the registry per initiator and result: the list asks for an existing one. */
    pages: Record<Initiator, Partial<Record<Status, number>>>;
};

export function setup(): SetupData {
    const sessions: Record<Initiator, Session> = {
        north: signedIn(USERS.north, 'setup'),
        south: signedIn(USERS.south, 'setup'),
    };
    const info = systemInfo(sessions.north);
    if (info.body === null) {
        throw new Error(`Cannot read the server time: ${info.status}`);
    }

    const requests = { north: [] as string[], south: [] as string[] };
    const pages: SetupData['pages'] = { north: {}, south: {} };
    for (const initiator of ['north', 'south'] as const) {
        const session = sessions[initiator];
        requests[initiator] = allRequests(session).map((item) => item.id);
        for (const status of STATUSES) {
            const res = listRequests(session, 'setup', { businessStatus: status, size: PAGE_SIZE });
            if (res.body === null) {
                throw new Error(`Cannot read the registry of ${session.user}: ${res.status}`);
            }
            if (res.body.totalPages > 0) {
                pages[initiator][status] = res.body.totalPages;
            }
        }
        const clients = initiator === 'north' ? Math.ceil(profile.vus / 2) : Math.floor(profile.vus / 2);
        if (requests[initiator].length < clients) {
            throw new Error(
                `${session.user} has ${requests[initiator].length} requests for ${clients} clients: ` +
                    'run make load-seed first.',
            );
        }
        if (Object.keys(pages[initiator]).length === 0) {
            throw new Error(`${session.user} has no request with a result to list: run make load-seed first.`);
        }
    }
    console.log(
        `Run ${config.runId} (${config.profileName}): ${profile.vus} clients, ` +
            `${profile.warmupSeconds} s warm-up + ${profile.runSeconds} s run; ` +
            `requests north ${requests.north.length}, south ${requests.south.length}`,
    );
    return { serverTime: info.body.serverTime, requests, pages };
}

type ClientState = { initiator: Initiator; session: Session; mine: string[] };

/** The client of this VU: signs in on its first iteration and keeps the session (and its jar) after that. */
let state: ClientState | null = null;

export default function (data: SetupData): void {
    // The tag is set first, so every request of the iteration (a sign-in too) carries the phase it started in.
    const sinceStart = Date.now() - exec.scenario.startTime;
    exec.vu.metrics.tags.phase = sinceStart < profile.warmupSeconds * 1000 ? 'warmup' : 'run';

    const vu = exec.vu.idInTest;
    const iteration = exec.vu.iterationInScenario;
    if (state === null) {
        const client = clientOf(vu, profile.vus);
        state = {
            initiator: client.initiator,
            session: signedIn(USERS[client.initiator]),
            mine: shareOf(data.requests[client.initiator], client.share, client.shares),
        };
    }
    const { initiator, session, mine } = state;

    // 1. The registry: a page of 20 filtered by the result, alternately SUCCEEDED and REJECTED.
    const available = STATUSES.filter((status) => data.pages[initiator][status] !== undefined);
    const status = available[iteration % available.length] as Status;
    const pages = data.pages[initiator][status] as number;
    listRequests(session, 'read', { businessStatus: status, page: Math.floor(Math.random() * pages), size: PAGE_SIZE });

    // 2. The card of the next request of this client's share.
    const id = mine[iteration % mine.length] as string;
    const card = getCard(session, 'read', id);

    // 3. A new request: the same business operation a user does, with a key of its own.
    createRequest(
        session,
        'write',
        requestContent(exec.scenario.iterationInTest, data.serverTime),
        runKey(config.runId, vu, iteration),
    );

    // 4. A comment on the request of step 2, with the version of its card (no other client touches it).
    if (card.body !== null) {
        patchRequest(session, 'write', id, {
            version: card.body.version,
            comment: `Нагрузка ${config.runId}-${vu}-${iteration}`,
        });
    }
}
