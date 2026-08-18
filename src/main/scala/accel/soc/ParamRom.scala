package accel.soc

import accel.core.CoreBus
import chisel3._
import chisel3.util._

/**
  * ParamRom: read-only boot memory (program words) using a real memory array.
  * A reset-init loop loads the program words deterministically; requests are
  * served only after initialization (the core stalls until then).
  * Writes are acknowledged but ignored.
  */
class ParamRom(size: Int, words: Seq[Long]) extends Module {
  val io = IO(Flipped(new CoreBus))

  val addrBits = 12 // 4KB region, word index addr[11:2]
  val mem = Mem(size, UInt(32.W))
  val initWords = VecInit(words.padTo(size, 0L).map(w => (w & 0xFFFFFFFFL).U(32.W)))

  val initCnt = RegInit(0.U(log2Ceil(size).W))
  val initDone = RegInit(false.B)
  when(!initDone) {
    mem(initCnt) := initWords(initCnt)
    when(initCnt === (size - 1).U) { initDone := true.B }
    .otherwise { initCnt := initCnt + 1.U }
  }

  io.rvalid := initDone && io.req.valid && !io.req.wen
  io.rdata := mem(io.req.addr(addrBits - 1, 2))
}
