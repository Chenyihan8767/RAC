package accel.soc

import accel.AxiLite4
import accel.core.CoreBus
import chisel3._
import chisel3.util._

/**
  * AxiBridge: converts a single core-bus request into an AXI4-Lite
  * transaction to the accelerator (AICore). Waits for the AXI response
  * before acknowledging the core (rvalid pulse).
  */
class AxiBridge extends Module {
  val io = IO(new Bundle {
    val core = Flipped(new CoreBus)
    val axi  = Flipped(new AxiLite4(32, 32))
  })

  val busy = RegInit(false.B)
  val isW  = RegInit(false.B)
  val aAddr = RegInit(0.U(32.W))
  val wData = RegInit(0.U(32.W))
  val wMask = RegInit(0.U(4.W))
  val awDone = RegInit(false.B)
  val wDone  = RegInit(false.B)
  val rPulse = RegInit(false.B)
  val rData  = RegInit(0.U(32.W))

  io.axi.b.ready := true.B
  io.axi.r.ready := true.B

  when(io.core.req.valid && !busy && !rPulse) {
    busy := true.B
    isW := io.core.req.wen
    aAddr := io.core.req.addr
    wData := io.core.req.wdata
    wMask := io.core.req.wmask
    awDone := false.B
    wDone := false.B
  }

  // ---- read transaction ----
  io.axi.ar.valid := busy && !isW && !awDone
  io.axi.ar.bits.addr := aAddr
  io.axi.ar.bits.prot := 0.U
  when(busy && !isW && io.axi.ar.fire) { awDone := true.B }
  when(busy && !isW && awDone && io.axi.r.fire) {
    rData := io.axi.r.bits.data
    rPulse := true.B
    busy := false.B
  }

  // ---- write transaction ----
  io.axi.aw.valid := busy && isW && !awDone
  io.axi.aw.bits.addr := aAddr
  io.axi.aw.bits.prot := 0.U
  io.axi.w.valid := busy && isW && !wDone
  io.axi.w.bits.data := wData
  io.axi.w.bits.strb := wMask
  when(busy && isW && io.axi.aw.fire) { awDone := true.B }
  when(busy && isW && io.axi.w.fire)  { wDone := true.B }
  when(busy && isW && awDone && wDone && io.axi.b.fire) {
    rPulse := true.B
    busy := false.B
  }

  io.core.rvalid := rPulse
  io.core.rdata := rData
  when(rPulse) { rPulse := false.B }
}
