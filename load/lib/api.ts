// Calls of dispatch's API. Every request carries the tags kind (read / write / setup) and op (list / card / create /
// update / …); an unexpected answer is counted in `errors` with the op, the status and the code of the problem.
import http from 'k6/http';
import { Counter } from 'k6/metrics';

import { config } from '../config.ts';
import type { NewRequest } from './content.ts';
import { csrfHeaders, type Session } from './session.ts';

/** Unexpected answers by op, status and the `code` of the problem details (or the k6 error code of a network error). */
export const errors = new Counter('errors');

export type Kind = 'read' | 'write' | 'setup';

export type ListItem = { id: string; number: string; businessStatus: string; deliveryStatus: string };

export type Page = { items: ListItem[]; page: number; size: number; totalElements: number; totalPages: number };

export type Card = { id: string; number: string; version: number; businessStatus: string; comment: string | null };

export type Result<T> = { status: number; ok: boolean; body: T | null };

type Params = {
    kind: Kind;
    op: string;
    expected: number;
    body?: unknown;
    headers?: Record<string, string>;
};

function parse<T>(raw: string | ArrayBuffer | null): T | null {
    if (typeof raw !== 'string' || raw === '') {
        return null;
    }
    try {
        return JSON.parse(raw) as T;
    } catch {
        return null;
    }
}

function call<T>(session: Session, method: 'GET' | 'POST' | 'PATCH', path: string, p: Params): Result<T> {
    const mutating = method !== 'GET';
    const headers: Record<string, string> = {
        Accept: 'application/json',
        ...(mutating ? { 'Content-Type': 'application/json', ...csrfHeaders(session) } : {}),
        ...p.headers,
    };
    const res = http.request(method, `${config.baseUrl}${path}`, mutating ? JSON.stringify(p.body ?? {}) : null, {
        jar: session.jar,
        headers,
        tags: { kind: p.kind, op: p.op },
    });

    const ok = res.status === p.expected;
    const body = parse<T>(res.body);
    if (!ok) {
        const problem = body as { code?: string } | null;
        const code = res.status === 0 ? String(res.error_code) : (problem?.code ?? '-');
        errors.add(1, { op: p.op, status: String(res.status), code });
    }
    return { status: res.status, ok, body: ok ? body : null };
}

/** Server time (an open endpoint): the base of the deadlines. */
export function systemInfo(session: Session): Result<{ serverTime: string }> {
    return call(session, 'GET', '/api/system/info', { kind: 'setup', op: 'system-info', expected: 200 });
}

export type ListQuery = { businessStatus?: string; page?: number; size?: number; sort?: string };

export function listRequests(session: Session, kind: Kind, query: ListQuery): Result<Page> {
    const params: string[] = [];
    if (query.businessStatus !== undefined) params.push(`businessStatus=${query.businessStatus}`);
    if (query.page !== undefined) params.push(`page=${query.page}`);
    if (query.size !== undefined) params.push(`size=${query.size}`);
    if (query.sort !== undefined) params.push(`sort=${encodeURIComponent(query.sort)}`);
    const suffix = params.length === 0 ? '' : `?${params.join('&')}`;
    return call(session, 'GET', `/api/requests${suffix}`, { kind, op: 'list', expected: 200 });
}

export function getCard(session: Session, kind: Kind, id: string): Result<Card> {
    return call(session, 'GET', `/api/requests/${id}`, { kind, op: 'card', expected: 200 });
}

export function createRequest(session: Session, kind: Kind, content: NewRequest, key: string): Result<Card> {
    return call(session, 'POST', '/api/requests', {
        kind,
        op: 'create',
        expected: 201,
        body: content,
        headers: { 'Idempotency-Key': key },
    });
}

export type Patch = { version: number; comment?: string | null; assigneeId?: string | null };

export function patchRequest(session: Session, kind: Kind, id: string, patch: Patch): Result<Card> {
    return call(session, 'PATCH', `/api/requests/${id}`, { kind, op: 'update', expected: 200, body: patch });
}

/** All the requests of the session's scope in the order of their numbers (pages of 100). */
export function allRequests(session: Session): ListItem[] {
    const items: ListItem[] = [];
    for (let page = 0; ; page++) {
        const res = listRequests(session, 'setup', { page, size: 100, sort: 'number,asc' });
        if (res.body === null) {
            throw new Error(`Cannot read page ${page} of the registry of ${session.user}: ${res.status}`);
        }
        items.push(...res.body.items);
        if (page + 1 >= res.body.totalPages) {
            return items;
        }
    }
}
