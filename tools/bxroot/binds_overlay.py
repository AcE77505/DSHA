"""Shared bind limits and fail-closed transport for the fixed native source."""
import hashlib

HEADER = '''#ifndef DSHA_BIND_LIMITS_H
#define DSHA_BIND_LIMITS_H
#define DSHA_MAX_BINDS 48
#define DSHA_BIND_PATH_BYTES 4096u
/* Linux's smallest supported per-argument limit, including NAME= and NUL. */
#define DSHA_BIND_ENV_BYTES (128u * 1024u)
#define DSHA_BIND_VALUE_BYTES (DSHA_BIND_ENV_BYTES - sizeof("BXROOT_BINDS="))
#endif
'''

PARSER = '''/* DSHA bind configuration is all-or-nothing, before guest code runs. */
static void dsha_bind_config_fail(const char *reason)
{
    fputs("DSHA_BXROOT_ENGINE_FAILURE: invalid bind configuration: ", stderr);
    fputs(reason, stderr);
    fputc('\\n', stderr);
    _exit(126);
}

/* From the same bounded serialization accepted by launcher and child env. */
static void parse_binds(void) {
    const char *env = getenv("BXROOT_BINDS");
    char *copy, *next, *entry;
    if (!env || !env[0]) return;
    if (strnlen(env, DSHA_BIND_VALUE_BYTES + 1u) > DSHA_BIND_VALUE_BYTES)
        dsha_bind_config_fail("bytes");
    copy = strdup(env);
    if (!copy) dsha_bind_config_fail("memory");
    g_config.bind_count = 0;
    g_config.bind_sources = calloc(MAX_BINDS, sizeof(char *));
    g_config.bind_targets = calloc(MAX_BINDS, sizeof(char *));
    g_config.bind_readonly = calloc(MAX_BINDS, sizeof(int));
    if (!g_config.bind_sources || !g_config.bind_targets || !g_config.bind_readonly)
        dsha_bind_config_fail("memory");
    next = copy;
    while ((entry = strsep(&next, ";")) != NULL) {
        char *target = strchr(entry, ':'), *flag, *src, *dst;
        int ro = 0;
        if (!target) dsha_bind_config_fail("field");
        *target++ = '\\0';
        flag = strchr(target, ':');
        if (flag) {
            *flag++ = '\\0';
            if (strcmp(flag, "ro")) dsha_bind_config_fail("flag");
            ro = 1;
        }
        if (entry[0] != '/' || target[0] != '/') dsha_bind_config_fail("path");
        if (strlen(entry) >= DSHA_BIND_PATH_BYTES || strlen(target) >= DSHA_BIND_PATH_BYTES)
            dsha_bind_config_fail("path-bytes");
        if (g_config.bind_count >= MAX_BINDS) dsha_bind_config_fail("count");
        src = strdup(entry); dst = strdup(target);
        if (!src || !dst) dsha_bind_config_fail("memory");
        strip_trailing_slash(src); strip_trailing_slash(dst);
        g_config.bind_sources[g_config.bind_count] = src;
        g_config.bind_targets[g_config.bind_count] = dst;
        g_config.bind_readonly[g_config.bind_count] = ro;
        g_config.bind_count++;
    }
    free(copy);
    LOG("bind mounts: %d", g_config.bind_count);
}

'''

def apply(source, replace):
    hashes = {}
    def edit(name, transform):
        path = source / name
        text = transform(path.read_text(encoding='utf-8'))
        path.write_text(text, encoding='utf-8', newline='\n')
        hashes[name] = hashlib.sha256(path.read_bytes()).hexdigest()
    header = source / 'src/runtime/dsha-bind-limits.h'
    if header.exists():
        raise ValueError('BXROOT_OVERLAY_ANCHOR:dsha-bind-limits.h already exists')
    header.write_text(HEADER, encoding='utf-8', newline='\n')
    hashes['src/runtime/dsha-bind-limits.h'] = hashlib.sha256(header.read_bytes()).hexdigest()
    edit('src/runtime/config.h', lambda text: replace(replace(text,
        '#include <limits.h>', '#include <limits.h>\n#include "dsha-bind-limits.h"'),
        '#define MAX_BINDS 16', '#define MAX_BINDS DSHA_MAX_BINDS'))
    edit('src/host/session.h', lambda text: replace(replace(text,
        '#include <stddef.h>', '#include <stddef.h>\n#include "../runtime/dsha-bind-limits.h"'),
        '#define BX_SESSION_MAX_BINDS 16', '#define BX_SESSION_MAX_BINDS DSHA_MAX_BINDS'))
    def preload(text):
        text = replace(text, '#define MAX_BINDS 16', '/* MAX_BINDS comes from the shared config.h contract. */')
        start = '/* 从环境变量解析 bind mount */\nstatic void parse_binds(void) {'
        end = '/*\n * ==================================================================\n * 官方 proroot launcher 配置块'
        if text.count(start) != 1 or text.count(end) != 1:
            raise ValueError('BXROOT_OVERLAY_ANCHOR:parse_binds span')
        begin, finish = text.index(start), text.index(end)
        text = replace(text, text[begin:finish], PARSER)
        text = replace(text, '    if (nb > PRCFG_MAX_ENTRIES)\n        nb = PRCFG_MAX_ENTRIES;',
            '    if (nb > PRCFG_MAX_ENTRIES || nb > MAX_BINDS)\n        dsha_bind_config_fail("legacy-count");')
        text = replace(text, '    for (i = 0; i < nb && g_config.bind_count < MAX_BINDS; i++) {',
            '    for (i = 0; i < nb; i++) {')
        text = replace(text, '        if (dl == 0 || sl == 0)\n            continue;',
            '        if (dl == 0 || sl == 0)\n            dsha_bind_config_fail("legacy-field");')
        text = replace(text, '        if (src == NULL || dst == NULL) {\n            free(src);\n            free(dst);\n            continue;\n        }',
            '        if (src == NULL || dst == NULL)\n            dsha_bind_config_fail("memory");')
        text = replace(text, '            g_config.bind_readonly = NULL;\n            free(b);\n            return;',
            '            g_config.bind_readonly = NULL;\n            free(b);\n            dsha_bind_config_fail("memory");')
        return text
    edit('src/runtime/preload.c', preload)
    def launcher(text):
        text = replace(text, '#include "../host/host-world.h"',
            '#include "../host/host-world.h"\n#include "../runtime/dsha-bind-limits.h"')
        text = replace(text, '#define MAX_BINDS 48', '#define MAX_BINDS DSHA_MAX_BINDS')
        before = '''    if (cfg->bind_count >= MAX_BINDS) {
        return -1;
    }
    cfg->binds[cfg->bind_count * 2] = strdup(host);
    cfg->binds[cfg->bind_count * 2 + 1] = strdup(guest);
    if (cfg->binds[cfg->bind_count * 2] == NULL ||
        cfg->binds[cfg->bind_count * 2 + 1] == NULL) {
        return -1;
    }'''
        after = '''    char *src, *dst;
    if (!host || !guest || host[0] != '/' || guest[0] != '/' ||
        strnlen(host, DSHA_BIND_PATH_BYTES) >= DSHA_BIND_PATH_BYTES ||
        strnlen(guest, DSHA_BIND_PATH_BYTES) >= DSHA_BIND_PATH_BYTES ||
        strpbrk(host, ":;") || strpbrk(guest, ":;")) {
        errno = EINVAL; return -1;
    }
    if (cfg->bind_count >= MAX_BINDS) { errno = E2BIG; return -1; }
    src = strdup(host); dst = strdup(guest);
    if (!src || !dst) { free(src); free(dst); return -1; }
    cfg->binds[cfg->bind_count * 2] = src;
    cfg->binds[cfg->bind_count * 2 + 1] = dst;'''
        text = replace(text, before, after)
        text = replace(text, '            if (need > (size_t)1 << 20) {         /* 1 MiB 上限，防御性 */',
            '            if (need > DSHA_BIND_VALUE_BYTES + 1u) { /* NAME= + NUL fits 128 KiB */')
        text = replace(text, '        setenv("BXROOT_BINDS", bind_env, 1);\n        free(bind_env);',
'''        if (setenv("BXROOT_BINDS", bind_env, 1) != 0) {
            fputs("DSHA_BXROOT_ENGINE_FAILURE: bind environment rejected\\n", stderr);
            free(bind_env); free_config(&cfg); return 1;
        }
        free(bind_env);''')
        if text.count('错误: bind 数量已达上限 %d') != 2:
            raise ValueError('BXROOT_OVERLAY_ANCHOR:launcher bind diagnostic pair')
        text = text.replace('错误: bind 数量已达上限 %d', '错误: bind 字段无效或数量超过上限 %d')
        return text
    edit('src/launcher/launcher.c', launcher)
    def proc(text):
        text = replace(text, '#include "proc.h"', '#include "proc.h"\n#include "../runtime/dsha-bind-limits.h"')
        text = replace(text, 'static char px_dsha_ulx[PX_PATH_MAX], px_dsha_ldso[PX_PATH_MAX];',
            'static char px_dsha_ulx[PX_PATH_MAX], px_dsha_ldso[PX_PATH_MAX];\nstatic char *px_dsha_binds;')
        text = replace(text, '    px_cfg_str(px_dsha_ldso, sizeof(px_dsha_ldso), getenv("BXROOT_ULX_LDSO"));',
'''    px_cfg_str(px_dsha_ldso, sizeof(px_dsha_ldso), getenv("BXROOT_ULX_LDSO"));
    v = getenv("BXROOT_BINDS");
    if (v) {
        if (strnlen(v, DSHA_BIND_VALUE_BYTES + 1u) > DSHA_BIND_VALUE_BYTES ||
            (px_dsha_binds = strdup(v)) == NULL) {
            fputs("DSHA_BXROOT_ENGINE_FAILURE: bind environment capture rejected\\n", stderr);
            _exit(126);
        }
    }''')
        text = replace(text, '    if (total > (size_t)PX_ENV_ENTRY_MAX) {',
            '    if (total > (!strcmp(name, "BXROOT_BINDS") ? DSHA_BIND_ENV_BYTES - 1u : (size_t)PX_ENV_ENTRY_MAX)) {')
        text = replace(text, '    {\n        const char *ulx = px_dsha_exec_config("BXROOT_ULX_PATH");',
'''    if (px_dsha_binds != NULL)
        forced[nf++] = (px_env_kv){"BXROOT_BINDS", px_dsha_binds, PX_ENV_SET};
    {
        const char *ulx = px_dsha_exec_config("BXROOT_ULX_PATH");''')
        text = replace(text, '            "BXROOT_BINDS", "PROROOT_BINDS", "PROROOT_CFG_FD", "PROROOT_ESCAPE_FD",',
            '            "PROROOT_BINDS", "PROROOT_CFG_FD", "PROROOT_ESCAPE_FD",')
        text = replace(text, '            if (out->n >= limit) {\n                break;\n            }',
            '            if (out->n >= limit) {\n                rc = PX_ENOSPC;\n                goto fail;\n            }')
        text = replace(text, '            if (name == NULL) {\n                continue;\n            }\n            /*',
'''            if (name == NULL) {
                continue;
            }
            if (out->n >= limit) { rc = PX_ENOSPC; goto fail; }
            /*''')
        text = replace(text, '                rc = px_env_push_kv(out, name, merged);',
'''                {
                    size_t left, need = strlen(name) + 1u + strlen(merged) + 1u;
                    if (!px_env_budget_left(out, budget, &left) || left < need) {
                        rc = PX_ENOSPC; goto fail;
                    }
                }
                rc = px_env_push_kv(out, name, merged);''')
        text = replace(text, '        final_env = envp;      /* 注入被禁用/失败 → 沿用调用方的 */',
'''        if (px_dsha_binds != NULL) {
            g_exec_guest_exe = NULL; g_exec_orig_comm = NULL;
            rc = -1; goto dsha_exec_fail;
        }
        final_env = envp;      /* No bind session: preserve the compatibility path. */''')
        text = replace(text, '        g_rt_stats.exec_env_injected++;\n    }\n    if (real_execveat == NULL) {',
'''        g_rt_stats.exec_env_injected++;
    } else if (px_dsha_binds != NULL) {
        return -1;
    }
    if (real_execveat == NULL) {''')
        text = replace(text, '        g_rt_stats.spawn_env_injected++;\n    }\n    g_exec_guest_exe = NULL;',
'''        g_rt_stats.spawn_env_injected++;
    } else if (px_dsha_binds != NULL) {
        g_exec_guest_exe = NULL; g_exec_orig_comm = NULL;
        rc = errno ? errno : E2BIG; goto out;
    }
    g_exec_guest_exe = NULL;''')
        text = replace(text, '    if (px_runtime_build_env(NULL, &env) != 0) {\n        goto fallback;\n    }',
'''    if (px_runtime_build_env(NULL, &env) != 0) {
        if (px_dsha_binds != NULL) return NULL;
        goto fallback;
    }''')
        text = replace(text, '        PX_LOG("proc: guest shell 不可用，system 回落真实实现");',
'''        if (px_dsha_binds != NULL) return rc;
        PX_LOG("proc: guest shell 不可用，system 回落真实实现");''')
        return text
    edit('src/proc/proc.c', proc)
    return hashes
