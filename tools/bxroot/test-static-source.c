/* Compile the actual patched implementation, not a copied scanner. */
#include "src/runtime/static_exec.c"
#include <stdio.h>
int main(void) {
    uint32_t known_path[]={0xd2800708u,0xd4000001u};
    uint32_t known_exec[]={0xd2801ba8u,0xd4000001u};
    uint32_t opaque[]={0xaa0003e8u,0xd4000001u};
    sx_dsha_strict=1;sx_dsha_opaque_svc=0;
    if(sx_scan(known_path,2,NULL,0)!=1 || sx_dsha_opaque_svc)return 1;
    if(sx_scan(known_exec,2,NULL,0)!=1 || sx_dsha_opaque_svc)return 2;
    if(sx_scan(opaque,2,NULL,0)!=0 || !sx_dsha_opaque_svc)return 3;
    if(px_static_dispatch(0,0,0,0,0,0,221)!=-ENOTSUP)return 4;
    if(px_static_dispatch(0,0,0,0,0,0,281)!=-ENOTSUP)return 5;
    puts("PATCHED_STATIC_SCAN_DISPATCH_OK");return 0;
}
