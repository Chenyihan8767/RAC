package accel

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

/** Test-only stub: AXI4-Lite slave with a trivial 2-register file. */
class AxiSlaveStub extends Module {
  val io = IO(new AxiLite4(32, 32))

  val slave = Module(new AxiLiteSlave(32, 32))
  slave.io.axi <> io
  slave.io.bus.write.ready := true.B
  slave.io.bus.read.ready := true.B

  val reg0 = RegInit(0.U(32.W))
  val reg1 = RegInit(0.U(32.W))
  when(slave.io.bus.write.fire) {
    when(slave.io.bus.write.bits.addr === 0x00.U) { reg0 := slave.io.bus.write.bits.data }
    when(slave.io.bus.write.bits.addr === 0x04.U) { reg1 := slave.io.bus.write.bits.data }
  }
  slave.io.bus.readData.valid := slave.io.bus.read.valid
  slave.io.bus.readData.bits := Mux(slave.io.bus.read.bits.addr === 0x00.U, reg0, reg1)
}

/** AXI4-Lite master driver that can drive AW and W in any order / with delays. */
class AxiMaster(c: AxiSlaveStub) {
  def resetSignals(): Unit = {
    c.io.aw.valid.poke(false.B)
    c.io.w.valid.poke(false.B)
    c.io.b.ready.poke(true.B)
    c.io.ar.valid.poke(false.B)
    c.io.r.ready.poke(true.B)
  }

  /** Drive an AW handshake to completion (valid stays high until ready). */
  def sendAw(addr: Long, hold: Int = 0): Unit = {
    c.io.aw.valid.poke(true.B)
    c.io.aw.bits.addr.poke(addr.U)
    c.io.aw.bits.prot.poke(0.U)
    while (!c.io.aw.ready.peek().litToBoolean) { c.clock.step(1) }
    c.clock.step(1) // commit the handshake on this edge
    c.io.aw.valid.poke(false.B)
    (0 until hold).foreach(_ => c.clock.step(1))
  }

  /** Drive a W handshake to completion. */
  def sendW(data: Long, hold: Int = 0): Unit = {
    c.io.w.valid.poke(true.B)
    c.io.w.bits.data.poke(data.U)
    c.io.w.bits.strb.poke(15.U)
    while (!c.io.w.ready.peek().litToBoolean) { c.clock.step(1) }
    c.clock.step(1) // commit the handshake on this edge
    c.io.w.valid.poke(false.B)
    (0 until hold).foreach(_ => c.clock.step(1))
  }

  /** Wait for and consume the B response (must be OKAY). */
  def expectB(): Unit = {
    while (!c.io.b.valid.peek().litToBoolean) { c.clock.step(1) }
    assert(c.io.b.bits.resp.peek().litValue == 0, "write resp != OKAY")
    c.clock.step(1) // b handshake completes
  }

  /** Full write with AW first, then W (the typical master order). */
  def writeAwThenW(addr: Long, data: Long): Unit = {
    sendAw(addr); sendW(data); expectB()
  }

  /** Full write with W first, then AW (out-of-order channels). */
  def writeWThenAw(addr: Long, data: Long): Unit = {
    sendW(data); sendAw(addr); expectB()
  }

  /** Full write with AW and W asserted in the same cycle. */
  def writeSimultaneous(addr: Long, data: Long): Unit = {
    c.io.aw.valid.poke(true.B)
    c.io.aw.bits.addr.poke(addr.U)
    c.io.aw.bits.prot.poke(0.U)
    c.io.w.valid.poke(true.B)
    c.io.w.bits.data.poke(data.U)
    c.io.w.bits.strb.poke(15.U)
    c.clock.step(1) // both fire on this edge
    c.io.aw.valid.poke(false.B)
    c.io.w.valid.poke(false.B)
    expectB()
  }

  /** Full read (AR -> R). */
  def read(addr: Long): BigInt = {
    c.io.ar.valid.poke(true.B)
    c.io.ar.bits.addr.poke(addr.U)
    c.io.ar.bits.prot.poke(0.U)
    while (!c.io.ar.ready.peek().litToBoolean) { c.clock.step(1) }
    c.clock.step(1) // commit AR on this edge
    c.io.ar.valid.poke(false.B)
    while (!c.io.r.valid.peek().litToBoolean) { c.clock.step(1) }
    val data = c.io.r.bits.data.peek().litValue
    assert(c.io.r.bits.resp.peek().litValue == 0, "read resp != OKAY")
    c.clock.step(1)
    data
  }
}

class AxiLiteSlaveTest extends AnyFlatSpec with ChiselScalatestTester {

  "AxiLiteSlave" should "write then readback (AW before W)" in {
    test(new AxiSlaveStub).withAnnotations(Seq(VerilatorBackendAnnotation)) { c =>
      val m = new AxiMaster(c)
      m.resetSignals()
      m.writeAwThenW(0x00, 0xDEADBEEFL)
      assert(m.read(0x00) == 0xDEADBEEFL)
    }
  }

  it should "write with W before AW (out-of-order)" in {
    test(new AxiSlaveStub).withAnnotations(Seq(VerilatorBackendAnnotation)) { c =>
      val m = new AxiMaster(c)
      m.resetSignals()
      m.writeWThenAw(0x04, 0x12345678)
      assert(m.read(0x04) == 0x12345678)
    }
  }

  it should "write with AW and W in the same cycle" in {
    test(new AxiSlaveStub).withAnnotations(Seq(VerilatorBackendAnnotation)) { c =>
      val m = new AxiMaster(c)
      m.resetSignals()
      m.writeSimultaneous(0x00, 0x1111FFFF)
      assert(m.read(0x00) == 0x1111FFFF)
    }
  }

  it should "hold B until b.ready (backpressure on response)" in {
    test(new AxiSlaveStub).withAnnotations(Seq(VerilatorBackendAnnotation)) { c =>
      val m = new AxiMaster(c)
      m.resetSignals()
      c.io.b.ready.poke(false.B) // master not ready to accept response yet
      m.sendAw(0x00)
      m.sendW(0xCAFEBABEL)
      // b.valid should be asserted while b.ready is low; hold it
      var n = 0
      while (!c.io.b.valid.peek().litToBoolean) { c.clock.step(1); n += 1 }
      assert(c.io.b.valid.peek().litToBoolean)
      // keep holding
      c.clock.step(2)
      assert(c.io.b.valid.peek().litToBoolean, "b.valid must stay asserted until b.ready")
      c.io.b.ready.poke(true.B)
      c.clock.step(1)
      assert(!c.io.b.valid.peek().litToBoolean, "b.valid must deassert after b.ready")
      assert(m.read(0x00) == 0xCAFEBABEL)
    }
  }

  it should "support back-to-back writes without gaps" in {
    test(new AxiSlaveStub).withAnnotations(Seq(VerilatorBackendAnnotation)) { c =>
      val m = new AxiMaster(c)
      m.resetSignals()
      m.writeAwThenW(0x00, 0x00000001)
      m.writeAwThenW(0x04, 0x00000002)
      assert(m.read(0x00) == 0x00000001)
      assert(m.read(0x04) == 0x00000002)
    }
  }

  it should "support consecutive reads" in {
    test(new AxiSlaveStub).withAnnotations(Seq(VerilatorBackendAnnotation)) { c =>
      val m = new AxiMaster(c)
      m.resetSignals()
      m.writeAwThenW(0x00, 0xAAAA5555L)
      m.writeAwThenW(0x04, 0x5555AAAAL)
      assert(m.read(0x00) == 0xAAAA5555L)
      assert(m.read(0x04) == 0x5555AAAAL)
      assert(m.read(0x00) == 0xAAAA5555L)
    }
  }

  it should "hold R until r.ready (backpressure on read data)" in {
    test(new AxiSlaveStub).withAnnotations(Seq(VerilatorBackendAnnotation)) { c =>
      val m = new AxiMaster(c)
      m.resetSignals()
      m.writeAwThenW(0x00, 0x77889900L)
      c.io.r.ready.poke(false.B)
      // issue a read
      c.io.ar.valid.poke(true.B)
      c.io.ar.bits.addr.poke(0x00.U)
      c.clock.step(1)
      c.io.ar.valid.poke(false.B)
      var n = 0
      while (!c.io.r.valid.peek().litToBoolean) { c.clock.step(1); n += 1 }
      assert(c.io.r.valid.peek().litToBoolean)
      assert(c.io.r.bits.data.peek().litValue == 0x77889900L)
      c.clock.step(2)
      assert(c.io.r.valid.peek().litToBoolean, "r.valid must stay until r.ready")
      c.io.r.ready.poke(true.B)
      c.clock.step(1)
      assert(!c.io.r.valid.peek().litToBoolean)
    }
  }

  it should "report OKAY on write and read responses" in {
    test(new AxiSlaveStub).withAnnotations(Seq(VerilatorBackendAnnotation)) { c =>
      val m = new AxiMaster(c)
      m.resetSignals()
      m.writeAwThenW(0x04, 0x0F0F0F0F)
      val d = m.read(0x04)
      assert(d == 0x0F0F0F0F)
    }
  }
}
