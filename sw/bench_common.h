/* bench_common.h — shared helpers for AI benchmark programs */
#ifndef BENCH_COMMON_H
#define BENCH_COMMON_H

#include <stdint.h>

#define RESULT_ADDR 0x00001000UL
#define OK   0x600d
#define FAIL 0xdead

/* INT8 quantization reference: sat8(relu(v >> shift)) */
static inline int32_t ref_quant(int32_t v, int shift, int relu)
{
    v >>= shift;
    if (relu && v < 0) v = 0;
    if (v > 127) v = 127;
    else if (v < -128) v = -128;
    return v;
}

static inline void report(int32_t ok)
{
    *(volatile uint32_t *)RESULT_ADDR = (uint32_t)(ok ? OK : FAIL);
    for (;;) {
    }
}

#endif
