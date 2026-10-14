# 固定 bxroot 原生引擎

DSHA build172 的独立执行闭包是 `libbxroot.so`、`libbxroot-runtime.so`、
`libbxroot-ulx.so`。固定上游提交、源码归档摘要、DSHA overlay、实际 ELF、
工具链与未改动 proot 摘要都在 `lock.json`；`build.py --check` 仅核对，不联网、
不启动进程、不写文件。Android 的精确资产投影由项目资产准备器生成。

从仓库根目录复建到一个新的空目录：

```powershell
python -B tools/bxroot/build.py --prepare-source artifacts/pr172/bxroot-engine/rebuild-source
```

在独立 Ubuntu24.04 amd64 Linux 工具链里安装锁记录的 GCC13 arm64 cross/sysroot
版本，然后在该源码树中执行：

```sh
BXROOT_OUTPUT=/absolute/dedicated/native sh /absolute/repo/tools/bxroot/build-linux.sh
```

`build-linux.sh` 留存全部本次对象、glibc/libgcc 和 crt 输入、输入 SHA、GNU 链接
map 与 GCC `-###` 真实链接命令。运行时仍是 glibc，launcher/ULX 仍以早期 SIGSYS
入口静态链接；不把 bionic runtime 作为 glibc 替代。

`overlay.py` 使用唯一源码 anchor：生产 overlay 编译期始终 strict，不依赖可丢失的
环境变量；`env -i`、Node/Python `env={}` 的子孙进程也不读外部
stub、不回落未翻译的动态 exec/spawn。实际 ULX 与 guest ldso 在运行时初始化时
锁存，并始终注入 guest 子环境；清空环境后仍能维持翻译执行。不支持的静态装载返回 ENOTSUP；静态 ELF
中无法确定调用号的 svc 在执行前拒绝，已识别静态 execve/execveat 站点返回
ENOTSUP，无法解析为同一宿主文件的 fd execveat 也返回 ENOTSUP。失败输出只用于
诊断与下次手动选择 proot，不能成为业务重放依据。
它没有增加系统沙箱；现有静态 trampoline 的寄存器和指令覆盖边界仍需如实说明。

默认 SysV SHM 两个 verified gate 均关闭，REENTRY 关闭，无外部静态加载器。
DSHA 终端、正式/维护/应急入口由 Java 生命周期实现同一配置和退出屏障；
本目录只负责固定的原生引擎和可复验来源。

本次本机证据在 `artifacts/pr172/bxroot-engine/`：Ubuntu Base 官方摘要、GCC/sysroot
包版本、源码前后逐件证明、编译日志、ELF/原件锁、ARM64 QEMU 回执与重链接材料。
glibc 原始源码、精确 Ubuntu8 修订、DSC 摘要、COPYING.LIB、实际 Ubuntu copyright
位于 `native/relink/sources/` 及 `native/glibc-copyright.txt`；GCC 的
`COPYING.RUNTIME` 和实际包 copyright 在 `native/`。MIT 仅描述 bxroot 自写源码，
静态 glibc 与 GCC runtime 的许可另外保留。

QEMU 测试临时登记并最终移除自己的 binfmt，不改宿主配置文件。其 `/proc` 能看到
QEMU 辅助线程，所以完整静态装载按生产单线程判据拒绝；回执区分这一拒绝与直接
编译实际静态 scanner/dispatcher 的 ARM64 断言。不能将此模拟证据宣称为 Android
seccomp、W^X、16KiB 真机、bionic 自动分流或完整静态工具矩阵通过。
