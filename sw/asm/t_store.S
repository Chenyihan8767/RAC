.section .text
.globl _start
_start:
    li x5, 42
    la x8, 0x1000
    sw x5, 0(x8)      # mem[0x1000] = 42
    sw x8, 4(x8)      # mem[0x1004] = x8
hang:
    j hang
