import { spawn } from 'node:child_process';
import { E2E_ROOT } from './config.mjs';

/**
 * Fault-injection primitives for scenarios that need to prove behaviour
 * under a real outage rather than assume it (R12+R13: MinIO down during a
 * cold load; MN-10a: a node that stops answering while it holds a lease).
 *
 * Shells out to the same `docker compose -f compose.yml --env-file
 * versions.env` invocation `cli.mjs` uses for `up`/`down`, so a scenario
 * stopping `minio` mid-run is indistinguishable from an operator doing it by
 * hand — which is the point: the plugin cannot tell the difference either.
 */
async function composeAction(action, serviceName) {
  if (!serviceName || typeof serviceName !== 'string') {
    throw new Error(`docker-control: serviceName is required for '${action}'`);
  }
  const args = ['compose', '-f', 'compose.yml', '--env-file', 'versions.env', action, serviceName];
  return new Promise((resolve, reject) => {
    const child = spawn('docker', args, { cwd: E2E_ROOT, stdio: 'pipe' });
    let stdout = '';
    let stderr = '';
    child.stdout?.on('data', (d) => { stdout += d; });
    child.stderr?.on('data', (d) => { stderr += d; });
    child.on('error', reject);
    child.on('close', (code) => {
      if (code === 0) {
        resolve({ stdout, stderr });
      } else {
        reject(new Error(`docker compose ${action} ${serviceName} failed with exit code ${code}: ${stderr || stdout}`));
      }
    });
  });
}

/** Stops the container (SIGTERM then SIGKILL after compose's grace period). Volumes and state survive. */
export function stopService(serviceName) {
  return composeAction('stop', serviceName);
}

/** Starts a previously-stopped container back up. */
export function startService(serviceName) {
  return composeAction('start', serviceName);
}

/**
 * Freezes every process in the container with SIGSTOP (via `docker pause`,
 * cgroup freezer) without terminating it — the closest a compose harness can
 * get to a node that stops responding without closing its sockets, which is
 * what MN-10a's self-fencing has to detect and survive.
 */
export function pauseService(serviceName) {
  return composeAction('pause', serviceName);
}

/** Reverses {@link pauseService}. */
export function unpauseService(serviceName) {
  return composeAction('unpause', serviceName);
}

export default { stopService, startService, pauseService, unpauseService };
