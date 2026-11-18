// Minimal k6 script: one client, a few seconds, one GET with a check. A starting point, not the NF08 profile.
// Run: make load-hello (needs the load-test stack: make load-up).
import http from 'k6/http';
import { check, sleep } from 'k6';
import type { Options } from 'k6/options';

const baseUrl: string = __ENV.BASE_URL ?? 'http://localhost:18080';

export const options: Options = {
    vus: 1,
    duration: '5s',
    thresholds: {
        http_req_failed: ['rate<0.01'],
        http_req_duration: ['p(95)<2000'],
    },
};

export default function (): void {
    const res = http.get(`${baseUrl}/actuator/health`);
    check(res, { 'status is 200': (r) => r.status === 200 });
    sleep(1);
}
