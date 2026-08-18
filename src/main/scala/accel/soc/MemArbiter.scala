package accel.soc

import accel.core.CoreBus
import chisel3._

/**
  * MemArbiter: round-robin arbitration between two core-bus masters (the
  * RISC-V core and the AICore DMA engine) onto one memory bus. Transactions
  * complete in one cycle (combinational response), so the arbiter can switch
  * every cycle when the currently-selected master is idle.
  */
class MemArbiter extends Module {
  val io = IO(new Bundle {
    val m0  = Flipped(new CoreBus) // core
    val m1  = Flipped(new CoreBus) // dma
    val out = new CoreBus
  })

  val sel = RegInit(false.B) // false = m0, true = m1
  val m0Req = io.m0.req.valid
  val m1Req = io.m1.req.valid
  val curReq = Mux(sel, m1Req, m0Req)

  io.out.req.valid := curReq
  io.out.req.addr := Mux(sel, io.m1.req.addr, io.m0.req.addr)
  io.out.req.wen := Mux(sel, io.m1.req.wen, io.m0.req.wen)
  io.out.req.wdata := Mux(sel, io.m1.req.wdata, io.m0.req.wdata)
  io.out.req.wmask := Mux(sel, io.m1.req.wmask, io.m0.req.wmask)

  io.m0.rvalid := !sel && io.out.rvalid
  io.m1.rvalid := sel && io.out.rvalid
  io.m0.rdata := io.out.rdata
  io.m1.rdata := io.out.rdata

  when(!curReq) { sel := !sel }
}
