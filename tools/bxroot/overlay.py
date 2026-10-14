"""DSHA fixed-source native overlay. Every upstream anchor must occur exactly once."""
from pathlib import Path
import hashlib

CLONE3_FALLBACK = '''/* DSHA: glibc creates threads with signals blocked, so clone3 cannot rely
 * on the SIGSYS handler. Match its complete AArch64 call/child/exit shape,
 * then report ENOSYS so __clone_internal takes its existing clone fallback.
 * Call only on a verified libc executable segment, never a static image.
 * A duplicate match is ambiguous and leaves the segment unchanged. */
static int lp_patch_clone3_fallback(uintptr_t lo, uintptr_t hi)
{
    uint32_t *code = (uint32_t *)lo;
    size_t words = (hi - lo) / sizeof(*code), j, found = 0;
    uint32_t *site = NULL;

    for (j = 0; j + 14 <= words; j++) {
        if (code[j] != 0xaa0a03e0u ||       /* mov x0, x10 */
            code[j+1] != 0xd2803668u ||    /* mov x8, #435 */
            code[j+2] != SVC_INSN ||
            code[j+3] != 0xf100001fu ||    /* cmp x0, #0 */
            code[j+4] != 0x540000a0u ||    /* b.eq child */
            code[j+5] != 0x5400004bu ||    /* b.lt error */
            code[j+6] != 0xd65f03c0u ||    /* ret (parent) */
            (code[j+7] & 0xfc000000u) != 0x14000000u || /* b errno */
            code[j+8] != 0xd503201fu ||
            code[j+9] != 0xd280001du ||    /* mov x29, #0 (child) */
            code[j+10] != 0xaa0c03e0u ||   /* mov x0, x12 */
            code[j+11] != 0xd63f0160u ||   /* blr x11 */
            code[j+12] != 0xd2800ba8u ||   /* mov x8, #93 (exit) */
            code[j+13] != SVC_INSN)
            continue;
        site = &code[j+2];
        if (++found != 1)
            return 0;
    }
    if (site == NULL)
        return 0;
    *site = MOV_X0_ENOSYS;
    __builtin___clear_cache((char *)site, (char *)site + sizeof(*site));
    return 1;
}

'''

def replace(text, before, after):
    if text.count(before) != 1:
        raise ValueError("BXROOT_OVERLAY_ANCHOR:"+before[:80])
    return text.replace(before,after)

def apply(source):
    path=source/"src/proc/proc.c"
    text=path.read_text(encoding="utf-8")
    text=replace(text,'static px_rtconfig g_rt_cfg;',
'''static px_rtconfig g_rt_cfg;
/* Runtime-owned loader configuration survives a guest clearenv(). Explicit
 * nonempty/empty overrides still fail through the strict translated path. */
static char px_dsha_ulx[PX_PATH_MAX], px_dsha_ldso[PX_PATH_MAX];
static const char *px_dsha_exec_config(const char *name)
{
    const char *v = getenv(name);
    if (v != NULL) return v;
    if (strcmp(name, "BXROOT_LD_PRELOAD") == 0)
        return g_rt_cfg.have_preload ? g_rt_cfg.preload : NULL;
    if (strcmp(name, "BXROOT_ULX_PATH") == 0)
        return px_dsha_ulx[0] ? px_dsha_ulx : NULL;
    return px_dsha_ldso[0] ? px_dsha_ldso : NULL;
}''')
    text=replace(text,'    memset(&g_rt_cfg, 0, sizeof(g_rt_cfg));',
'''    memset(&g_rt_cfg, 0, sizeof(g_rt_cfg));
    px_cfg_str(px_dsha_ulx, sizeof(px_dsha_ulx), getenv("BXROOT_ULX_PATH"));
    px_cfg_str(px_dsha_ldso, sizeof(px_dsha_ldso), getenv("BXROOT_ULX_LDSO"));''')
    text=replace(text,'            "PROROOT_STUB_LOADER", "BXROOT_ULX_PATH", "BXROOT_ULX_LDSO"',
'''            "PROROOT_STUB_LOADER"''')
    text=replace(text,'    if (px_auto_host()) {\n        const char *host_path = getenv("BXROOT_HOST_PATH");',
'''    {
        const char *ulx = px_dsha_exec_config("BXROOT_ULX_PATH");
        const char *ldso = px_dsha_exec_config("BXROOT_ULX_LDSO");
        if (ulx != NULL) forced[nf++] = (px_env_kv){"BXROOT_ULX_PATH", ulx, PX_ENV_SET};
        if (ldso != NULL) forced[nf++] = (px_env_kv){"BXROOT_ULX_LDSO", ldso, PX_ENV_SET};
    }
    if (px_auto_host()) {
        const char *host_path = getenv("BXROOT_HOST_PATH");''')
    for before, after in (
        ('getenv("BXROOT_LD_PRELOAD")) == 0)', 'px_dsha_exec_config("BXROOT_LD_PRELOAD")) == 0)'),
        ('getenv("BXROOT_LD_PRELOAD"), fa, attr) == 0)', 'px_dsha_exec_config("BXROOT_LD_PRELOAD"), fa, attr) == 0)'),
        ('getenv("BXROOT_LD_PRELOAD"), NULL, NULL) == 0)', 'px_dsha_exec_config("BXROOT_LD_PRELOAD"), NULL, NULL) == 0)'),
        ('getenv("BXROOT_LD_PRELOAD"));', 'px_dsha_exec_config("BXROOT_LD_PRELOAD"));'),
    ):
        text=replace(text, before, after)
    text=replace(text,"static void px_tramp_cfg(const char **tramp, const char **linker)",
'''/* DSHA build172: unsupported translated execution never enters a raw host exec.
 * This is a compatibility contract, not a system sandbox. */
static int px_dsha_strict_exec(void)
{
    /* The production build's exec contract survives env -i and env={}. */
    return 1;
}

static int px_dsha_unsupported(const char *what)
{
    fprintf(stderr, "DSHA_BXROOT_ENGINE_FAILURE: DSHA_BXROOT_UNSUPPORTED: %s; choose proot for the next start\\n", what);
    errno = ENOTSUP;
    return -1;
}

static void px_tramp_cfg(const char **tramp, const char **linker)''')
    text=replace(text,'    *tramp = getenv("PROROOT_TRAMPOLINE_PATH");',
'''    if (px_dsha_strict_exec()) {
        *tramp = px_dsha_exec_config("BXROOT_ULX_PATH");
        *linker = px_dsha_exec_config("BXROOT_ULX_LDSO");
        return;
    }
    *tramp = getenv("PROROOT_TRAMPOLINE_PATH");''')
    text=replace(text,'    const char *stub = getenv("BXROOT_STUB_LOADER_EXEC");',
'''    if (px_dsha_strict_exec()) return px_dsha_unsupported("external static loader disabled");
    const char *stub = getenv("BXROOT_STUB_LOADER_EXEC");''')
    text=replace(text,'                (void)px_static_exec(host, argv0, argv, envp);\n                (void)px_stub_exec(host, argv, envp, argv0);',
'''                (void)px_static_exec(host, argv0, argv, envp);
                if (px_dsha_strict_exec()) {
                    (void)px_dsha_unsupported("static spawn unsupported");
                    _exit(126);
                }
                (void)px_stub_exec(host, argv, envp, argv0);''')
    text=replace(text,'        px_stub_plan *sp = (px_stub_plan *)malloc(sizeof(*sp));',
'''        if (px_dsha_strict_exec()) return px_dsha_unsupported("static spawn attributes or threads unsupported");
        px_stub_plan *sp = (px_stub_plan *)malloc(sizeof(*sp));''')
    text=replace(text,'            PX_LOG("proc: 进程内执行失败(errno=%d)，回退 stub-loader %s", errno, host);',
'''            if (px_dsha_strict_exec()) {
                (void)px_dsha_unsupported("static exec unsupported");
                rc = -1;
                goto dsha_exec_fail;
            }
            PX_LOG("proc: 进程内执行失败(errno=%d)，回退 stub-loader %s", errno, host);''')
    text=replace(text,'            PX_LOG("proc: trampoline exec 失败，回退直接 execve %s", host);\n        }\n    }',
'''            PX_LOG("proc: trampoline exec 失败，回退直接 execve %s", host);
        }
        if (!definitive_no && px_dsha_strict_exec()) {
            (void)px_dsha_unsupported("translated dynamic exec unavailable");
            rc = -1;
            goto dsha_exec_fail;
        }
    }''')
    text=replace(text,'    /* 走到这里说明 exec 失败（成功则映像已被替换，永不返回）。',
'''dsha_exec_fail:
    /* 走到这里说明 exec 失败（成功则映像已被替换，永不返回）。''')
    text=replace(text,'        rc = 0;\n        goto out;\n    }\n\n    if (real_posix_spawn == NULL) {',
'''        rc = 0;
        goto out;
    }
    if (px_dsha_strict_exec()) {
        (void)px_dsha_unsupported("translated spawn unavailable");
        rc = ENOTSUP;
        goto out;
    }

    if (real_posix_spawn == NULL) {''')
    text=replace(text,'        return px_real_execveat(dirfd, path, argv, envp, flags);',
'''        if (px_dsha_strict_exec())
            return px_dsha_unsupported("unresolved execveat file descriptor");
        return px_real_execveat(dirfd, path, argv, envp, flags);''')
    path.write_text(text,encoding="utf-8",newline="\n")
    hashes={"src/proc/proc.c":hashlib.sha256(path.read_bytes()).hexdigest()}
    path=source/"src/runtime/static_exec.c"
    text=path.read_text(encoding="utf-8")
    text=replace(text,'static int sx_wants_site(long nr)\n{\n    return sx_path_mask(nr) != 0 || nr == 17 || (nr >= 194 && nr <= 197);\n}',
'''static int sx_dsha_strict, sx_dsha_opaque_svc;
static int sx_wants_site(long nr)
{
    return sx_path_mask(nr) != 0 || nr == 17 || (nr >= 194 && nr <= 197)
           || (sx_dsha_strict && (nr == 221 || nr == 281));
}''')
    text=replace(text,'    if (nr >= 194 && nr <= 197 && bxroot_sysvshm_emulate != NULL) {',
'''    if (sx_dsha_strict && (nr == 221 || nr == 281)) {
        static const char message[] = "DSHA_BXROOT_ENGINE_FAILURE: static guest exec unsupported; choose proot for the next start\\n";
        (void)SC(SYS_write, 2, message, sizeof(message)-1, 0);
        return -ENOTSUP;
    }
    if (nr >= 194 && nr <= 197 && bxroot_sysvshm_emulate != NULL) {''')
    text=replace(text,'        if (ins == SX_SVC_INSN) {\n            if (nr >= 0 && sx_wants_site(nr)) {',
'''        if (ins == SX_SVC_INSN) {
            if (sx_dsha_strict && nr < 0) sx_dsha_opaque_svc = 1;
            if (nr >= 0 && sx_wants_site(nr)) {''')
    text=replace(text,'    sites = sx_count_sites(img);',
'''    /* DSHA production overlay: clean descendant env cannot relax exec. */
    sx_dsha_strict = 1;
    sx_dsha_opaque_svc = 0;
    sites = sx_count_sites(img);
    if (sx_dsha_strict && sx_dsha_opaque_svc) {
        sx_unload(img);
        free(img);
        errno = ENOTSUP;
        return -1;
    }''')
    path.write_text(text,encoding="utf-8",newline="\n")
    hashes["src/runtime/static_exec.c"]=hashlib.sha256(path.read_bytes()).hexdigest()
    path=source/"src/launcher/launcher.c"
    text=path.read_text(encoding="utf-8")
    text=replace(text,'    try_exec_via_ulx(&cfg, lib_dir, caller_guest_path);   /* 成功不返回 */\n    syscall(SYS_execve, cfg.guest_exe, cfg.guest_argv, environ);',
'''    try_exec_via_ulx(&cfg, lib_dir, caller_guest_path);   /* 成功不返回 */
    {
        /* DSHA production overlay is always strict, including clean env. */
        if (1) {
            fprintf(stderr, "DSHA_BXROOT_ENGINE_FAILURE: initial translated exec unavailable; choose proot for the next start\\n");
            free_config(&cfg);
            return 126;
        }
    }
    syscall(SYS_execve, cfg.guest_exe, cfg.guest_argv, environ);''')
    path.write_text(text,encoding="utf-8",newline="\n")
    hashes["src/launcher/launcher.c"]=hashlib.sha256(path.read_bytes()).hexdigest()
    path=source/"src/ldr/ulx.c"
    text=path.read_text(encoding="utf-8")
    text=replace(text,'    sigprocmask(SIG_SETMASK, &none, NULL);\n\n    __asm__ volatile("mov sp, %0\\n"',
'''    sigprocmask(SIG_SETMASK, &none, NULL);

    /* User-space exec keeps the previous static libc TLS allocation. Preserve
     * those bytes, but hand the next libc a page-aligned real program break.
     * No old allocator/TLS object is freed or reused after this point. */
    {
        unsigned long page = getauxval(AT_PAGESZ);
        unsigned long current = (unsigned long)syscall(SYS_brk, 0);
        unsigned long aligned, actual;

        if (page == 0 || (page & (page - 1)) != 0 || current == 0 ||
            current == ~0UL || current > ~0UL - (page - 1))
            die(126, "invalid program break handoff", NULL);
        aligned = (current + page - 1) & ~(page - 1);
        if (aligned != current) {
            actual = (unsigned long)syscall(SYS_brk, aligned);
            if (actual != aligned)
                die(126, "program break alignment rejected", NULL);
        }
    }

    __asm__ volatile("mov sp, %0\\n"''')
    path.write_text(text,encoding="utf-8",newline="\n")
    hashes["src/ldr/ulx.c"]=hashlib.sha256(path.read_bytes()).hexdigest()
    path=source/"src/runtime/livepatch.c"
    text=path.read_text(encoding="utf-8")
    anchor='#ifdef LP_TEST_HOOK\n/*\n * 测试专用：对一段调用方提供的机器码缓冲跑扫描逻辑'
    text=replace(text,anchor,CLONE3_FALLBACK+anchor)
    text=replace(text,'int bxroot_livepatch_scan_buffer_for_test(uint32_t *buf, size_t words)',
'''int bxroot_livepatch_clone3_buffer_for_test(uint32_t *buf, size_t words)
{
    return lp_patch_clone3_fallback((uintptr_t)buf, (uintptr_t)(buf + words));
}

int bxroot_livepatch_scan_buffer_for_test(uint32_t *buf, size_t words)''')
    text=replace(text,'                    int h = lp_scan_and_patch(seg_lo[k], seg_hi[k]);',
'''                    int h = lp_scan_and_patch(seg_lo[k], seg_hi[k]);
                    h += lp_patch_clone3_fallback(seg_lo[k], seg_hi[k]);''')
    path.write_text(text,encoding="utf-8",newline="\n")
    hashes["src/runtime/livepatch.c"]=hashlib.sha256(path.read_bytes()).hexdigest()
    import importlib.util
    spec=importlib.util.spec_from_file_location("bxroot_binds_overlay",Path(__file__).with_name("binds_overlay.py"))
    mod=importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    hashes.update(mod.apply(source,replace))
    return hashes
