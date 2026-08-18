package accel

import chisel3._
import chisel3.util._

class MacArray(cfg: AIConfig) extends Module {
  val io = IO(new Bundle {
    val a      = Input(Vec(cfg.arraySize, SInt(cfg.dataW.W)))
    val b      = Input(Vec(cfg.arraySize, SInt(cfg.dataW.W)))
    val valid  = Input(Bool())
    val clear  = Input(Bool())
    val rowMask = Input(Vec(cfg.arraySize, Bool()))
    val colMask = Input(Vec(cfg.arraySize, Bool()))
    val out     = Output(Vec(cfg.arraySize, Vec(cfg.arraySize, SInt(cfg.accW.W))))
  })

  val acc = RegInit(VecInit(Seq.fill(cfg.arraySize) {
    VecInit(Seq.fill(cfg.arraySize)(0.S(cfg.accW.W)))
  }))

  when(io.clear) {
    for (i <- 0 until cfg.arraySize; j <- 0 until cfg.arraySize) {
      acc(i)(j) := 0.S
    }
  }.elsewhen(io.valid) {
    for (i <- 0 until cfg.arraySize; j <- 0 until cfg.arraySize) {
      when(io.rowMask(i) && io.colMask(j)) {
        acc(i)(j) := acc(i)(j) + io.a(i) * io.b(j)
      }
    }
  }

  io.out := acc
}
