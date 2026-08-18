/* main.c — bare-metal SoC test: run a GEMM on AICore and write the result
 * to a fixed SRAM address (0x00002000) for external checking. */
#include <stdint.h>
#include "gemm_driver.c"

#define RESULT_ADDR 0x00002000UL
#define DUMP_BASE   0x00003000UL

#define OK   0x600d
#define FAIL 0xdead

int main(void)
{
    /* A (2x3), B (3x3) */
    static const int8_t a[2][32] = {
        {1, 2, 3}, {4, 5, 6},
    };
    static const int8_t b[3][8] = {
        {1, 0, 0}, {0, 1, 0}, {0, 0, 1},
    };
    int32_t c[2][8];
    int32_t result = FAIL;

    if (gemm(a, b, c, 2, 3, 3) == 0) {
        /* expected: c = a * I = a */
        if (c[0][0] == 1 && c[0][1] == 2 && c[0][2] == 3 &&
            c[1][0] == 4 && c[1][1] == 5 && c[1][2] == 6) {
            result = OK;
        }
    }
    *(volatile uint32_t *)RESULT_ADDR = (uint32_t)result;

    /* dump the GEMM result matrix for debug / verification */
    {
        int i, j;
        for (i = 0; i < 2; i++)
            for (j = 0; j < 8; j++)
                *(volatile uint32_t *)(DUMP_BASE + 4 * (i * 8 + j)) = (uint32_t)c[i][j];
    }
    return 0;
}
