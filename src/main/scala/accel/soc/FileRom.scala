package accel.soc

import accel.core.CoreBus
import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFile

/**
  * FileRom: read-only boot memory initialized from a hex file
  * (readmemh format, one 32-bit word per line / @address markers).
  * 1-cycle response latency; writes are acknowledged but ignored.
  */
class FileRom(sizeWords: Int, hexFile: String) extends Module {
  val io = IO(Flipped(new CoreBus))

  val mem = Mem(sizeWords, UInt(32.W))
  loadMemoryFromFile(mem, hexFile)

  val busy = RegInit(false.B)
  val rvalidR = RegInit(false.B)
  val rdataR = RegInit(0.U(32.W))
  val addrBits = log2Ceil(sizeWords * 4)

  when(busy) {
    rvalidR := true.B
    busy := false.B
  }.otherwise {
    rvalidR := false.B
    when(io.req.valid) {
      busy := true.B
      when(!io.req.wen) {
        rdataR := mem(io.req.addr(addrBits - 1, 2))
      }
    }
  }

  io.rvalid := rvalidR
  io.rdata := rdataR
}
