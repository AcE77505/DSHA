.text
.global _start
_start:
    adr x0,path
    sub sp,sp,#32
    str x0,[sp]
    str xzr,[sp,#8]
    mov x1,sp
    mov x2,#0
    mov x8,#221
    svc #0
    cmn x0,#95
    b.ne fail
    mov x0,#1
    adr x1,message
    mov x2,#22
    mov x8,#64
    svc #0
    mov x0,#0
    mov x8,#93
    svc #0
fail:
    mov x0,#21
    mov x8,#93
    svc #0
path:
    .asciz "/bin/true"
message:
    .ascii "STATIC_EXEC_REJECT_OK\n"
