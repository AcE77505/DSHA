.text
.global _start
_start:
    mov x8,x0
    svc #0
    mov x0,#0
    mov x8,#93
    svc #0
