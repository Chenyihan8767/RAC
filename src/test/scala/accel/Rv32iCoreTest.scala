package accel

import java.io.File
import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import accel.soc.SoC

/** Rv32iCore + SoC end-to-end tests running small assembly programs. */
class Rv32iCoreTest extends AnyFlatSpec with ChiselScalatestTester {

  private def parseHex(rel: String): Seq[Long] = {
    val f = new File(rel)
    val src = scala.io.Source.fromFile(f)
    try {
      src.getLines().flatMap(_.trim.split("\\s+")).filter(_.nonEmpty)
        .map(w => java.lang.Long.parseLong(w, 16)).toSeq
    } finally src.close()
  }

  /** Step the SoC until the SRAM word at resultAddr becomes nonzero, then check it. */
  private def runUntilResult(dut: SoC, resultAddr: Long, expect: BigInt, maxCycles: Int = 200000): Int = {
    dut.clock.setTimeout(0)
    dut.io.dbgAddr.poke(resultAddr.U)
    var cycles = 0
    while (dut.io.dbgData.peek().litValue == BigInt(0) && cycles < maxCycles) {
      dut.clock.step(1)
      cycles += 1
    }
    val got = dut.io.dbgData.peek().litValue
    assert(got != BigInt(0), s"timed out after $cycles cycles (result never written)")
    assert(got == expect, s"result=0x$got%x expected=0x$expect%x")
    cycles
  }

  it should "execute arithmetic + memory + branch (li/add/sw/lw/bne)" in {
    val prog = parseHex("sw/asm/t_arith_mem.hex")
    test(new SoC(program = prog)).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      val c = runUntilResult(dut, 0x1000, BigInt(107))
      println(f"[core] t_arith_mem done in $c cycles")
    }
  }

  it should "execute a branch loop computing sum(1..10)=55" in {
    val prog = parseHex("sw/asm/t_loop.hex")
    test(new SoC(program = prog)).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      val c = runUntilResult(dut, 0x1000, BigInt(55))
      println(f"[core] t_loop done in $c cycles")
    }
  }

  it should "execute signed byte load and byte store (lb/sb/lw)" in {
    val prog = parseHex("sw/asm/t_bytes.hex")
    test(new SoC(program = prog)).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      // lb(0x1103)=0xFF sign-extends; sb writes only byte0 of 0x1200;
      // result word = 0x000000FF = 255
      val c = runUntilResult(dut, 0x1000, BigInt(0xFF))
      println(f"[core] t_bytes done in $c cycles")
    }
  }

  it should "execute the M-extension multiplies (mul/mulh/mulhu/mulhsu)" in {
    val prog = parseHex("sw/asm/t_mul.hex")
    test(new SoC(program = prog)).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      dut.clock.setTimeout(0)
      dut.io.dbgAddr.poke(0x1010.U) // last store
      var cycles = 0
      var r0 = BigInt(0)
      while (r0 == BigInt(0) && cycles < 10000) {
        dut.clock.step(1)
        cycles += 1
        r0 = dut.io.dbgData.peek().litValue
      }
      assert(r0 != BigInt(0), "t_mul never finished")
      def rd(a: Long): BigInt = { dut.io.dbgAddr.poke(a.U); dut.io.dbgData.peek().litValue }
      for (a <- Seq(0x1000L, 0x1004, 0x1008, 0x100C, 0x1010)) {
        println(f"[core] mem[0x$a%x]=0x${rd(a)}%08x")
      }
      assert(rd(0x1004) == BigInt(0xFFFFFFFED4L & 0xFFFFFFFFL), "mul negative")
      assert(rd(0x1008) == BigInt(0xFFFFFFFFL), "mulh")
      assert(rd(0x100C) == BigInt(0xFFFFFFFEL), "mulhu")
      assert(rd(0x1010) == BigInt(0xFFFFFFFFL), "mulhsu")
      println(f"[core] t_mul done in $cycles cycles")
    }
  }
}
