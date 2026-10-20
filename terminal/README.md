# terminal

终端模拟器核心, 供工作区终端页使用。界面部分 (`TerminalView`) 仍使用上游的 `terminal-view` aar。

## 来源

`src/main/java` 与 `src/test/java` 取自 [termux-app](https://github.com/termux/termux-app) 的
`terminal-emulator` 模块, tag `v0.118.0` (commit `6e2689f55295fa444be8ac8592c527c2c5ef3253`),
按 Apache License 2.0 发布, 全文见 [LICENSE](LICENSE)。包名保持 `com.termux.terminal` 不变,
以便与 `terminal-view` aar 二进制兼容。

## 与上游的差异

- 原生部分不用上游的 `termux.c`, 改为 `src/main/cpp/termux_pty.cpp`, 以 16KB 页对齐编译
- `TerminalSession` 不再直接管理本地进程, 改为通过 `TerminalTransport` 收发数据, 以便接入远程 shell:
  本地 pty 进程挪到 `LocalPtyTransport` (fd 改由 `ParcelFileDescriptor` 持有, 不再反射 `FileDescriptor` 私有字段);
  进程在后台线程启动, 启动失败时在终端里打印原因; 退出前等待读线程取完最后的输出;
  去掉只对本地进程有意义的 `getPid()` / `getCwd()`
- 主线程写入不会阻塞在没人读取的队列上: 启动期间的输入先暂存 (最多 4096 字节, 放不下的整段丢弃), 启动后再发送;
  transport 不再接收输入后, 写线程继续清空队列直到会话清理
- 关闭本地终端时杀掉整个会话里的进程, 而不只是会话首进程: 首进程是 PRoot 时,
  shell 和它的任务是被跟踪的进程, 只杀 PRoot 会让它们脱离跟踪继续运行
