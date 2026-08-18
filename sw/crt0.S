.section .text
.globl _start
_start:
    la sp, __stack_top
    jal ra, main
1:  j 1b
