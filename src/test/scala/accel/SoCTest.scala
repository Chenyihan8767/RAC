package accel

import java.io.File
import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import accel.soc.SoC

/** SoC integration tests: real bare-metal C programs drive AICore. */
class SoCTest extends AnyFlatSpec with ChiselScalatestTester {

  private def loadHex(rel: String): Seq[Long] = {
    val src = scala.io.Source.fromFile(new File(rel))
    try {
      src.getLines().flatMap(_.trim.split("\\s+")).filter(_.nonEmpty)
        .map(w => java.lang.Long.parseLong(w, 16)).toSeq
    } finally src.close()
  }

  /** Run a program and wait until the SRAM word at resultAddr is nonzero. */
  private def runProgram(dut: SoC, resultAddr: Long, maxCycles: Int = 1000000): (BigInt, Int) = {
    dut.clock.setTimeout(0)
    dut.io.dbgAddr.poke(resultAddr.U)
    var cycles = 0
    var result = BigInt(0)
    while (result == BigInt(0) && cycles < maxCycles) {
      dut.clock.step(1)
      cycles += 1
      result = dut.io.dbgData.peek().litValue
    }
    (result, cycles)
  }

  it should "run the PIO bare-metal GEMM program and report OK at 0x2000" in {
    test(new SoC(program = loadHex("sw/gemm_test.hex"))).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      val (result, cycles) = runProgram(dut, 0x2000)
      assert(result == BigInt(0x600d), s"result=0x$result%x cycles=$cycles (expected OK=0x600d)")
      println(f"[SoC] PIO bare-metal GEMM passed in $cycles cycles (OK 0x600d)")
    }
  }

  it should "run the DMA-mode GEMM: core stages data, AICore DMA moves/computes/stores" in {
    test(new SoC(program = loadHex("sw/dma_test.hex"))).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      val (result, cycles) = runProgram(dut, 0x1000)
      assert(result == BigInt(0x600d), s"DMA result=0x$result%x cycles=$cycles (expected OK=0x600d)")
      println(f"[SoC] DMA-mode GEMM passed in $cycles cycles (OK 0x600d)")
      // verify C matrix written by DMA to SRAM at 0x4000 (i*8+j layout)
      for (i <- 0 until 2; j <- 0 until 3) {
        dut.io.dbgAddr.poke((0x4000L + 4 * (i * 8 + j)).U)
        val c = dut.io.dbgData.peek().litValue.toInt
        val expect = Seq(1, 2, 3, 4, 5, 6)(i * 3 + j)
        assert(c == expect, s"C[$i][$j]=$c expected $expect")
      }
    }
  }

  /** Generic AI benchmark: run program, expect OK at 0x1000. */
  private def benchTest(hex: String, name: String): Unit = {
    test(new SoC(program = loadHex(hex))).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      val (result, cycles) = runProgram(dut, 0x1000)
      assert(result == BigInt(0x600d), s"$name result=0x$result%x cycles=$cycles (expected OK)")
      println(f"[SoC] $name passed in $cycles cycles (OK 0x600d)")
    }
  }

  it should "run 1D convolution (im2col->GEMM)" in {
    benchTest("sw/bench_conv1d.hex", "conv1d")
  }

  it should "run 2D convolution (im2col->GEMM)" in {
    benchTest("sw/bench_conv2d.hex", "conv2d")
  }

  it should "run 2-layer MLP inference (GEMM + bias + ReLU)" in {
    benchTest("sw/bench_mlp.hex", "mlp")
  }

  it should "run an FP16-quantized GEMM (FP16 -> INT8 quantization)" in {
    benchTest("sw/bench_fp16.hex", "fp16")
  }
}
