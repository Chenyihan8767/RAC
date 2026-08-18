package accel

import chisel3._
import chisel3.util._

/**
  * DataBuffer: one row of the activation/weight storage for the MAC array.
  *
  * Storage: maxK x arraySize bytes (k-major rows of 8 bytes), implemented as
  * registers so PIO reads/writes and the compute row-read are all combinational.
  *
  * Ports:
  *  - pioWr:  PIO byte write (used by the register-bus DATA write port)
  *  - pioRd:  PIO byte read  (used by the register-bus DATA read port)
  *  - rowAddr/rowData: combinational read of a full k-row (feeds the MAC array)
  *
  * Note: writes are synchronous (visible one cycle after pioWr.valid); reads are
  * combinational (reflect the current registered contents).
  */
class DataBuffer(cfg: AIConfig) extends Module {
  val io = IO(new Bundle {
    val pioWr = Input(new Bundle {
      val valid = Bool()
      val k     = UInt(log2Ceil(cfg.maxK).W)
      val e     = UInt(log2Ceil(cfg.arraySize).W)
      val data  = UInt(cfg.dataW.W)
    })
    val wordWr = Input(new Bundle { // DMA: write 4 bytes at (k, eOff..eOff+3)
      val valid = Bool()
      val k     = UInt(log2Ceil(cfg.maxK).W)
      val eOff  = UInt(log2Ceil(cfg.arraySize).W)
      val data  = UInt((cfg.dataW * 4).W)
    })
    val pioRd = Input(new Bundle {
      val k = UInt(log2Ceil(cfg.maxK).W)
      val e = UInt(log2Ceil(cfg.arraySize).W)
    })
    val pioRdData = Output(UInt(cfg.dataW.W))
    val rowAddr   = Input(UInt(log2Ceil(cfg.maxK).W))
    val rowData   = Output(Vec(cfg.arraySize, UInt(cfg.dataW.W)))
  })

  val mem = RegInit(VecInit(Seq.fill(cfg.maxK)(
    VecInit(Seq.fill(cfg.arraySize)(0.U(cfg.dataW.W)))
  )))

  when(io.pioWr.valid) {
    mem(io.pioWr.k)(io.pioWr.e) := io.pioWr.data
  }
  when(io.wordWr.valid) {
    for (b <- 0 until 4) {
      mem(io.wordWr.k)(io.wordWr.eOff + b.U) := io.wordWr.data(8 * b + 7, 8 * b)
    }
  }

  io.pioRdData := mem(io.pioRd.k)(io.pioRd.e)
  io.rowData := mem(io.rowAddr)
}
