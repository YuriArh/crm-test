// The report in Markdown, in the order of docs/specs/load-test.md §7.
import type {
    ComposeConfig, DatasetRow, Env, ErrorRow, ExecRow, Profile, RunInfo, StatSample, Summary,
} from './inputs.ts';

export type ReportInput = {
    env: Env | null;
    run: RunInfo | null;
    profile: Profile | null;
    compose: ComposeConfig | null;
    faults: Record<string, boolean> | null;
    dataset: DatasetRow[] | null;
    summary: Summary | null;
    errors: ErrorRow[] | null;
    execTimes: ExecRow[] | null;
    stats: Map<string, StatSample[]> | null;
};

// NF08: the thresholds of the verdict.
const READ_P95_MS = 2000;
const WRITE_P95_MS = 3000;
const MAX_ERROR_RATE = 0.01;

const NO_DATA = '_нет данных_';

const OPERATIONS = [
    { op: 'list', label: 'Список (`GET /api/requests?businessStatus=…&size=20`)' },
    { op: 'card', label: 'Карточка (`GET /api/requests/{id}`)' },
    { op: 'create', label: 'Создание (`POST /api/requests`)' },
    { op: 'update', label: 'Изменение (`PATCH /api/requests/{id}`)' },
] as const;

const GROUPS = [
    { kind: 'read', label: 'Чтение (список + карточка)', limitMs: READ_P95_MS },
    { kind: 'write', label: 'Изменение (создание + изменение)', limitMs: WRITE_P95_MS },
] as const;

function table(headers: string[], rows: string[][]): string {
    const line = (cells: string[]): string => `| ${cells.join(' | ')} |`;
    return [line(headers), line(headers.map(() => '---')), ...rows.map(line)].join('\n');
}

const ms = (value: number | undefined): string => {
    if (value === undefined) return '—';
    return value >= 1000 ? `${(value / 1000).toFixed(2)} с` : `${value.toFixed(1)} мс`;
};
const sec = (value: number | undefined): string => (value === undefined ? '—' : `${value.toFixed(1)} с`);
const gib = (value: number): string => `${(value / 1024 ** 3).toFixed(2)} ГиБ`;
const mib = (value: number): string => `${(value / 1024 ** 2).toFixed(0)} МиБ`;

/** Linear interpolation between the closest ranks, as k6 does. */
export function percentile(sorted: number[], p: number): number {
    if (sorted.length === 0) return NaN;
    const rank = (sorted.length - 1) * p;
    const low = Math.floor(rank);
    const high = Math.ceil(rank);
    return (sorted[low] ?? 0) + ((sorted[high] ?? 0) - (sorted[low] ?? 0)) * (rank - low);
}

type Failed = { total: number; failed: number };

function failedOf(summary: Summary, tags: string): Failed | null {
    const metric = summary.metrics[`http_req_failed{phase:run,${tags}}`];
    return metric === undefined ? null : { total: (metric['passes'] ?? 0) + (metric['fails'] ?? 0), failed: metric['passes'] ?? 0 };
}

/** Verdict of the three thresholds, computed from the numbers of the summary. */
function verdicts(summary: Summary): { text: string; passed: boolean }[] {
    const rows: { text: string; passed: boolean }[] = [];
    for (const group of GROUPS) {
        const p95 = summary.metrics[`http_req_duration{phase:run,kind:${group.kind}}`]?.['p(95)'];
        rows.push({
            text: `p95 ${group.label.split(' ')[0]?.toLowerCase()} < ${ms(group.limitMs)}: ${ms(p95)}`,
            passed: p95 !== undefined && p95 < group.limitMs,
        });
    }
    let requests = 0;
    let failed = 0;
    for (const group of GROUPS) {
        const part = failedOf(summary, `kind:${group.kind}`);
        requests += part?.total ?? 0;
        failed += part?.failed ?? 0;
    }
    rows.push({
        text: `ошибок < ${MAX_ERROR_RATE * 100} %: ${failed} из ${requests}`,
        passed: requests > 0 && failed / requests < MAX_ERROR_RATE,
    });
    return rows;
}

function environment(env: Env | null): string {
    if (env === null) return NO_DATA;
    return [
        `- Дата прогона: ${env.date}; коммит: \`${env.commit}\`.`,
        `- Хост: ${env.host.os}, ${env.host.cpus} CPU, ${gib(env.host.memoryBytes)}.`,
        `- ВМ Docker: ${env.docker.cpus} CPU, ${gib(env.docker.memoryBytes)}; Docker ${env.docker.version}.`,
        `- Генератор: \`${env.k6Image}\` (работает вне лимитов стека, в той же ВМ Docker).`,
        `- Часовой пояс бизнес-логики: \`${env.app.timezone}\`.`,
    ].join('\n');
}

function limits(compose: ComposeConfig | null, env: Env | null, faults: Record<string, boolean> | null): string {
    if (compose === null) return NO_DATA;
    let cpus = 0;
    let memory = 0;
    const rows = Object.entries(compose.services).map(([name, service]) => {
        const limit = service.deploy?.resources?.limits;
        const cpu = Number(limit?.cpus ?? 0);
        const mem = Number(limit?.memory ?? 0);
        cpus += cpu;
        memory += mem;
        return [`\`${name}\``, cpu === 0 ? '—' : String(cpu), mem === 0 ? '—' : mib(mem)];
    });
    rows.push(['**Сумма**', `**${cpus.toFixed(1)}** (лимит ТЗ — 2)`, `**${gib(memory)}** (лимит ТЗ — 4 ГиБ)`]);
    const bullets: string[] = [];
    if (env !== null) {
        bullets.push(
            `- Пул соединений dispatch (\`DB_POOL_SIZE\`): ${env.app.dbPoolSize}.`,
            `- Параметры JVM dispatch (\`JAVA_TOOL_OPTIONS\`): \`${env.app.jvmOptions}\`.`,
        );
    }
    if (faults !== null) {
        const on = Object.entries(faults).filter(([, enabled]) => enabled).map(([mode]) => mode);
        bullets.push(`- Режимы сбоев эмулятора перед прогоном: ${on.length === 0 ? 'все выключены' : `**включены: ${on.join(', ')}**`}.`);
    }
    return [table(['Контейнер', 'CPU', 'Память'], rows), bullets.join('\n')].filter((part) => part !== '').join('\n\n');
}

function dataset(rows: DatasetRow[] | null): string {
    if (rows === null) return NO_DATA;
    const total = rows.reduce((sum, row) => sum + row.count, 0);
    return [
        table(['Инициатор', 'Итог', 'Заявок'], rows.map((row) => [row.username, row.status, String(row.count)])),
        `Всего на начало прогона: **${total}**.`,
    ].join('\n\n');
}

function profileSection(profile: Profile | null, run: RunInfo | null): string {
    if (profile === null || run === null) return NO_DATA;
    return [
        `- Профиль \`${run.profile}\`, прогон \`${run.runId}\`: ${profile.vus} клиентов в замкнутом цикле (следующий запрос — после ответа на предыдущий), без пауз.`,
        `- Прогрев ${profile.warmupSeconds} с (в статистику не входит), затем замер ${profile.runSeconds} с.`,
        '- Итерация клиента — четыре запроса по порядку, поэтому операции равночастотны: список с фильтром `businessStatus` (страница 20) → карточка → создание → изменение комментария.',
        `- Эмулятор перевозчика в обычном режиме, без задержек и отказов. Код выхода k6: ${run.k6Exit}.`,
    ].join('\n');
}

function results(summary: Summary | null, profile: Profile | null): string {
    if (summary === null) return NO_DATA;
    const seconds = profile?.runSeconds;
    const rate = (count: number | undefined): string =>
        count === undefined || seconds === undefined ? '—' : (count / seconds).toFixed(1);
    const row = (label: string, duration: Record<string, number> | undefined, failed: Failed | null): string[] => [
        label,
        String(duration?.['count'] ?? '—'),
        failed === null ? '—' : String(failed.total - failed.failed),
        failed === null ? '—' : String(failed.failed),
        ms(duration?.['med']),
        ms(duration?.['p(95)']),
        ms(duration?.['p(99)']),
        ms(duration?.['max']),
        rate(duration?.['count']),
    ];
    const headers = ['', 'Запросов', 'Успешных', 'Ошибок', 'p50', 'p95', 'p99', 'max', 'запр./с'];
    const groups = GROUPS.map((g) =>
        row(`**${g.label}**`, summary.metrics[`http_req_duration{phase:run,kind:${g.kind}}`], failedOf(summary, `kind:${g.kind}`)));
    const operations = OPERATIONS.map((o) =>
        row(o.label, summary.metrics[`http_req_duration{phase:run,op:${o.op}}`], failedOf(summary, `op:${o.op}`)));
    const verdictRows = verdicts(summary);
    const passed = verdictRows.every((v) => v.passed);
    return [
        `**Вердикт: ${passed ? 'принят' : 'НЕ принят'}**`,
        verdictRows.map((v) => `- ${v.passed ? '✓' : '✗'} ${v.text}`).join('\n'),
        table(headers, [...groups, ...operations]),
        'Запросы входа и подготовки (`kind:auth`, `kind:setup`) и прогрев в статистику не входят. Ошибки в выборку p95 включены.',
    ].join('\n\n');
}

function errorsSection(errors: ErrorRow[] | null): string {
    if (errors === null) return NO_DATA;
    if (errors.length === 0) return 'Ошибок нет.';
    return table(
        ['Операция', 'Статус', 'Код', 'Раз'],
        errors.map((e) => [e.op, e.status === '0' ? '0 (сеть)' : e.status, e.code, String(e.count)]),
    );
}

function execTimes(rows: ExecRow[] | null, run: RunInfo | null): string {
    if (rows === null) return NO_DATA;
    const interval = (label: string, pick: (row: ExecRow) => number | null): string[] => {
        const values = rows.map(pick).filter((v): v is number => v !== null).sort((a, b) => a - b);
        return [
            label,
            String(values.length),
            String(rows.length - values.length),
            sec(values.length === 0 ? undefined : percentile(values, 0.5)),
            sec(values.length === 0 ? undefined : percentile(values, 0.95)),
            sec(values[values.length - 1]),
        ];
    };
    const drain = run === null ? '' : ` После окончания k6 очередь разбиралась ещё ${run.drain.seconds} с, не исполнено: ${run.drain.pending}.`;
    return [
        `Заявки, созданные прогоном (прогрев и замер): ${rows.length}.${drain} Это время обмена с перевозчиком, оно не входит в время ответа API.`,
        table(
            ['От создания заявки до…', 'Наступило', 'Не наступило', 'p50', 'p95', 'max'],
            [
                interval('подтверждения получения (наша очередь отправки)', (r) => r.ack),
                interval('исполнения у перевозчика', (r) => r.exec),
                interval('применения итога у нас', (r) => r.applied),
            ],
        ),
    ].join('\n\n');
}

function resources(stats: Map<string, StatSample[]> | null, compose: ComposeConfig | null): string {
    if (stats === null) return NO_DATA;
    const rows = [...stats.entries()].map(([service, samples]) => {
        const cpu = samples.map((s) => s.cpuPercent);
        const mem = samples.map((s) => s.memoryBytes);
        const avg = (values: number[]): number => values.reduce((a, b) => a + b, 0) / values.length;
        const limit = Number(compose?.services[service]?.deploy?.resources?.limits?.cpus ?? 0);
        return [
            `\`${service}\``,
            `${avg(cpu).toFixed(0)} %`,
            `${Math.max(...cpu).toFixed(0)} %`,
            limit === 0 ? '—' : `${(limit * 100).toFixed(0)} %`,
            mib(avg(mem)),
            mib(Math.max(...mem)),
            String(samples.length),
        ];
    });
    return [
        table(['Контейнер', 'CPU средн.', 'CPU пик', 'CPU лимит', 'Память средн.', 'Память пик', 'Замеров'], rows),
        'CPU по `docker stats`: 100 % — одно ядро. Замеры раз в 5 с за весь запуск, включая прогрев и ожидание исполнения очереди.',
    ].join('\n\n');
}

export function render(input: ReportInput): string {
    return [
        '# Отчёт нагрузочного прогона (NF08)',
        '## 1. Среда', environment(input.env),
        '## 2. Ограничения', limits(input.compose, input.env, input.faults),
        '## 3. Набор данных', dataset(input.dataset),
        '## 4. Профиль', profileSection(input.profile, input.run),
        '## 5. Результат', results(input.summary, input.profile),
        '## 6. Ошибки', errorsSection(input.errors),
        '## 7. Время до исполнения', execTimes(input.execTimes, input.run),
        '## 8. Ресурсы', resources(input.stats, input.compose),
    ].join('\n\n') + '\n';
}
