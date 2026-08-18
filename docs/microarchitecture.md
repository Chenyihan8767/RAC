# AICore 微架构规格（Phase 1 实现）

> 目标：面向 AI 的 INT8 GEMM 加速器，作为 RISC-V 主核的 AXI4-Lite MMIO 外设。
> 最新完整接口/寄存器/时序/上板细节见 **`chip_manual.md`**；验证见 **`verification.md`**。

## 0. 模块划分（迭代 3/5 模块化重构）

```
AICore (top)                        src/main/scala/accel/AICore.scala
├── AxiLiteSlave                    AxiLiteSlave.scala   — AXI4-Lite → RegBus
├── DataBuffer (×2)                 DataBuffer.scala     — A_T / B 缓冲（PIO + wordWr）
├── MacArray                        MacArray.scala       — 8×8 MAC + INT32 累加
├── Quantizer                       Quantizer.scala      — sat8(relu((acc+bias)>>shift))
└── master 口                       DMA（LOAD/STORE，见 chip_manual 7a）

SoC (top)                           src/main/scala/accel/soc/SoC.scala
├── Rv32iCore                       core/Rv32iCore.scala — RV32IM 单发射核
├── ParamRom / CoreRam / AxiBridge / MemArbiter
└── AICore
```

每个模块均有独立 chiseltest（见 `verification.md`）。参数见 `Params.scala`（`AIConfig`）。

## 1. 总体架构

```
                    ┌──────────────────────────────────────────┐
                    │               AICore                     │
 RISC-V 主核 ──────►│  AXI4-Lite Slave ──► 寄存器堆 + 控制器 FSM│
 (自研 RV32I)       │                          │               │
                    │   ┌────────┐   ┌──────┐  │               │
                    │   │A 缓冲  │   │B 缓冲│  ▼               │
                    │   │8×Kmax  │   │8×Kmax│                 │
                    │   └────────┘   └──────┘                 │
                    │        │            │                    │
                    │        ▼            ▼                    │
                    │   ┌─────────────────────────┐            │
                    │   │   MAC 阵列 8×8 (INT8)     │           │
                    │   │   output-stationary      │           │
                    │   │   INT32 累加器 8×8       │           │
                    │   └─────────────────────────┘            │
                    │              │                            │
                    │   PIO 读出：累加器 8×8 × 32bit            │
                    └──────────────────────────────────────────┘
```

- 主核通过 **MMIO（AXI4-Lite）** 访问加速器。
- Phase 1 为 **PIO 模式**：数据经 MMIO 直接装入缓冲、结果经 MMIO 读回。
- Phase 2 预留：A_BASE/B_BASE/C_BASE 地址寄存器 + AXI4 Master DMA（访存自动搬运）。

## 2. 计算核心：MAC 阵列（8×8，output-stationary）

计算 C[i][j] = Σ_k A[i][k]·B[k][j]，i,j ∈ [0,8)：

- PE(i,j) 内部持有累加器 C[i][j]（INT32），权重 B[k][j] 从 B 缓冲逐 k 进入，激活值 A[i][k] 从 A 缓冲逐 k 进入。
- 每个时钟处理一个 k 步：全部 64 个 PE 并行执行 `acc += a·b`。
- 完成全部 K 步后，累加器即最终结果。

## 3. 数据布局（PIO 装入时按此写）

| 缓冲 | 逻辑内容 | 物理布局 | 字节偏移 |
|---|---|---|---|
| A 缓冲 | A[i][k]，i∈[0,8)，k∈[0,K) | 按 k 主序：`A_T[k][i]` | `k*8 + i` |
| B 缓冲 | B[k][j]，k∈[0,K)，j∈[0,8) | 按 k 主序：`B[k][j]` | `k*8 + j` |
| 累加器 | C[i][j]，32bit | 按 i 主序 | `(i*8+j)*4`（字索引 `i*8+j`）|

> 软件需把 A 转置后按 `A_T[k][i]` 写入（驱动已封装）。

## 4. 寄存器映射（32bit，AXI4-Lite，偏移相对基址）

| 偏移 | 名称 | 读写 | 位域 |
|---|---|---|---|
| 0x00 | CTRL | RW | [0] start(写1启动)；[1] clear_done(写1清 done)；其余保留 |
| 0x04 | STATUS | RO | [0] busy；[1] done |
| 0x08 | A_BASE | RW | A 矩阵基址（预留，Phase 2 DMA 用） |
| 0x0C | B_BASE | RW | B 矩阵基址（预留） |
| 0x10 | C_BASE | RW | C 矩阵基址（预留） |
| 0x14 | M_DIM | RW | A 行数 M（≤8） |
| 0x18 | K_DIM | RW | 内维 K（≤32） |
| 0x1C | N_DIM | RW | B 列数 N（≤8） |
| 0x20 | BUF_CTRL | RW | [1:0] sel（0=A，1=B，2=ACC，3=OUT）；[11:0] index（元素索引） |
| 0x24 | DATA | RW | 数据端口。写：装入 sel 所指缓冲；读：读出。访问后 index 自动 +1 |
| 0x28 | QUANT | RW | [0] relu_en；[7:4] shift（算术右移，输出量化缩放） |
| 0x2C | PERF | RO | 最近一次运算 compute+quant 周期数（用于性能测量） |

- 写 A/B 缓冲：`DATA[7:0]` 写入 `sel` 缓冲的 `index` 元素（字节）。
- 读 ACC：`DATA` = 累加器 `index` 字（INT32）；读 OUT：量化后的 INT8（符号扩展）。
- `M/N/K_DIM` 上限由参数 `arraySize=8, maxK=32` 决定。

## 5. 控制流（FSM）

```
IDLE ──(CTRL.start)──► COMPUTE ──(k==K_DIM-1)──► QUANT ──► DONE
  ▲                     │(每周期 k++)               │(1 周期)
  └────────(新 start / CTRL.clear_done)──────────────┘
```

1. IDLE：主核写 CTRL.start=1 → 锁存维度，清空累加器与 PERF，k=0，进入 COMPUTE。
2. COMPUTE：每周期读 A_T[k]、B[k]，64 个 PE 同时累加，k++；`k==K_DIM-1` 后进入 QUANT。
3. QUANT（1 周期）：对最终累加值做量化 `y = sat8(relu(acc >> shift))` 写入 OUT 缓冲，随后进入 DONE（done=1）。
4. DONE：busy=0、done=1。主核轮询 STATUS 读到 done，读回 OUT（INT8）或 ACC（INT32 原始）。
5. 超范围抑制：`i≥M_DIM` 或 `j≥N_DIM` 的 PE 不参与累加。
6. 性能：PERF 寄存器给出本次 compute+quant 总周期数（=K+1，不含 PIO 搬运开销）。

## 6. AXI4-Lite 从机行为

- 32bit 数据，单笔事务，无 burst。
- AW 与 W 通道可乱序到达（内部缓冲合并后写寄存器，B 响应）。
- AR 通道：1 拍握手后下一周期返回数据（R 有效，含 resp=OKAY）。
- 写 DATA（缓冲装入）与读 DATA（累加器读出）在软件中由主核保证顺序（等待 B 后再发 AR）。

## 7. 参数

```scala
case class AIConfig(arraySize: Int = 8, dataW: Int = 8, accW: Int = 32, maxK: Int = 32)
```

## 8. 量化与激活（已实现）

- OUT 缓冲：计算完成后 QUANT 状态对累加值做 `y = sat8(relu(acc >> shift))`，写入 INT8 输出缓冲（BUF_SEL=3）。
- `relu_en`（QUANT[0]）：负数置 0；`shift`（QUANT[7:4]）：算术右移实现输出量化缩放。
- 饱和截断：超 [−128,127] 钳制到 INT8。
- 原始 INT32 累加值仍可通过 BUF_SEL=2 读取（调试/浮点后处理）。

## 9. 性能测量（已实现）

- PERF 寄存器（0x2C）：本次运算 compute+quant 周期数。
- 全尺寸 8×8×K：周期 = K+1，MAC 利用率 ≈ K/(K+1)（K=32 时 97%）。

## 10. 规划（Phase 2 剩余）

- AXI4 Master DMA：用 A_BASE/B_BASE/C_BASE 自动从内存搬运数据、写回结果（当前 PIO 模式）。
- bias 累加；FP16 可选。
