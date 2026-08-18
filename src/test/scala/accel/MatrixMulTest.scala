package accel

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class MatrixMulTest extends AnyFlatSpec with ChiselScalatestTester {
  "MatrixMul" should "compute product" in {
    test(new MatrixMul) { c =>
      c.io.in_a.poke(7.U)
      c.io.in_b.poke(6.U)
      c.io.sum.expect(42.U)
    }
  }
}
