/* bench_fp16.c — FP16 model running on the INT8 accelerator.
 * 1. FP16 weights/inputs (exact binary16 values) are quantized to INT8 with
 *    a power-of-2 scale using fp16_to_scaled_int (pure integer).
 * 2. The INT8 GEMM runs on the AICore.
 * 3. The raw INT32 accumulator is checked against the integer reference.
 *
 * Demonstrates the standard FP16 -> INT8 quantized inference flow. */
#include <stdint.h>
#include "bench_common.h"
#include "fp16.h"
#include "gemm_driver.c"

#define SHIFT 4 /* scale = 2^-4, values fit INT8 */

/* 2x2 FP16 matrices (exact binary16 bit patterns) */
static const fp16_t a16[2][2] = {
    {0x3800, 0x3C00}, /* 0.5, 1.0 */
    {0xB400, 0x4000}, /* -0.25, 2.0 */
};
static const fp16_t b16[2][2] = {
    {0x3C00, 0x3800}, /* 1.0, 0.5 */
    {0x3400, 0xBC00}, /* 0.25, -1.0 */
};

int main(void)
{
    int8_t a[2][32];
    int8_t b[2][8];
    int32_t c[2][8];
    int i, j, k, ok = 1;

    for (i = 0; i < 2; i++)
        for (k = 0; k < 2; k++)
            a[i][k] = (int8_t)fp16_to_scaled_int(a16[i][k], SHIFT);
    for (k = 0; k < 2; k++)
        for (j = 0; j < 2; j++)
            b[k][j] = (int8_t)fp16_to_scaled_int(b16[k][j], SHIFT);

    if (gemm(a, b, c, 2, 2, 2) != 0) report(0);

    /* reference: integer GEMM of the quantized matrices */
    for (i = 0; i < 2; i++)
        for (j = 0; j < 2; j++) {
            int32_t s = 0;
            for (k = 0; k < 2; k++) s += a[i][k] * b[k][j];
            if (c[i][j] != s) ok = 0;
        }
    report(ok);
    return 0;
}
