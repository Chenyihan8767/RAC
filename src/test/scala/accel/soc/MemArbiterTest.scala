package accel.soc

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

/**
  * Unit tests for the round-robin MemArbiter.
  *
  * Protocol recap (see CoreBus): a master holds req.valid/addr/wen/wdata/wmask
  * until rvalid pulses for 1 cycle. The slave (here simulated by the test)
  * accepts one request per cycle and pulses rvalid combinationally.
  *
  * Timing note: poke takes effect on combinational paths immediately, so
  * expectations on combinational outputs must be checked BEFORE clock.step.
  */
class MemArbiterTest extends AnyFlatSpec with ChiselScalatestTester {

  /** Drive one master's request lines (request persists until re-poked). */
  private def pokeReq(c: MemArbiter, master: String,
                      valid: Boolean, addr: Int = 0,
                      wen: Boolean = false, wdata: Long = 0L, wmask: Int = 0xF): Unit = {
    val m = if (master == "m0") c.io.m0 else c.io.m1
    m.req.valid.poke(valid.B)
    m.req.addr.poke(addr.U)
    m.req.wen.poke(wen.B)
    m.req.wdata.poke(wdata.U)   // Long literal: 0xDEAD0000L.U is a valid 32-bit UInt
    m.req.wmask.poke(wmask.U)
  }

  /** Simulate the memory slave: responds only when a request is on the bus. */
  private def pokeSlave(c: MemArbiter, respond: Boolean, rdata: Int = 0): Unit = {
    c.io.out.rvalid.poke(respond.B)
    c.io.out.rdata.poke(rdata.U)
  }

  "MemArbiter" should "forward m0 requests exclusively while m0 is busy" in {
    test(new MemArbiter).withAnnotations(Seq(VerilatorBackendAnnotation)) { c =>
      pokeSlave(c, respond = true, rdata = 0x11223344)
      pokeReq(c, "m1", valid = false)

      // m0 fires a store every cycle -> m0 must own the bus forever
      for (cycle <- 0 until 8) {
        val addr = 0x1000 + cycle * 4
        pokeReq(c, "m0", valid = true, addr = addr, wen = true, wdata = 0xDEAD0000L + cycle, wmask = 0xF)
        c.io.out.req.valid.expect(true.B)            // combinational: check before step
        c.io.out.req.addr.expect(addr.U)
        c.io.out.req.wen.expect(true.B)
        c.io.out.req.wdata.expect((0xDEAD0000L + cycle).U)
        c.io.out.req.wmask.expect(0xF.U)
        c.io.m0.rvalid.expect(true.B)                // response to m0 only
        c.io.m1.rvalid.expect(false.B)
        c.io.m0.rdata.expect(0x11223344.U)
        c.clock.step(1)
      }
    }
  }

  it should "wait one cycle to switch to m1 after m0 goes idle" in {
    test(new MemArbiter).withAnnotations(Seq(VerilatorBackendAnnotation)) { c =>
      // cycle 0: m0 has a request (served), m1 idle
      pokeReq(c, "m0", valid = true, addr = 0x2000, wen = false)
      pokeReq(c, "m1", valid = false)
      pokeSlave(c, respond = true)
      c.io.out.req.valid.expect(true.B)
      c.io.out.req.addr.expect(0x2000.U)
      c.io.m0.rvalid.expect(true.B)
      c.clock.step(1)

      // cycle 1: m0 idle, m1 requests -> arbiter switches this cycle,
      // nothing appears on the bus, memory must not respond
      pokeReq(c, "m0", valid = false)
      pokeReq(c, "m1", valid = true, addr = 0x3000, wen = false)
      pokeSlave(c, respond = false)
      c.io.out.req.valid.expect(false.B)
      c.io.m0.rvalid.expect(false.B)
      c.io.m1.rvalid.expect(false.B)
      c.clock.step(1)                                // sel flips to m1 here

      // cycle 2: m1 is now selected and served
      pokeReq(c, "m1", valid = true, addr = 0x3000, wen = false)
      pokeSlave(c, respond = true)
      c.io.out.req.valid.expect(true.B)
      c.io.out.req.addr.expect(0x3000.U)
      c.io.m1.rvalid.expect(true.B)
      c.io.m0.rvalid.expect(false.B)
      c.clock.step(1)
    }
  }

  it should "keep serving the current master when both request simultaneously" in {
    test(new MemArbiter).withAnnotations(Seq(VerilatorBackendAnnotation)) { c =>
      pokeSlave(c, respond = true)

      // both masters fire continuously; sel starts at m0 -> m0 keeps the bus
      for (cycle <- 0 until 4) {
        pokeReq(c, "m0", valid = true, addr = 0x4000, wen = true, wdata = cycle)
        pokeReq(c, "m1", valid = true, addr = 0x5000, wen = true, wdata = 100 + cycle)
        c.io.out.req.valid.expect(true.B)
        c.io.out.req.addr.expect(0x4000.U)           // m0 wins while busy
        c.io.m0.rvalid.expect(true.B)
        c.io.m1.rvalid.expect(false.B)
        c.clock.step(1)
      }

      // m0 idle -> one switching cycle, then m1 is served
      pokeReq(c, "m0", valid = false)
      pokeSlave(c, respond = false)
      c.io.out.req.valid.expect(false.B)
      c.clock.step(1)                                // sel flips to m1

      pokeReq(c, "m1", valid = true, addr = 0x5000, wen = true, wdata = 42)
      pokeSlave(c, respond = true)
      c.io.out.req.valid.expect(true.B)
      c.io.out.req.addr.expect(0x5000.U)
      c.io.m1.rvalid.expect(true.B)
      c.io.m0.rvalid.expect(false.B)
      c.clock.step(1)
    }
  }

  it should "round-robin back to m0 after m1 goes idle" in {
    test(new MemArbiter).withAnnotations(Seq(VerilatorBackendAnnotation)) { c =>
      // get m1 selected first
      pokeReq(c, "m0", valid = false)
      pokeReq(c, "m1", valid = true, addr = 0x6000, wen = false)
      pokeSlave(c, respond = false)
      c.io.out.req.valid.expect(false.B)             // switching cycle
      c.clock.step(1)                                // sel -> m1
      pokeSlave(c, respond = true)
      c.io.out.req.valid.expect(true.B)              // serve m1
      c.io.out.req.addr.expect(0x6000.U)
      c.io.m1.rvalid.expect(true.B)
      c.clock.step(1)

      // m1 idle, m0 requests -> back to m0 after one switching cycle
      pokeReq(c, "m1", valid = false)
      pokeReq(c, "m0", valid = true, addr = 0x7000, wen = false)
      pokeSlave(c, respond = false)
      c.io.out.req.valid.expect(false.B)             // switching cycle
      c.clock.step(1)                                // sel -> m0
      pokeSlave(c, respond = true)
      c.io.out.req.valid.expect(true.B)              // serve m0
      c.io.out.req.addr.expect(0x7000.U)
      c.io.m0.rvalid.expect(true.B)
      c.io.m1.rvalid.expect(false.B)
      c.clock.step(1)
    }
  }
}
