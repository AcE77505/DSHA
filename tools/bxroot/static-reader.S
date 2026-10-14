.text
.global _start
_start:
    mov x0,#-100
    adr x1,path
    mov x2,#0
    mov x3,#0
    mov x8,#56
    svc #0
    cmp x0,#0
    b.lt fail
    mov x19,x0
    sub sp,sp,#256
    mov x1,sp
    mov x2,#128
    mov x8,#63
    svc #0
    mov x2,x0
    mov x0,#1
    mov x1,sp
    mov x8,#64
    svc #0
    mov x0,x19
    mov x8,#57
    svc #0
    mov x0,#0
    mov x8,#93
    svc #0
fail:
    mov x0,#20
    mov x8,#93
    svc #0
path:
    .asciz "/DSHA_BXROOT_GUEST_MARKER"
