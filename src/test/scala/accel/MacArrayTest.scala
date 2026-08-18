package accel

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

/** Standalone test for the 8x8 INT8 MAC array with INT32 accumulator. */
class MacArrayTest extends AnyFlatSpec with ChiselScalatestTester {

  "MacArray" should "start from zero after clear" in {
    test(new MacArray(AIConfig())).withAnnotations(Seq(VerilatorBackendAnnotation)) { c =>
      c.io.clear.poke(true.B)
      c.io.valid.poke(false.B)
      c.clock.step(1)
      for (i <- 0 until 8; j <- 0 until 8) c.io.out(i)(j).expect(0.S)
    }
  }

  it should "accumulate a single product then hold" in {
    test(new MacArray(AIConfig())).withAnnotations(Seq(VerilatorBackendAnnotation)) { c =>
      for (i <- 0 until 8) c.io.rowMask(i).poke(true.B)
      for (j <- 0 until 8) c.io.colMask(j).poke(true.B)
      c.io.clear.poke(true.B)
      c.clock.step(1)
      c.io.clear.poke(false.B)

      // one k-step: a = all 7, b = all 6  -> every acc = 42
      for (i <- 0 until 8) c.io.a(i).poke(7.S)
      for (j <- 0 until 8) c.io.b(j).poke(6.S)
      c.io.valid.poke(true.B)
      c.clock.step(1)
      c.io.valid.poke(false.B)
      for (i <- 0 until 8; j <- 0 until 8) c.io.out(i)(j).expect(42.S)
      // hold
      c.clock.step(1)
      for (i <- 0 until 8; j <- 0 until 8) c.io.out(i)(j).expect(42.S)
    }
  }

  it should "respect row/col masks (dimension gating)" in {
    test(new MacArray(AIConfig())).withAnnotations(Seq(VerilatorBackendAnnotation)) { c =>
      for (i <- 0 until 8) c.io.rowMask(i).poke((i < 2).B)   // M = 2
      for (j <- 0 until 8) c.io.colMask(j).poke((j < 3).B)   // N = 3
      c.io.clear.poke(true.B)
      c.clock.step(1)
      c.io.clear.poke(false.B)
      for (i <- 0 until 8) c.io.a(i).poke(5.S)
      for (j <- 0 until 8) c.io.b(j).poke(4.S)
      c.io.valid.poke(true.B)
      c.clock.step(1)
      c.io.valid.poke(false.B)
      // gated PEs must stay 0, active PEs = 20
      for (i <- 0 until 8; j <- 0 until 8) {
        val exp = if (i < 2 && j < 3) 20 else 0
        c.io.out(i)(j).expect(exp.S)
      }
    }
  }

  it should "match the reference over multiple random k steps" in {
    test(new MacArray(AIConfig())).withAnnotations(Seq(VerilatorBackendAnnotation)) { c =>
      val m = 8; val n = 8; val k = 32
      val rnd = new scala.util.Random(5)
      def r8() = rnd.nextInt(256) - 128
      val a = Seq.tabulate(m)(_ => Seq.tabulate(k)(_ => r8()))
      val b = Seq.tabulate(k)(_ => Seq.tabulate(n)(_ => r8()))
      val ref = TestRefs.gemm(a, b, m, k, n)

      for (i <- 0 until 8) c.io.rowMask(i).poke(true.B)
      for (j <- 0 until 8) c.io.colMask(j).poke(true.B)
      c.io.clear.poke(true.B)
      c.clock.step(1)
      c.io.clear.poke(false.B)
      for (kk <- 0 until k) {
        for (i <- 0 until 8) c.io.a(i).poke(a(i)(kk).S)
        for (j <- 0 until 8) c.io.b(j).poke(b(kk)(j).S)
        c.io.valid.poke(true.B)
        c.clock.step(1)
      }
      c.io.valid.poke(false.B)
      var mismatch = 0
      for (i <- 0 until m; j <- 0 until n) {
        val got = c.io.out(i)(j).peek().litValue.toInt
        val expect = ref(i)(j)
        if (got != expect) {
          mismatch += 1
          if (mismatch <= 3) println(s"mismatch [$i][$j] got=$got expect=$expect")
        }
      }
      assert(mismatch == 0, s"$mismatch mismatches out of ${m * n}")
    }
  }
}
