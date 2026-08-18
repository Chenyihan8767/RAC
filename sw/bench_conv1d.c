/* bench_conv1d.c — 1D convolution on AICore (im2col -> GEMM).
 * input x[6], 2 output channels with 3-tap kernels.
 *   y[c][i] = sum_k x[i+k] * w[c][k],  i = 0..3
 * im2col: A[i][k] = x[i+k] (4x3), B[k][j] = w[j][k] (3x2). */
#include <stdint.h>
#include "bench_common.h"
#include "gemm_driver.c"

#define SHIFT 2
#define RELU  1

static const int8_t x[6] = {1, 2, 3, 4, 5, 6};
static const int8_t w[2][3] = {{1, 0, -1}, {0, 1, 1}};

int main(void)
{
    int8_t a[4][32];
    int8_t b[3][8];
    int8_t cout[4][8];
    int32_t c[4][8];
    int i, j, k, ok = 1;

    /* im2col: A[i][k] = x[i+k] */
    for (i = 0; i < 4; i++)
        for (k = 0; k < 3; k++)
            a[i][k] = x[i + k];
    /* B[k][j] = w[j][k] */
    for (k = 0; k < 3; k++)
        for (j = 0; j < 2; j++)
            b[k][j] = w[j][k];

    if (gemm_quant(a, b, c, cout, 4, 3, 2, SHIFT, RELU, 0) != 0) {
        report(0);
    }

    /* debug: dump cout and reference for external checking */
    {
        volatile int32_t *dbg = (volatile int32_t *)0x2000UL;
        int p = 0;
        for (i = 0; i < 4; i++)
            for (j = 0; j < 2; j++) {
                dbg[p] = cout[i][j];
                dbg[8 + p] = c[i][j];
                dbg[16 + p] = -999;
                p++;
            }
    }

    for (i = 0; i < 4; i++) {
        for (j = 0; j < 2; j++) {
            int32_t s = 0;
            for (k = 0; k < 3; k++) s += x[i + k] * w[j][k];
            if (cout[i][j] != ref_quant(s, SHIFT, RELU)) ok = 0;
        }
    }
    report(ok);
    return 0;
}
