# AGENT.md — RISC-V AI 加速器（Chisel）项目规划

> 本文件记录项目的规划、实现方法与目标，随项目推进持续更新。

## 项目目标

使用 Chisel（Scala 硬件描述语言）快速开发一个面向 AI 工作负载的 RISC-V 协处理器/AI 加速器。

## 阶段状态

- **Phase 0（环境搭建）已完成 ✓**（2026-08-08）
- **Phase 1（微架构设计与 RTL）核心完成 ✓**：AICore（8×8 INT8 GEMM，AXI4-Lite MMIO）
- **Phase 2（量化/性能/模块化）完成 ✓**：QUANT/PERF、模块化重构
- **迭代 3（模块化+严谨验证+完整交付）完成 ✓**：34/34、芯片手册/验证报告/README
- **Phase 3（自研 RV32I 核 + SoC 集成）完成 ✓**：核+SoC 端到端跑通裸机 GEMM，38/38 测试
- **迭代 5（DMA + AI benchmark + bias/FP16）完成 ✓**：AXI4 Master DMA、逐列 bias、卷积/MLP benchmark、FP16 量化库；`sbt test` 46/46 全过
- 下一步：FPGA 上板综合（需 Vivado）、更多 benchmark、多核

## Phase 1 成果

- **RTL**：`src/main/scala/accel/` — AxiLiteSlave / MacArray / AICore / Params
- **测试**：`AICoreTest` 5 个用例全过（寄存器回读、小 GEMM、随机 8×8×8、K=32 维度门控、重复运行），`sbt test` 73s
- **Verilog**：`sbt "runMain accel.AICoreMain"` → `generated/AICore.sv`，Verilator lint 通过
- **软件**：`sw/gemm_driver.c` + `main.c` + `Makefile`，`riscv64-unknown-elf-gcc -march=rv32im` 交叉编译通过（680B text），供后续自研核集成
- **规格**：`docs/microarchitecture.md`

## 环境信息

- OS: Ubuntu 24.04 LTS (WSL2, kernel 6.18, x86_64)，16 核，7.6 GiB 内存
- 网络: GitHub 慢（但可下载）；apt 走清华 TUNA；Maven Central 正常
- sudo: 当前用户已免密
- 工作目录: /home/yihan/riscv-ai-accel

## 已装工具链（版本固化）

| 组件 | 版本 | 安装方式 | 验证 |
|---|---|---|---|
| JDK | OpenJDK 17.0.19 | apt (TUNA) | `java -version` ✓ |
| sbt | 1.10.7 | GitHub release tgz → /usr/local/lib/sbt | `sbt --version` ✓ |
| Verilator | 5.030（源码装 /usr/local，含 PCH 补丁） | apt+源码 | `verilator --lint-only` ✓ |
| RISC-V GCC | riscv64-unknown-elf-gcc 13.2.0 | apt + gdb-multiarch | rv32im 编译 ✓ |
| Chisel | 6.7.0 | sbt (Maven Central) | `sbt test` ✓ |
| chiseltest | 6.0.0 | sbt (`edu.berkeley.cs` 组!) | `sbt test` ✓ |
| Scala | 2.13.14 | sbt | ✓ |

## 迭代 3：模块化重构 + 严谨验证 + 完整交付

### 目标

以**实际上板功能正确**为目标，把 AICore 重构为清晰的模块层级，每个模块独立设计并验证后再做顶层连接；最终交付完整项目 + 详尽芯片手册。

### 模块划分（每模块独立测试）

| # | 模块 | 职责 | 独立测试重点 |
|---|---|---|---|
| 1 | `AxiLite4` / `AxiLiteSlave` | AXI4-Lite 从机，单笔事务，AW/W 乱序合并 | 协议：W先/AW先/同时/背压/连续读写/resp=OKAY |
| 2 | `DataBuffer` | A/B 字节缓冲（Reg[Vec]），PIO 写 + PIO 读 + 计算行读 | 写后读时序、行读正确性、逐字节 |
| 3 | `Quantizer` | 组合输出变换 `sat8(relu(acc>>shift))` | 饱和边界、ReLU、shift 0..15、穷举 vs 参考 |
| 4 | `MacArray` | 8×8 INT8 MAC + INT32 累加 + 行/列门控 | 清零、门控、随机多步 vs 参考 |
| 5 | `AICore`（顶层） | 控制器 FSM + 寄存器堆 + 缓冲/MAC/量化集成 | 复位、确定性、AXI 顺序、边界（K=0/1）、随机 sweep |

### 验证策略（严谨性）

- 每个模块：**独立 chiseltest**，用 Scala 参考模型逐元素比对（golden reference）。
- 顶层集成：回归 + 复位测试 + 确定性（同输入两次同结果）+ AXI 协议顺序 + 边界 + 多组随机 GEMM。
- 全部状态 RegInit（无未初始化），Verilator lint 通过，VCD 波形可导出。
- 上板注意：同步复位、单时钟域、AXI4-Lite MMIO 基址、时序约束（写入 chip manual）。

### 交付物

1. `src/main/scala/accel/` — 模块化 RTL（各模块独立文件）
2. `src/test/scala/accel/` — 每模块独立测试 + 集成测试
3. `docs/chip_manual.md` — **芯片手册**（总体架构、全模块接口/端口/时序、寄存器映射、数据格式、FSM、测试方法、上板指南）
4. `docs/verification.md` — 验证计划与结果
5. `docs/microarchitecture.md` — 更新为模块化描述
6. `README.md` — 项目入口
7. `sw/` — 裸机驱动（gemm / gemm_quant）
8. `AGENT.md` / `OPERATIONS_LOG.md` 全程维护

## 关键技术决策与教训

1. **Chisel 用 6.x 而非 7.x**：chiseltest 最新仅到 6.0.0，与 Chisel 6.x 配套。7.x 暂无正式 chiseltest。
2. **chiseltest 坐标**：`edu.berkeley.cs %% chiseltest % 6.0.0`（不在 `org.chipsalliance` 组）。
3. **apt 必须串行**：并行 `apt-get install` 会撞 dpkg 锁。
4. **sbt 安装**：GitHub release tgz 可用（慢）；Maven Central 也有 sbt-launch 备用方案。
5. **Verilator 后端需要 make/g++**：chiseltest 用 make 编译生成的 C++（`apt install build-essential`）。
6. **Verilator PCH 缺陷（已修）**：chiseltest 的 `-CFLAGS` 含 `-include <top>.h`，先于 PCH include 出现，违反 GCC"PCH 只能给首个 include 头文件"规则，导致报 `VAICore__pch.h.fast: No such file or directory`。已给 `/usr/local/share/verilator/include/verilated.mk` 打补丁生成真实 `.fast/.slow` 头文件。排障用二分法 + `make -n` + `-Winvalid-pch`。
7. **Verilator 版本**：5.030（源码编译装于 /usr/local，替换 apt 的 5.020；此缺陷与版本无关）。编译 verilated 模型务必用 `make -j`（16 核并行），串行会超时。
8. **RV32I 核排障要点（Phase 3）**：S 型立即数（store 地址）、负立即数符号扩展、组合读协议（避免 busy/rvalid 双周期多余脉冲）、AICore 地址低 8 位偏移解码、AXI 桥防重接受（`!rPulse`）、store 字节通道移位。详见 OPERATIONS_LOG。

## Phase 3：RV32I 核 + SoC（完成 ✓）

```
SoC
├── Rv32iCore    单发射 FSM（IF/EX/MEM），RV32I 全集，32×32 寄存器堆，core-bus
├── ParamRom     4KB boot ROM（程序词烘焙），组合读
├── CoreRam      16KB SRAM（字节通道，按字节写使能）
├── AxiBridge    core-bus → AXI4-Lite → AICore（单事务，防重接受）
└── AICore       8×8 INT8 GEMM 加速器（MMIO 0x30000000）
```

- 地址映射：ROM 0x0000_0000–0x0000_0FFF；SRAM 0x0000_1000–0x0000_4FFF；AICore 0x3000_0000–0x3000_003F
- 端到端：裸机 C 程序（crt0+main+driver）→ hex → boot ROM → 核执行 → MMIO 驱动 AICore GEMM → 1379 周期返回 OK
- 测试：`Rv32iCoreTest`(3 汇编程序) + `SoCTest`(真实 GEMM)；**sbt test 38/38 全过**

## Phase 1 设计决策（已确认）

- 计算核心：**8×8 MAC 阵列，output-stationary，INT8×INT8→INT32 累加**
- 接口：**AXI4-Lite MMIO 从机**（Phase 1 为 PIO 模式，数据经 MMIO 装入/读回）
- 集成：自研 RV32I 精简核（Phase 3）
- 规格见 `docs/microarchitecture.md`；寄存器映射：CTRL/STATUS/A_BASE/B_BASE/C_BASE/M_DIM/K_DIM/N_DIM/BUF_CTRL/DATA

## 规划（Roadmap）

### Phase 0 — 环境搭建 ✅（已完成）
- [x] 安装 JDK 17 / sbt / Verilator / RISC-V 工具链
- [x] Chisel 项目脚手架（build.sbt + 最小模块 + chiseltest）
- [x] 冒烟验证：`sbt compile` / `sbt run`(生成 .sv) / `sbt test` 全通过

### Phase 1 — 微架构设计（核心完成）
- [x] AXI4-Lite MMIO 从机 + 寄存器堆（CTRL/STATUS/维度/缓冲控制）
- [x] 8×8 MAC 阵列（output-stationary，INT8×INT8→INT32 累加，行列门控）
- [x] A_T/B 缓冲 + 累加器 PIO 读写
- [x] 控制器 FSM（IDLE→COMPUTE→DONE）+ 全量 chiseltest 验证
- [x] RISC-V 软件驱动（gemm_driver.c）交叉编译
- [ ] Phase 2 增强：量化（relu/shift/饱和截断 INT8）、bias、可选 FP16
- [ ] Phase 2：AXI4 Master DMA（用 A_BASE/B_BASE/C_BASE 自动搬运）
- [ ] Phase 2：波形仿真（fst）与性能测量

### Phase 2 — RTL 增强（迭代 2、3 完成 ✓）
- [x] 量化/激活：QUANT 寄存器（relu_en + shift），INT8 饱和截断输出缓冲（SEL_OUT=3），FSM 增 QUANT 状态
- [x] 性能：PERF 周期计数器寄存器（0x2C）；8×8×K 周期=K+1，MAC 利用率 97%（K=32）
- [x] 波形：WriteVcdAnnotation 生成 VCD
- [x] 软件驱动 `gemm_quant()`（量化输出），交叉编译通过
- [x] **迭代 3：模块化重构**（AxiLiteSlave/DataBuffer/MacArray/Quantizer 独立模块）
- [x] **迭代 3：每模块独立测试 + 集成测试**（34/34 全过）+ 芯片手册/验证报告/README
- [ ] AXI4 Master DMA 数据搬运（A_BASE/B_BASE/C_BASE 自动读写内存，替代 PIO）
- [ ] bias 累加；FP16 可选

### Phase 3 — 自研 RISC-V 核与集成（完成 ✓）
- [x] 自研 RV32I 精简核（取指/译码/执行/访存，core-bus 单发射）
- [x] SoC 集成：核 + BootROM + SRAM + AXI 桥 + AICore（MMIO 0x30000000）
- [x] 跑通裸机 gemm_test（软件驱动真实上核，1379 周期 OK）
- [ ] 运行 AI benchmark（GEMM、1D/2D 卷积、MLP 推理）
- [ ] FPGA 综合与上板（可选，需 Vivado）；DMA；bias/FP16

## 实现方法与技术选型

- 语言: Chisel 6.7.0 / Scala 2.13.14，sbt 1.10.7
- 仿真: chiseltest 6.0.0（Verilator 后端）
- 交叉编译: riscv64-unknown-elf-gcc（裸机，rv32im 起步）
- 代码结构:
  ```
  riscv-ai-accel/
  ├── AGENT.md / OPERATIONS_LOG.md / README.md / build.sbt / .gitignore
  ├── docs/                    # microarchitecture / chip_manual / verification
  ├── generated/               # Chisel 生成的 SystemVerilog（AICore.sv / SoC.sv）
  ├── sw/                      # 裸机程序（crt0.S / main.c / gemm_driver.c / link.ld / mkhex.py / Makefile / asm/）
  └── src/
      ├── main/scala/accel/        # AICore / AxiLiteSlave / DataBuffer / MacArray / Quantizer / Params
      ├── main/scala/accel/core/   # Rv32iCore / CoreBus
      ├── main/scala/accel/soc/    # SoC / CoreRam / ParamRom / AxiBridge / SoCMain
      └── test/scala/accel/        # 每模块测试 + 集成测试 + 参考模型
  ```

## 操作纪律

1. 每步操作后立即更新 `OPERATIONS_LOG.md`（时间、命令、结果、异常）。
2. 规划、方法、目标变化时同步更新 `AGENT.md`。
3. 失败/教训记录进日志与"关键技术决策"节，用于自我迭代。
4. 常用命令：
   - `sbt compile` / `sbt test` / `sbt run`（生成 Verilog 到 generated/）
   - `verilator --lint-only --timing generated/<m>.sv`
