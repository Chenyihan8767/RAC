.section .text
.globl _start
_start:
    li x5, 6
    li x6, 7
    mul x7, x5, x6        # 42
    li x8, -3
    li x9, 100
    mul x10, x8, x9       # -300
    mulh x11, x8, x9      # high(-3*100) = -1
    li x12, -1
    li x13, -1
    mulhu x14, x12, x13   # high(0xFFFFFFFF*0xFFFFFFFF) = 0xFFFFFFFE
    mulhsu x16, x12, x13  # high(-1 * 0xFFFFFFFF) = -1
    la x17, 0x1000
    sw x7, 0(x17)         # 42
    sw x10, 4(x17)        # -300
    sw x11, 8(x17)        # -1
    sw x14, 12(x17)       # 0xFFFFFFFE
    sw x16, 16(x17)       # -1
hang:
    j hang
