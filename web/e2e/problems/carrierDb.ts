import { execFileSync } from 'node:child_process';
import path from 'node:path';

/**
 * The carrier changes its own data: a direct update of the emulator's database through `psql` in the stand's
 * PostgreSQL container — the same command as the manual checks of docs/specs/demo-seed.md §8. The emulator has no
 * command for it on purpose. Needs the Docker stack (`make up`); used only by the `faults` project.
 */
const REPO_ROOT = path.resolve(import.meta.dirname, '../../..');
const DB_USER = process.env.CARRIER_DB_USER ?? 'carrier';
const DB_NAME = process.env.CARRIER_DB_NAME ?? 'carrier';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const TRACKING = /^TRK-[0-9A-F]{8}$/;

/** Replaces the executed operation's result with a successful one; `GET /operations/{id}` shows it at once. */
export function changeCarrierResult(operationId: string, trackingNumber: string, price: string): void {
  // Values go into SQL text, so they are checked against their formats first.
  if (!UUID.test(operationId) || !TRACKING.test(trackingNumber) || !/^\d+\.\d{2}$/.test(price)) {
    throw new Error(`refusing to build SQL from ${operationId} / ${trackingNumber} / ${price}`);
  }
  const sql =
    `UPDATE operation SET result_status = 'SUCCEEDED', tracking_number = '${trackingNumber}', price = ${price}, ` +
    `reason = NULL WHERE operation_id = '${operationId}' AND state = 'EXECUTED'`;
  const output = execFileSync(
    'docker',
    ['compose', 'exec', '-T', 'postgres', 'psql', '-U', DB_USER, '-d', DB_NAME, '-v', 'ON_ERROR_STOP=1', '-c', sql],
    { cwd: REPO_ROOT, encoding: 'utf8' },
  );
  if (!output.includes('UPDATE 1')) throw new Error(`the carrier has no executed operation ${operationId}: ${output}`);
}
