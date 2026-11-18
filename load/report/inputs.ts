// What a load run leaves in load/out/ and how it is read (docs/specs/load-test.md §7). Every part is optional: a part
// that is missing is reported as "no data", it never stops the report.
import { createReadStream, existsSync, readFileSync } from 'node:fs';
import { createInterface } from 'node:readline';

export type Profile = { vus: number; warmupSeconds: number; runSeconds: number; seedSize: number; seedVus: number };

/** env.json: written by load-test.sh. */
export type Env = {
    date: string;
    commit: string;
    k6Image: string;
    host: { os: string; cpus: number; memoryBytes: number };
    docker: { version: string; cpus: number; memoryBytes: number };
    app: { timezone: string; dbPoolSize: string; jvmOptions: string };
};

/** run.json: written by load-test.sh. */
export type RunInfo = {
    profile: string;
    runId: string;
    k6Exit: number;
    startedAt: string;
    finishedAt: string;
    drain: { seconds: number; pending: number };
};

/** summary.json of k6 (--summary-export): metric name with its tags → statistics. */
export type Summary = { metrics: Record<string, Record<string, number>> };

/** docker compose config --format json, the part the report needs. */
export type ComposeConfig = {
    services: Record<string, { deploy?: { resources?: { limits?: { cpus?: number | string; memory?: number | string } } } }>;
};

export type ErrorRow = { op: string; status: string; code: string; count: number };
export type DatasetRow = { username: string; status: string; count: number };
export type ExecRow = { ack: number | null; exec: number | null; applied: number | null };
export type StatSample = { cpuPercent: number; memoryBytes: number };

export function readJson<T>(path: string): T | null {
    return existsSync(path) ? (JSON.parse(readFileSync(path, 'utf8')) as T) : null;
}

/** Data lines of a CSV with a header and no quoted commas (psql COPY … CSV HEADER). */
function readCsv(path: string): string[][] | null {
    if (!existsSync(path)) {
        return null;
    }
    const lines = readFileSync(path, 'utf8').split('\n').filter((line) => line.trim() !== '');
    return lines.slice(1).map((line) => line.split(','));
}

export function readDataset(path: string): DatasetRow[] | null {
    const rows = readCsv(path);
    return rows === null ? null : rows.map(([username, status, count]) => ({
        username: username ?? '',
        status: status ?? '',
        count: Number(count),
    }));
}

/** exec-times.csv: seconds from the creation of a request to each event; empty when the event has not happened. */
export function readExecTimes(path: string): ExecRow[] | null {
    const rows = readCsv(path);
    const seconds = (value: string | undefined): number | null =>
        value === undefined || value === '' ? null : Number(value);
    return rows === null ? null : rows.map(([, ack, exec, applied]) => ({
        ack: seconds(ack),
        exec: seconds(exec),
        applied: seconds(applied),
    }));
}

/** The errors of the measured phase by operation, status and code, from the points of the `errors` counter. */
export async function readErrors(path: string): Promise<ErrorRow[] | null> {
    if (!existsSync(path)) {
        return null;
    }
    const rows = new Map<string, ErrorRow>();
    const lines = createInterface({ input: createReadStream(path), crlfDelay: Infinity });
    for await (const line of lines) {
        if (!line.includes('"metric":"errors"') || !line.includes('"type":"Point"')) {
            continue;
        }
        const tags = (JSON.parse(line) as { data: { tags: Record<string, string> } }).data.tags;
        if (tags['phase'] !== 'run') {
            continue;
        }
        const row = { op: tags['op'] ?? '-', status: tags['status'] ?? '-', code: tags['code'] ?? '-' };
        const key = `${row.op}|${row.status}|${row.code}`;
        const known = rows.get(key);
        if (known === undefined) {
            rows.set(key, { ...row, count: 1 });
        } else {
            known.count += 1;
        }
    }
    return [...rows.values()].sort((a, b) => b.count - a.count);
}

const UNITS: Record<string, number> = {
    B: 1, kB: 1e3, KB: 1e3, MB: 1e6, GB: 1e9, KiB: 1024, MiB: 1024 ** 2, GiB: 1024 ** 3,
};

/** "512.3MiB" → bytes. */
function bytes(text: string): number {
    const match = /^([\d.]+)\s*([A-Za-z]+)$/.exec(text.trim());
    return match === null ? 0 : Number(match[1]) * (UNITS[match[2] ?? 'B'] ?? 1);
}

/** docker-stats.jsonl: one `docker stats --format '{{json .}}'` line per container per sample, by service name. */
export function readDockerStats(path: string): Map<string, StatSample[]> | null {
    if (!existsSync(path)) {
        return null;
    }
    const byService = new Map<string, StatSample[]>();
    for (const line of readFileSync(path, 'utf8').split('\n')) {
        if (line.trim() === '') {
            continue;
        }
        const stat = JSON.parse(line) as { Name: string; CPUPerc: string; MemUsage: string };
        const service = stat.Name.replace(/^exchange-load-/, '').replace(/-\d+$/, '');
        const samples = byService.get(service) ?? [];
        samples.push({ cpuPercent: parseFloat(stat.CPUPerc), memoryBytes: bytes(stat.MemUsage.split('/')[0] ?? '') });
        byService.set(service, samples);
    }
    return byService;
}
