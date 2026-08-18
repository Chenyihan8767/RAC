package accel

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

/** Standalone test for the combinational INT8 quantizer. */
class QuantizerTest extends AnyFlatSpec with ChiselScalatestTester {

  private def runAll(c: Quantizer, acc: Seq[Seq[Int]], shift: Int, relu: Boolean): Unit = {
    for (i <- 0 until 8; j <- 0 until 8) c.io.acc(i)(j).poke(acc(i)(j).S)
    c.io.shift.poke(shift.U)
    c.io.reluEn.poke(relu.B)
    for (i <- 0 until 8; j <- 0 until 8) {
      val expect = TestRefs.quant(acc(i)(j), shift, relu)
      c.io.out(i)(j).expect(expect.S)
    }
  }

  "Quantizer" should "pass through small values unchanged (shift=0, relu off)" in {
    test(new Quantizer(AIConfig())).withAnnotations(Seq(VerilatorBackendAnnotation)) { c =>
      val acc = Seq.tabulate(8)(i => Seq.tabulate(8)(j => (i * 8 + j) % 50 - 25))
      runAll(c, acc, 0, relu = false)
    }
  }

  it should "saturate to +127 / -128" in {
    test(new Quantizer(AIConfig())).withAnnotations(Seq(VerilatorBackendAnnotation)) { c =>
      val acc = Seq.tabulate(8)(i => Seq.tabulate(8)(j =>
        if ((i * 8 + j) % 3 == 0) 3000 else if ((i * 8 + j) % 3 == 1) -3000 else 42))
      runAll(c, acc, 0, relu = false)
    }
  }

  it should "apply ReLU (negatives -> 0)" in {
    test(new Quantizer(AIConfig())).withAnnotations(Seq(VerilatorBackendAnnotation)) { c =>
      val acc = Seq.tabulate(8)(i => Seq.tabulate(8)(j => (i * 8 + j) % 50 - 25))
      runAll(c, acc, 0, relu = true)
    }
  }

  it should "apply arithmetic right shift before saturation" in {
    test(new Quantizer(AIConfig())).withAnnotations(Seq(VerilatorBackendAnnotation)) { c =>
      val acc = Seq.tabulate(8)(i => Seq.tabulate(8)(j => (i * 8 + j) % 20 - 10))
      for (shift <- Seq(1, 4, 8, 15)) {
        runAll(c, acc, shift, relu = false)
        runAll(c, acc, shift, relu = true)
      }
    }
  }

  it should "match the reference across random vectors" in {
    test(new Quantizer(AIConfig())).withAnnotations(Seq(VerilatorBackendAnnotation)) { c =>
      val rnd = new scala.util.Random(11)
      for (trial <- 0 until 20) {
        val acc = Seq.tabulate(8)(_ => Seq.tabulate(8)(_ => rnd.nextInt(65536) - 32768))
        val shift = rnd.nextInt(16)
        val relu = rnd.nextBoolean()
        runAll(c, acc, shift, relu)
      }
    }
  }
}
