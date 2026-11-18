// Builds load/out/report.md from what a run left in load/out/ (docs/specs/load-test.md §7).
// Run by load-test.sh: node --experimental-strip-types load/report/main.ts [out dir]
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';

import {
    readDataset, readDockerStats, readErrors, readExecTimes, readJson,
    type ComposeConfig, type Env, type Profile, type RunInfo, type Summary,
} from './inputs.ts';
import { render } from './render.ts';

const root = join(import.meta.dirname, '..');
const outDir = process.argv[2] ?? join(root, 'out');

const run = readJson<RunInfo>(join(outDir, 'run.json'));
const profiles = readJson<Record<string, Profile>>(join(root, 'profiles.json'));

const report = render({
    env: readJson<Env>(join(outDir, 'env.json')),
    run,
    profile: run === null ? null : (profiles?.[run.profile] ?? null),
    compose: readJson<ComposeConfig>(join(outDir, 'compose.json')),
    faults: readJson<Record<string, boolean>>(join(outDir, 'faults.json')),
    dataset: readDataset(join(outDir, 'dataset.csv')),
    summary: readJson<Summary>(join(outDir, 'summary.json')),
    errors: await readErrors(join(outDir, 'requests.json')),
    execTimes: readExecTimes(join(outDir, 'exec-times.csv')),
    stats: readDockerStats(join(outDir, 'docker-stats.jsonl')),
});

const target = join(outDir, 'report.md');
writeFileSync(target, report);
console.log(`Report: ${target}`);
