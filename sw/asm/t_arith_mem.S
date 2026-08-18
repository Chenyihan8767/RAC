.section .text
.globl _start
_start:
    li x5, 100
    li x6, 7
    add x7, x5, x6        # x7 = 107
    la x13, 0x1010        # scratch in SRAM
    sw x7, 0(x13)         # mem[0x1010] = 107
    lw x8, 0(x13)         # x8 = mem[0x1010]
    bne x7, x8, fail      # verify load matches store
    la x9, 0x1000
    sw x7, 0(x9)          # result = 107
hang:
    j hang
fail:
    j fail
