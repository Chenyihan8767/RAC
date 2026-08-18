package accel.core

import chisel3._

/** Core memory request (single outstanding transaction). */
class CoreReq extends Bundle {
  val valid = Output(Bool())
  val addr  = Output(UInt(32.W))
  val wen   = Output(Bool())       // 1 = store, 0 = load
  val wdata = Output(UInt(32.W))   // store data (unshifted, lane selected by wmask/addr)
  val wmask = Output(UInt(4.W))    // byte enables (sb/sh/sw)
}

/**
  * Core memory bus.
  *
  * Protocol: the master holds `req.valid/addr/wen/wdata/wmask` stable until
  * `rvalid` pulses (1 cycle). `rdata` is valid during `rvalid` for loads.
  * Every slave accepts one request when idle and pulses `rvalid` when the
  * transaction completes (1-cycle latency for memories, ~4 for AXI bridge).
  */
class CoreBus extends Bundle {
  val req    = new CoreReq
  val rvalid = Input(Bool())
  val rdata  = Input(UInt(32.W))
}
