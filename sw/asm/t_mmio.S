.section .text
.globl _start
_start:
    li x5, 8
    la x6, 0x30000014
    sw x5, 0(x6)        # write M_DIM = 8
    lw x7, 0(x6)        # read back M_DIM
    la x8, 0x1000
    sw x7, 0(x8)        # result = read-back value
hang:
    j hang
