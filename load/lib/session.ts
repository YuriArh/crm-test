// Sign-in as the SPA does it (docs/specs/load-test.md §5.1): GET /api/auth/csrf, then POST /api/auth/login with the
// X-XSRF-TOKEN header. k6 clears the cookies of a VU before every iteration, so every session owns its jar and passes
// it to each request: such a jar is not cleared.
import http, { type CookieJar } from 'k6/http';
import { check } from 'k6';

import { config } from '../config.ts';

export type Session = {
    user: string;
    jar: CookieJar;
};

const CSRF_COOKIE = 'XSRF-TOKEN';
const CSRF_HEADER = 'X-XSRF-TOKEN';

export function newSession(user: string): Session {
    return { user, jar: new http.CookieJar() };
}

/** The token of the current XSRF-TOKEN cookie: dispatch rotates it on sign-in, so it is read before every change. */
export function csrfHeaders(session: Session): Record<string, string> {
    const token = session.jar.cookiesForURL(config.baseUrl)[CSRF_COOKIE]?.[0];
    return token === undefined ? {} : { [CSRF_HEADER]: token };
}

/** Signs the user in. The requests are tagged kind:auth (or kind:setup) and stay out of the measured statistics. */
export function login(session: Session, kind: 'auth' | 'setup' = 'auth'): void {
    const tags = { kind, op: 'login' };
    http.get(`${config.baseUrl}/api/auth/csrf`, { jar: session.jar, tags });
    const res = http.post(
        `${config.baseUrl}/api/auth/login`,
        JSON.stringify({ username: session.user, password: config.password }),
        { jar: session.jar, tags, headers: { 'Content-Type': 'application/json', ...csrfHeaders(session) } },
    );
    const ok = check(res, { [`signed in as ${session.user}`]: (r) => r.status === 200 });
    if (!ok) {
        throw new Error(`Sign-in as ${session.user} failed: ${res.status} ${String(res.body).slice(0, 200)}`);
    }
}

/** A signed-in session. */
export function signedIn(user: string, kind: 'auth' | 'setup' = 'auth'): Session {
    const session = newSession(user);
    login(session, kind);
    return session;
}
