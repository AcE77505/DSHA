import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { testRuntime } from './test-runtime-fixture.mjs';
import * as guard from '../app/src/main/assets/builtin-plugins/dsh-destructive-guard/lib/index.js';

const runtime = testRuntime('raw');
const require = createRequire(pathToFileURL(runtime + '/package.json'));
const { Context } = await import(pathToFileURL(require.resolve('@deepseek-ai/cordis')));
const { ToolRuntime } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-tools')));
const { ApprovalService } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-user-approval')));
let cases = 0;
async function scenario(outcome, policy = 'ask', override = false) {
  const context = new Context();
  context.provide('systemPrompt', { tools() {}, context() {}, getContextOrder() { return 10; } });
  context.provide('shellEnv', { collect() { return {}; } });
  let body = 0; let asks = 0; const events = [{ type: 'turn/start', data: {} }];
  const session = { header: { cwd: '/root/work' }, get seq() { return events.length; },
    eventAt(index) { return events[index]; }, append(type, data) { events.push({ type, data }); } };
  const agent = { session, ctx: context };
  const tools = new ToolRuntime(context);
  if (outcome !== 'missing-service') new ApprovalService(context, { policy });
  tools.register({ name: 'bash', description: 'Counter only: never invokes a shell.', parameters: {},
    output: { schema: { type: 'object' }, render: () => [{ type: 'text', text: 'counted' }] },
    execute() { body++; return {}; } });
  context.on('approval/request', async request => {
    asks++;
    if (outcome === 'throws') throw Error('fixture unavailable');
    if (outcome === 'scope-switch') { agent.session = { ...session, header: { cwd: '/different-session' } }; return 'allowed-once'; }
    if (outcome === 'signal-cancel') { setImmediate(() => cancellation.abort()); return new Promise(() => {}); }
    return outcome;
  });
  context.plugin(guard);
  await new Promise(resolve => setImmediate(resolve));
  if (override) context.on('tools/pre-execute', async () => ({ kind: 'allow' }), { prepend: true });
  const cancellation = new AbortController();
  const result = await tools.execute({ name: 'bash', callId: 'pipeline-' + cases,
    arguments: { command: 'rm /root/work/fixture' }, agent, signal: cancellation.signal });
  assert.equal(body, outcome === 'allowed-once' && policy === 'ask' && !override ? 1 : 0);
  if (body === 0) assert.equal(result.isError, true);
  if (policy === 'never' || override || outcome === 'missing-service') assert.equal(asks, 0);
  else assert.equal(asks, 1);
  if (asks) assert(events.some(event => event.type === 'approval/asked') && events.some(event => event.type === 'approval/decided'));
  await context.fiber.dispose(); cases++;
}
for (const outcome of ['allowed-once', 'rejected', 'cancelled', 'unavailable', 'throws', 'rogue', 'missing-service', 'scope-switch', 'signal-cancel']) await scenario(outcome);
await scenario('allowed-once', 'never');
await scenario('allowed-once', 'ask', true);
console.log('PASS real rc2 Cordis + ToolRuntime + ApprovalService pipeline', cases, 'cases; all shell bodies are counters');
