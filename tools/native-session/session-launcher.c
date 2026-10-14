#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stddef.h>
#include <dirent.h>
#include <fcntl.h>
#include <poll.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

/* 签名宿主监督器：业务输出和关闭证明走独立通道；仅 ECHILD 可证明 owned 树空。
 * 从不杀自己或广播进程组；未知子进程保持监督器及原现场。 */
static volatile sig_atomic_t stop_requested;
static void request_stop(int signal_number) { (void)signal_number; stop_requested = 1; }
static long long milliseconds(void) {
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now)) return -1;
    return now.tv_sec * 1000LL + now.tv_nsec / 1000000;
}
static int send_line(int fd, const char *line) {
    size_t size = strlen(line), sent = 0;
    while (sent < size) {
        ssize_t n = send(fd, line + sent, size - sent, MSG_NOSIGNAL);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return -1;
        sent += (size_t)n;
    }
    return 0;
}
static int receive_line(int fd, char *value, size_t limit, int timeout) {
    long long end = milliseconds() + timeout;
    for (size_t used = 0; used + 1 < limit;) {
        long long remaining = end - milliseconds();
        if (remaining <= 0) return -1;
        struct pollfd waiting = { .fd = fd, .events = POLLIN };
        int ready = poll(&waiting, 1, (int)remaining);
        if (ready < 0 && errno == EINTR) continue;
        if (ready <= 0) return -1;
        char byte; ssize_t got = recv(fd, &byte, 1, 0);
        if (got <= 0) return -1;
        if (byte == '\n') { value[used] = 0; return 0; }
        if (byte < 32 || byte > 126) return -1;
        value[used++] = byte;
    }
    return -1;
}
static int connection(const char *name) {
    struct sockaddr_un address = { .sun_family = AF_UNIX };
    size_t size = strlen(name);
    if (!size || size + 1 >= sizeof(address.sun_path)) return -1;
    memcpy(address.sun_path + 1, name, size);
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    if (connect(fd, (struct sockaddr *)&address, (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + size))) {
        close(fd); return -1;
    }
    return fd;
}
static pid_t parent_of(const char *name) {
    char path[96], bytes[8192];
    if (strlen(name) > 10 || snprintf(path, sizeof(path), "/proc/%.10s/stat", name) >= (int)sizeof(path)) return -1;
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    ssize_t count = read(fd, bytes, sizeof(bytes)-1);
    close(fd);
    if (count <= 0) return -1;
    bytes[count] = 0;
    char *end = strrchr(bytes, ')');
    if (!end || end[1] != ' ' || end[3] != ' ') return -1;
    return (pid_t)strtol(end + 4, NULL, 10);
}
/* 单线程监督器在本循环之外不 reap。尚未回收的直接子 PID 不能被复用。
 * 无法枚举不表示空，后续 waitpid 的 0 继续保持未知。 */
static void kill_owned_children(pid_t self) {
#ifdef DSHA_SESSION_TEST
    if (getenv("DSHA_SESSION_TEST_HIDE_PROC")) return;
#endif
    DIR *directory = opendir("/proc");
    if (!directory) return;
    struct dirent *entry;
    while ((entry = readdir(directory))) {
        if (entry->d_name[0] < '1' || entry->d_name[0] > '9') continue;
        pid_t pid = (pid_t)strtol(entry->d_name, NULL, 10);
        if (pid > 1 && pid != self && parent_of(entry->d_name) == self)
            (void)kill(pid, SIGKILL);
    }
    closedir(directory);
}
/* 未 fork 的失败也必须走同一条可信树空回执；不能让 START 前的超时留下未知 guest。
 * 仍要求真实 ECHILD、subreaper 与宿主 COMMIT，不以超时本身证明退出。 */
static int no_guest(int control) {
    int status, subreaper = 0;
    if (waitpid(-1, &status, __WALL | WNOHANG) != -1 || errno != ECHILD
        || prctl(PR_GET_CHILD_SUBREAPER, &subreaper, 0, 0, 0) || subreaper != 1) return 125;
    if (send_line(control, "NO_GUEST\n")
        || send_line(control, "COMMAND_EXITED 125\n")
        || send_line(control, "OWNED_TREE_EMPTY ECHILD\n")) return 125;
    char message[128];
    long long deadline = milliseconds() + 5000;
    while (milliseconds() < deadline) {
        if (receive_line(control, message, sizeof(message), (int)(deadline - milliseconds()))) return 125;
        if (!strcmp(message, "COMMIT")) return 125;
        /* START 可能已在超时与回执之间排队；本分支永不再 fork。 */
        if (strcmp(message, "START") && strcmp(message, "STOP") && strcmp(message, "HARD")) return 125;
    }
    return 125;
}
int main(int argc, char **argv) {
    int command = 1, existing_pty = 0, stop_signal = SIGTERM;
    const char *control_name = NULL;
    for (; command < argc; command++) {
        if (!strcmp(argv[command], "--birth-handshake")) continue;
        if (!strcmp(argv[command], "--pty-existing-session")) { existing_pty = 1; continue; }
        if (!strncmp(argv[command], "--control=", 10)) { control_name = argv[command] + 10; continue; }
        if (!strncmp(argv[command], "--stop-signal=", 14)) { stop_signal = atoi(argv[command]+14); continue; }
        if (!strcmp(argv[command], "--")) { command++; break; }
        break;
    }
    if (command >= argc || !control_name || (stop_signal != SIGTERM && stop_signal != SIGQUIT)) {
        fputs("DSHA_SESSION_USAGE\n", stderr); return 125;
    }
    pid_t original_parent = getppid();
    if (original_parent <= 1) return 125;
    if (existing_pty ? getsid(0) != getpid() : setsid() < 0) {
        fputs("DSHA_SESSION_SETSID\n", stderr); return 125;
    }
    int subreaper = 0;
    if (prctl(PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0) || prctl(PR_GET_CHILD_SUBREAPER, &subreaper, 0, 0, 0) || subreaper != 1) {
        fputs("DSHA_SESSION_SUBREAPER_UNAVAILABLE\n", stderr); return 125;
    }
    struct sigaction action = {0}; action.sa_handler = request_stop; sigemptyset(&action.sa_mask);
    sigaction(SIGTERM, &action, NULL); sigaction(SIGHUP, &action, NULL);
    signal(SIGCHLD, SIG_DFL); signal(SIGINT, SIG_IGN); signal(SIGQUIT, SIG_IGN); signal(SIGPIPE, SIG_IGN);
    if (prctl(PR_SET_PDEATHSIG, SIGTERM, 0, 0, 0) || getppid() != original_parent) return 125;
    int control = connection(control_name);
    if (control < 0) { fputs("DSHA_SESSION_CONTROL_UNAVAILABLE\n", stderr); return 125; }
    char stat[8192];
    int identity = open("/proc/self/stat", O_RDONLY | O_CLOEXEC);
    ssize_t length = identity < 0 ? -1 : read(identity, stat, sizeof(stat)-1);
    if (identity >= 0) close(identity);
    if (length <= 0 || stat[length-1] != '\n') return 125;
    stat[length] = 0;
    if (!existing_pty && (printf("DSHA_SESSION_STAT_V1 %s", stat) < 0 || fflush(stdout))) return 125;
    if (send_line(control, "READY DSHA_OWNED_TREE_V2\n")) return 125;
    /* 无 guest 前才允许握手超时退出；STDIN 不参与控制，保留 piped-input 与 PTY。 */
    struct pollfd pollfd = { .fd = control, .events = POLLIN };
    if (poll(&pollfd, 1, 5000) <= 0 || stop_requested) return no_guest(control);
    char message[128]; ssize_t got;
    if (receive_line(control, message, sizeof(message), 5000) || strcmp(message, "START")
        || stop_requested) return no_guest(control);
    int exec_error[2];
    if (pipe2(exec_error, O_CLOEXEC | O_NONBLOCK)) return no_guest(control);
    /* STOP may arrive immediately after STARTED, before the child resets inherited handlers. */
    sigset_t child_signals, prior_signals;
    sigemptyset(&child_signals); sigaddset(&child_signals, SIGTERM); sigaddset(&child_signals, SIGHUP);
    sigaddset(&child_signals, SIGINT); sigaddset(&child_signals, SIGQUIT);
    if (sigprocmask(SIG_BLOCK, &child_signals, &prior_signals)) return no_guest(control);
    pid_t child = fork();
    if (child < 0) {
        sigprocmask(SIG_SETMASK, &prior_signals, NULL);
        close(exec_error[0]); close(exec_error[1]);
        return no_guest(control);
    }
    if (!child) {
        close(control);
        close(exec_error[0]);
        signal(SIGTERM, SIG_DFL); signal(SIGHUP, SIG_DFL); signal(SIGINT, SIG_DFL); signal(SIGQUIT, SIG_DFL); signal(SIGPIPE, SIG_DFL);
        sigprocmask(SIG_SETMASK, &prior_signals, NULL);
        execvp(argv[command], argv + command);
        int error = errno;
        ssize_t wrote = write(exec_error[1], &error, sizeof(error));
        if (wrote != (ssize_t)sizeof(error)) _exit(125);
        perror("DSHA_SESSION_EXEC"); _exit(error == ENOENT ? 127 : 126);
    }
    sigprocmask(SIG_SETMASK, &prior_signals, NULL);
    close(exec_error[1]);
    send_line(control, "STARTED\n");
    int main_reaped = 0, main_code = 125, notified = 0, hard = 0;
    long long stop_at = -1, unknown_at = milliseconds();
    size_t buffered = 0;
    for (;;) {
        int status; pid_t waited;
        while ((waited = waitpid(-1, &status, __WALL | WNOHANG)) > 0) {
            if (waited == child) {
                main_reaped = 1;
                int execution_errno = 0;
                ssize_t execution_bytes = read(exec_error[0], &execution_errno, sizeof(execution_errno));
                close(exec_error[0]);
                if (execution_bytes == (ssize_t)sizeof(execution_errno) && execution_errno > 0) {
                    char refused[48]; snprintf(refused, sizeof(refused), "ENGINE_EXEC_FAILED %d\n", execution_errno);
                    send_line(control, refused);
                }
                main_code = WIFEXITED(status) ? WEXITSTATUS(status) : WIFSIGNALED(status) ? 128 + WTERMSIG(status) : 125;
                char exited[48]; snprintf(exited, sizeof(exited), "COMMAND_EXITED %d\n", main_code);
                send_line(control, exited);
            }
        }
        if (waited < 0 && errno != ECHILD && errno != EINTR) {
            if (!notified++) send_line(control, "UNCONFIRMED WAIT_FAILED\n");
        }
        if (main_reaped && waited < 0 && errno == ECHILD) {
            subreaper = 0;
            if (!prctl(PR_GET_CHILD_SUBREAPER, &subreaper, 0, 0, 0) && subreaper == 1) {
                if (send_line(control, "OWNED_TREE_EMPTY ECHILD\n")) return 125;
                pollfd.revents = 0;
                /* 回执落盘前保持监督器活着；丢连接不伪造宿主 closed。 */
                long long commit_deadline = milliseconds() + 5000;
                while (milliseconds() < commit_deadline) {
                    if (receive_line(control, message, sizeof(message), (int)(commit_deadline - milliseconds()))) return 125;
                    if (!strcmp(message, "COMMIT")) return main_code;
                    if (strcmp(message, "STOP") && strcmp(message, "HARD")) return 125;
                }
                return 125;
            }
            if (!notified++) send_line(control, "UNCONFIRMED SUBREAPER_CHANGED\n");
        }
        if (main_reaped) kill_owned_children(getpid());
        if (stop_requested && !main_reaped && stop_at < 0) {
            (void)kill(child, stop_signal); stop_at = milliseconds();
        }
        if (stop_at >= 0 && !main_reaped && !hard && milliseconds() - stop_at >= 3000) {
            (void)kill(child, SIGKILL); hard = 1;
        }
        if (main_reaped && !notified && milliseconds() - unknown_at > 3000) {
            send_line(control, "UNCONFIRMED LIVE_OR_HIDDEN_CHILD\n"); notified = 1;
        }
        pollfd.revents = 0;
        if (poll(&pollfd, 1, 20) > 0) {
            got = recv(control, message + buffered, sizeof(message)-1-buffered, MSG_DONTWAIT);
            if (got <= 0) { stop_requested = 1; pollfd.fd = -1; }
            else {
                buffered += (size_t)got; message[buffered] = 0;
                char *newline;
                while ((newline = strchr(message, '\n'))) {
                    *newline = 0;
                    if (!strcmp(message, "STOP")) stop_requested = 1;
                    else if (!strcmp(message, "HARD") && !main_reaped) {
                        (void)kill(child, SIGKILL); hard = 1; stop_requested = 1;
                    }
                    else if (*message) stop_requested = 1;
                    size_t rest = buffered - (size_t)(newline + 1 - message);
                    memmove(message, newline+1, rest); buffered = rest; message[buffered] = 0;
                }
                if (buffered == sizeof(message)-1) { buffered = 0; stop_requested = 1; }
            }
        }
    }
}
