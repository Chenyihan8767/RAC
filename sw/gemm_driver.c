/*
 * gemm_driver.c — RISC-V bare-metal driver for the AICore accelerator.
 *
 * Address map: AICore base register (AXI4-Lite MMIO slave).
 * Set BASE to the MMIO base address used in the SoC.
 */
#include <stdint.h>

#define BASE 0x30000000UL

#define REG_CTRL      (*(volatile uint32_t *)(BASE + 0x00))
#define REG_STATUS    (*(volatile uint32_t *)(BASE + 0x04))
#define REG_A_BASE    (*(volatile uint32_t *)(BASE + 0x08))
#define REG_B_BASE    (*(volatile uint32_t *)(BASE + 0x0C))
#define REG_C_BASE    (*(volatile uint32_t *)(BASE + 0x10))
#define REG_M_DIM     (*(volatile uint32_t *)(BASE + 0x14))
#define REG_K_DIM     (*(volatile uint32_t *)(BASE + 0x18))
#define REG_N_DIM     (*(volatile uint32_t *)(BASE + 0x1C))
#define REG_BUF_CTRL  (*(volatile uint32_t *)(BASE + 0x20))
#define REG_DATA      (*(volatile uint32_t *)(BASE + 0x24))
#define REG_QUANT     (*(volatile uint32_t *)(BASE + 0x28))

#define SEL_A   0u
#define SEL_B   1u
#define SEL_ACC 2u
#define SEL_OUT 3u
#define SEL_BIAS 4u

static void set_buf(uint32_t sel, uint32_t idx) { REG_BUF_CTRL = (idx << 4) | sel; }

/* Load an INT8 vector (bytes) into buffer `sel` starting at element `idx`. */
static void load_bytes(uint32_t sel, uint32_t idx, const int8_t *data, uint32_t n)
{
    set_buf(sel, idx);
    for (uint32_t i = 0; i < n; i++) REG_DATA = (uint32_t)(uint8_t)data[i];
}

/* Read one INT32 accumulator word. */
static int32_t read_acc(uint32_t word)
{
    set_buf(SEL_ACC, word);
    return (int32_t)REG_DATA;
}

/* Read one quantized INT8 output element (sign-extended to int32). */
static int32_t read_out(uint32_t word)
{
    set_buf(SEL_OUT, word);
    return (int32_t)REG_DATA;
}

static void start(void)  { REG_CTRL = 1u; }

static uint32_t busy(void) { return REG_STATUS & 1u; }
static uint32_t done(void) { return (REG_STATUS >> 1) & 1u; }

/*
 * gemm: C[M][N] = A[M][K] * B[K][N], all INT8, K <= 32, M,N <= 8.
 * A is stored transposed: A_T[k][i] = A[i][k] at buffer index k*8+i.
 * B stored as B[k][j] at index k*8+j. Rows padded to 8 bytes.
 */
int32_t gemm(const int8_t a[][32], const int8_t b[][8], int32_t c[][8], int m, int k, int n)
{
    int32_t i, j, kk;

    /* transpose A into the buffer layout expected by hardware */
    for (kk = 0; kk < k; kk++) {
        uint8_t row[8] = {0};
        for (i = 0; i < m; i++) row[i] = (uint8_t)a[i][kk];
        load_bytes(SEL_A, (uint32_t)kk * 8u, (const int8_t *)row, 8);
    }
    for (kk = 0; kk < k; kk++) {
        uint8_t row[8] = {0};
        for (j = 0; j < n; j++) row[j] = (uint8_t)b[kk][j];
        load_bytes(SEL_B, (uint32_t)kk * 8u, (const int8_t *)row, 8);
    }

    REG_M_DIM = (uint32_t)m;
    REG_K_DIM = (uint32_t)k;
    REG_N_DIM = (uint32_t)n;
    start();

    while (!done()) {
        /* spin */
    }
    if (busy()) return -1;

    for (i = 0; i < m; i++)
        for (j = 0; j < n; j++)
            c[i][j] = read_acc((uint32_t)(i * 8 + j));

    return 0;
}

/*
 * gemm_quant: GEMM + output quantization y = sat8(relu((acc+bias) >> shift)).
 * Fills `cout` (INT8) and also returns raw INT32 in `c` (may be NULL).
 * `relu`: 1 to enable ReLU. `bias`: per-column bias (n values, may be NULL).
 */
int32_t gemm_quant(const int8_t a[][32], const int8_t b[][8], int32_t c[][8],
                   int8_t cout[][8], int m, int k, int n, int shift, int relu,
                   const int32_t *bias)
{
    int32_t dummy[8][8];
    uint32_t q = ((uint32_t)(shift & 0xFu) << 4) | (relu ? 1u : 0u);
    if (bias != 0) {
        int32_t j;
        q |= (1u << 1); /* bias_en */
        set_buf(SEL_BIAS, 0);
        for (j = 0; j < n; j++) REG_DATA = (uint32_t)bias[j];
    }
    REG_QUANT = q;
    if (gemm(a, b, c ? c : dummy, m, k, n) != 0) return -1;

    if (cout != 0) {
        int32_t i, j;
        for (i = 0; i < m; i++)
            for (j = 0; j < n; j++)
                cout[i][j] = (int8_t)read_out((uint32_t)(i * 8 + j));
    }
    return 0;
}
