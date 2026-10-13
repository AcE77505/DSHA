/**
 * DSH bash 文本守卫：真实 ApprovalService 的 allowed-once 才登记本次批准。
 * tools.guard() 在可扩展 waterfall 之后核对命令、cwd 与 shellEnv；其它监听器
 * 即使返回 allow，也不能放行没有匹配批准的已识别破坏性命令。
 * 只覆盖文档列明的文本形式，不约束原生终端或同 UID 任意代码。
 */
import { readFileSync, opendirSync, statSync } from 'node:fs';
import { posix } from 'node:path';
import { analyzeCommand } from './destructive.js';
import { resolveEffectiveWorkdir } from './workdir.js';

export const name = 'dsh-destructive-guard';

/** 审批不可用 / 被策略挡下时给模型看的说明（模型必须停下来问人，而不是绕路）。 */
const BLOCK_HINT = '破坏性命令没有被确认，因此没有执行。请把要删的内容和原因告诉用户，由用户决定；不要改用别的工具或写法绕过这次确认。';

const KIND_LABELS = {
  rm: ['删除文件/目录（rm）', 'delete files/directories (rm)'],
  rmdir: ['删除空目录（rmdir）', 'remove empty directories (rmdir)'],
  unlink: ['删除文件（unlink）', 'unlink a file'],
  shred: ['擦除文件（shred）', 'overwrite/erase a file (shred)'],
  truncate: ['截断文件（truncate）', 'truncate a file'],
  dd: ['裸覆写（dd）', 'raw overwrite (dd)'],
  'mv-void': ['移入临时区（mv）', 'move into a temporary/void location (mv)'],
  'mv-clobber': ['覆盖已存在文件（mv）', 'overwrite an existing file (mv)'],
  'cp-clobber': ['覆盖已存在文件（cp）', 'overwrite an existing file (cp)'],
  tee: ['截断写入（tee）', 'truncating write (tee)'],
  'rsync-delete': ['同步删除（rsync --delete）', 'mirror-delete (rsync --delete)'],
  'find-delete': ['按匹配批量删除（find）', 'bulk delete by match (find)'],
  'git-clean': ['清理未跟踪文件（git clean -f）', 'delete untracked files (git clean -f)'],
  'git-reset-hard': ['丢弃工作区改动（git reset --hard）', 'discard working-tree changes (git reset --hard)'],
  'git-discard': ['丢弃工作区改动（git checkout/restore）', 'discard working-tree changes (git checkout/restore)'],
  'git-branch-delete': ['删除分支（git branch -D）', 'delete a branch (git branch -D)'],
  'git-stash-drop': ['丢弃 stash 条目', 'drop a stash entry'],
  'git-unparsed': ['git 调用无法完整识别', 'unclassified git invocation'],
  'redirect-truncate': ['重定向截断（>）', 'truncate by redirection (>)'],
  'redirect-device': ['写入裸设备（>）', 'write to a raw device (>)'],
};

/** 目标形态的两种语言标注。 */
const TARGET_LABELS = {
  dir: ['目录', 'directory'],
  file: ['文件', 'file'],
  other: ['其它类型', 'other type'],
  missing: ['当前不存在', 'does not exist right now'],
  glob: ['通配，已匹配 {n} 项', 'glob, {n} match(es)'],
  'glob-truncated': ['通配，至少 {n} 项（匹配已截断）', 'glob, at least {n} match(es) (list truncated)'],
  'glob-empty': ['通配，当前无匹配', 'glob, no match right now'],
  'glob-unknown': ['通配，数量未知', 'glob, unknown count'],
  unlisted: ['数量未知', 'unknown count'],
};

/** 关键目标最多列几个，超出只报数量。 */
const MAX_LISTED = 6;

/** 只读、有上限、避开凭据区的脚本读取；守卫不需要也不应该读敏感文件。 */
function safeReadFile(path) {
  try {
    if (path.includes('/.dsh/') || path.includes('/.ssh/') || path.includes('/.android/')) return null;
    if (/(?:\.credentials\.yaml|id_rsa|id_ed25519|\.bridge_token|\.bridge_headers)$/.test(path)) return null;
    const stats = statSync(path);
    if (!stats.isFile() || stats.size > 65536) return null;
    return readFileSync(path, 'utf8');
  } catch {
    return null;
  }
}

/** 通配展开：`**` 与受保护根一律不展开（可能扫全盘），此时按"数量未知"保守上报。 */
function safeGlob(pattern) {
  if (pattern.includes('**') || /[{}]/.test(pattern)) return null;
  const cut = pattern.search(/[*?[{]/);
  const prefix = (cut < 0 ? pattern : pattern.slice(0, cut)).replace(/\/+$/, '');
  if (prefix.split('/').filter(Boolean).length < 2) return null;
  const parts = pattern.split('/').filter(Boolean), found = [];
  let visited = 0, exhausted = false;
  function walk(base, at) {
    if (visited > 5000 || found.length > 200) { exhausted = true; return; }
    if (at === parts.length) {
      try { statSync(base); found.push(base); }
      catch (error) { if (error?.code !== 'ENOENT' && error?.code !== 'ENOTDIR') exhausted = true; }
      return;
    }
    const part = parts[at];
    if (!/[*?[]/.test(part)) { walk(base + '/' + part, at + 1); return; }
    const directory = opendirSync(base || '/');
    try {
      let entry;
      while ((entry = directory.readSync()) !== null) {
        if (++visited > 5000) { exhausted = true; return; }
        const file = (base || '') + '/' + entry.name;
        if (!posix.matchesGlob(file, (base || '') + '/' + part)) continue;
        if (at < parts.length - 1 && entry.isSymbolicLink()) { exhausted = true; return; }
        if (at < parts.length - 1 && !entry.isDirectory()) continue;
        walk(file, at + 1);
        if (found.length > 200) return;
      }
    } finally { directory.closeSync(); }
  }
  try { walk('', 0); return exhausted && found.length <= 200 ? null : found; }
  catch { return null; }
}

function probe(path) {
  try {
    const stats = statSync(path);
    if (stats.isDirectory()) return 'dir';
    if (stats.isFile()) return 'file';
    return 'other';
  } catch (error) {
    return error?.code === 'ENOENT' || error?.code === 'ENOTDIR' ? 'missing' : 'unknown';
  }
}

/** 目标当前形态的短标注。 */
function targetLabel(target, language) {
  const key = target.truncated === true && target.kind === 'glob' ? 'glob-truncated' : target.kind;
  const entry = TARGET_LABELS[key] ?? ['存在性未知', 'existence unknown'];
  return entry[language].replace('{n}', String(target.count ?? 0));
}

/** 把识别结果压成"删什么、几个"的清单（审批面板 headline 不渲染换行）。 */
function describe(analysis) {
  const heads = { zh: [], en: [] };
  const listed = { zh: [], en: [] };
  for (const finding of analysis.findings) {
    const labels = KIND_LABELS[finding.kind] ?? [finding.kind, finding.kind];
    const flagsZh = [finding.recursive ? '递归' : '', finding.force ? '强制' : ''].filter((part) => part !== '').join('、');
    const flagsEn = [finding.recursive ? 'recursive' : '', finding.force ? 'force' : ''].filter((part) => part !== '').join(', ');
    heads.zh.push(labels[0] + (flagsZh === '' ? '' : `［${flagsZh}］`));
    heads.en.push(labels[1] + (flagsEn === '' ? '' : ` [${flagsEn}]`));
    for (const target of finding.targets) {
      if (listed.zh.length >= MAX_LISTED) continue;
      const shown = target.path ?? target.text;
      listed.zh.push(`${listed.zh.length + 1}. ${shown}（${targetLabel(target, 0)}）`);
      listed.en.push(`${listed.en.length + 1}. ${shown} (${targetLabel(target, 1)})`);
    }
  }
  const count = analysis.unknownTargets ? null : analysis.total;
  return {
    heads: { zh: [...new Set(heads.zh)], en: [...new Set(heads.en)] },
    listed,
    count,
    truncated: analysis.truncatedTargets === true,
  };
}

/** 已批准的事实绑定本次 exec；最终单调闸门不会接受另一条命令的批准。 */
export function apply(ctx) {
  const grants = new WeakMap();
  let guardReady = false;
  const toolsGuard = exec => {
    if (exec.name !== 'bash') return undefined;
    const facts = inspect(ctx, exec);
    if (!facts.analysis.destructive && !facts.analysis.failed) return undefined;
    return guardReady && grants.get(exec) === facts.binding
      ? undefined : 'DSHA_DESTRUCTIVE_BLOCKED: destructive bash call has no matching one-time approval.';
  };
  ctx.inject(['tools'], scope => {
    scope.effect(() => {
      const dispose = scope.tools.guard(toolsGuard);
      guardReady = true;
      return () => { guardReady = false; dispose(); };
    });
  });
  ctx.on('tools/pre-execute', async (exec, next) => {
    const downstream = await next();
    if (downstream !== undefined && downstream.kind !== 'allow' && downstream.kind !== 'ask') return downstream;
    if (exec.name !== 'bash') return downstream;
    const facts = inspect(ctx, exec);
    const analysis = facts.analysis;
    if (!analysis.destructive && !analysis.failed) return downstream;
    if (!guardReady || analysis.failed) return blocked('DSHA destructive guard is unavailable or classification failed.');
    const approval = ctx.get('approval');
    if (!approval || !exec.agent || typeof approval.request !== 'function') return blocked('No approval service or agent is available.');
    try {
      if (approval.effectivePolicy(exec.agent.session) === 'never') return blocked('The session approval policy is never.');
      const { heads, listed, count, truncated } = describe(analysis);
      const countZh = count === null ? (truncated ? `至少 ${analysis.total} 个目标（匹配已截断）` : '数量未知的目标') : `${count} 个目标`;
      const countEn = count === null ? (truncated ? `at least ${analysis.total} targets (truncated)` : 'an unknown number of targets') : `${count} targets`;
      const zh = `破坏性命令确认：${heads.zh.join(' + ')}，${countZh}。目标：${listed.zh.join('；') || '无法预先枚举'}。请核对原始命令和实际工作目录，仅本次允许才执行。`;
      const en = `DSHA destructive command: ${heads.en.join(' + ')}, ${countEn}. Targets: ${listed.en.join('; ') || 'not enumerable'}. Verify the original command and workdir before approving once.`;
      const outcome = await approval.request({ agent: exec.agent, toolName: exec.name, callId: exec.callId,
        reason: truncate(en, 900), displayReason: { en: truncate(en, 900), zh: truncate(zh, 900) }, signal: exec.signal });
      if (outcome !== 'allowed-once' || exec.signal?.aborted) return blocked(`Approval outcome: ${String(outcome)}.`);
      // 用户等候期间的 cwd/env/命令变化同样不能借用这次批准。
      if (inspect(ctx, exec).binding !== facts.binding) return blocked('Command or execution facts changed during approval.');
      grants.set(exec, facts.binding);
      return downstream ?? { kind: 'allow' };
    } catch {
      return blocked('Approval could not be completed.');
    }
  });
}

function blocked(reason) {
  return { kind: 'deny', reason: truncate(reason + ' ' + BLOCK_HINT, 900),
    info: { name: 'DSHA_DESTRUCTIVE_COMMAND_BLOCKED', code: 'DSHA_DESTRUCTIVE_BLOCKED' } };
}

function inspect(ctx, exec) {
  const command = exec.arguments?.command;
  if (typeof command !== 'string' || command.trim() === '') return { analysis: { destructive: false }, binding: '' };
  try {
    const service = ctx.get('sandboxPolicy');
    const policy = service?.resolve(exec.agent === undefined ? {} : { session: exec.agent.session });
    const workdir = resolveEffectiveWorkdir({ workdir: exec.arguments?.workdir,
      workspaceRoot: policy?.workspaceRoot, sessionCwd: exec.agent?.session?.header?.cwd, execCwd: exec.cwd });
    const shellEnv = ctx.get('shellEnv');
    const collected = shellEnv?.collect(exec) ?? {};
    const env = { ...process.env, ...collected };
    const binding = JSON.stringify([command, workdir, Object.entries(env).sort(([a], [b]) => a.localeCompare(b))]);
    return { binding, analysis: analyzeCommand(command, { cwd: workdir.dir, cwdKnown: workdir.exact,
      env, probe, sizeProbe: path => { const stat = statSync(path); return stat.isFile() ? stat.size : null; }, glob: safeGlob, readFile: workdir.exact ? safeReadFile : null }) };
  } catch {
    return { analysis: { destructive: true, failed: true }, binding: '' };
  }
}

function truncate(text, limit) {
  return text.length <= limit ? text : text.slice(0, limit - 1) + '…';
}
