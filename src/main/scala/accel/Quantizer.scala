package accel

import chisel3._

/**
  * Quantizer: combinational output transform for the accumulator.
  *
  *   y = sat8( relu( (acc + bias) >> shift ) )
  *
  *  - shift (0..15): arithmetic right shift, the log2 quantization scale
  *  - reluEn: if set, negative results are clamped to 0
  *  - biasEn: if set, a per-column bias vector (8 x INT32) is added before
  *    the shift (used for MLP/convolution bias)
  *  - saturation: result is clamped to [-128, 127] (INT8)
  *
  * Purely combinational; the caller latches io.out into a register when the
  * accumulation is finished.
  */
class Quantizer(cfg: AIConfig) extends Module {
  val io = IO(new Bundle {
    val acc    = Input(Vec(cfg.arraySize, Vec(cfg.arraySize, SInt(cfg.accW.W))))
    val bias   = Input(Vec(cfg.arraySize, SInt(cfg.accW.W))) // per-column
    val biasEn = Input(Bool())
    val reluEn = Input(Bool())
    val shift  = Input(UInt(4.W))
    val out    = Output(Vec(cfg.arraySize, Vec(cfg.arraySize, SInt(8.W))))
  })

  val maxV = 127.S(cfg.accW.W)
  val minV = -128.S(cfg.accW.W)

  for (i <- 0 until cfg.arraySize; j <- 0 until cfg.arraySize) {
    val biased = io.acc(i)(j) + Mux(io.biasEn, io.bias(j), 0.S)
    val scaled = biased >> io.shift
    val relued = Mux(io.reluEn, Mux(scaled < 0.S, 0.S, scaled), scaled)
    io.out(i)(j) := Mux(relued > maxV, 127.S(8.W), Mux(relued < minV, -128.S(8.W), relued(7, 0).asSInt))
  }
}
