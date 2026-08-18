/* bench_conv2d.c — 2D convolution on AICore via im2col -> GEMM.
 * input 4x4, one 3x3 kernel, VALID -> output 2x2.
 *   Y[r][c] = sum_{kr,kc} X[r+kr][c+kc] * W[kr][kc]
 * im2col: A[patch][9] (4 patches), B[9][1] (one output channel). */
#include <stdint.h>
#include "bench_common.h"
#include "gemm_driver.c"

#define SHIFT 1
#define RELU  0

static const int8_t X[4][4] = {
    {1, 2, 3, 1},
    {0, 1, 0, 2},
    {2, 0, 1, 1},
    {1, 1, 2, 0},
};
static const int8_t W[3][3] = {
    {1, 0, -1},
    {0, 1, 0},
    {-1, 0, 1},
};

int main(void)
{
    int8_t a[4][32];
    int8_t b[9][8];
    int8_t cout[4][8];
    int32_t c[4][8];
    int r, cidx, kr, kc, ok = 1;

    /* im2col: patch p=(r,c) -> 9 elements */
    {
        int p = 0;
        for (r = 0; r < 2; r++)
            for (cidx = 0; cidx < 2; cidx++) {
                int t = 0;
                for (kr = 0; kr < 3; kr++)
                    for (kc = 0; kc < 3; kc++)
                        a[p][t++] = X[r + kr][cidx + kc];
                p++;
            }
    }
    /* B[k][j]: flatten W, 1 channel -> B[9][0] */
    {
        int t = 0;
        for (kr = 0; kr < 3; kr++)
            for (kc = 0; kc < 3; kc++)
                b[t++][0] = W[kr][kc];
    }

    if (gemm_quant(a, b, c, cout, 4, 9, 1, SHIFT, RELU, 0) != 0) {
        report(0);
    }

    /* reference */
    {
        int p = 0;
        for (r = 0; r < 2; r++)
            for (cidx = 0; cidx < 2; cidx++) {
                int32_t s = 0;
                for (kr = 0; kr < 3; kr++)
                    for (kc = 0; kc < 3; kc++)
                        s += X[r + kr][cidx + kc] * W[kr][kc];
                if (cout[p][0] != ref_quant(s, SHIFT, RELU)) ok = 0;
                p++;
            }
    }
    report(ok);
    return 0;
}
