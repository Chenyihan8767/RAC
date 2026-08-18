/* dma_test.c — verify the AICore DMA mode on the SoC:
 *  1. CPU copies A_T / B into SRAM (A_ADDR / B_ADDR)
 *  2. programs A_BASE/B_BASE/C_BASE + dims
 *  3. starts with CTRL DMA-mode bit
 *  4. AICore DMA engine reads matrices, computes, writes C to SRAM
 *  5. CPU checks C and reports OK/FAIL to RESULT_ADDR */
#include <stdint.h>

#define RESULT_ADDR 0x00001000UL
#define A_ADDR      0x00002000UL
#define B_ADDR      0x00003000UL
#define C_ADDR      0x00004000UL

#define OK   0x600d
#define FAIL 0xdead

#define REG_CTRL    (*(volatile uint32_t *)(0x30000000UL))
#define REG_STATUS  (*(volatile uint32_t *)(0x30000004UL))
#define REG_A_BASE  (*(volatile uint32_t *)(0x30000008UL))
#define REG_B_BASE  (*(volatile uint32_t *)(0x3000000CUL))
#define REG_C_BASE  (*(volatile uint32_t *)(0x30000010UL))
#define REG_M_DIM   (*(volatile uint32_t *)(0x30000014UL))
#define REG_K_DIM   (*(volatile uint32_t *)(0x30000018UL))
#define REG_N_DIM   (*(volatile uint32_t *)(0x3000001CUL))
#define REG_QUANT   (*(volatile uint32_t *)(0x30000028UL))

static const int8_t a[2][3] = {{1, 2, 3}, {4, 5, 6}};
static const int8_t b[3][3] = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};

int main(void)
{
    int i, j, kk;
    volatile uint8_t *ab = (volatile uint8_t *)A_ADDR;
    volatile uint8_t *bb = (volatile uint8_t *)B_ADDR;
    volatile int32_t *cb = (volatile int32_t *)C_ADDR;
    int32_t result = FAIL;

    /* stage matrices in DMA buffer layout: A_T[k][i] at k*8+i, B[k][j] at k*8+j */
    for (kk = 0; kk < 3; kk++)
        for (i = 0; i < 2; i++)
            ab[kk * 8 + i] = (uint8_t)a[i][kk];
    for (kk = 0; kk < 3; kk++)
        for (j = 0; j < 3; j++)
            bb[kk * 8 + j] = (uint8_t)b[kk][j];

    REG_A_BASE = A_ADDR;
    REG_B_BASE = B_ADDR;
    REG_C_BASE = C_ADDR;
    REG_M_DIM = 2;
    REG_K_DIM = 3;
    REG_N_DIM = 3;
    REG_QUANT = 0;
    REG_CTRL = 0x5; /* start (bit0) + DMA mode (bit2) */

    while (!((REG_STATUS >> 1) & 1)) {
        /* spin */
    }

    if (cb[0 * 8 + 0] == 1 && cb[0 * 8 + 1] == 2 && cb[0 * 8 + 2] == 3 &&
        cb[1 * 8 + 0] == 4 && cb[1 * 8 + 1] == 5 && cb[1 * 8 + 2] == 6) {
        result = OK;
    }
    *(volatile uint32_t *)RESULT_ADDR = (uint32_t)result;
    return 0;
}
