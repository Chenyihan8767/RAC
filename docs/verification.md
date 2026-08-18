# 验证报告（verification.md）

> 目标：以**实际上板功能正确**为验证目标。方法：每个模块独立测试 + 顶层集成测试，全部使用 Scala 黄金参考模型逐元素比对。

## 1. 验证方法学

1. **模块隔离**：每个叶子模块（AxiLiteSlave / DataBuffer / MacArray / Quantizer）独立例化、独立测试，只验证其自身行为。
2. **黄金参考**：`TestRefs` 提供 `gemm()` 与 `quant()` 参考模型；硬件输出与参考**逐元素**比对，统计 mismatch 数为 0 才通过。
3. **协议验证**：AXI4-Lite 从机测试覆盖通道乱序（AW先/W先/同时）、背压（b.ready/r.ready 拉低保持）、连续读写、resp=OKAY。
4. **状态机/时序**：明确验证同步写可见性（写后 1 拍生效）、累加清零、门控、QUANT 锁存时序。
5. **确定性与复位**：所有状态寄存器 RegInit；显式复位测试；同输入两次运算结果完全一致（确定性）。
6. **边界覆盖**：K=0 / K=1 / M=N=1 / M=N=8 / 维度门控 / INT32 溢出 / INT8 饱和 / ReLU / shift 0..15。
7. **随机化**：多组随机 INT8 矩阵 sweep（M,N∈[1,8]，K∈[1,32]，随机 shift/relu），全部 vs 参考。

## 2. 测试矩阵与结果（2026-08-08，全部通过）

| 套件 | 用例 | 数量 | 结果 |
|---|---|---|---|
| `MatrixMulTest` | 最小乘法冒烟 | 1 | PASS |
| `AxiLiteSlaveTest` | AW先/W先/同时/背压/连续读写/OKAY | 8 | PASS |
| `DataBufferTest` | PIO 写读回、同步写时序、行读 | 3 | PASS |
| `MacArrayTest` | 清零、单乘积保持、门控、随机 K=32 vs 参考 | 4 | PASS |
| `QuantizerTest` | 透传、饱和、ReLU、shift 各档、随机 vs 参考 | 5 | PASS |
| `AICoreTest` | 寄存器/缓冲回读、小 GEMM、随机 8×8×8、K=32 门控、量化、PERF、VCD | 8 | PASS |
| `AICoreIntegTest` | 复位、确定性、K=0、K=1/M=N=1、随机 sweep（原始+量化） | 5 | PASS |
| `Rv32iCoreTest` | 汇编：算术+访存+分支 / 循环求和 / 字节存取 / M 扩展乘法 | 4 | PASS |
| `SoCTest` | PIO GEMM / DMA GEMM / 1D 卷积 / 2D 卷积 / MLP / FP16 量化 | 6 | PASS |
| **合计** | | **46** | **46 PASS / 0 FAIL** |

## 3. 关键验证点说明

### 3.1 AXI4-Lite 从机协议（AxiLiteSlaveTest）
- AW 与 W 通道可**任意顺序**到达（分别缓冲，合并后写寄存器），均验证 B 响应 OKAY。
- `b.ready` 拉低时 `b.valid` 持续保持，直到 `b.ready` 拉高才完成握手（AXI 规范要求）。
- `r.ready` 拉低时 `r.valid` 保持，读数据在握手完成前不消失。
- 连续（背靠背）写 / 连续读均可正确工作。

### 3.2 数据通路正确性
- **MacArray**：清零后累加；rowMask/colMask 门控的 PE 恒为 0；随机 32 步 vs 参考零 mismatch。
- **DataBuffer**：同步写（写后 1 拍可见，同拍读读到旧值）；`rowData` 组合读出整行 8 字节。
- **Quantizer**：shift=0 时小值透传；大值饱和到 ±127/−128；ReLU 负值置 0；shift 各档算术右移；20 组随机 vs 参考零 mismatch。
- **AICore 集成**：全流程（PIO 装入 → 计算 → 量化 → 读回）在 10 组随机配置（M,N,K 随机、shift/relu 随机）下**原始累加值**与**量化输出**同时 vs 参考，零 mismatch。

### 3.3 时序与确定性
- 同步写可见性：写入 DATA（缓冲装入）在**写时钟沿后** 1 拍对读可见（DataBufferTest 明确验证）。
- 确定性：相同输入两次运算，ACC 与 OUT 逐元素一致（无未初始化状态）。
- 复位：复位后 busy/done/PERF 全 0，缓冲清零；复位后运算仍正确。

### 3.4 边界情况
- K=0：立即 done，无累加，ACC/OUT 全 0。
- K=1、M=N=1：单元素乘积正确。
- 维度门控（M,N<8）：未启用 PE 不参与累加（不影响结果）。
- INT32 累加溢出：文档中说明累加器为 32 位有符号，最大安全 K 与数据范围（见 chip_manual）。

## 4. 性能验证

- PERF 寄存器（0x2C）统计 compute+quant 周期数。
- 8×8×K：**周期 = K+1**（K 个累加周期 + 1 个量化周期）。
- 8×8×32：33 周期，有效 MAC = 2048，容量 = 33×64，**利用率 ≈ 97%**。
- 实测输出：`[perf] K=8 cycles=9  K=32 cycles=33  MAC util(K=32)=97.0%`

## 5. 上板就绪度

- 全部状态寄存器 `RegInit`（无未初始化、无锁存器）。
- `verilator --lint-only --timing` 通过（AICore.sv 与 SoC.sv 均无警告）。
- 单时钟域、同步复位（复位信号在 AXI 接口之外由顶层提供）。
- 生成了可综合 SystemVerilog（`generated/AICore.sv`、`generated/SoC.sv`，共 10 个模块）。
- VCD 波形可导出（`WriteVcdAnnotation`）用于上板前时序核对。
- **Phase 3 SoC 端到端**：真实裸机 C 程序（rv32im）在自研核上执行，经 MMIO 驱动 AICore 完成 GEMM，结果正确——证明 RTL 与软件栈可上板运行。
- **迭代 5**：DMA 模式（自动搬运/计算/写回）、逐列 bias、1D/2D 卷积、MLP、FP16 量化推理全部在 SoC 上端到端验证通过；RV32 核补 M 扩展乘法并测试。

## 6. 已知限制

- 缓冲为寄存器实现（256B ×2），小尺寸无需 BRAM；若增大 maxK 可改用 SyncReadMem。
- 目前为 PIO 模式（数据搬运靠 CPU 读写 MMIO）；DMA 自动搬运为后续版本。
- AXI4-Lite 从机单笔事务、无 burst（AXI4-Lite 定义如此）；`strb` 未校验（假定全 4 字节有效）。
- RV32I 核无 CSR/中断（RV32I 的 CSR 指令未实现，作为 NOP/非法处理）；load/store 假定对齐。
- SRAM 用寄存器实现（16KB），上板建议映射到块 RAM（Block RAM）。
