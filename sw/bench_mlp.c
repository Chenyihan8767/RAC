/* bench_mlp.c — 2-layer MLP inference on AICore (GEMM + bias + ReLU).
 * layer1: 8 -> 4, ReLU + shift1 ; layer2: 4 -> 2, shift2.
 *   y1 = sat8(relu((W1^T x + b1) >> shift1))
 *   y2 =       (W2^T y1 + b2) >> shift2          */
#include <stdint.h>
#include "bench_common.h"
#include "gemm_driver.c"

#define SHIFT1 2
#define SHIFT2 1

static const int8_t x[8] = {1, -1, 2, 0, 3, 1, -2, 1};
static const int8_t w1[8][4] = {
    {1, 0, -1, 2}, {0, 1, 1, 0}, {1, 1, 0, -1}, {0, 0, 1, 1},
    {1, -1, 0, 1}, {0, 1, -1, 0}, {1, 0, 1, -1}, {0, -1, 0, 1},
};
static const int32_t b1[4] = {1, -1, 0, 2};
static const int8_t w2[4][2] = {
    {1, 0}, {0, 1}, {1, 1}, {-1, 1},
};
static const int32_t b2[2] = {0, 1};

int main(void)
{
    int8_t y1[4][8];
    int32_t c[4][8];
    int j, k, ok = 1;

    /* layer1: A = x (1x8), B = w1^T (8x4) */
    {
        int8_t a1[1][32];
        int8_t b1m[8][8];
        for (j = 0; j < 8; j++) a1[0][j] = x[j];
        for (j = 0; j < 8; j++)
            for (k = 0; k < 4; k++)
                b1m[j][k] = w1[j][k];
        if (gemm_quant(a1, b1m, c, y1, 1, 8, 4, SHIFT1, 1, b1) != 0) report(0);
    }

    /* layer2: A = y1 (1x4), B = w2^T (4x2) */
    {
        int8_t a2[1][32];
        int8_t b2m[4][8];
        int32_t c2[1][8];
        int8_t y2[1][8];
        for (j = 0; j < 4; j++) a2[0][j] = y1[0][j];
        for (j = 0; j < 4; j++)
            for (k = 0; k < 2; k++)
                b2m[j][k] = w2[j][k];
        if (gemm_quant(a2, b2m, c2, y2, 1, 4, 2, SHIFT2, 0, b2) != 0) report(0);

        /* reference */
        {
            int32_t y1r[4], y2r[2];
            for (j = 0; j < 4; j++) {
                int32_t s = b1[j];
                for (k = 0; k < 8; k++) s += x[k] * w1[k][j];
                y1r[j] = ref_quant(s, SHIFT1, 1);
            }
            for (j = 0; j < 2; j++) {
                int32_t s = b2[j];
                for (k = 0; k < 4; k++) s += y1r[k] * w2[k][j];
                y2r[j] = ref_quant(s, SHIFT2, 0);
            }
            if (y2[0][0] != y2r[0] || y2[0][1] != y2r[1]) ok = 0;
        }
    }
    report(ok);
    return 0;
}
