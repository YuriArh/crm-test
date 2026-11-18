// The load data set (docs/specs/load-test.md §4): requests created through the API by the two initiators, so every
// one has a real exchange history; then the script waits for the carrier's results and varies the data a little.
// Run: make load-seed (PROFILE=full — 5000 requests, PROFILE=smoke — 200). Works only on an empty stand.
import exec from 'k6/execution';
import { sleep } from 'k6';
import type { Options } from 'k6/options';

import { config, NORTH_OPERATOR_ID, USERS } from './config.ts';
import { allRequests, createRequest, getCard, listRequests, patchRequest, systemInfo, type Page } from './lib/api.ts';
import type { Session } from './lib/session.ts';
import { signedIn } from './lib/session.ts';
import { requestContent, seedKey } from './lib/content.ts';

const { profile } = config;

/** How long the carrier's results of all requests may take: dispatch sends about 20 operations a second. */
const RESULTS_TIMEOUT_MS = 15 * 60 * 1000;

export const options: Options = {
    scenarios: {
        seed: {
            executor: 'shared-iterations',
            vus: profile.seedVus,
            iterations: profile.seedSize,
            maxDuration: '30m',
        },
    },
    thresholds: { errors: ['count==0'] },
    setupTimeout: '2m',
    teardownTimeout: '30m',
};

type SetupData = { serverTime: string; startedAt: number };

/** Sessions of this VU: an iteration creates for either initiator, so each signs in lazily, once. */
const sessions = new Map<string, Session>();

function sessionOf(user: string): Session {
    let session = sessions.get(user);
    if (session === undefined) {
        session = signedIn(user);
        sessions.set(user, session);
    }
    return session;
}

function totalOf(session: Session, businessStatus?: string): number {
    const res = listRequests(session, 'setup', { size: 1, ...(businessStatus ? { businessStatus } : {}) });
    if (res.body === null) {
        throw new Error(`Cannot read the registry of ${session.user}: ${res.status}`);
    }
    return (res.body as Page).totalElements;
}

export function setup(): SetupData {
    const north = signedIn(USERS.north, 'setup');
    const south = signedIn(USERS.south, 'setup');
    const existing = totalOf(north) + totalOf(south);
    if (existing > 0) {
        throw new Error(
            `The stand is not empty: the initiators already have ${existing} requests. ` +
                'The seed needs an empty stand: make load-reset && make load-up.',
        );
    }
    const info = systemInfo(north);
    if (info.body === null) {
        throw new Error(`Cannot read the server time: ${info.status}`);
    }
    console.log(`Seed ${config.profileName}: ${profile.seedSize} requests, ${profile.seedVus} clients`);
    return { serverTime: info.body.serverTime, startedAt: Date.now() };
}

export default function (data: SetupData): void {
    const i = exec.scenario.iterationInTest;
    const user = i % 2 === 0 ? USERS.north : USERS.south;
    createRequest(sessionOf(user), 'setup', requestContent(i, data.serverTime), seedKey(i));
}

/** Waits until no request of the initiators is without a final result. */
function waitForResults(initiators: Session[]): void {
    const until = Date.now() + RESULTS_TIMEOUT_MS;
    let lastLog = 0;
    for (;;) {
        let pending = 0;
        for (const session of initiators) {
            pending += totalOf(session, 'AWAITING') + totalOf(session, 'IN_PROGRESS');
        }
        if (pending === 0) {
            return;
        }
        if (Date.now() > until) {
            throw new Error(`${pending} requests still have no final result after ${RESULTS_TIMEOUT_MS / 60000} min`);
        }
        if (Date.now() - lastLog > 15000) {
            console.log(`Waiting for the carrier: ${pending} requests without a final result`);
            lastLog = Date.now();
        }
        sleep(3);
    }
}

/** Every fifth request gets a comment from its author; with an operator, another fifth gets that operator. */
function varyData(owner: Session, operator: Session | null): void {
    const items = allRequests(owner);
    items.forEach((item, position) => {
        if (position % 5 === 0) {
            const card = getCard(owner, 'setup', item.id);
            if (card.body !== null) {
                patchRequest(owner, 'setup', item.id, {
                    version: card.body.version,
                    comment: `Нагрузочный набор: заявка ${item.number}`,
                });
            }
        }
        if (operator !== null && position % 5 === 2) {
            const card = getCard(operator, 'setup', item.id);
            if (card.body !== null) {
                patchRequest(operator, 'setup', item.id, {
                    version: card.body.version,
                    assigneeId: NORTH_OPERATOR_ID,
                });
            }
        }
    });
}

export function teardown(data: SetupData): void {
    const createdSeconds = (Date.now() - data.startedAt) / 1000;
    const north = signedIn(USERS.north, 'setup');
    const south = signedIn(USERS.south, 'setup');
    const operator = signedIn(USERS.operator, 'setup');

    const waitStarted = Date.now();
    waitForResults([north, south]);
    const waitedSeconds = (Date.now() - waitStarted) / 1000;

    varyData(north, operator);
    varyData(south, null);

    const summary = [north, south].map((s) => `${s.user}: ${totalOf(s)}`).join(', ');
    const succeeded = totalOf(north, 'SUCCEEDED') + totalOf(south, 'SUCCEEDED');
    const rejected = totalOf(north, 'REJECTED') + totalOf(south, 'REJECTED');
    console.log(
        `Seed done. Requests — ${summary}. SUCCEEDED ${succeeded}, REJECTED ${rejected}. ` +
            `Creating took ${createdSeconds.toFixed(0)} s, waiting for the results ${waitedSeconds.toFixed(0)} s.`,
    );
}
