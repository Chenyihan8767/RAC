# Makefile — cross-compile the AICore software (bare-metal RISC-V rv32im)
CROSS  ?= riscv64-unknown-elf-
CC      = $(CROSS)gcc
OBJDUMP = $(CROSS)objdump
SIZE    = $(CROSS)size
CFLAGS  = -march=rv32im -mabi=ilp32 -O2 -Wall -ffreestanding
MKHEX   = python3 mkhex.py

all: gemm_test.elf gemm_test.hex dma_test.elf dma_test.hex
	$(SIZE) gemm_test.elf dma_test.elf

# SoC bare-metal GEMM program (crt0 + main + driver) linked for the SoC memory map
gemm_test.elf: main.c gemm_driver.c crt0.S link.ld
	$(CC) $(CFLAGS) -nostdlib -T link.ld -Wl,-e,_start -o $@ crt0.S main.c
	$(OBJDUMP) -d $@ > gemm_test.dis

gemm_test.hex: gemm_test.elf
	$(MKHEX) $< $@

# SoC DMA-mode test program
dma_test.elf: dma_test.c crt0.S link.ld
	$(CC) $(CFLAGS) -nostdlib -T link.ld -Wl,-e,_start -o $@ crt0.S dma_test.c
	$(OBJDUMP) -d $@ > dma_test.dis

dma_test.hex: dma_test.elf
	$(MKHEX) $< $@

# AI benchmark programs
BENCH := bench_conv1d bench_conv2d bench_mlp bench_fp16
bench: $(BENCH:%=%.hex)

%.hex: %.elf
	$(MKHEX) $< $@

%.elf: %.c gemm_driver.c bench_common.h crt0.S link.ld
	$(CC) $(CFLAGS) -nostdlib -T link.ld -Wl,-e,_start -o $@ crt0.S $<
	$(OBJDUMP) -d $@ > $*.dis

# RISC-V core unit-test programs (hand-written assembly -> hex)
ASM_TESTS := t_arith_mem t_loop t_bytes t_mul
asm: $(ASM_TESTS:%=asm/%.hex)

asm/%.hex: asm/%.S
	$(CC) $(CFLAGS) -nostdlib -Wl,-Ttext=0 -Wl,-e,_start -o /tmp/$*.elf $<
	$(MKHEX) /tmp/$*.elf $@

clean:
	rm -f gemm_test.elf gemm_test.hex gemm_test.dis asm/*.hex
