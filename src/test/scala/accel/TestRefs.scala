package accel

/** Shared golden-reference models used by the module tests. */

object TestRefs {
  /** INT8 GEMM reference: C[i][j] = sum_k a[i][k]*b[k][j]. */
  def gemm(a: Seq[Seq[Int]], b: Seq[Seq[Int]], m: Int, k: Int, n: Int): Seq[Seq[Int]] =
    (0 until m).map { i =>
      (0 until n).map { j =>
        (0 until k).map(kk => a(i)(kk) * b(kk)(j)).sum
      }
    }

  def transpose(a: Seq[Seq[Int]]): Seq[Seq[Int]] =
    (0 until a(0).length).map { i =>
      (0 until a.length).map { j => a(j)(i)}
    }
  /** INT8 quantization reference: sat8(relu(acc >> shift)). */
  def quant(acc: Int, shift: Int, relu: Boolean): Int = {
    //var v = acc >> shift
    //if (relu && v < 0) v = 0
    //if (v > 127) 127 else if (v < -128) -128 else v
    val v = acc >> shift
    if (relu && v < 0) 0
    else if (v > 127) 127
    else if (v < -128) -128
    else v
  }
}
