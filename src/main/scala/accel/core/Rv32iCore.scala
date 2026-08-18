package accel.core

import chisel3._
import chisel3.util._

/**
  * Rv32iCore: a minimal single-issue RV32I processor.
  *
  * Two-state FSM (IF / EX) plus a MEM sub-state for load/store instructions:
  *   - IF:  instruction fetch over the core bus; on rvalid latch instr -> EX
  *   - EX:  decode + execute. ALU/control complete in one cycle (writeback +
  *          PC update). Load/store assert a bus request and move to MEM.
  *   - MEM: hold the request until rvalid, then writeback (load) and PC+4.
  *
  * Implements the full RV32I base ISA (integer only, no CSR):
  *   ALU:  add/sub/sll/slt/sltu/xor/srl/sra/or/and
  *   IMM:  addi/slti/sltiu/xori/ori/andi/slli/srli/srai
  *   BR:   beq/bne/blt/bge/bltu/bgeu
  *   J:    jal/jalr   U: lui/auipc
  *   MEM:  lb/lh/lw/lbu/lhu/sb/sh/sw (aligned loads/stores assumed)
  *
  * Reset: synchronous, PC = 0.
  */
class Rv32iCore extends Module {
  val io = IO(new Bundle {
    val bus = new CoreBus
    val dbgRegAddr = Input(UInt(5.W))
    val dbgRegVal  = Output(UInt(32.W))
    val dbg = Output(new Bundle {
      val pc      = UInt(32.W)
      val instr   = UInt(32.W)
      val reg8    = UInt(32.W)
      val memAddr = UInt(32.W)
      val alu     = UInt(32.W)
      val rs1v    = UInt(32.W)
      val rs2v    = UInt(32.W)
      val state   = UInt(2.W)
      val pcNext  = UInt(32.W)
      val brTaken = Bool()
      val bTgt    = UInt(32.W)
    })
  })

  // ---- FSM states ----
  private val S_IF  = 0.U(2.W)
  private val S_EX  = 1.U(2.W)
  private val S_MEM = 2.U(2.W)
  val state = RegInit(S_IF)

  val pc = RegInit(0.U(32.W))
  val instrReg = RegInit(0.U(32.W))
  val memAddrReg = RegInit(0.U(32.W)) // latched load/store address

  // ---- register file ----
  val regfile = RegInit(VecInit(Seq.fill(32)(0.U(32.W))))

  when(state === S_IF && io.bus.rvalid) {
    instrReg := io.bus.rdata
    state := S_EX
  }

  // ---- decode (combinational, from instrReg) ----
  private val opcode = instrReg(6, 0)
  private val rd     = instrReg(11, 7)
  private val funct3 = instrReg(14, 12)
  private val rs1    = instrReg(19, 15)
  private val rs2    = instrReg(24, 20)
  private val funct7 = instrReg(31, 25)

  val isLui    = opcode === "b0110111".U
  val isAuipc  = opcode === "b0010111".U
  val isJal    = opcode === "b1101111".U
  val isJalr   = opcode === "b1100111".U
  val isBranch = opcode === "b1100011".U
  val isLoad   = opcode === "b0000011".U
  val isStore  = opcode === "b0100011".U
  val isOpImm  = opcode === "b0010011".U
  val isOp     = opcode === "b0110011".U
  val isMem    = isLoad || isStore
  val isAlu    = isOp || isOpImm
  val isLuiAup = isLui || isAuipc
  val isJalJalr = isJal || isJalr

  private val instRs1 = rs1
  private val instRs2 = rs2

  // immediates (all sign-extended to 32 bits)
  private val iImm = Cat(Fill(20, instrReg(31)), instrReg(31, 20)).asSInt // I-type
  private val sImm = Cat(Fill(20, instrReg(31)), instrReg(31, 25), instrReg(11, 7)).asSInt // S-type
  private val bImm = Cat(Fill(19, instrReg(31)), instrReg(31), instrReg(7), instrReg(30, 25), instrReg(11, 8), 0.U(1.W)).asSInt // B-type
  private val uImm = Cat(instrReg(31, 12), 0.U(12.W)).asSInt // U-type
  private val jImm = Cat(Fill(11, instrReg(31)), instrReg(31), instrReg(19, 12), instrReg(20), instrReg(30, 21), 0.U(1.W)).asSInt // J-type

  val rs1V = regfile(instRs1)
  val rs2V = regfile(instRs2)

  // ---- ALU (SInt(32) operands so immediates stay sign-extended) ----
  private val aluB = Mux(isOp, rs2V.asSInt, iImm)
  private val shamt = iImm(4, 0) // slli/srli/srai shift amount
  private val addRes  = (rs1V.asSInt + aluB).asUInt
  private val subRes  = (rs1V.asSInt - rs2V.asSInt).asUInt
  private val sltSig  = rs1V.asSInt < aluB
  private val sltuSig = rs1V < aluB.asUInt
  private val shiftDir = instrReg(30) // 1 = sra/srai, 0 = srl/srli
  private val shiftRes = Mux(shiftDir, (rs1V.asSInt >> shamt).asUInt, (rs1V >> shamt))

  // M-extension multiplies (rv32im)
  private val isMul   = isOp && funct7 === "b0000001".U
  private val mulFull = rs1V.asSInt * rs2V.asSInt // SInt(64)
  private val mulLo   = mulFull(31, 0)
  private val mulHi   = mulFull(63, 32)
  private val mulHu   = (rs1V * rs2V)(63, 32)
  private val mulHsu  = (rs1V.asSInt * rs2V)(63, 32)

  private val memImm = Mux(isStore, sImm, iImm) // stores use S-type, loads use I-type
  val aluResult = Mux(isMem, (rs1V.asSInt + memImm).asUInt, MuxCase(0.U(32.W), Seq(
    (funct3 === 0.U) -> Mux(isMul, mulLo, Mux(isOp && funct7 === "b0100000".U, subRes, addRes)),
    (funct3 === 1.U) -> Mux(isMul, mulHi, (rs1V << shamt)),
    (funct3 === 2.U) -> Mux(isMul, mulHsu, Mux(sltSig, 1.U, 0.U)),
    (funct3 === 3.U) -> Mux(isMul, mulHu, Mux(sltuSig, 1.U, 0.U)),
    (funct3 === 4.U) -> (rs1V ^ aluB.asUInt),
    (funct3 === 5.U) -> shiftRes,
    (funct3 === 6.U) -> (rs1V | aluB.asUInt),
    (funct3 === 7.U) -> (rs1V & aluB.asUInt)
  )))

  // ---- branch ----
  private val brEq  = rs1V === rs2V
  private val brLt  = rs1V.asSInt < rs2V.asSInt
  private val brLtu = rs1V < rs2V
  val brTaken = MuxCase(false.B, Seq(
    (funct3 === 0.U) -> brEq,
    (funct3 === 1.U) -> !brEq,
    (funct3 === 4.U) -> brLt,
    (funct3 === 5.U) -> !brLt,
    (funct3 === 6.U) -> brLtu,
    (funct3 === 7.U) -> !brLtu
  ))

  // ---- next PC ----
  private val pcPlus4 = pc + 4.U
  private val branchTarget = (pc.asSInt + bImm).asUInt
  private val jalTarget    = (pc.asSInt + jImm).asUInt
  private val jalrTarget   = (addRes & ~1.U(32.W))
  val pcNext = MuxCase(pcPlus4, Seq(
    isJal   -> jalTarget,
    isJalr  -> jalrTarget,
    isBranch -> Mux(brTaken, branchTarget, pcPlus4)
  ))

  // ---- load value transform (in MEM) ----
  private val byteSel = memAddrReg(1, 0)
  private val halfSel = memAddrReg(1)
  private val rByte = (io.bus.rdata >> (byteSel << 3))(7, 0)
  private val rHalf = (io.bus.rdata >> (halfSel << 4))(15, 0)
  val loadedValue = MuxCase(0.U(32.W), Seq(
    (funct3 === 0.U) -> rByte.asSInt.pad(32).asUInt, // lb
    (funct3 === 1.U) -> rHalf.asSInt.pad(32).asUInt, // lh
    (funct3 === 2.U) -> io.bus.rdata,                // lw
    (funct3 === 4.U) -> rByte,                       // lbu
    (funct3 === 5.U) -> rHalf                        // lhu
  ))

  // ---- store mask ----
  val storeMask = MuxCase(0.U(4.W), Seq(
    (funct3 === 0.U) -> (1.U(4.W) << memAddrReg(1, 0)),         // sb
    (funct3 === 1.U) -> (3.U(4.W) << Cat(memAddrReg(1), 0.U(1.W))), // sh
    (funct3 === 2.U) -> 0xF.U                                    // sw
  ))

  // ---- writeback ----
  private val luiAuipc = Mux(isLui, uImm.asUInt, (pc.asSInt + uImm).asUInt)
  val wbAlu = Mux(isLuiAup, luiAuipc, Mux(isJalJalr, pcPlus4, aluResult))
  // ALU/control write back in EX; loads write back in MEM (after data arrives)
  val wbWenEx  = (isAlu || isLuiAup || isJalJalr) && (rd =/= 0.U)
  val wbWenMem = isLoad && (rd =/= 0.U)

  // ---- store byte-lane alignment: shift rs2 into the addressed byte lane ----
  private val storeShift = MuxCase(0.U(5.W), Seq(
    (funct3 === 0.U) -> (memAddrReg(1, 0) << 3), // sb: byte lane * 8
    (funct3 === 1.U) -> (memAddrReg(1) << 4),    // sh: halfword lane * 16
    (funct3 === 2.U) -> 0.U                       // sw: aligned word
  ))

  // ---- memory request (fetch in IF, load/store in MEM) ----
  io.bus.req.valid := state === S_IF || state === S_MEM
  io.bus.req.addr := Mux(state === S_IF, pc, memAddrReg)
  io.bus.req.wen := state === S_MEM && isStore
  io.bus.req.wdata := Mux(state === S_MEM, regfile(instRs2) << storeShift, 0.U)
  io.bus.req.wmask := Mux(state === S_MEM, storeMask, 0.U)

  io.dbg.pc := pc
  io.dbg.instr := instrReg
  io.dbg.reg8 := regfile(8)
  io.dbg.memAddr := memAddrReg
  io.dbg.alu := aluResult
  io.dbg.rs1v := rs1V
  io.dbg.rs2v := rs2V
  io.dbg.state := state
  io.dbg.pcNext := pcNext
  io.dbg.brTaken := brTaken
  io.dbg.bTgt := branchTarget
  io.dbgRegVal := regfile(io.dbgRegAddr)

  // ---- EX / MEM ----
  switch(state) {
    is(S_EX) {
      when(isMem) {
        memAddrReg := aluResult
        state := S_MEM
      }.otherwise {
        when(wbWenEx) { regfile(rd) := wbAlu }
        pc := pcNext
        state := S_IF
      }
    }
    is(S_MEM) {
      when(io.bus.rvalid) {
        when(wbWenMem) { regfile(rd) := loadedValue }
        pc := pcPlus4
        state := S_IF
      }
    }
  }
}
