.section .text
.globl _start
_start:
    li x5, -1
    la x10, 0x1100
    sw x5, 0(x10)         # mem[0x1100] = 0xFFFFFFFF
    lb x6, 3(x10)         # sign-extend byte 0xFF -> x6 = -1
    la x11, 0x1200
    sb x6, 0(x11)         # store byte 0xFF -> mem[0x1200] = 0xFFFFFF00
    lw x7, 0(x11)         # x7 = 0xFFFFFF00
    la x12, 0x1000
    sw x7, 0(x12)         # result = 0xFFFFFF00
hang:
    j hang
