package accel

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

/** Standalone test for the byte DataBuffer (PIO write/read + row read). */
class DataBufferTest extends AnyFlatSpec with ChiselScalatestTester {

  "DataBuffer" should "write and read back bytes via the PIO port" in {
    test(new DataBuffer(AIConfig())).withAnnotations(Seq(VerilatorBackendAnnotation)) { c =>
      c.io.wordWr.valid.poke(false.B)
      c.io.wordWr.k.poke(0.U)
      c.io.wordWr.eOff.poke(0.U)
      c.io.wordWr.data.poke(0.U)
      // write (k=0,e=2) = 0xAB ; (k=3,e=7) = 0x11
      c.io.pioWr.valid.poke(true.B)
      c.io.pioWr.k.poke(0.U)
      c.io.pioWr.e.poke(2.U)
      c.io.pioWr.data.poke(0xAB.U)
      c.clock.step(1)
      c.io.pioWr.k.poke(3.U)
      c.io.pioWr.e.poke(7.U)
      c.io.pioWr.data.poke(0x11.U)
      c.clock.step(1)
      c.io.pioWr.valid.poke(false.B)

      c.io.pioRd.k.poke(0.U)
      c.io.pioRd.e.poke(2.U)
      c.io.pioRdData.expect(0xAB.U)
      c.io.pioRd.k.poke(3.U)
      c.io.pioRd.e.poke(7.U)
      c.io.pioRdData.expect(0x11.U)
      // untouched location is 0
      c.io.pioRd.k.poke(5.U)
      c.io.pioRd.e.poke(0.U)
      c.io.pioRdData.expect(0.U)
    }
  }

  it should "reflect a write only on the next cycle (synchronous write)" in {
    test(new DataBuffer(AIConfig())).withAnnotations(Seq(VerilatorBackendAnnotation)) { c =>
      c.io.wordWr.valid.poke(false.B)
      c.io.pioRd.k.poke(1.U)
      c.io.pioRd.e.poke(1.U)
      c.io.pioRdData.expect(0.U)
      // same cycle as the write, read must still see old value
      c.io.pioWr.valid.poke(true.B)
      c.io.pioWr.k.poke(1.U)
      c.io.pioWr.e.poke(1.U)
      c.io.pioWr.data.poke(0x77.U)
      c.io.pioRdData.expect(0.U) // not yet visible
      c.clock.step(1)
      c.io.pioWr.valid.poke(false.B)
      c.io.pioRdData.expect(0x77.U) // visible after the write clock edge
    }
  }

  it should "return the full k-row on the row port" in {
    test(new DataBuffer(AIConfig())).withAnnotations(Seq(VerilatorBackendAnnotation)) { c =>
      c.io.wordWr.valid.poke(false.B)
      // fill row k=2 with bytes 0..7
      c.io.pioWr.valid.poke(true.B)
      c.io.pioWr.k.poke(2.U)
      for (e <- 0 until 8) {
        c.io.pioWr.e.poke(e.U)
        c.io.pioWr.data.poke(e.U)
        c.clock.step(1)
      }
      c.io.pioWr.valid.poke(false.B)

      c.io.rowAddr.poke(2.U)
      for (e <- 0 until 8) c.io.rowData(e).expect(e.U)
      // other rows read as 0
      c.io.rowAddr.poke(7.U)
      for (e <- 0 until 8) c.io.rowData(e).expect(0.U)
    }
  }

  it should "write 4 bytes at once via the word port" in {
    test(new DataBuffer(AIConfig())).withAnnotations(Seq(VerilatorBackendAnnotation)) { c =>
      c.io.pioWr.valid.poke(false.B)
      c.io.wordWr.valid.poke(true.B)
      c.io.wordWr.k.poke(1.U)
      c.io.wordWr.eOff.poke(4.U)
      c.io.wordWr.data.poke(0x44332211L.U) // bytes 11,22,33,44
      c.clock.step(1)
      c.io.wordWr.valid.poke(false.B)
      c.io.rowAddr.poke(1.U)
      for (e <- 0 until 8) {
        val expect = if (e < 4) 0 else Seq(0x11, 0x22, 0x33, 0x44)(e - 4)
        c.io.rowData(e).expect(expect.U)
      }
    }
  }
}
