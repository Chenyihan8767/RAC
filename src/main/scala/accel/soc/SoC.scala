package accel.soc

import accel.AIConfig
import accel.AICore
import accel.core.Rv32iCore
import chisel3._

/**
  * SoC: Rv32iCore + boot ROM + SRAM + AxiBridge + AICore.
  *
  * Address map (single unified core bus):
  *   - 0x0000_0000 .. 0x0000_0FFF : Boot ROM (code / rodata)
  *   - 0x0000_1000 .. 0x0000_4FFF : SRAM (data / bss / stack, 16KB)
  *   - 0x3000_0000 .. 0x3000_003F : AICore registers (AXI4-Lite via AxiBridge)
  *   - otherwise                  : unmapped (immediate 0 response)
  *
  * The debug ports read the SRAM, PC and instruction combinationally for
  * test observability.
  */
class SoC(cfg: AIConfig = AIConfig(), program: Seq[Long] = Seq.empty) extends Module {
  val io = IO(new Bundle {
    val dbgAddr = Input(UInt(32.W))
    val dbgData = Output(UInt(32.W))
    val dbgPc    = Output(UInt(32.W))
    val dbgInstr = Output(UInt(32.W))
    val dbgReg8  = Output(UInt(32.W))
    val dbgMemA  = Output(UInt(32.W))
    val dbgAlu   = Output(UInt(32.W))
    val dbgRs1   = Output(UInt(32.W))
    val dbgRs2   = Output(UInt(32.W))
    val dbgState = Output(UInt(2.W))
    val dbgPcN   = Output(UInt(32.W))
    val dbgBrT   = Output(Bool())
    val dbgBTgt  = Output(UInt(32.W))
    val dbgReqV  = Output(Bool())
    val dbgReqA  = Output(UInt(32.W))
    val dbgRV    = Output(Bool())
    val dbgRD    = Output(UInt(32.W))
    val dbgRomRV = Output(Bool())
    val dbgRomB  = Output(Bool())
    val dbgMdim  = Output(UInt(32.W))
    val dbgKdim  = Output(UInt(32.W))
    val dbgNdim  = Output(UInt(32.W))
    val dbgBusy  = Output(Bool())
    val dbgDone  = Output(Bool())
    val dbgPerf  = Output(UInt(32.W))
    val dbgAWv   = Output(Bool())
    val dbgAWr   = Output(Bool())
    val dbgWv    = Output(Bool())
    val dbgWr    = Output(Bool())
    val dbgBv    = Output(Bool())
    val dbgWD    = Output(UInt(32.W))
    val dbgHA    = Output(Bool())
    val dbgHD    = Output(Bool())
    val dbgBusW  = Output(Bool())
    val dbgOutAddr = Input(UInt(32.W))
    val dbgOutData = Output(UInt(32.W))
    val dbgBufAddr = Input(UInt(32.W))
    val dbgBufEn   = Input(Bool())
    val dbgBufSel  = Input(Bool())
    val dbgBufData = Output(UInt(32.W))
    val dbgAccAddr = Input(UInt(32.W))
    val dbgAccData = Output(UInt(32.W))
    val dbgBufIdx  = Output(UInt(16.W))
    val dbgBufS    = Output(UInt(2.W))
    val dbgWdata   = Output(UInt(32.W))
    val dbgWrAddr  = Output(UInt(8.W))
    val dbgWrFire  = Output(Bool())
    val dbgRegAddr = Input(UInt(5.W))
    val dbgRegVal  = Output(UInt(32.W))
  })

  val core   = Module(new Rv32iCore())
  val rom    = Module(new ParamRom(1024, program))
  val ram    = Module(new CoreRam(4096))
  val bridge = Module(new AxiBridge())
  val accel  = Module(new AICore(cfg))
  val arb    = Module(new MemArbiter())

  bridge.io.axi <> accel.io.axi

  // Arbitrate the core bus and the AICore DMA master onto one memory bus.
  arb.io.m0 <> core.io.bus
  arb.io.m1 <> accel.io.master

  val addr = arb.io.out.req.addr
  val isRom = addr < 0x1000.U
  val isRam = addr >= 0x1000.U && addr < 0x5000.U
  val isAxi = addr >= 0x30000000.U && addr < 0x40000000.U

  rom.io.req.valid := arb.io.out.req.valid && isRom
  rom.io.req.addr := addr
  rom.io.req.wen := arb.io.out.req.wen
  rom.io.req.wdata := arb.io.out.req.wdata
  rom.io.req.wmask := arb.io.out.req.wmask

  ram.io.bus.req.valid := arb.io.out.req.valid && isRam
  ram.io.bus.req.addr := addr
  ram.io.bus.req.wen := arb.io.out.req.wen
  ram.io.bus.req.wdata := arb.io.out.req.wdata
  ram.io.bus.req.wmask := arb.io.out.req.wmask

  bridge.io.core.req.valid := arb.io.out.req.valid && isAxi
  bridge.io.core.req.addr := addr
  bridge.io.core.req.wen := arb.io.out.req.wen
  bridge.io.core.req.wdata := arb.io.out.req.wdata
  bridge.io.core.req.wmask := arb.io.out.req.wmask

  arb.io.out.rvalid := rom.io.rvalid || ram.io.bus.rvalid || bridge.io.core.rvalid ||
    (arb.io.out.req.valid && !isRom && !isRam && !isAxi)
  arb.io.out.rdata := Mux(isRom, rom.io.rdata,
    Mux(isRam, ram.io.bus.rdata,
      Mux(isAxi, bridge.io.core.rdata, 0.U)))

  io.dbgAddr <> ram.io.dbgAddr
  io.dbgData := ram.io.dbgData
  io.dbgPc := core.io.dbg.pc
  io.dbgInstr := core.io.dbg.instr
  io.dbgReg8 := core.io.dbg.reg8
  io.dbgMemA := core.io.dbg.memAddr
  io.dbgAlu := core.io.dbg.alu
  io.dbgRs1 := core.io.dbg.rs1v
  io.dbgRs2 := core.io.dbg.rs2v
  io.dbgState := core.io.dbg.state
  io.dbgPcN := core.io.dbg.pcNext
  io.dbgBrT := core.io.dbg.brTaken
  io.dbgBTgt := core.io.dbg.bTgt
  io.dbgReqV := core.io.bus.req.valid
  io.dbgReqA := core.io.bus.req.addr
  io.dbgRV := core.io.bus.rvalid
  io.dbgRD := core.io.bus.rdata
  io.dbgRomRV := rom.io.rvalid
  io.dbgRomB := rom.io.req.valid
  io.dbgMdim := accel.io.dbg.mDim
  io.dbgKdim := accel.io.dbg.kDim
  io.dbgNdim := accel.io.dbg.nDim
  io.dbgBusy := accel.io.dbg.busy
  io.dbgDone := accel.io.dbg.done
  io.dbgPerf := accel.io.dbg.perf
  io.dbgAWv := bridge.io.axi.aw.valid
  io.dbgAWr := bridge.io.axi.aw.ready
  io.dbgWv := bridge.io.axi.w.valid
  io.dbgWr := bridge.io.axi.w.ready
  io.dbgBv := bridge.io.axi.b.valid
  io.dbgWD := bridge.io.axi.w.bits.data
  io.dbgHA := accel.io.dbg.ha
  io.dbgHD := accel.io.dbg.hd
  io.dbgBusW := accel.io.dbg.wv
  accel.io.dbgOutAddr := io.dbgOutAddr
  io.dbgOutData := accel.io.dbgOutData
  accel.io.dbgBufAddr := io.dbgBufAddr
  accel.io.dbgBufEn := io.dbgBufEn
  accel.io.dbgBufSel := io.dbgBufSel
  io.dbgBufData := accel.io.dbgBufData
  accel.io.dbgAccAddr := io.dbgAccAddr
  io.dbgAccData := accel.io.dbgAccData
  io.dbgBufIdx := accel.io.dbg.bufIdx
  io.dbgBufS := accel.io.dbg.bufSel
  io.dbgWdata := accel.io.dbg.wdata
  io.dbgWrAddr := accel.io.dbg.wrAddr
  io.dbgWrFire := accel.io.dbg.wrFire
  core.io.dbgRegAddr := io.dbgRegAddr
  io.dbgRegVal := core.io.dbgRegVal
}
