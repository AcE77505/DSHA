#define _GNU_SOURCE
#include <errno.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <linux/stat.h>
#include <pthread.h>
#include <spawn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/mman.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>
#include <sys/syscall.h>
extern char **environ;
static volatile int ready, done;
static void *thread(void *unused) {
    (void)unused; ready=1; while(!done) usleep(1000); return NULL;
}
int main(int argc,char **argv) {
    if(argc!=2)return 2;
    if(!strcmp(argv[1],"raw-stat")) {
        struct statx st;
        if(syscall(291,AT_FDCWD,"/DSHA_BXROOT_GUEST_MARKER",0,STATX_BASIC_STATS,&st))return 3;
        if(st.stx_uid || st.stx_gid || st.stx_size!=12)return 4;
        puts("RAW_STATX_OK"); return 0;
    }
    if(!strcmp(argv[1],"unix-socket")) {
        struct sockaddr_un a={.sun_family=AF_UNIX};
        strcpy(a.sun_path,"/tmp/dsha-bxroot-socket");unlink(a.sun_path);
        int s=socket(AF_UNIX,SOCK_STREAM,0);if(s<0 || bind(s,(void *)&a,sizeof a) || listen(s,1))return 5;
        pid_t p=fork();if(p<0)return 6;
        if(!p){int c=socket(AF_UNIX,SOCK_STREAM,0);if(connect(c,(void *)&a,sizeof a)||write(c,"X",1)!=1)_exit(7);close(c);_exit(0);}
        int c=accept(s,NULL,NULL),st;char b=0;if(c<0||read(c,&b,1)!=1||b!='X')return 8;
        close(c);close(s);waitpid(p,&st,0);unlink(a.sun_path);if(st)return 9;puts("UNIX_SOCKET_OK");return 0;
    }
    if(!strcmp(argv[1],"shm-gates")) {
        int (*emu)(long,unsigned long,unsigned long,unsigned long,long *)=dlsym(RTLD_DEFAULT,"bxroot_sysvshm_emulate");
        long out=0;if(!emu)return 10;
        if(!emu(194,0,4096,01000|0600,&out)||out!=-ENOSYS)return 11;
        if(!emu(194,12345,4096,01000|0600,&out)||out!=-ENOSYS)return 12;
        puts("SHM_DEFAULT_ENOSYS_OK");return 0;
    }
    if(!strcmp(argv[1],"static-spawn-attrs")) {
        posix_spawn_file_actions_t fa;posix_spawn_file_actions_init(&fa);
        posix_spawn_file_actions_adddup2(&fa,STDOUT_FILENO,STDOUT_FILENO);
        pid_t p=-42;char *args[]={"/tmp/dsha-static-reader",NULL};
        int r=posix_spawn(&p,args[0],&fa,NULL,args,environ);posix_spawn_file_actions_destroy(&fa);
        if(r!=ENOTSUP || p!=-42)return 13;
        puts("STATIC_SPAWN_REJECT_OK");return 0;
    }
    if(!strcmp(argv[1],"static-exec-threads")) {
        pthread_t t;if(pthread_create(&t,NULL,thread,NULL))return 14;while(!ready)usleep(1000);
        char *args[]={"/tmp/dsha-static-reader",NULL};errno=0;int r=execve(args[0],args,environ),e=errno;
        done=1;pthread_join(t,NULL);if(r!=-1||e!=ENOTSUP)return 15;puts("STATIC_THREAD_REJECT_OK");return 0;
    }
    if(!strcmp(argv[1],"static-opaque")) {
        char *args[]={"/tmp/dsha-static-opaque",NULL};errno=0;int r=execve(args[0],args,environ);
        if(r!=-1||errno!=ENOTSUP)return 16;
        puts("STATIC_OPAQUE_REJECT_OK");return 0;
    }
    if(!strcmp(argv[1],"trampoline-failure")) {
        setenv("BXROOT_ULX_PATH","/does-not-exist-dsha-ulx",1);
        char *args[]={"/bin/true",NULL};errno=0;int r=execve(args[0],args,environ);
        if(r!=-1||errno!=ENOTSUP)return 17;
        puts("TRAMPOLINE_REJECT_OK");return 0;
    }
    if(!strcmp(argv[1],"missing-exec")) {
        char *args[]={"/does-not-exist-dsha-guest",NULL};errno=0;int r=execve(args[0],args,environ);
        if(r!=-1||errno!=ENOENT)return 18;
        puts("MISSING_EXEC_ENOENT_OK");return 0;
    }
    if(!strcmp(argv[1],"empty-env-descendant")) {
        char *args[]={"/tmp/dsha-probe","trampoline-failure",NULL};
        char *clean[]={NULL};
        execve(args[0],args,clean);perror("clean descendant exec");return 20;
    }
    if(!strcmp(argv[1],"strict-unset")) {
        unsetenv("DSHA_BXROOT_STRICT_EXEC");
        setenv("BXROOT_ULX_PATH","/does-not-exist-dsha-ulx",1);
        char *args[]={"/bin/true",NULL};errno=0;int r=execve(args[0],args,environ);
        if(r!=-1||errno!=ENOTSUP)return 21;
        puts("STRICT_UNSET_REJECT_OK");return 0;
    }
    if(!strcmp(argv[1],"unresolved-fd")) {
        int fd=memfd_create("dsha-unresolved-exec",MFD_CLOEXEC);if(fd<0)return 22;
        int source=open("/bin/true",O_RDONLY);if(source<0)return 23;
        char buffer[8192];ssize_t count;
        while((count=read(source,buffer,sizeof buffer))>0)if(write(fd,buffer,(size_t)count)!=count)return 24;
        close(source);if(count<0)return 25;
        char *args[]={"/bin/true",NULL};errno=0;
        int r=execveat(fd,"",args,environ,AT_EMPTY_PATH),e=errno;close(fd);
        if(r!=-1||e!=ENOTSUP)return 26;
        puts("UNRESOLVED_FD_REJECT_OK");return 0;
    }
    return 19;
}
