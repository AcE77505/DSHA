#define _GNU_SOURCE
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "config.h"
#include "session.h"
#include "proc.h"
bxroot_config_t g_config;
static int fail_calloc;
static void *parser_calloc(size_t n,size_t width) { return fail_calloc?NULL:calloc(n,width); }
#define calloc parser_calloc
#include "bind-parser-test.inc"
#undef calloc
_Static_assert(MAX_BINDS==48 && BX_SESSION_MAX_BINDS==48,"bind capacities must agree");
static char *map(size_t count, size_t width) {
    size_t cap=count*(width*2+40)+1, used=0, i;
    char *out=calloc(1,cap), *part=malloc(width+1);assert(out&&part&&width>0);
    memset(part,'a',width);part[0]='/';part[width]=0;
    for(i=0;i<count;i++) {
        int n=snprintf(out+used,cap-used,"%s%s%zu:%s%zu%s",i?";":"",part,i,part,i,i==count-1?":ro":"");
        assert(n>0&&(size_t)n<cap-used);used+=(size_t)n;
    }
    free(part);return out;
}
static char *exact_map(size_t length) {
    size_t i,extra=length-(48*4-1),used=0;char *out=malloc(length+1);assert(out);
    for(i=0;i<48;i++) {
        size_t source=extra>4094?4094:extra;extra-=source;
        size_t target=extra>4094?4094:extra;extra-=target;
        if(i)out[used++]=';';out[used++]='/';memset(out+used,'a',source);used+=source;
        out[used++]=':';out[used++]='/';memset(out+used,'b',target);used+=target;
    }
    assert(!extra&&used==length);out[used]=0;return out;
}
int main(int argc,char **argv) {
    char *value;px_envout out;px_env_kv forced;px_envpolicy policy;int code;
    assert(argc==2);
    if(!strcmp(argv[1],"32")||!strcmp(argv[1],"48")) {
        int count=atoi(argv[1]);char last[64];
        value=map((size_t)count,8);assert(setenv("BXROOT_BINDS",value,1)==0);parse_binds();
        assert(g_config.bind_count==count);snprintf(last,sizeof(last),"/aaaaaaa%d",count-1);
        assert(!strcmp(g_config.bind_targets[count-1],last)&&g_config.bind_readonly[count-1]==1);
    } else if(!strcmp(argv[1],"49")) {
        value=map(49,8);assert(setenv("BXROOT_BINDS",value,1)==0);parse_binds();assert(0);
    } else if(!strcmp(argv[1],"path")) {
        value=map(1,4096);assert(setenv("BXROOT_BINDS",value,1)==0);parse_binds();assert(0);
    } else if(!strcmp(argv[1],"path-edge")) {
        value=map(1,4094);assert(setenv("BXROOT_BINDS",value,1)==0);parse_binds();
        assert(g_config.bind_count==1&&strlen(g_config.bind_sources[0])==4095);
    } else if(!strcmp(argv[1],"utf8")||!strcmp(argv[1],"utf8-over")) {
        size_t n=!strcmp(argv[1],"utf8")?2047:2048,i;value=malloc(n*2+20);assert(value);value[0]='/';
        for(i=0;i<n;i++){value[1+i*2]=(char)0xc3;value[2+i*2]=(char)0xa9;}
        strcpy(value+1+n*2,":/guest");assert(setenv("BXROOT_BINDS",value,1)==0);parse_binds();
        assert(n==2047&&strlen(g_config.bind_sources[0])==4095);
    } else if(!strcmp(argv[1],"bytes-edge")) {
        value=exact_map(DSHA_BIND_VALUE_BYTES);assert(setenv("BXROOT_BINDS",value,1)==0);parse_binds();
        assert(g_config.bind_count==48);
    } else if(!strcmp(argv[1],"memory")) {
        fail_calloc=1;assert(setenv("BXROOT_BINDS","/host:/guest",1)==0);parse_binds();assert(0);
    } else if(!strcmp(argv[1],"relative")) {
        assert(setenv("BXROOT_BINDS","host:/guest",1)==0);parse_binds();assert(0);
    } else if(!strcmp(argv[1],"field")||!strcmp(argv[1],"flag")||!strcmp(argv[1],"empty")) {
        const char *bad=!strcmp(argv[1],"field")?"/host":!strcmp(argv[1],"flag")?"/host:/guest:rw":"/host:/guest;";
        assert(setenv("BXROOT_BINDS",bad,1)==0);parse_binds();assert(0);
    } else if(!strcmp(argv[1],"bytes")) {
        value=malloc(DSHA_BIND_VALUE_BYTES+2);assert(value);
        memset(value,'a',DSHA_BIND_VALUE_BYTES+1);value[DSHA_BIND_VALUE_BYTES+1]=0;
        assert(setenv("BXROOT_BINDS",value,1)==0);parse_binds();assert(0);
    } else {
        const char *input[]={"ORDINARY=value",NULL};
        value=map(32,1200);assert(strlen(value)>PX_ENV_ENTRY_MAX);
        memset(&policy,0,sizeof(policy));forced=(px_env_kv){"BXROOT_BINDS",value,PX_ENV_SET};
        policy.forced=&forced;policy.forced_n=1;
        if(!strcmp(argv[1],"env-clean")||!strcmp(argv[1],"env-inherit")) {
            code=px_env_build(!strcmp(argv[1],"env-clean")?NULL:input,&policy,&out,NULL);
            assert(code==PX_OK);assert(!strcmp(px_env_lookup((const char *const *)out.v,"BXROOT_BINDS"),value));
            px_env_dispose(&out);
        } else if(!strcmp(argv[1],"env-bytes")) {
            free(value);value=malloc(DSHA_BIND_VALUE_BYTES+2);assert(value);
            memset(value,'a',DSHA_BIND_VALUE_BYTES+1);value[DSHA_BIND_VALUE_BYTES+1]=0;
            forced.value=value;assert(px_env_build(NULL,&policy,&out,NULL)==PX_ETOOLONG);
        } else if(!strcmp(argv[1],"env-budget")) {
            policy.max_bytes=100;assert(px_env_build(NULL,&policy,&out,NULL)==PX_ENOSPC);
        } else if(!strcmp(argv[1],"env-count")) {
            policy.max_entries=1;assert(px_env_build(input,&policy,&out,NULL)==PX_ENOSPC);
        } else if(!strcmp(argv[1],"env-merge-budget")) {
            forced=(px_env_kv){"LD_PRELOAD","/runtime.so",PX_ENV_MERGE_PRELOAD};policy.max_bytes=3;
            assert(px_env_build(NULL,&policy,&out,NULL)==PX_ENOSPC);
        } else if(!strcmp(argv[1],"env-merge-count")) {
            px_env_kv pair[]={{"FIRST","1",PX_ENV_SET},{"LD_PRELOAD","/runtime.so",PX_ENV_MERGE_PRELOAD}};
            policy.forced=pair;policy.forced_n=2;policy.max_entries=1;
            assert(px_env_build(NULL,&policy,&out,NULL)==PX_ENOSPC);
        } else assert(0);
    }
    puts("BIND_CONTRACT_READY");return 0;
}
