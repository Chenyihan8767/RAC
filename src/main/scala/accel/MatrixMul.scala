package accel

import chisel3._

class MatrixMul extends Module {
  val io = IO(new Bundle {
    val in_a  = Input(UInt(8.W))
    val in_b  = Input(UInt(8.W))
    val sum   = Output(UInt(16.W))
  })
  io.sum := io.in_a * io.in_b
}

object MatrixMulMain extends App {
  emitVerilog(new MatrixMul(), Array("--target-dir", "generated"))
}
