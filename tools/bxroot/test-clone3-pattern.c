/* This includes the actual overlaid matcher, extracted by test-overlay.py. */
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include "livepatch-clone3-test.inc"
static const uint32_t shape[] = {
    0xaa0a03e0,0xd2803668,0xd4000001,0xf100001f,0x540000a0,0x5400004b,
    0xd65f03c0,0x17fcf30a,0xd503201f,0xd280001d,0xaa0c03e0,0xd63f0160,
    0xd2800ba8,0xd4000001
};
static int patch(uint32_t *code, size_t words) {
    return lp_patch_clone3_fallback((uintptr_t)code,(uintptr_t)(code+words));
}
int main(int argc, char **argv) {
    uint32_t buf[28],before[28];size_t index;
    assert(argc==2);
    memcpy(buf,shape,sizeof(shape));
    if(strcmp(argv[1],"positive")==0) {
        assert(patch(buf,14)==1);
        assert(buf[2]==0x928004a0u && buf[2]!=0xd2800000u);
        for(index=0;index<14;index++)if(index!=2)assert(buf[index]==shape[index]);
    } else if(strcmp(argv[1],"mutations")==0) {
        for(index=0;index<14;index++) {
            if(index==7)continue;
            memcpy(buf,shape,sizeof(shape));buf[index]^=1;
            memcpy(before,buf,sizeof(shape));
            assert(patch(buf,14)==0);
            assert(memcmp(before,buf,sizeof(shape))==0);
        }
        memcpy(buf,shape,sizeof(shape));buf[7]=0x94000000u;
        memcpy(before,buf,sizeof(shape));
        assert(patch(buf,14)==0 && memcmp(before,buf,sizeof(shape))==0);
    } else if(strcmp(argv[1],"duplicate")==0) {
        memcpy(buf+14,shape,sizeof(shape));memcpy(before,buf,sizeof(buf));
        assert(patch(buf,28)==0 && memcmp(before,buf,sizeof(buf))==0);
    } else if(strcmp(argv[1],"short")==0) {
        memcpy(before,buf,sizeof(shape));
        for(index=0;index<14;index++) {
            assert(patch(buf,index)==0 && memcmp(before,buf,sizeof(shape))==0);
        }
    } else if(strcmp(argv[1],"idempotent")==0) {
        assert(patch(buf,14)==1);memcpy(before,buf,sizeof(shape));
        assert(patch(buf,14)==0 && memcmp(before,buf,sizeof(shape))==0);
    } else assert(0);
    printf("CLONE3_%s_READY\n",argv[1]);return 0;
}
