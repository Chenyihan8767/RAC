package accel.soc

import accel.core.CoreBus
import chisel3._
import chisel3.util._

/**
  * CoreRam: byte-addressable word memory (SRAM) on the core bus.
  * Uses a real memory array (Mem) with combinational reads and per-byte write
  * enables. A short reset-init loop zeroes the whole memory deterministically
  * (important for reproducible simulation); requests are only served after
  * initialization completes (the core stalls on rvalid until then).
  * Exposes a combinational debug read port.
  */
class CoreRam(size: Int) extends Module {
  val io = IO(new Bundle {
    val bus     = Flipped(new CoreBus)
    val dbgAddr = Input(UInt(32.W))
    val dbgData = Output(UInt(32.W))
  })

  val addrBits = log2Ceil(size * 4)
  val mem = Mem(size, Vec(4, UInt(8.W)))

  // Deterministic zero initialization after reset.
  val initCnt = RegInit(0.U(log2Ceil(size).W))
  val initDone = RegInit(false.B)
  when(!initDone) {
    mem(initCnt) := 0.U.asTypeOf(Vec(4, UInt(8.W)))
    when(initCnt === (size - 1).U) { initDone := true.B }
    .otherwise { initCnt := initCnt + 1.U }
  }

  io.bus.rvalid := initDone && io.bus.req.valid
  io.bus.rdata := mem(io.bus.req.addr(addrBits - 1, 2)).asUInt

  when(initDone && io.bus.req.valid && io.bus.req.wen) {
    for (b <- 0 until 4) {
      when(io.bus.req.wmask(b)) {
        mem(io.bus.req.addr(addrBits - 1, 2))(b) := io.bus.req.wdata(8 * b + 7, 8 * b)
      }
    }
  }

  io.dbgData := mem(io.dbgAddr(addrBits - 1, 2)).asUInt
}
