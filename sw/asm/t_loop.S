.section .text
.globl _start
_start:
    li x5, 0              # sum = 0
    li x6, 1              # i = 1
    li x7, 11             # limit
loop:
    add x5, x5, x6        # sum += i
    addi x6, x6, 1        # i++
    blt x6, x7, loop      # while i < 11
    la x8, 0x1000
    sw x5, 0(x8)          # result = 55
hang:
    j hang
