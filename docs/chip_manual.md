# AICore 芯片手册（Chip Manual / Datasheet）

> 版本：v1.0（2026-08-08）
> 器件类型：RISC-V AI 协处理器（INT8 GEMM 加速器，AXI4-Lite MMIO 外设）
> 目标：作为 RISC-V 主核（自研精简核）的 MMIO 外设，或独立验证
> 配套文档：`microarchitecture.md`（规格）、`verification.md`（验证报告）、`AGENT.md`/`OPERATIONS_LOG.md`（工程过程）

---

## 1. 产品概述

AICore 是一个面向 AI 推理的小型整数 GEMM 加速器：

- **计算核心**：8×8 乘加（MAC）阵列，output-stationary 数据流
- **数据类型**：INT8 输入 × INT8 权重 → INT32 累加 →（可选）量化输出 INT8
- **量化/激活**：ReLU + 算术右移缩放 + INT8 饱和截断（片上实现）
- **接口**：AXI4-Lite 从机（MMIO），32bit 数据，单笔事务
- **工作模式**：PIO（程序搬数：CPU 写缓冲 → 启动 → 轮询 → 读结果）
- **性能**：8×8×K 计算周期 = K+1；K=32 时 MAC 利用率 ≈ 97%

### 1.1 特性一览

| 特性 | 参数 |
|---|---|
| MAC 阵列 | 8 × 8 = 64 MAC/周期 |
| 输入位宽 | INT8（有符号） |
| 累加位宽 | INT32（有符号） |
| 输出位宽 | INT32 原始 / INT8 量化（可选） |
| 内维 K | 1..32（每次运算） |
| 输出尺寸 M×N | ≤ 8×8（由 M_DIM/N_DIM 门控） |
| 片上缓冲 | A：8×32 B（=256B），B：8×32 B（=256B），均为寄存器 |
| 控制接口 | AXI4-Lite 从机，32bit，10 个 32bit 寄存器 |
| 时钟/复位 | 单时钟、同步复位 |
| 综合结果 | SystemVerilog，6 个模块，Verilator lint 通过 |

### 1.2 模块层次

```
AICore (top)
├── AxiLiteSlave         AXI4-Lite 从机 → 寄存器总线
├── DataBuffer (×2)      A 缓冲 / B 缓冲（PIO 读写 + 计算行读）
├── MacArray             8×8 MAC 阵列 + INT32 累加器
└── Quantizer            组合输出变换（sat8(relu(acc>>shift))）
```

---

## 2. 架构与数据流

```
         ┌────────────────────────────────────────────────────────────┐
 RISC-V   │  AICore                                                    │
 主核 ────► AXI4-Lite Slave ──► 寄存器堆 + 控制器 FSM                   │
 (MMIO)   │                        │                                    │
          │   ┌─────────┐  ┌───────┐│                                    │
          │   │DataBuffer│  │DataBuffer│                               │
          │   │ A_T[k][i]│  │ B[k][j] │                                │
          │   └─────────┘  └───────┘│                                  │
          │        │          │      │                                  │
          │        ▼          ▼      │                                  │
          │   ┌───────────────────────┐                                 │
          │   │   MacArray 8×8        │                                │
          │   │   INT32 累加器        │                                │
          │   └──────────┬────────────┘                                 │
          │              ▼                                              │
          │   ┌───────────────┐        ┌──────────────┐                │
          │   │  Quantizer    │──►锁存→│  OUT 缓冲(INT8)│               │
          │   └───────────────┘        └──────────────┘                │
          │   PIO 读回：ACC(INT32) / OUT(INT8)                          │
          └────────────────────────────────────────────────────────────┘
```

### 2.1 计算模型

```
C[i][j] = Σ_{k=0..K-1} A[i][k] · B[k][j]，  i,j ∈ [0,8)
Y[i][j] = sat8( relu( C[i][j] >> shift ) )        （可选）
```

- 每周期推进一个 k：`rowData`（A_T[k][0..7]）与 `rowData`（B[k][0..7]）组合读出，64 个 PE 并行 `acc += a·b`。
- output-stationary：每个 PE 持有自己的累加器 C[i][j]，K 步后即为最终结果。

### 2.2 数据布局（软件按此装入）

| 缓冲 | 逻辑内容 | 物理布局 | 元素索引 | 容量 |
|---|---|---|---|---|
| A（sel=0） | A[i][k]，i∈[0,8)，k∈[0,K) | 按 k 主序：`A_T[k][i]` | `k*8+i`（字节） | 8×32 |
| B（sel=1） | B[k][j]，k∈[0,K)，j∈[0,8) | 按 k 主序：`B[k][j]` | `k*8+j`（字节） | 8×32 |
| ACC（sel=2） | C[i][j]（INT32） | 按 i 主序 | `i*8+j`（字） | 8×8 |
| OUT（sel=3） | Y[i][j]（INT8 量化） | 按 i 主序 | `i*8+j`（字节，读回符号扩展） | 8×8 |
| BIAS（sel=4） | 逐列偏置 bias[j]（INT32） | 按 j | `j`（字） | 8 |

> 软件必须把 A 转置为 `A_T[k][i]` 写入（驱动已封装）。每 k 行按 8 字节对齐，不足 8 的补 0。

---

## 3. 接口定义

### 3.1 AICore 顶层接口

| 信号 | 方向 | 位宽 | 说明 |
|---|---|---|---|
| clock | in | 1 | 系统时钟（单时钟域） |
| reset | in | 1 | 同步复位（高有效），复位所有内部状态 |
| axi_aw_valid/ready | in/out | 1 | AXI4-Lite 写地址通道握手 |
| axi_aw_addr | in | 32 | 写地址 |
| axi_aw_prot | in | 3 | 保护类型（恒 0 处理） |
| axi_w_valid/ready | in/out | 1 | 写数据通道握手 |
| axi_w_data | in | 32 | 写数据 |
| axi_w_strb | in | 4 | 写选通（本设计按全有效处理） |
| axi_b_valid/ready | out/in | 1 | 写响应通道握手 |
| axi_b_resp | out | 2 | 写响应（恒 OKAY=0） |
| axi_ar_valid/ready | in/out | 1 | 读地址通道握手 |
| axi_ar_addr | in | 32 | 读地址 |
| axi_ar_prot | in | 3 | 保护类型 |
| axi_r_valid/ready | out/in | 1 | 读数据通道握手 |
| axi_r_data | out | 32 | 读数据 |
| axi_r_resp | out | 2 | 读响应（恒 OKAY=0） |

复位行为：所有内部寄存器（含缓冲、累加器、FSM、状态/控制寄存器）复位后清零。

### 3.2 模块接口（内部）

**AxiLiteSlave(32,32)**
- 输入：`axi`（AXI4-Lite 从机端口，同 3.1）
- 输出寄存器总线 `bus`：
  - `write.valid/ready`，`write.bits.addr`（32），`write.bits.data`（32）：写请求（AW/W 合并后单次发出）
  - `read.valid/ready`，`read.bits.addr`（32）：读请求
  - `readData.valid/ready`，`readData.bits`（32）：读数据返回

**DataBuffer(cfg)**
| 端口 | 方向 | 位宽 | 说明 |
|---|---|---|---|
| pioWr.valid | in | 1 | PIO 字节写使能（同步写，1 拍后可见） |
| pioWr.k | in | 5 | k 行索引（0..31） |
| pioWr.e | in | 3 | 列索引（0..7） |
| pioWr.data | in | 8 | 字节数据 |
| pioRd.k / pioRd.e | in | 5/3 | PIO 读地址（组合读） |
| pioRdData | out | 8 | PIO 读数据 |
| rowAddr | in | 5 | 计算行读地址 |
| rowData | out | 8×8 | 整行 8 字节（组合读，喂 MAC 阵列） |

**MacArray(cfg)**
| 端口 | 方向 | 位宽 | 说明 |
|---|---|---|---|
| a | in | 8×8 | 激活向量 A_T[k][i]，SINT8 |
| b | in | 8×8 | 权重向量 B[k][j]，SINT8 |
| valid | in | 1 | 使能累加（valid=1 时 acc+=a·b） |
| clear | in | 1 | 清零所有累加器（优先于 valid） |
| rowMask | in | 8 | 行门控（i≥M 时该行不累加） |
| colMask | in | 8 | 列门控（j≥N 时该列不累加） |
| out | out | 8×8×32 | 累加器当前值（SINT32） |

**Quantizer(cfg)**
| 端口 | 方向 | 位宽 | 说明 |
|---|---|---|---|
| acc | in | 8×8×32 | 累加值（SINT32） |
| reluEn | in | 1 | ReLU 使能 |
| shift | in | 4 | 算术右移量（0..15） |
| out | out | 8×8×8 | 量化结果（SINT8） |

---

## 4. 寄存器映射（编程接口）

基址：`BASE`（由 SoC 决定，默认示例 0x3000_0000）。所有寄存器 32bit，AXI4-Lite 32 位对齐访问。

| 偏移 | 名称 | 属性 | 复位值 | 位域 |
|---|---|---|---|---|
| 0x00 | CTRL | RW | 0 | [0] start：写 1 启动运算；[1] clear_done：写 1 清 done；[2] dma_mode：1 为 DMA 模式；[3:31] 保留 |
| 0x04 | STATUS | RO | 0 | [0] busy；[1] done；[31:2] 保留 |
| 0x08 | A_BASE | RW | 0 | A 矩阵基址（**DMA 模式**下数据源；PIO 模式未使用） |
| 0x0C | B_BASE | RW | 0 | B 矩阵基址（DMA 数据源） |
| 0x10 | C_BASE | RW | 0 | C 矩阵基址（DMA 结果写回目标） |
| 0x14 | M_DIM | RW | 0 | A 行数 M（1..8；超范围按 8 处理） |
| 0x18 | K_DIM | RW | 0 | 内维 K（0..32；0 表示空运算立即 done） |
| 0x1C | N_DIM | RW | 0 | B 列数 N（1..8） |
| 0x20 | BUF_CTRL | RW | 0 | [2:0] sel：0=A、1=B、2=ACC、3=OUT、4=BIAS；[15:4] index（元素索引，见 2.2） |
| 0x24 | DATA | RW | 0 | 数据端口。写：装入 sel 所指缓冲；读：读出。访问后 index 自动 +1 |
| 0x28 | QUANT | RW | 0 | [0] relu_en；[1] bias_en（逐列偏置）；[7:4] shift；其余保留 |
| 0x2C | PERF | RO | 0 | 最近一次运算 compute+quant 周期数（不含 PIO/DMA 搬运） |

### 4.1 软件使用序列（编程流程）

```
1. 写 A 缓冲：  BUF_CTRL=sel_A, idx=0; 连续写 DATA（A_T[k][i]，每 k 行 8 字节）
2. 写 B 缓冲：  BUF_CTRL=sel_B, idx=0; 连续写 DATA（B[k][j]，每 k 行 8 字节）
3. 写维度：    M_DIM, K_DIM, N_DIM
4. 写量化：    QUANT = (shift<<4) | relu_en     （可选，默认全关）
5. 启动：      CTRL = 1
6. 轮询：      读 STATUS 直到 done=1（busy=0）
7. 读结果：    BUF_CTRL=sel_ACC 或 sel_OUT; 连续读 DATA（index 自动递增）
8. （可选）    读 PERF 获得周期数
```

> 注意：写入 DATA（缓冲装入）与读取 DATA 之间，软件应等待 B 响应后再发起读（AXI 写后读顺序）。驱动 `sw/gemm_driver.c` 已封装。

---

## 5. 控制状态机（FSM）

```
IDLE ──(CTRL.start)──► COMPUTE ──(k==K_DIM-1)──► QUANT ──► DONE
  ▲                     │(每周期 k++)              │(1 周期)
  └────────(新 start / CTRL.clear_done)─────────────┘
```

| 状态 | 行为 |
|---|---|
| IDLE | 等待 CTRL.start。收到后锁存 K_DIM，清累加器与 PERF，k=0，进 COMPUTE |
| COMPUTE | 每周期从 A/B 缓冲组合读出第 k 行，64 PE 同时累加，k++。K=0 直接进 DONE；k==K-1 进 QUANT |
| QUANT | 1 周期：把累加器经 Quantizer 变换后锁存进 OUT 缓冲（INT8）。随后进 DONE |
| DONE | busy=0、done=1。新 start 重启；CTRL[1] 清 done |

- `busy` = (COMPUTE | QUANT)，`done` = DONE 状态标志。
- 关键时序：**QUANT 状态单独 1 拍**，避免在最后一个累加周期就锁存（会漏掉最后一项累加值）。
- 维度门控：`i≥M_DIM` 或 `j≥N_DIM` 的 PE 在 COMPUTE 中不累加（结果保持 0）。

---

## 6. 时序说明

### 6.1 AXI4-Lite 从机时序
- **写**：AW 与 W 可任意顺序到达；两者齐后下一拍 `bus.write` 有效并（b.ready=1 时）写寄存器，同时 B 响应 OKAY。`b.valid` 保持直到 `b.ready`。
- **读**：AR 握手后下一拍 `r.valid=1`，`r.data` 为组合读结果；`r.valid` 保持直到 `r.ready`。读延迟固定（AR 后约 2 拍出数据）。
- 单笔事务，无 burst，无 ID 字段（AXI4-Lite 简化）。

### 6.2 运算时序
- 启动写入（CTRL）生效后：1 拍进入 COMPUTE（清累加器）。
- COMPUTE 持续 **K** 拍（每拍一个 k）。
- QUANT 持续 **1** 拍。
- 总运算周期（PERF）= **K + 1**。
- 例：K=8 → 9 拍；K=32 → 33 拍；K=0 → 立即 done（PERF=1，无累加）。

### 6.3 同步写可见性
- 缓冲字节写入在写时钟沿后 1 拍对 PIO 读 / 计算行读可见（DataBuffer 同步写语义）。

---

## 7. 数据格式与精度

| 量 | 格式 | 范围 | 说明 |
|---|---|---|---|
| A/B 元素 | INT8 补码 | −128..127 | 输入激活/权重 |
| 乘积 | INT16 | −16384..16256 | INT8×INT8 精确 |
| 累加 | INT32 补码 | −2³¹..2³¹−1 | 每 k 加一个乘积；K=32 满值 32×127²=516128，远未溢出 |
| 量化输出 | INT8 补码 | −128..127 | `sat8(relu((acc+bias)>>shift))` |

- 溢出保证：`K·127² < 2³¹` ⇔ `K < 2³¹/16129 ≈ 133113`，本设计 K≤32，**绝不会溢出**（数学上可证）。
- shift 为算术右移（负数右移保留符号），等价于除以 2^shift（向下取整）。

## 7a. DMA 模式（AXI4 Master 自动搬运，迭代 5）

当 `CTRL[2]=dma_mode` 时，启动后加速器自动完成 **LOAD → COMPUTE/QUANT → STORE → DONE**，CPU 无需逐字节搬数。

- **LOAD**：对每个 k∈[0,K)，从 `A_BASE+k*8`（A_T，8 字节/行）与 `B_BASE+k*8`（B，8 字节/行）各读 2 个 32 位字（wordWr 一次写 4 字节）装入 A/B 缓冲。
- **STORE**：对每个 (i,j)，i<M、j<N，将 OUT（INT8，符号扩展）写入 `C_BASE+(i*8+j)*4`（C 矩阵按 i*8+j 布局）。
- **DMA 主端口**：AICore 的 `master` 口（core-bus 协议）在 SoC 内经 `MemArbiter`（核+DMA 双主轮询）访问 SRAM/ROM。
- 软件流程：CPU 摆好 A_T/B → 写 A_BASE/B_BASE/C_BASE/维度 → QUANT → CTRL=0x5（start+dma_mode）→ 轮询 done → 从 C_BASE 读结果。

---

## 8. 性能

| 场景 | 周期 | 有效 MAC | 利用率 |
|---|---|---|---|
| 8×8×8 | 9 | 512 | 512/576 ≈ 89% |
| 8×8×32 | 33 | 2048 | 2048/2112 ≈ 97% |
| M×N×K（通用） | K+1 | M·N·K | M·N·K / ((K+1)·64) |

- PERF 寄存器可直接读取周期数（实测：K=8→9，K=32→33）。
- 说明：PIO 模式的整体吞吐受数据搬运（MMIO 写入）限制；计算本身 64 MAC/周期。

---

## 9. 上板集成指南（FPGA）

### 9.1 时钟与复位
- 单时钟域；`clock` 接系统时钟（建议先跑低频率，如 25–50 MHz 验证）。
- `reset` 为**同步高有效**；FPGA 上建议用外部复位（或内部复位桥）驱动，复位持续 ≥ 数个时钟周期。
- 若使用异步复位源，先经同步器转成同步复位再接本模块。

### 9.2 AXI4-Lite 连接
- 将 `axi_*` 接到 SoC 总线互联（或主核）的 AXI4-Lite 从机端口。
- 地址映射：建议 `BASE = 0x3000_0000`（0x3000_0000..0x3000_002F 覆盖 10 个寄存器）。
- 主核访问必须 32 位对齐；未使用的地址读返回 0、写忽略。

### 9.3 验证与调试
- 上板前用 chiseltest（`sbt test`）回归；用 `WriteVcdAnnotation` 导出的 VCD 核对时序。
- 裸机自测：`sw/` 提供 `gemm()` / `gemm_quant()` 驱动与 `main.c` 自测（结果比对）。
- 寄存器回读（BUF_CTRL/DATA/STATUS/PERF）可用于上板后的基本连通性自检。

### 9.4 综合建议
- 缓冲为寄存器实现（512B 总量），无需 BRAM；若扩展 maxK，建议改为 `SyncReadMem` 映射 BRAM。
- 无组合环路（Chisel 保证 + Verilator lint 确认），可直接综合。
- 建议约束：`create_clock` + 输入/输出延迟约束到 AXI 端口。

---

## 10. 已知限制与后续版本（Roadmap）

| 限制 | 说明 | 计划 |
|---|---|---|
| PIO 数据搬运 | CPU 逐字节写缓冲，吞吐受限 | Phase 2：AXI4 Master DMA（用 A_BASE/B_BASE/C_BASE） |
| 无 burst | AXI4-Lite 定义无 burst | 引入 AXI4 全主/从端口时支持 |
| 无 bias | 卷积/BN 需软件处理 | 增加 bias 累加寄存器 |
| 仅 INT8 | 覆盖推理主流 | FP16 选项 |
| 单通道单核 | 单实例 | 多核/多实例互连 |

---

## 11. 参考

- 寄存器/布局/时序细节：`docs/microarchitecture.md`
- 验证计划与结果：`docs/verification.md`
- 软件驱动：`sw/gemm_driver.c`、`sw/main.c`
- 生成 RTL：`generated/AICore.sv`、`generated/SoC.sv`
- 源码：`src/main/scala/accel/`（AICore/AxiLiteSlave/DataBuffer/MacArray/Quantizer/Params）、`src/main/scala/accel/core|soc/`

---

# 附录 A：RV32I 核与 SoC（Phase 3）

## A.1 总体

`SoC` 集成自研 RV32I 精简核、BootROM、SRAM 与 AICore，可运行真实裸机 C 程序：
程序经交叉编译 → `mkhex.py` 生成 word hex → 烘焙进 `ParamRom` → 复位后核从 ROM 取指执行，
通过 MMIO 写寄存器驱动 AICore 完成 GEMM，结果写回 SRAM。

```
SoC
├── Rv32iCore    单发射（IF/EX/MEM），RV32I 全集，32×32 寄存器堆
├── ParamRom     boot ROM（4KB，组合读）
├── CoreRam      SRAM（16KB，字节通道写）
├── AxiBridge    core-bus → AXI4-Lite（AICore）
└── AICore       8×8 INT8 GEMM（MMIO 0x30000000）
```

## A.2 地址映射

| 区域 | 地址 | 说明 |
|---|---|---|
| Boot ROM | 0x0000_0000 – 0x0000_0FFF | 指令/只读数据，复位后 PC=0 |
| SRAM | 0x0000_1000 – 0x0000_4FFF | 数据/栈，`__stack_top=0x5000` |
| AICore | 0x3000_0000 – 0x3000_003F | MMIO 寄存器（见第 4 节） |
| 未映射 | 其他 | 立即返回 0 |

## A.3 Rv32iCore 微架构

- **状态机**：IF（取指，组合读）→ EX（译码执行）→ MEM（访存）。
  ALU/控制 2 拍/指令；load/store 经 AXI 桥 5–8 拍。
- **RV32I 全集**：add/sub/sll/slt/sltu/xor/srl/sra/or/and；addi/slti/sltiu/xori/ori/andi/slli/srli/srai；
  beq/bne/blt/bge/bltu/bgeu；jal/jalr；lui/auipc；lb/lh/lw/lbu/lhu/sb/sh/sw。
- **M 扩展（乘法）**：mul/mulh/mulhu/mulhsu（64 位乘积取低/高 32 位），供裸机参考计算使用。
- **core-bus 协议**：`req.valid/addr/wen/wdata/wmask` 保持直到 `rvalid` 脉冲；读 `rdata`。
  存储组合读（1 拍响应）；AXI 桥延迟响应。
- **store 字节通道**：`sb`/`sh` 时 wdata 按地址通道移位，SRAM 按 wmask 逐字节写。
- **复位**：PC=0，寄存器堆清零，同步复位。

## A.4 AxiBridge

- 将单个 core-bus 事务转换为 AXI4-Lite 单事务（AW/W/B 或 AR/R）。
- **防重接受**：接受条件含 `!rPulse`，避免响应后同请求被重复接受（会导致写应用两次）。
- 写：AW+W 同时发，B 后返回 rPulse；读：AR 后 R 返回 rPulse+rdata。

## A.5 软件（裸机）

- `sw/crt0.S`：`_start` 设 `sp=0x5000`，`jal main`，随后自循环。
- `sw/link.ld`：`.text/.rodata`→ROM(0x0)，`.bss`→SRAM(0x1000)，栈顶 0x5000。
- `sw/main.c`：调用 `gemm()`（驱动见第 4/9 节），结果写 0x2000（OK=0x600d / FAIL=0xdead），并 dump C 到 0x3000。
- 构建：`make -C sw all`（gemm_test.elf/hex）与 `make -C sw asm`（核单元测试程序）。
- `sw/mkhex.py`：ELF → `readmemh` word 格式。

## A.6 验证（SoC 端到端）

- `Rv32iCoreTest`：4 个汇编程序（算术+访存+分支 107、循环求和 55、字节存取 0xFF、M 扩展乘法 42/-300/-1/0xFFFFFFFE/-1）全过。
- `SoCTest`：PIO GEMM（OK 0x600d）、DMA 模式 GEMM（OK，C 矩阵全对）、1D 卷积、2D 卷积、MLP、FP16 量化 GEMM —— 全部通过。
- 全量：`sbt test` 46/46（9 套件）；SoC.sv（11 模块）Verilator lint 干净。

## A.7 AI benchmark（迭代 5）

| 程序 | 工作负载 | 方式 | SoC 周期 |
|---|---|---|---|
| `bench_conv1d.c` | 1D 卷积（2 通道×3 核，4 输出） | im2col→GEMM+量化 | 3853 |
| `bench_conv2d.c` | 2D 卷积（4×4 输入，3×3 核，VALID） | im2col→GEMM | 6719 |
| `bench_mlp.c` | 2 层 MLP（8→4→2）+ bias + ReLU | GEMM+bias+量化 | 7818 |
| `bench_fp16.c` | FP16 模型经 INT8 量化推理 | fp16→int8 + GEMM | 1985 |

每个程序在 SoC 上用 RV32 核计算参考并比对 AICore 输出，一致则写 OK(0x600d) 到 0x1000。

## A.8 FP16 支持（软件量化，行业标准做法）

- `sw/fp16.h`：IEEE-754 binary16 完整转换（fp16↔float）与量化（`fp16_quant` 浮点版 / `fp16_to_scaled_int` 纯整数版，供无 FPU 核使用）。
- 流程：FP16 模型权重/激活 → 量化到 INT8（带 scale/zero-point）→ 在 INT8 加速器上推理 → 反量化。这是真实 INT8 加速器处理 FP16 模型的通用做法。
- 宿主 x86 测试 `host_fp16_test.c` 验证转换/量化正确性（含 subnormal、round-trip、定点量化）。

