#!/bin/sh
# 固定源码树中运行；输出对象、sysroot来源与重链接资料，不安装到任何设备。
set -eu
export LC_ALL=C SOURCE_DATE_EPOCH=1791906267
CC=${CC:-aarch64-linux-gnu-gcc-13}
OUT=${BXROOT_OUTPUT:?BXROOT_OUTPUT must be an absolute dedicated output directory}
mkdir -p "$OUT/relink"
LD_FLAGS='-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=4096 -Wl,-z,relro -Wl,-z,now -Wl,--build-id=sha1'
export CC
sh ./BUILD_RUNTIME.sh -O1
# Upstream's build uses max-page-size; relink the actual runtime with the DSHA RELRO flags.
SRC='src/runtime/preload.c src/l2s/l2s.c src/l2s/l2s-runtime.c src/runtime/fakeroot.c src/runtime/crash.c src/runtime/sigsys.c src/runtime/syscall_guard.c src/runtime/livepatch.c src/runtime/static_exec.c src/host/host-world.c src/host/session.c src/host/native-session.c src/proc/proc.c'
OBJECTS=''
for s in $SRC; do
    o="$OUT/relink/$(printf '%s' "$s" | tr '/' '_').o"
    "$CC" -c -O1 -g0 -fPIC -D_GNU_SOURCE= -DFAKEROOT_PURE_LOGIC -DPX_PURE_LOGIC=0 -DBXROOT_SHM_COPYOUT_VERIFIED=0 -DBXROOT_SHM_CLAIM_VERIFIED=0 -Isrc/l2s -Isrc/runtime -Isrc/proc -o "$o" "$s"
    OBJECTS="$OBJECTS $o"
done
# shellcheck disable=SC2086
"$CC" -shared -nostartfiles $OBJECTS -ldl $LD_FLAGS -o "$OUT/libbxroot-runtime.so"
"$CC" -c -O2 -g0 -D_GNU_SOURCE -o "$OUT/relink/launcher.o" src/launcher/launcher.c
"$CC" -c -O2 -g0 -D_GNU_SOURCE -o "$OUT/relink/launcher-host-world.o" src/host/host-world.c
"$CC" -static "$OUT/relink/launcher.o" "$OUT/relink/launcher-host-world.o" $LD_FLAGS -Wl,-e,bx_early_start -Wl,-Map,"$OUT/relink/launcher.map" -o "$OUT/libbxroot.so"
# ULX stays resident while guest ldso maps ET_EXEC programs at their fixed
# addresses. A fixed-address ULX is overwritten by Python/Node's 0x400000 LOAD.
"$CC" -c -O2 -g0 -fPIE -D_GNU_SOURCE -o "$OUT/relink/ulx.o" src/ldr/ulx.c
"$CC" -static-pie "$OUT/relink/ulx.o" $LD_FLAGS -Wl,-e,bx_early_start -Wl,-Map,"$OUT/relink/ulx.map" -o "$OUT/libbxroot-ulx.so"
# Keep the early SIGSYS entry before glibc self-relocation. Its local handler
# must use no GOT or libc calls; save the actual instructions for review.
READELF=$("$CC" -print-prog-name=readelf)
OBJDUMP=$("$CC" -print-prog-name=objdump)
reject_ulx() { printf 'BXROOT_ULX_BUILD_REJECT: %s\n' "$1" >&2; exit 1; }
"$READELF" -hW "$OUT/libbxroot-ulx.so" > "$OUT/relink/ulx-elf-header.txt"
"$READELF" -lW "$OUT/libbxroot-ulx.so" > "$OUT/relink/ulx-program-headers.txt"
"$READELF" -dW "$OUT/libbxroot-ulx.so" > "$OUT/relink/ulx-dynamic.txt"
"$READELF" -sW "$OUT/libbxroot-ulx.so" > "$OUT/relink/ulx-symbols.txt"
"$OBJDUMP" -d --disassemble=bx_early_start "$OUT/libbxroot-ulx.so" > "$OUT/relink/ulx-early-entry.txt"
"$OBJDUMP" -d --disassemble=bx_early_sigsys "$OUT/libbxroot-ulx.so" > "$OUT/relink/ulx-early-handler.txt"
grep -Eq '^[[:space:]]*Type:[[:space:]]+DYN[[:space:]]' "$OUT/relink/ulx-elf-header.txt" || reject_ulx ET_DYN_REQUIRED
if grep -q 'INTERP' "$OUT/relink/ulx-program-headers.txt"; then reject_ulx INTERPRETER; fi
if grep -q '(NEEDED)' "$OUT/relink/ulx-dynamic.txt"; then reject_ulx SHARED_DEPENDENCY; fi
entry=$(awk '/Entry point address:/ {print $4}' "$OUT/relink/ulx-elf-header.txt")
early=$(awk '$8 == "bx_early_start" {print $2; exit}' "$OUT/relink/ulx-symbols.txt")
test -n "$entry" || reject_ulx ENTRY_MISSING
test -n "$early" || reject_ulx EARLY_ENTRY_MISSING
test "$((entry))" -eq "$((0x$early))" || reject_ulx EARLY_ENTRY_NOT_SELECTED
grep -q '<bx_early_sigsys>:' "$OUT/relink/ulx-early-handler.txt" || reject_ulx EARLY_HANDLER_MISSING
if grep -Eq '[[:space:]](bl|blr|adr|adrp)[[:space:]]' "$OUT/relink/ulx-early-handler.txt"; then reject_ulx EARLY_HANDLER_RELOCATION_DEPENDENCY; fi
if grep -Eq '[[:space:]](bl|blr)[[:space:]]|@plt|:got:' "$OUT/relink/ulx-early-entry.txt"; then reject_ulx EARLY_ENTRY_RELOCATION_DEPENDENCY; fi
grep -Eq '[[:space:]]b[[:space:]]+[^[:space:]]+[[:space:]]+<_start>' "$OUT/relink/ulx-early-entry.txt" || reject_ulx GLIBC_START_NOT_DIRECT
cp build/BUILD-METADATA "$OUT/BUILD-METADATA"
"$CC" --version > "$OUT/compiler.txt"
dpkg-query -W > "$OUT/packages.txt"
cp /usr/share/doc/libc6-dev-arm64-cross/copyright "$OUT/glibc-copyright.txt"
cp /usr/share/common-licenses/LGPL-2.1 "$OUT/LGPL-2.1.txt"
for name in libc.a libgcc.a libgcc_eh.a crt1.o rcrt1.o crti.o crtn.o crtbeginT.o crtbeginS.o crtend.o crtendS.o; do
    archive=$($CC -print-file-name="$name")
    test -f "$archive"
    cp "$archive" "$OUT/relink/$name"
done
sha256sum "$OUT"/relink/*.a "$OUT"/relink/*.o > "$OUT/relink/input-sha256.txt"
"$CC" -### -static "$OUT/relink/launcher.o" "$OUT/relink/launcher-host-world.o" $LD_FLAGS -Wl,-e,bx_early_start -o "$OUT/libbxroot.so" 2> "$OUT/relink/launcher-command.txt"
"$CC" -### -static-pie "$OUT/relink/ulx.o" $LD_FLAGS -Wl,-e,bx_early_start -o "$OUT/libbxroot-ulx.so" 2> "$OUT/relink/ulx-command.txt"
"$CC" -print-search-dirs > "$OUT/compiler-search-dirs.txt"
printf '%s\n' "$LD_FLAGS" > "$OUT/link-flags.txt"
printf 'BXROOT_BUILD_PASS\n'
