package accel

import chisel3._
import chiseltest._
import chiseltest.simulator.WriteVcdAnnotation
import org.scalatest.flatspec.AnyFlatSpec

object BufferSel {
  val A = 0
  val B = 1
  val Acc = 2
  val Out = 3
  val Bias = 4
}

class AICoreTest extends AnyFlatSpec with ChiselScalatestTester {

  private def setBufCtrl(dut: AICore, sel: Int, idx: Int): Unit = {
    AxiTester.write(dut, 0x20, (idx << 4) | sel)
  }

  private def loadBytes(dut: AICore, sel: Int, startIdx: Int, bytes: Seq[Int]): Unit = {
    setBufCtrl(dut, sel, startIdx)
    bytes.foreach(b => AxiTester.write(dut, 0x24, b & 0xFF))
  }

  private def readAcc(dut: AICore, wordIdx: Int): Int = {
    setBufCtrl(dut, BufferSel.Acc, wordIdx)
    AxiTester.read(dut, 0x24).toInt
  }

  "AICore" should "readback written registers and buffer bytes" in {
    test(new AICore()).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      AxiTester.write(dut, 0x14, 8)  // M_DIM
      AxiTester.write(dut, 0x18, 4)  // K_DIM
      AxiTester.write(dut, 0x1C, 6)  // N_DIM
      assert(AxiTester.read(dut, 0x14) == 8)
      assert(AxiTester.read(dut, 0x18) == 4)
      assert(AxiTester.read(dut, 0x1C) == 6)

      loadBytes(dut, BufferSel.A, 0, Seq(10, 20, 30, 40))
      setBufCtrl(dut, BufferSel.A, 0)
      assert(AxiTester.read(dut, 0x24) == 10)
      assert(AxiTester.read(dut, 0x24) == 20)
      assert(AxiTester.read(dut, 0x24) == 30)
      assert(AxiTester.read(dut, 0x24) == 40)

      loadBytes(dut, BufferSel.B, 0, Seq(1, 2, 3, 4))
      setBufCtrl(dut, BufferSel.B, 0)
      assert(AxiTester.read(dut, 0x24) == 1)
      assert(AxiTester.read(dut, 0x24) == 2)
      assert(AxiTester.read(dut, 0x24) == 3)
      assert(AxiTester.read(dut, 0x24) == 4)
    }
  }

  it should "compute a small GEMM against reference" in {
    test(new AICore()).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      val m = 2
      val k = 3
      val n = 3
      // A[i][k] row-major
      val a = Seq(Seq(1, 2, 3), Seq(4, 5, 6))
      val b = Seq(Seq(1, 0, 0), Seq(0, 1, 0), Seq(0, 0, 1)) // identity 3x3

      // A transposed: A_T[k][i] at index k*8+i, each k row padded to 8 bytes
      val aT = for (kk <- 0 until k) yield (0 until m).map(i => a(i)(kk)) ++ Seq.fill(8 - m)(0)
      loadBytes(dut, BufferSel.A, 0, aT.flatten)
      // B[k][j] at index k*8+j, each k row padded to 8 bytes
      val bFlat = (for (kk <- 0 until k) yield (0 until n).map(j => b(kk)(j)) ++ Seq.fill(8 - n)(0)).flatten
      loadBytes(dut, BufferSel.B, 0, bFlat)

      AxiTester.write(dut, 0x14, m)
      AxiTester.write(dut, 0x18, k)
      AxiTester.write(dut, 0x1C, n)
      AxiTester.write(dut, 0x00, 1) // start

      val (busy, done) = AxiTester.pollStatus(dut, 0, 1)
      assert(!busy, "should not be busy after done")
      assert(done, "should be done")

      val ref = for (i <- 0 until m) yield
        for (j <- 0 until n) yield (0 until k).map(kk => a(i)(kk) * b(kk)(j)).sum

      for (i <- 0 until m; j <- 0 until n) {
        val got = readAcc(dut, i * 8 + j)
        assert(got == ref(i)(j), s"C[$i][$j] = $got, expected ${ref(i)(j)}")
      }
    }
  }

  it should "compute random 8x8x8 GEMM against reference" in {
    test(new AICore()).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      val m = 8
      val k = 8
      val n = 8
      val rnd = new scala.util.Random(42)
      def r8() = rnd.nextInt(256) - 128 // signed int8

      val a = Seq.tabulate(m)(_ => Seq.tabulate(k)(_ => r8()))
      val b = Seq.tabulate(k)(_ => Seq.tabulate(n)(_ => r8()))

      val aT = for (kk <- 0 until k) yield (0 until m).map(i => a(i)(kk))
      loadBytes(dut, BufferSel.A, 0, aT.flatten)
      val bFlat = for (kk <- 0 until k; j <- 0 until n) yield b(kk)(j)
      loadBytes(dut, BufferSel.B, 0, bFlat)

      AxiTester.write(dut, 0x14, m)
      AxiTester.write(dut, 0x18, k)
      AxiTester.write(dut, 0x1C, n)
      AxiTester.write(dut, 0x00, 1)

      val (busy, done) = AxiTester.pollStatus(dut, 0, 1)
      assert(!busy && done)

      val ref = for (i <- 0 until m) yield
        for (j <- 0 until n) yield (0 until k).map(kk => a(i)(kk) * b(kk)(j)).sum

      var mismatch = 0
      for (i <- 0 until m; j <- 0 until n) {
        val got = readAcc(dut, i * 8 + j)
        if (got != ref(i)(j)) mismatch += 1
      }
      assert(mismatch == 0, s"$mismatch mismatches out of ${m * n}")
    }
  }

  it should "compute a K=32 GEMM with dimension gating (M,N < 8)" in {
    test(new AICore()).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      val m = 3
      val k = 32
      val n = 4
      val rnd = new scala.util.Random(7)
      def r8() = rnd.nextInt(256) - 128

      val a = Seq.tabulate(m)(_ => Seq.tabulate(k)(_ => r8()))
      val b = Seq.tabulate(k)(_ => Seq.tabulate(n)(_ => r8()))

      val aT = for (kk <- 0 until k) yield (0 until m).map(i => a(i)(kk)) ++ Seq.fill(8 - m)(0)
      loadBytes(dut, BufferSel.A, 0, aT.flatten)
      val bFlat = (for (kk <- 0 until k) yield (0 until n).map(j => b(kk)(j)) ++ Seq.fill(8 - n)(0)).flatten
      loadBytes(dut, BufferSel.B, 0, bFlat)

      AxiTester.write(dut, 0x14, m)
      AxiTester.write(dut, 0x18, k)
      AxiTester.write(dut, 0x1C, n)
      AxiTester.write(dut, 0x00, 1)

      val (busy, done) = AxiTester.pollStatus(dut, 0, 1)
      assert(!busy && done)

      val ref = for (i <- 0 until m) yield
        for (j <- 0 until n) yield (0 until k).map(kk => a(i)(kk) * b(kk)(j)).sum

      var mismatch = 0
      for (i <- 0 until m; j <- 0 until n) {
        val got = readAcc(dut, i * 8 + j)
        if (got != ref(i)(j)) mismatch += 1
      }
      assert(mismatch == 0, s"$mismatch mismatches out of ${m * n}")
    }
  }

  it should "report busy then done and support repeated runs" in {
    test(new AICore()).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      // two runs, verify done bit toggles and buffers reloaded
      for (run <- 0 until 2) {
        loadBytes(dut, BufferSel.A, 0, Seq(2, 3, 4))
        loadBytes(dut, BufferSel.B, 0, Seq(5, 6, 7))
        AxiTester.write(dut, 0x14, 2)
        AxiTester.write(dut, 0x18, 1)
        AxiTester.write(dut, 0x1C, 2)
        AxiTester.write(dut, 0x00, 1)
        val (busy, done) = AxiTester.pollStatus(dut, 0, 1)
        assert(!busy && done)
        // C[0][0] = 2*5 = 10
        assert(readAcc(dut, 0) == 10)
      }
    }
  }

  private def quantRef(acc: Int, shift: Int, relu: Boolean): Int = {
    var v = acc >> shift
    if (relu && v < 0) v = 0
    if (v > 127) 127 else if (v < -128) -128 else v
  }

  it should "quantize/saturate output to INT8 with RELU and shift" in {
    test(new AICore()).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      val m = 8
      val k = 8
      val n = 8
      val shift = 3
      val relu = true
      val rnd = new scala.util.Random(99)
      def r8() = rnd.nextInt(256) - 128

      // include large magnitudes to force saturation: a[i][k] = +/-100
      val a = Seq.tabulate(m)(i => Seq.tabulate(k)(kk => if (i == 0) 100 else r8()))
      val b = Seq.tabulate(k)(kk => Seq.tabulate(n)(j => if (j == 0) 2 else r8()))

      val aT = (for (kk <- 0 until k) yield (0 until m).map(i => a(i)(kk))).flatten
      loadBytes(dut, BufferSel.A, 0, aT)
      val bFlat = (for (kk <- 0 until k) yield (0 until n).map(j => b(kk)(j))).flatten
      loadBytes(dut, BufferSel.B, 0, bFlat)

      AxiTester.write(dut, 0x14, m)
      AxiTester.write(dut, 0x18, k)
      AxiTester.write(dut, 0x1C, n)
      AxiTester.write(dut, 0x28, (shift << 4) | (if (relu) 1 else 0)) // QUANT
      AxiTester.write(dut, 0x00, 1)

      val (busy, done) = AxiTester.pollStatus(dut, 0, 1)
      assert(!busy && done)

      var mismatch = 0
      for (i <- 0 until m; j <- 0 until n) {
        val accRef = (0 until k).map(kk => a(i)(kk) * b(kk)(j)).sum
        val expect = quantRef(accRef, shift, relu)
        val got = readOut(dut, i * 8 + j)
        if (got != expect) mismatch += 1
        // raw accumulator still readable
        if (i == 0 && j == 0) assert(readAcc(dut, 0) == accRef, "raw acc should be unchanged")
      }
      assert(mismatch == 0, s"$mismatch mismatches out of ${m * n}")
    }
  }

  private def readOut(dut: AICore, wordIdx: Int): Int = {
    setBufCtrl(dut, BufferSel.Out, wordIdx)
    AxiTester.read(dut, 0x24).toInt
  }

  it should "report compute latency scaling with K and high MAC utilization" in {
    test(new AICore()).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      def measure(k: Int): Int = {
        // full 8x8, all ones
        val aT = Seq.fill(k)(Seq.fill(8)(1)).flatten
        val bFlat = Seq.fill(k)(Seq.fill(8)(1)).flatten
        loadBytes(dut, BufferSel.A, 0, aT)
        loadBytes(dut, BufferSel.B, 0, bFlat)
        AxiTester.write(dut, 0x14, 8)
        AxiTester.write(dut, 0x18, k)
        AxiTester.write(dut, 0x1C, 8)
        AxiTester.write(dut, 0x00, 1)
        val (busy, done) = AxiTester.pollStatus(dut, 0, 1)
        assert(!busy && done)
        AxiTester.read(dut, 0x2C).toInt // PERF: compute+quant cycles
      }

      val c8 = measure(8)
      val c32 = measure(32)
      // compute+quant cycles should be exactly K+1
      assert(c8 == 9, s"c8=$c8, expected 9")
      assert(c32 == 33, s"c32=$c32, expected 33")

      // MAC utilization over the compute+quant phase: useful = 64*K, capacity = cycles*64
      val useful = 64 * 32
      val util = useful.toDouble / (c32 * 64)
      println(f"[perf] K=8 cycles=$c8  K=32 cycles=$c32  MAC util(K=32)=${util * 100}%.1f%%")
    }
  }

  it should "add a per-column bias before quantization" in {
    test(new AICore()).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      val m = 2
      val k = 3
      val n = 3
      val shift = 1
      val bias = Seq(10, -5, 100) // per output column
      // A = [[1,2,3],[4,5,6]], B = identity
      val aT = Seq(Seq(1, 4, 0, 0, 0, 0, 0, 0),
                   Seq(2, 5, 0, 0, 0, 0, 0, 0),
                   Seq(3, 6, 0, 0, 0, 0, 0, 0)).flatten
      loadBytes(dut, BufferSel.A, 0, aT)
      val bFlat = Seq(1, 0, 0, 0, 0, 0, 0, 0,
                      0, 1, 0, 0, 0, 0, 0, 0,
                      0, 0, 1, 0, 0, 0, 0, 0)
      loadBytes(dut, BufferSel.B, 0, bFlat)
      // load bias words
      setBufCtrl(dut, BufferSel.Bias, 0)
      bias.foreach(b => AxiTester.write(dut, 0x24, b & 0xFFFFFFFFL))

      AxiTester.write(dut, 0x14, m)
      AxiTester.write(dut, 0x18, k)
      AxiTester.write(dut, 0x1C, n)
      // QUANT: shift=1, relu off, bias_en on
      AxiTester.write(dut, 0x28, (shift << 4) | (1 << 1))
      AxiTester.write(dut, 0x00, 1)
      val (busy, done) = AxiTester.pollStatus(dut, 0, 1)
      assert(!busy && done)

      // expected: C = A*I = [[1,2,3],[4,5,6]], then (C + bias[j]) >> 1
      val a = Seq(Seq(1, 2, 3), Seq(4, 5, 6))
      var mismatch = 0
      for (i <- 0 until m; j <- 0 until n) {
        val expect = quantRef(a(i)(j) + bias(j), shift, relu = false)
        val got = readOut(dut, i * 8 + j)
        if (got != expect) {
          mismatch += 1
          if (mismatch <= 5) println(s"bias out[$i][$j] got=$got expect=$expect")
        }
      }
      assert(mismatch == 0, s"$mismatch mismatches")
    }
  }

  it should "dump a VCD waveform" in {
    test(new AICore()).withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut =>
      loadBytes(dut, BufferSel.A, 0, Seq(7, 6))
      loadBytes(dut, BufferSel.B, 0, Seq(5, 4))
      AxiTester.write(dut, 0x14, 2)
      AxiTester.write(dut, 0x18, 1)
      AxiTester.write(dut, 0x1C, 2)
      AxiTester.write(dut, 0x00, 1)
      val (busy, done) = AxiTester.pollStatus(dut, 0, 1)
      assert(!busy && done)
      assert(readAcc(dut, 0) == 35)
    }
  }
}
