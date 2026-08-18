package accel.soc

import chisel3._

object SoCMain extends App {
  // Load the bare-metal GEMM test program (space-separated 32-bit hex words).
  val src = scala.io.Source.fromFile("sw/gemm_test.hex")
  val words = try {
    src.getLines().flatMap(_.trim.split("\\s+")).filter(_.nonEmpty)
      .map(w => java.lang.Long.parseLong(w, 16)).toSeq
  } finally src.close()

  emitVerilog(new SoC(program = words), Array("--target-dir", "generated"))
}
