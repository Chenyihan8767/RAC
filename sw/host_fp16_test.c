/* host_fp16_test.c — verify fp16.h conversions on the host (native gcc). */
#include <stdio.h>
#include <math.h>
#include <stdint.h>
#include "fp16.h"

static int fails = 0;
static void chk(const char *name, int cond)
{
    if (!cond) { printf("FAIL: %s\n", name); fails++; }
    else printf("OK:   %s\n", name);
}

int main(void)
{
    chk("0.5", fabsf(fp16_to_float(0x3800) - 0.5f) < 1e-6f);
    chk("1.0", fp16_to_float(0x3C00) == 1.0f);
    chk("-0.25", fp16_to_float(0xB400) == -0.25f);
    chk("2.0", fp16_to_float(0x4000) == 2.0f);
    chk("0.25", fp16_to_float(0x3400) == 0.25f);
    chk("-1.0", fp16_to_float(0xBC00) == -1.0f);
    chk("subnormal 2^-24", fabsf(fp16_to_float(0x0001) - 5.96e-8f) < 1e-10f);

    /* float->fp16 round trip */
    chk("rt 0.5", float_to_fp16(0.5f) == 0x3800);
    chk("rt -0.25", float_to_fp16(-0.25f) == 0xB400);
    chk("rt 3.25", float_to_fp16(3.25f) == 0x4280);

    /* integer quantization with shift=4 */
    chk("q 0.5 -> 8", fp16_to_scaled_int(0x3800, 4) == 8);
    chk("q 1.0 -> 16", fp16_to_scaled_int(0x3C00, 4) == 16);
    chk("q -0.25 -> -4", fp16_to_scaled_int(0xB400, 4) == -4);
    chk("q 2.0 -> 32", fp16_to_scaled_int(0x4000, 4) == 32);
    chk("q 0.25 -> 4", fp16_to_scaled_int(0x3400, 4) == 4);

    if (fails == 0) printf("ALL PASS\n");
    return fails;
}
