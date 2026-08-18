package accel

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

/** Top-level integration tests targeting on-board correctness:
  * reset, determinism, edge cases and randomized GEMM sweeps vs reference. */
class AICoreIntegTest extends AnyFlatSpec with ChiselScalatestTester {

  private def setBufCtrl(dut: AICore, sel: Int, idx: Int): Unit =
    AxiTester.write(dut, 0x20, (idx << 4) | sel)

  private def loadBytes(dut: AICore, sel: Int, startIdx: Int, bytes: Seq[Int]): Unit = {
    setBufCtrl(dut, sel, startIdx)
    bytes.foreach(b => AxiTester.write(dut, 0x24, b & 0xFF))
  }

  private def readAccMat(dut: AICore): Seq[Seq[Int]] = {
    setBufCtrl(dut, BufferSel.Acc, 0)
    (0 until 8).map(_ => (0 until 8).map(_ => AxiTester.read(dut, 0x24).toInt))
  }

  private def readOutMat(dut: AICore): Seq[Seq[Int]] = {
    setBufCtrl(dut, BufferSel.Out, 0)
    (0 until 8).map(_ => (0 until 8).map(_ => AxiTester.read(dut, 0x24).toInt & 0xFF))
  }

  /** Load, start, wait, and return (raw INT32 acc, quantized INT8 out). */
  private def runGemm(dut: AICore, a: Seq[Seq[Int]], b: Seq[Seq[Int]],
                      m: Int, k: Int, n: Int, shift: Int = 0, relu: Boolean = false): (Seq[Seq[Int]], Seq[Seq[Int]]) = {
    val aT = (for (kk <- 0 until k) yield (0 until m).map(i => a(i)(kk)) ++ Seq.fill(8 - m)(0)).flatten
    loadBytes(dut, BufferSel.A, 0, aT)
    val bFlat = (for (kk <- 0 until k) yield (0 until n).map(j => b(kk)(j)) ++ Seq.fill(8 - n)(0)).flatten
    loadBytes(dut, BufferSel.B, 0, bFlat)
    AxiTester.write(dut, 0x14, m)
    AxiTester.write(dut, 0x18, k)
    AxiTester.write(dut, 0x1C, n)
    AxiTester.write(dut, 0x28, (shift << 4) | (if (relu) 1 else 0))
    AxiTester.write(dut, 0x00, 1)
    val (busy, done) = AxiTester.pollStatus(dut, 0, 1)
    assert(!busy, "should not be busy after done")
    assert(done, "should report done")
    (readAccMat(dut), readOutMat(dut))
  }

  it should "come out of reset with idle status and zero state" in {
    test(new AICore()).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      val st = AxiTester.read(dut, 0x04)
      assert(((st >> 0) & 1) == 0, "busy must be 0 after reset")
      assert(((st >> 1) & 1) == 0, "done must be 0 after reset")
      assert(AxiTester.read(dut, 0x2C) == 0, "PERF must be 0 after reset")
      // buffers read as zero
      setBufCtrl(dut, BufferSel.A, 0)
      assert(AxiTester.read(dut, 0x24) == 0)
    }
  }

  it should "be deterministic: same input twice gives identical results" in {
    test(new AICore()).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      val rnd = new scala.util.Random(31)
      def r8() = rnd.nextInt(256) - 128
      val a = Seq.tabulate(8)(_ => Seq.tabulate(16)(_ => r8()))
      val b = Seq.tabulate(16)(_ => Seq.tabulate(8)(_ => r8()))
      val (acc1, out1) = runGemm(dut, a, b, 8, 16, 8, shift = 2, relu = true)
      val (acc2, out2) = runGemm(dut, a, b, 8, 16, 8, shift = 2, relu = true)
      for (i <- 0 until 8; j <- 0 until 8) {
        assert(acc1(i)(j) == acc2(i)(j), s"acc mismatch [$i][$j]")
        assert(out1(i)(j) == out2(i)(j), s"out mismatch [$i][$j]")
      }
    }
  }

  it should "handle K=0 (no accumulation, immediate done, zero output)" in {
    test(new AICore()).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      val (acc, out) = runGemm(dut, Seq.fill(8)(Seq.empty[Int]), Seq.empty[Seq[Int]], 8, 0, 8)
      for (i <- 0 until 8; j <- 0 until 8) {
        assert(acc(i)(j) == 0)
        assert(out(i)(j) == 0)
      }
    }
  }

  it should "handle K=1 and minimum dimensions M=N=1" in {
    test(new AICore()).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      // M=N=1, K=1: single product
      val (acc, out) = runGemm(dut, Seq(Seq(-7)), Seq(Seq(6)), 1, 1, 1)
      assert(acc(0)(0) == -42, s"got ${acc(0)(0)}")
      assert(out(0)(0) == (-42 & 0xFF), s"out ${out(0)(0)}")
    }
  }

  it should "match reference across a randomized sweep of GEMMs (raw and quantized)" in {
    test(new AICore()).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      val rnd = new scala.util.Random(1234)
      def r8() = rnd.nextInt(256) - 128
      var totalMismatch = 0
      var cases = 0
      for (trial <- 0 until 10) {
        val m = 1 + rnd.nextInt(8) // 1..8
        val n = 1 + rnd.nextInt(8)
        val k = 1 + rnd.nextInt(32)
        val shift = rnd.nextInt(16)
        val relu = rnd.nextBoolean()
        val a = Seq.tabulate(m)(_ => Seq.tabulate(k)(_ => r8()))
        val b = Seq.tabulate(k)(_ => Seq.tabulate(n)(_ => r8()))
        val ref = TestRefs.gemm(a, b, m, k, n)

        val (acc, out) = runGemm(dut, a, b, m, k, n, shift, relu)
        for (i <- 0 until m; j <- 0 until n) {
          cases += 1
          if (acc(i)(j) != ref(i)(j)) {
            totalMismatch += 1
            if (totalMismatch <= 5) println(s"[t$trial] acc[$i][$j] got=${acc(i)(j)} expect=${ref(i)(j)} (m=$m k=$k n=$n)")
          }
          val expectOut = TestRefs.quant(ref(i)(j), shift, relu) & 0xFF
          if ((out(i)(j) & 0xFF) != expectOut) {
            totalMismatch += 1
            if (totalMismatch <= 5) println(s"[t$trial] out[$i][$j] got=${out(i)(j)} expect=$expectOut")
          }
        }
      }
      assert(totalMismatch == 0, s"$totalMismatch mismatches over $cases checked elements")
    }
  }
}
