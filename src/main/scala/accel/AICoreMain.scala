package accel

import chisel3._

object AICoreMain extends App {
  emitVerilog(new AICore(), Array("--target-dir", "generated"))
}
