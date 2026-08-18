package accel

import chisel3._
import chiseltest._

object AxiTester {
  def write(dut: AICore, addr: Long, data: Long): Unit = {
    val a = dut.io.axi
    a.aw.valid.poke(true.B)
    a.aw.bits.addr.poke(addr.U)
    a.aw.bits.prot.poke(0.U)
    a.w.valid.poke(true.B)
    a.w.bits.data.poke(data.U)
    a.w.bits.strb.poke(15.U)
    a.b.ready.poke(true.B)
    dut.clock.step(1)
    a.aw.valid.poke(false.B)
    a.w.valid.poke(false.B)
    while (!a.b.valid.peek().litToBoolean) {
      dut.clock.step(1)
    }
    dut.clock.step(1)
  }

  def read(dut: AICore, addr: Long): BigInt = {
    val a = dut.io.axi
    a.ar.valid.poke(true.B)
    a.ar.bits.addr.poke(addr.U)
    a.ar.bits.prot.poke(0.U)
    a.r.ready.poke(true.B)
    dut.clock.step(1)
    a.ar.valid.poke(false.B)
    while (!a.r.valid.peek().litToBoolean) {
      dut.clock.step(1)
    }
    val data = a.r.bits.data.peek().litValue
    dut.clock.step(1)
    data
  }

  def pollStatus(dut: AICore, busyBit: Int, doneBit: Int, maxCycles: Int = 1000): (Boolean, Boolean) = {
    var busy = true
    var done = false
    var cycles = 0
    while ((busy || !done) && cycles < maxCycles) {
      val st = read(dut, 0x04)
      busy = ((st >> busyBit) & 1) == 1
      done = ((st >> doneBit) & 1) == 1
      cycles += 1
    }
    (busy, done)
  }
}
