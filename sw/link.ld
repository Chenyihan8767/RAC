OUTPUT_ARCH(riscv)
ENTRY(_start)

MEMORY {
    ROM (rx)  : ORIGIN = 0x00000000, LENGTH = 0x1000   /* boot ROM 4KB */
    RAM (rwx) : ORIGIN = 0x00001000, LENGTH = 0x4000   /* SRAM 16KB */
}

SECTIONS {
    .text : {
        *(.text*)
        *(.rodata*)
    } > ROM

    .data : { *(.data*) } > RAM AT > ROM
    .bss  : { *(.bss*) *(COMMON) } > RAM

    __stack_top = ORIGIN(RAM) + LENGTH(RAM);
}
