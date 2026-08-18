# OPERATIONS_LOG.md — 操作日志

> 详细记录每一步操作（时间、命令、结果、异常）。按时间倒序排列，最新在最上。

## 2026-08-09 — 仿真加速优化（SRAM/ROM 改为真实存储阵列）

**T  | 2026-08-09 | 仿真提速 4.4×（16:03 → 3:41）**

- **瓶颈定位**：SoC 的 CoreRam（16KB）与 ParamRom（4KB）用寄存器实现 ≈ 640K 触发器，Verilator 每周期全量求值，占 `sbt test` 总时间 ~80%。
- **改动**：
  - `CoreRam`：`Reg(Vec(...))` → `Mem(size, Vec(4,UInt(8)))` + 复位初始化循环（4KB 周期清零，保证确定性；init 完成前 rvalid=0，核自然停顿等待）。
  - `ParamRom`：`RegInit(Vec(words))` → `Mem(size, UInt(32))` + 复位循环把程序词写入（`VecInit` 常量数组硬件索引）。
- **实测**（本机，清空 test_run_dir 全新跑）：
  - 改前：16:03（缓存态 16:07）
  - 改后：3:41（缓存态 ~4:05），46/46 全过
- **副作用**：每个 SoC 用例复位后多 ~4KB 周期 init 停顿（对结果无影响）。
- 说明：AXI 从机/bridge/MAC 等小模块保持寄存器实现；进一步可考虑 Verilator `--threads` 与并行套件，收益有限。

---

## 2026-08-08 — 迭代 5：DMA + AI benchmark + bias/FP16（已完成 ✓）

### 操作日志（时间倒序）

---

**T  | 2026-08-09 01:40 | 迭代 5 完成**

- **`sbt test` 46/46 全过（9 套件）**；SoC Verilog 零警告、Verilator lint 干净（11 模块）。
- **bias**：Quantizer 支持逐列偏置（bias 缓冲 BUF_SEL=4，QUANT[1]=bias_en，8×INT32）；AICoreTest 新增 bias 用例。
- **DMA（AXI4 Master）**：
  - AICore 增加 `master`（core-bus 协议）口 + DmaLd/DmaSt 状态：LOAD 读 A_T/B（2 字/行，wordWr 一次写 4 字节）→ COMPUTE/QUANT → STORE 写 OUT 到 C_BASE（i*8+j 布局，跳过 j≥N）。
  - SoC 新增 `MemArbiter`（核 + DMA 双主轮询仲裁）。
  - 启动锁存 `dmaModeL := CTRL[2]`（修 bug：从 ctrlReg 读旧值导致 DMA 模式不触发）。
  - **SoCTest DMA 用例**：CPU 摆数据到 SRAM → DMA 模式自动搬运/计算/写回 → 校验通过（377 周期）。
- **RV32 核补 M 扩展**：mul/mulh/mulhu/mulhsu（`t_mul.S` 汇编测试，5 个结果全对）。
  - 根因：benchmark 参考计算用 `*`，核原来只有 RV32I 无乘法 → 参考结果错误。
- **AI benchmark**（SoC 上运行并校验）：
  - `bench_conv1d`：1D 卷积 im2col→GEMM（3853 周期）
  - `bench_conv2d`：2D 卷积 im2col→GEMM，4×4 输入 3×3 核（6719 周期）
  - `bench_mlp`：2 层 MLP，GEMM+bias+ReLU（7818 周期）
- **FP16 支持**：`sw/fp16.h` 完整 FP16↔float 转换 + 量化（`fp16_quant` 浮点版 + `fp16_to_scaled_int` 纯整数版，供无 FPU 核用）；宿主 x86 测试全过；`bench_fp16` 在 SoC 上演示 FP16→INT8 量化推理（1985 周期）。
- **踩坑**：fp16 转换 subnormal/rounding/分支条件 bug（宿主测试驱动修复）；`-0.25` 编码错（0xB800→0xB400）；DMA store 索引应为 i*8+j 布局（线性 0..M*N 是错的）。

---

**T  | 2026-08-08 23:00 | 开始迭代 5**

---

## 2026-08-08 — Phase 3：RV32I 核 + SoC 集成（已完成 ✓）

### 本迭代目标（迭代 4）

自研 RV32I 精简核 + SoC（核+存储+AICore），跑通真实裸机 C 程序通过 MMIO 驱动 AICore 做 GEMM。

### 操作日志（时间倒序）

---

**T  | 2026-08-08 22:50 | 迭代 4 完成（SoC 端到端跑通）**

- **`sbt test` 38/38 全过（9 套件）**；SoC Verilog 生成零警告、Verilator lint 干净。
- **端到端**：`SoCTest` — 裸机 C 程序（crt0.S + main.c + gemm_driver）加载到 boot ROM，
  核心执行并**通过 MMIO 驱动 AICore 完成 GEMM**，1379 周期返回 OK(0x600d)。✓
- **新增 RTL**（`src/main/scala/accel/core|soc/`）：
  - `Rv32iCore`：单发射 FSM（IF/EX/MEM），RV32I 全集（ALU/分支/jal/jalr/lui/auipc + lb/lh/lw/lbu/lhu/sb/sh/sw），32×32 寄存器堆，统一 core-bus
  - `CoreRam`（字节通道 SRAM）、`ParamRom`（程序烘焙）、`AxiBridge`（core-bus→AXI4-Lite→AICore）、`SoC`（译码/仲裁）
- **核单元测试**：`Rv32iCoreTest`（3 个汇编程序：算术+访存+分支、循环求和、字节存取）+ `SoCTest`。
- **踩坑记录（重要）**：
  1. **S 型立即数**：store 地址误用 I 型立即数（`alu=0x1005`）→ 用 `sImm`。
  2. **负立即数符号扩展**：`iImm.asUInt` 丢符号位（`li x5,-1`→4095）→ 全部立即数显式符号扩展到 32 位。
  3. **组合读协议**：早期 busy/rvalid 两周期协议产生多余脉冲导致指令重复执行 → 改为组合读（rvalid 跟随 req.valid）。
  4. **AICore 地址解码**：SoC 集成后地址是完整 MMIO（0x30000014），AICore 用偏移(0x14)匹配失败 → 解码改低 8 位偏移。
  5. **AXI 桥双接受**：rPulse 后 req.valid 仍高 1 拍导致每个写被应用两次、bufIdx 每次 +2 → 接受条件加 `!rPulse`。
  6. **store 字节通道移位**：`sb` 到奇地址时 SRAM 从 `wdata(15,8)` 取字节但核心未移位 → 核心按地址通道移位 wdata。
- **软件**：`sw/crt0.S`、`sw/link.ld`（ROM 4KB@0x0 + SRAM 16KB@0x1000）、`main.c`（结果写 0x2000）、`sw/mkhex.py`（ELF→readmemh word）、Makefile 支持 `all`/`asm`。

---

**T  | 2026-08-08 20:30 | 开始迭代 4（Phase 3）**

---

## 2026-08-08 — Phase 2 迭代 3：模块化重构 + 严谨验证 + 完整交付（已完成 ✓）

### 本迭代目标

模块化设计（每模块独立验证后顶层连接）+ 严谨测试（以上板正确为目标）+ 完整项目交付（芯片手册/验证报告）。

### 操作日志（时间倒序）

---

**T  | 2026-08-08 20:20 | 迭代 3 完成（交付物齐备）**

- **模块化重构**：AICore 拆为独立模块
  - `DataBuffer.scala`（新）：A/B 缓冲，PIO 写/读 + 计算行读（组合）
  - `Quantizer.scala`（新）：`sat8(relu(acc>>shift))` 组合输出变换
  - `MacArray`/`AxiLiteSlave` 保持独立模块；AICore 集成三者
  - 生成 Verilog 确认 6 模块层级（AICore/AxiLiteSlave/DataBuffer/MacArray/Quantizer）
- **每模块独立测试**（新文件）：
  - `AxiLiteSlaveTest`（8）：AW先/W先/同时/背压(b/r.ready 保持)/连续读写/OKAY
  - `DataBufferTest`（3）：PIO 写读回、同步写时序（同拍读旧值）、整行读
  - `MacArrayTest`（4）：清零、单乘积保持、行列门控、随机 K=32 vs 参考
  - `QuantizerTest`（5）：透传/饱和/ReLU/shift 各档/20 组随机 vs 参考
  - `AICoreIntegTest`（5）：复位、确定性（同输入两次一致）、K=0、K=1/M=N=1、10 组随机 sweep（原始+量化 vs 参考）
- **踩坑记录**：
  - DataBuffer 的 pioWr.valid 在部分分支未驱动 → FIRRTL RefNotInitialized（补默认 false + 无条件驱动地址）
  - 测试驱动握手 bug：valid 置高后未 step 就撤销 → 事务永不 fire（改"等 ready → step 提交 → 撤销"）
  - Scala 字面量：`0xDEADBEEF` 被当负 Int 传入 `.U` 报错 → 加 `L` 后缀
- **结果**：`sbt test` **34/34 全过**（7 套件）；Verilator lint OK；VCD 可导出
- **交付文档**：
  - `docs/chip_manual.md`（芯片手册：架构/接口/寄存器/数据格式/时序/FSM/性能/上板指南/限制）
  - `docs/verification.md`（验证计划与结果）
  - `README.md`（项目入口）
  - 更新 `docs/microarchitecture.md`（模块划分节）、`AGENT.md`

---

**T  | 2026-08-08 20:20 | 开始迭代 3（模块化 + 严谨验证 + 完整交付）**

- 规划：模块划分表、验证策略（golden reference/协议/时序/确定性/边界/随机）、交付物清单 → 写入 AGENT.md

---

## 2026-08-08 — Phase 2 迭代 2：量化/激活 + 性能/波形（已完成 ✓）

---

**T  | 2026-08-08 18:36 | 迭代 2 完成（量化/激活 + 性能/波形）**

- **量化/激活**：新增 QUANT 寄存器（0x28：[0] relu_en，[7:4] shift）+ INT8 输出缓冲（BUF_SEL=3）。
  - FSM 增加 QUANT 状态（1 周期）：在最后一次累加之后锁存 `sat8(relu(acc >> shift))`，避免时序竞争（若在最后累加周期锁存会缺最后一项）。
  - 原始 INT32 累加值保留（BUF_SEL=2），供调试。
- **性能**：新增 PERF 寄存器（0x2C），统计 compute+quant 周期数（=K+1）。
  - 修正：最初用测试侧手动数时钟，但 AXI 读本身步进时钟导致周期数低估 3 倍（K=32 误报 12）→ 改为硬件计数器。
  - 结果：K=8→9 周期，K=32→33 周期，**MAC 利用率 97%**（8×8×32：2048 有效 MAC / 2112 容量）。
- **波形**：`WriteVcdAnnotation` 测试成功生成 `test_run_dir/AICore_should_dump_a_VCD_waveform/AICore.vcd`。
- **软件驱动**：`gemm_quant()` 支持量化输出（shift/relu → INT8 cout），`c` 可传 NULL（内部 dummy）。交叉编译零警告。
- **回归**：`sbt test` 9/9 通过；`runMain` 生成 `generated/AICore.sv`，Verilator lint OK。
- 更新 `docs/microarchitecture.md`（寄存器表加 QUANT/PERF、FSM 加 QUANT 状态、量化/性能章节）。

---

**T  | 2026-08-08 18:22 | 开始 Phase 2（迭代 2）**

- 更新 AGENT.md 规划，明确本迭代范围（量化/激活 + 性能/波形，DMA 为延伸）。

---

## 2026-08-08 — Phase 1: 微架构设计（已完成 ✓）

### 操作日志（时间倒序）

---

**T  | 2026-08-08 18:21 | AICore 全量验证通过 + 软件驱动**

- `sbt test`：**6/6 通过**（73s）— MatrixMul + AICore 5 用例（寄存器回读、小 GEMM vs 参考、随机 8×8×8 vs 参考、K=32 维度门控、busy/done/重复运行）。
- `sbt "runMain accel.AICoreMain"` → `generated/AICore.sv`（503KB），`verilator --lint-only --timing` 通过 ✓
- 新增 `sw/`：`gemm_driver.c`（寄存器/缓冲装入/GEMM 封装）、`main.c`（自测 main）、`Makefile`。
- `riscv64-unknown-elf-gcc -march=rv32im -mabi=ilp32` 交叉编译零警告（680B text）。修复：数组初始化触发 memset（改 static）、`-Wl,-e,main`。

---

**T  | 2026-08-08 18:19 | Verilator PCH 彻底修复，编译提速 7.3s（重要）**

- 修复后完整模型 `make -f VAICore.mk -j16` **7.3 秒**构建出 `VAICore` 可执行文件（此前 15–20 分钟超时）。
- **PCH 生效确认**：删除 `.fast/.slow` 文本兜底文件后，`g++ -include VAICore__pch.h.slow ... -Winvalid-pch` 仍 0.184s 编译成功、无 invalid 警告 → 证明 GCC 实际加载了 `.gch`。
- 过程：
  1. 第一版补丁只加 `.fast/.slow` 文件副本 → 能编译但每个文件仍全量解析（PCH 被跳过），单文件 15min+ 不达标。
  2. 定位到真正要解决的是**让 PCH 生效**：把 `$(VK_PCH_I_FAST/SLOW)`（`-include ...pch.h.fast/slow`）在编译规则中**提前到 `$(CPPFLAGS)` 之前**（chiseltest 的 `-include <top>.h` 在 CPPFLAGS 里）。
  3. **补丁 bug**：插入 `.fast/.slow` 副本规则时把 `%.slow.gch` 的 recipe 截断，`g++ -o ...pch.h.slow` 被挂到 `cp` 规则后，导致 `.slow` 文件变成二进制 PCH（`gpch` 魔数）→ 用 `make -n` + 最小复现定位 → 重写整个 PCH 规则块（`patch_verilated_mk3.py`）。
  4. 遗留的失效 `.gch` 用当前 make 规则重建。
- 最终 `verilated.mk` 改动（两处）：
  - `.o` 编译规则 PCH `-include` 前置 + 依赖 `.fast/.slow` 文件
  - `%.fast.gch/%.slow.gch` 规则保持完整；新增 `$(VK_PCH_H).fast/slow: $(VK_PCH_H)` 生成文本副本兜底

---

**T  | 2026-08-08 16:20 | AICore RTL 实现（Phase 1）**

- 设计确认：**MAC 阵列（8×8, output-stationary）+ INT8 + AXI4-Lite MMIO + 自研 RV32I 核集成**。
- 写入 `docs/microarchitecture.md`（总体架构、数据布局、寄存器映射、FSM、AXI 行为）。
- 新增源文件：
  - `Params.scala` — `AIConfig(arraySize=8, dataW=8, accW=32, maxK=32)`
  - `AxiLiteSlave.scala` — AXI4-Lite 从机（单笔事务，AW/W 乱序合并）
  - `MacArray.scala` — 8×8 MAC 阵列，INT32 累加器，rowMask/colMask 维度门控
  - `AICore.scala` — 顶层：缓冲(A_T/B，Reg[Vec])、寄存器堆、FSM(IDLE→COMPUTE→DONE)
  - `AICoreMain.scala` — 生成 Verilog 的 main
  - `AxiTester.scala` + `AICoreTest.scala` — chiseltest（AXI 读写、小 GEMM、随机 8×8×8、K=32、重复运行）
- 编译期修复：
  - `ZeroExt` 不在默认作用域 → 改 `Cat(0.U(24.W), byte)`
  - 位域提取 bug（`byteIdx(hi,lo)` 当 lo>hi 时负数区间）→ 重写为 `byteAddr/kIdx/eIdx/accI/accJ`
  - `slave.io.bus.write.ready/read.ready` 未连接（FIRRTL RefNotInitialized）→ 补 `:= true.B`

---

**T  | 2026-08-08 16:09-16:15 | Phase 0 环境搭建（已完成，见下）**

## 2026-08-08 — Phase 0: 环境搭建（已完成 ✓）

### 操作日志（时间倒序）

---

**T  | 2026-08-08 16:38 | Verilator PCH 问题根因与修复（重要）**

- 现象：AICore 的 chiseltest 在 Verilator 编译阶段报 `fatal error: VAICore__pch.h.fast: No such file or directory`。
- 排查过程：
  - 首次以为是 Debian 包 verilator 5.020 缺陷 → 从源码装了 5.030（`/usr/local`，需 flex/bison/autoconf/help2man），问题依旧 → **排除版本因素**。
  - 隔离实验证实 GCC PCH 机制本身正常；逐步二分命令行标志，定位到关键差异：
    - `g++ -include VAICore__pch.h.fast`（单独）→ GCC 能找到 `.gch` ✓
    - 命令中多出 `-include VAICore.h`（来自 chiseltest 的 `-CFLAGS`）且排在 PCH include **之前** → GCC 找不到 PCH ✗
- **根因**：GCC 只为**首个**被 include 的头文件使用 PCH。chiseltest 把 `-include VAICore.h` 放进 `VM_USER_CFLAGS`→`CPPFLAGS`，在命令行中先于 `-include VAICore__pch.h.fast`；PCH 被跳过，GCC 回退读取 `VAICore__pch.h.fast`，而 verilator **从不生成**该文件 → fatal。
- **修复**：给 `/usr/local/share/verilator/include/verilated.mk` 打补丁（`/tmp/opencode/patch_verilated_mk.py`）：
  1. `.o` 规则依赖新增 `$(VK_PCH_H).fast` / `$(VK_PCH_H).slow`
  2. 新增规则：`$(VK_PCH_H).fast: $(VK_PCH_H)` → `cp $< $@`（同样 .slow）
  这样即使 PCH 被跳过，GCC 也能读到真实头文件副本。
- 教训：排障要先用二分法定位而非过早更换工具版本；已用 `grep`/`make -n`/`-Winvalid-pch` 逐步锁定。

**T  | 2026-08-08 16:26 | 安装 make / g++（Verilator 仿真必需）**

- `apt install build-essential` → GNU Make 4.3、g++ 13.3.0。
- chiseltest 的 Verilator 后端需要 `make` 编译生成的 C++。

---

**T  | 2026-08-08 16:15 | 端到端验证（全部通过）**

- RISC-V 冒烟：`riscv64-unknown-elf-gcc -march=rv32im -O1` 编译 C 程序并 `objdump` 反汇编成功 ✓
- Verilator 冒烟：`verilator --lint-only generated/MatrixMul.sv` → "verilator lint OK" ✓
- 清理临时文件 `/tmp/sbt.tgz`；添加 `.gitignore`（target/generated 等）。

---

**T  | 2026-08-08 16:15 | chiseltest 运行通过**

- `sbt test` → 1 个测试通过（MatrixMul 7×6=42），用时 6s。
- chiseltest 6.0.0 + Verilator 后端工作正常。

---

**T  | 2026-08-08 16:15 | Verilog 生成成功**

- `sbt run`（accel.MatrixMulMain）→ 在 `generated/` 生成 `MatrixMul.sv`（SystemVerilog）✓

---

**T  | 2026-08-08 16:15 | Chisel 项目脚手架 + 编译**

- 创建 `build.sbt`、`project/build.properties`、`src/main/scala/accel/MatrixMul.scala`、`src/test/scala/accel/MatrixMulTest.scala`。
- 版本决策（查 Maven Central 元数据后确定）：
  - Chisel `6.7.0`（org.chipsalliance，Scala 2.13.12 构建）
  - chiseltest `6.0.0`（**注意：在 `edu.berkeley.cs` 组，不在 org.chipsalliance**）
  - Scala `2.13.14`，sbt `1.10.7`
  - 选 Chisel 6.x 而非 7.x 的原因：chiseltest 只发布到 6.0.0，生态兼容性优先
- `sbt compile` 首次编译 27s 成功（依赖从 Maven Central 拉取正常）。

---

**T  | 2026-08-08 16:12 | 安装 sbt 1.10.7**

- 从 GitHub Releases 下载 `sbt-1.10.7.tgz`（57MB，`curl -L`，GitHub 慢但可达）。
- 解压到 `/usr/local/lib/sbt`，软链 `/usr/local/bin/sbt`。
- `sbt --version` 正常，launcher 自动拉取 Scala 2.12.20 ✓

---

**T  | 2026-08-08 16:11 | 安装 Verilator 5.020 + RISC-V 工具链**

- `apt install verilator` → **Verilator 5.020** ✓
- **教训**：三个 apt 并行安装触发 dpkg 锁冲突，RISC-V 工具链/curl 失败。改为**串行**重试。
- `apt install gcc-riscv64-unknown-elf gdb-multiarch` → **riscv64-unknown-elf-gcc 13.2.0** ✓

---

**T  | 2026-08-08 16:09 | 安装 JDK 17**

- `sudo apt-get update`（TUNA 镜像）+ `apt install openjdk-17-jdk-headless`
- 验证：OpenJDK **17.0.19**，javac 17.0.19 ✓

---

**T  | 2026-08-08 16:10 | 初始化文档**

- 创建项目目录 `/home/yihan/riscv-ai-accel` 及 `docs/` 子目录。
- 创建 `AGENT.md`（规划/方法/目标）与 `OPERATIONS_LOG.md`（本日志）。

---

**T  | 2026-08-08 16:06 | 确认免密 sudo 配置**

- 用户执行 `echo "$(whoami) ALL=(ALL) NOPASSWD:ALL" | sudo tee /etc/sudoers.d/$(whoami)-nopasswd`。
- 验证：`sudo -n true` 返回 OK。

---

**T  | 2026-08-08 15:58 | 环境勘察**

- OS: Ubuntu 24.04 LTS (WSL2)，16 核，内存 7.6GiB，磁盘可用 953G。
- 初始状态：**未安装** java / sbt / scala / coursier。
- 网络探测结果：
  - `apt` 源为清华 TUNA 镜像 ✓
  - Maven Central（repo1.maven.org）可达 ✓
  - github.com 直连慢/不稳定；raw.githubusercontent.com 可访问
- 决策：优先使用 apt（TUNA）+ Maven Central 安装；必要时从 GitHub release 下载。
- 阻塞：sudo 需要密码 → 请用户配置免密 sudo。

---
