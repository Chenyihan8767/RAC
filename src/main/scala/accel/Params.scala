package accel

case class AIConfig(
  arraySize: Int = 8,
  dataW: Int = 8,
  accW: Int = 32,
  maxK: Int = 32
) {
  require(arraySize > 0 && (arraySize & (arraySize - 1)) == 0, "arraySize must be power of 2")
  require(maxK > 0 && (maxK & (maxK - 1)) == 0, "maxK must be power of 2")
  val aBytes = maxK * arraySize
  val accWords = arraySize * arraySize
}
