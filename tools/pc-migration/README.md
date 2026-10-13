# DSHA 手机到电脑的便携迁移

这套工具迁移会话、附件、工作区注册表、所选 profile 的设置层和文件 API 缓存。用户插件、设备桥凭据、受管资产和本机状态不在范围内；它不能替代原生完整 v5 备份。原始 home 和原包保留。

手机正式环境随包提供 `dsha-portable`。电脑使用可信 APK 中完整的 `assets/pc-migration/` 目录，里面已经包含锁定的 YAML 2.9.1 和 ISC 许可证。恢复需要 Node 24+；正式环境不用安装依赖或运行安装脚本。源码维护者可以在构建主机执行 `npm ci --ignore-scripts`，或用 `prepare-assets.py` 从已核验的 vendor 原件离线生成整套工具。

手机端示例：

```sh
dsha-portable plan
dsha-portable export --out /root/Documents/exports
dsha-portable export --out /root/Documents/exports --with-workspace /root/Documents/project
```

默认输出目录是 `/sdcard/Download/DSHA`。访问权限不够时命令明确失败，可指定可写目录，再通过已有文件入口取出归档。输出名包含随机标识，使用排他创建和实际 SHA-256 回读，不覆盖同名文件。工作区默认跳过 `.git`、`node_modules` 等可再生/另行迁移的目录；源目录、文件入口和后缀中的链接、Windows junction、特殊文件均拒绝。Linux 文件读取逐层使用 NOFOLLOW 的目录描述符。

导出会重复读取源与副本的完整内容，但不冻结正在写入的 DSH。需要停写的完整现场时先停止 DSH。实际限制为展开内容 16 GiB、压缩包 4 GiB、100000 个条目、64 层目录；恢复会话解码单日志限 256 MiB。

电脑端先看计划，文件名换成实际输出名：

```sh
node dsha-portable-restore.mjs plan --package ./DSHA-portable-0123456789abcdef0123456789abcdef.tar.gz --home ./desktop-home
node dsha-portable-restore.mjs apply --package ./DSHA-portable-0123456789abcdef0123456789abcdef.tar.gz --home ./desktop-home --workspace-dir ./desktop-work --rewrite-cwd
```

`plan` 只在隔离临时目录读取归档，不修改目标；需要工作区落点时输出明确占位参数。`apply` 在修改旧 home 前检查参数依赖、目标重叠、链接、实际归档类型/路径/预算、manifest 和本地白名单。归档中不创建软链、硬链或特殊文件，未知条目及路径碰撞直接拒绝。v1 旧便携包可读取，同样接受实际内容检查，包内声明不能扩大恢复权限。

可以多次使用 `--with-workspace`。电脑端指定 `--workspace-dir` 时按 `ws1`、`ws2` 分开，子目录 cwd 保留对应相对关系；也可多次提供 `--workspace-map '手机旧cwd=电脑新目录'`。同 session ID 位于不同项目时保留各自项目；转换碰撞拒绝提交。`--rewrite-cwd` 将识别到的各代日志转为明文，事件尾部逐字节保留，并以 YAML AST 设置真实的 `root` 和 `compression: none`。转换前副本保留在候选旁，其他会话附属文件保留。默认不改写 cwd。

home 和包内工作区先生成独立候选并核对字节，再通过本机回执发布。旧目录进入 `.before-restore-<UUID>`，不提供直接覆盖的 `--merge`。发生错误或中断时保留候选、原件和回执；显式恢复使用：

```sh
node dsha-portable-restore.mjs recover --journal ./目录/.dsha-portable-operations/回执UUID.json
```

恢复回执重新读取实际内容；后来修改过的活动目标或备份不会被回滚。请在电脑 DSH 停止时切换其 home。此处文件 fsync 和目录改名的宿主验证不代表所有文件系统的断电持久化矩阵已经完成。

`.credentials.yaml` 在导出和恢复两端都默认排除，只有显式 `--with-credentials` 才处理。YAML 结构严格解析，浏览器 `client-connection/*` 记录/引用剔除；未知 schema、alias、重复键或解析失败不落地凭据。用户 API Key 不写入诊断。profile 设置需在启动电脑 DSH 前审查；电脑需要另行安装的插件与设备入口不会随便携包补装。

维护验证入口：`hardened-selftest.mjs` 使用实际 Python 导出和 Node 恢复合成归档；`rc2-selftest.mjs` 使用具有完整内容证明的当前 rc2，调用真实 `loadOverlayPatches`、`composeEntries` 和官方会话存储，检查读取、写打开/flush、重开。所有输入与故障点都在隔离夹具，未操作用户数据、未发送模型请求。

未完成的范围：真实 Android 外存输出、完整 Windows/macOS/Linux 网页与模型续聊、多图片/外部文件 API 引用的跨机器矩阵。首装旧内核兼容与本工具的宿主证据分别记录，不能据此声称真机迁移验收完成。
