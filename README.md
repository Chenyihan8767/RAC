<<<<<<< HEAD
# RAC
RISC-V AI Accelerator, developed with chisel.
=======
# RISC-V AI 加速器（Chisel）

使用 **Chisel 6.7.0** 开发的、面向 RISC-V 主核的 **INT8 GEMM AI 加速器**（AXI4-Lite MMIO 外设），
包含**自研 RV32I 精简核 + SoC**，可运行真实裸机 C 程序端到端完成 GEMM，以**实际上板功能正确**为目标。

## 特性

- 8×8 乘加阵列（output-stationary），INT8×INT8 → INT32 累加，64 MAC/周期
- 量化/激活：`y = sat8(relu((acc+bias) >> shift))`，逐列 bias
- AXI4-Lite 从机 MMIO 控制（PIO 模式）+ **AXI4 Master DMA**（自动读 A/B、写 C）
- PERF 周期计数器
- **自研 RV32IM 核**（单发射，RV32I+M 扩展）+ **SoC**（BootROM/SRAM/AXI 桥/仲裁器/AICore）
- **AI benchmark**：1D/2D 卷积（im2col）、MLP 推理、FP16 量化推理在 SoC 上端到端验证
- **FP16 支持**：`sw/fp16.h` FP16↔INT8 量化库（行业标准流程）
- 全部状态初始化、同步复位、Verilator lint 通过、VCD 可导出

## 文档

| 文档 | 内容 |
|---|---|
| `docs/chip_manual.md` | **芯片手册**：AICore 与 RV32I/SoC 全模块接口、寄存器、时序、上板指南 |
| `docs/microarchitecture.md` | AICore 微架构规格与数据布局 |
| `docs/verification.md` | 验证计划与结果（38 用例全过） |
| `AGENT.md` | 工程规划、决策与教训 |
| `OPERATIONS_LOG.md` | 逐条操作日志 |

## 目录结构

```
riscv-ai-accel/
├── build.sbt
├── src/main/scala/accel/        # RTL：AICore / AxiLiteSlave / DataBuffer / MacArray / Quantizer / Params
├── src/main/scala/accel/core/   # Rv32iCore / CoreBus
├── src/main/scala/accel/soc/    # SoC / CoreRam / ParamRom / AxiBridge
├── src/test/scala/accel/        # 每模块独立测试 + 集成测试 + 参考模型
├── sw/                          # 裸机程序（crt0.S / main.c / gemm_driver.c / link.ld / mkhex.py / asm/）
├── generated/                   # 生成的 SystemVerilog（AICore.sv / SoC.sv）
└── docs/                        # 芯片手册/规格/验证报告
```

## 快速开始

```bash
# 编译 + 全量测试（38 用例）
sbt test

# 生成 AICore / SoC SystemVerilog
sbt "runMain accel.AICoreMain"
sbt "runMain accel.soc.SoCMain"
verilator --lint-only --timing generated/AICore.sv
verilator --lint-only --timing generated/SoC.sv

# 构建裸机程序（rv32im）与核单元测试程序
make -C sw all asm
```

## 模块与验证

| 模块 | 文件 | 独立测试 |
|---|---|---|
| AxiLiteSlave | `accel/AxiLiteSlave.scala` | AW/W 乱序、背压、连续读写、OKAY |
| DataBuffer | `accel/DataBuffer.scala` | PIO 写读、同步时序、整行读 |
| MacArray | `accel/MacArray.scala` | 清零、门控、随机 vs 参考 |
| Quantizer | `accel/Quantizer.scala` | 饱和、ReLU、shift 穷举 vs 参考 |
| AICore（顶） | `accel/AICore.scala` | 复位、确定性、边界、随机 sweep |
| Rv32iCore | `core/Rv32iCore.scala` | 汇编程序：算术/分支/访存/字节 |
| SoC | `soc/SoC.scala` | 端到端裸机 GEMM |

## Roadmap

- [x] Phase 0 环境 / Phase 1 核心 GEMM / Phase 2 量化+性能+模块化 / Phase 3 RV32I 核+SoC
- [ ] AXI4 Master DMA、bias、FP16、AI benchmark、FPGA 上板

## 环境

- Chisel 6.7.0 / Scala 2.13.14 / sbt 1.10.7 / JDK 17
- Verilator 5.030（源码安装，含 PCH 补丁，见 AGENT.md）
- riscv64-unknown-elf-gcc 13.2.0（rv32im）
>>>>>>> master
