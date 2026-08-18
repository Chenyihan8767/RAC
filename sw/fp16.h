/* fp16.h — minimal IEEE-754 half-precision (binary16) helpers for the
 * RV32 core. Conversions go through binary32 since the core has no FPU.
 * Used to demonstrate running FP16-trained models on the INT8 accelerator
 * via quantization. */
#ifndef FP16_H
#define FP16_H

#include <stdint.h>

typedef uint16_t fp16_t;

/* fp16 -> binary32 bit pattern */
static inline uint32_t fp16_to_f32_bits(fp16_t h)
{
    uint32_t s = ((uint32_t)h & 0x8000u) << 16;   /* sign */
    uint32_t e = ((uint32_t)h >> 10) & 0x1Fu;     /* exponent */
    uint32_t m = ((uint32_t)h) & 0x3FFu;          /* mantissa */
    uint32_t eo, mo;

    if (e == 0) {                        /* subnormal or zero */
        if (m == 0) { eo = 0; mo = 0; }
        else {
            int p = 0;
            uint32_t t = m;
            while (t > 1) { t >>= 1; p++; }        /* leading bit position */
            eo = (uint32_t)(127 + p - 24);
            mo = (m & ((1u << p) - 1u)) << (22 - p);
        }
    } else if (e == 31) {                /* inf / nan */
        eo = 0xFFu;
        mo = m << 13;
    } else {                             /* normal */
        eo = e - 15u + 127u;
        mo = m << 13;
    }
    return s | (eo << 23) | mo;
}

/* binary32 -> fp16 */
static inline fp16_t f32_to_fp16(uint32_t f)
{
    uint32_t s = (f >> 16) & 0x8000u;
    uint32_t e = (f >> 23) & 0xFFu;
    uint32_t m = f & 0x7FFFFFu;
    uint32_t eo, mo;

    if (e == 0xFFu) { eo = 31; mo = (m != 0) ? 0x200u : 0; }
    else if (e >= 0x8Fu) { eo = 31; mo = 0; }            /* overflow -> inf */
    else if (e >= 0x71u) {                                /* normal (2^-14..65504) */
        eo = e - 112u;
        mo = (m + 0x1000u) >> 13;                         /* round to nearest */
        if (mo > 0x3FFu) { eo++; mo = 0; }                /* round carry */
    } else {                                              /* subnormal or 0 */
        uint32_t sh = 126u - e;                           /* e in [0x67..0x70] */
        uint64_t v = (uint64_t)(0x800000u | m);
        if (sh > 0) v = (v + (uint64_t)(1u << (sh - 1))) >> sh;
        eo = 0;
        mo = (uint32_t)(v & 0x3FFu);
        if (mo & 0x400u) { eo = 1; mo = 0; }              /* rounded to min normal */
    }
    return (fp16_t)(s | (eo << 10) | (mo & 0x3FFu));
}

/* fp16 -> float (for host-side reference computation via soft-float) */
static inline float fp16_to_float(fp16_t h)
{
    union { uint32_t u; float f; } c;
    c.u = fp16_to_f32_bits(h);
    return c.f;
}

static inline fp16_t float_to_fp16(float f)
{
    union { uint32_t u; float f; } c;
    c.f = f;
    return f32_to_fp16(c.u);
}

/* Quantize an fp16 value to INT8: q = clamp(round(v / scale)) */
static inline int8_t fp16_quant(fp16_t v, float scale)
{
    float q = fp16_to_float(v) / scale;
    int32_t r;
    /* round to nearest */
    q = (q >= 0.0f) ? q + 0.5f : q - 0.5f;
    r = (int32_t)q;
    if (r > 127) r = 127;
    else if (r < -128) r = -128;
    return (int8_t)r;
}

/* Integer quantization: q = round(v * 2^shift), pure integer (no FPU).
 * shift such that the result fits INT8. Used on the bare-metal core. */
static inline int32_t fp16_to_scaled_int(fp16_t h, int shift)
{
    uint32_t s = (uint32_t)(h >> 15) & 1u;
    int32_t e = (int32_t)((h >> 10) & 0x1Fu);
    int32_t m = (int32_t)(h & 0x3FFu);
    int32_t frac;
    int32_t bias;
    int32_t v;

    if (e == 0) {
        frac = m;                  /* subnormal: m * 2^(-24) */
        bias = shift - 24;
    } else if (e == 31) {
        return 0;                  /* inf/nan -> 0 */
    } else {
        frac = 0x400 | m;          /* 1.mantissa -> (1024+m) * 2^(e-25) */
        bias = e - 25 + shift;
    }
    if (bias >= 0) v = frac << bias;
    else v = frac >> (-bias);
    return s ? -v : v;
}

#endif
