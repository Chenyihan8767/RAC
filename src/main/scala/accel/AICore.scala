package accel

import chisel3._
import chisel3.util._
import accel.core.CoreBus

object RegMap {
  val CTRL     = 0x00.U
  val STATUS   = 0x04.U
  val A_BASE   = 0x08.U
  val B_BASE   = 0x0C.U
  val C_BASE   = 0x10.U
  val M_DIM    = 0x14.U
  val K_DIM    = 0x18.U
  val N_DIM    = 0x1C.U
  val BUF_CTRL = 0x20.U
  val DATA     = 0x24.U
  val QUANT    = 0x28.U
  val PERF     = 0x2C.U

  val SelA   = 0.U(3.W)
  val SelB   = 1.U(3.W)
  val SelAcc = 2.U(3.W)
  val SelOut = 3.U(3.W)
  val SelBias = 4.U(3.W)
}

class AICore(cfg: AIConfig = AIConfig()) extends Module {
  val io = IO(new Bundle {
    val axi = new AxiLite4(32, 32)
    val master = new CoreBus
    val dbgOutAddr = Input(UInt(32.W))
    val dbgOutData = Output(UInt(32.W))
    val dbgBufAddr = Input(UInt(32.W))
    val dbgBufEn   = Input(Bool())
    val dbgBufSel  = Input(Bool()) // 0 = A, 1 = B
    val dbgBufData = Output(UInt(32.W))
    val dbgAccAddr = Input(UInt(32.W)) // i = addr[3:0], j = addr[7:4]
    val dbgAccData = Output(UInt(32.W))
    val dbg = Output(new Bundle {
      val mDim = UInt(32.W)
      val kDim = UInt(32.W)
      val nDim = UInt(32.W)
      val busy = Bool()
      val done = Bool()
      val perf = UInt(32.W)
      val ha = Bool()
      val hd = Bool()
      val wv = Bool()
      val bufIdx = UInt(16.W)
      val bufSel = UInt(2.W)
      val wdata = UInt(32.W)
      val wrAddr = UInt(8.W)
      val wrFire = Bool()
    })
  })

  private val as = cfg.arraySize
  private val kIdxW = log2Ceil(cfg.maxK)
  private val byteMask = cfg.maxK * cfg.arraySize - 1
  private val wordMask = cfg.arraySize * cfg.arraySize - 1
  private val byteMaskW = log2Ceil(cfg.maxK * cfg.arraySize)
  private val wordMaskW = log2Ceil(cfg.arraySize * cfg.arraySize)

  val slave = Module(new AxiLiteSlave(32, 32))
  slave.io.axi <> io.axi
  slave.io.bus.write.ready := true.B
  slave.io.bus.read.ready := true.B

  val aBuf = Module(new DataBuffer(cfg))
  val bBuf = Module(new DataBuffer(cfg))
  val quant = Module(new Quantizer(cfg))

  val ctrlReg = RegInit(0.U(32.W))
  val aBase = RegInit(0.U(32.W))
  val bBase = RegInit(0.U(32.W))
  val cBase = RegInit(0.U(32.W))
  val mDimReg = RegInit(0.U(32.W))
  val kDimReg = RegInit(0.U(32.W))
  val nDimReg = RegInit(0.U(32.W))
  val quantReg = RegInit(0.U(32.W))
  val bufSel = RegInit(0.U(3.W))
  val bufIdx = RegInit(0.U(16.W))
  val biasBuf = RegInit(VecInit(Seq.fill(cfg.arraySize)(0.S(cfg.accW.W))))

  val mDim = mDimReg(3, 0)
  val nDim = nDimReg(3, 0)

  private val byteAddr = (bufIdx & byteMask.U(16.W))(byteMaskW - 1, 0)
  private val kIdx = byteAddr(log2Ceil(as) + kIdxW - 1, log2Ceil(as))
  private val eIdx = byteAddr(log2Ceil(as) - 1, 0)
  private val accAddr = (bufIdx & wordMask.U(16.W))(wordMaskW - 1, 0)
  private val accI = accAddr(log2Ceil(as) + log2Ceil(as) - 1, log2Ceil(as))
  private val accJ = accAddr(log2Ceil(as) - 1, 0)

  val mac = Module(new MacArray(cfg))
  val doneReg = RegInit(false.B)
  val outBuf = RegInit(VecInit(Seq.fill(as)(VecInit(Seq.fill(as)(0.S(8.W))))))

  private val Idle    = 0.U(3.W)
  private val Compute = 1.U(3.W)
  private val Done    = 2.U(3.W)
  private val Quant   = 3.U(3.W)
  private val DmaLd   = 4.U(3.W)
  private val DmaSt   = 5.U(3.W)
  val state = RegInit(Idle)
  val kCnt = RegInit(0.U(6.W))
  val kDimLatch = RegInit(0.U(6.W))

  // ---- DMA state ----
  val dmaStep = RegInit(0.U(2.W)) // 0..3: A0,A1,B0,B1
  val dmaCnt = RegInit(0.U(8.W))  // store word counter
  val aBaseL = RegInit(0.U(32.W))
  val bBaseL = RegInit(0.U(32.W))
  val cBaseL = RegInit(0.U(32.W))
  val mDimL = RegInit(0.U(4.W))
  val nDimL = RegInit(0.U(4.W))
  val dmaModeL = RegInit(false.B) // latched from CTRL[2] at start

  val startPulse = slave.io.bus.write.fire &&
    (slave.io.bus.write.bits.addr(7, 0) === RegMap.CTRL) &&
    slave.io.bus.write.bits.data(0)

  val clearDone = slave.io.bus.write.fire &&
    (slave.io.bus.write.bits.addr(7, 0) === RegMap.CTRL) &&
    slave.io.bus.write.bits.data(1)

  when(clearDone) { doneReg := false.B }

  val accClear = Wire(Bool())
  accClear := false.B

  // ---- DMA master helpers (defined before the FSM that uses them) ----
  private val dmaBase = Mux(dmaStep < 2.U, aBaseL, bBaseL)
  private val dmaRowAddr = Mux(dmaStep(0) === 0.U, dmaBase + (kCnt << 3), dmaBase + (kCnt << 3) + 4.U)
  private val dmaStAddr = cBaseL + (dmaCnt << 2)
  private val dmaStoreData = outBuf(dmaCnt(5, 3))(dmaCnt(2, 0)).asSInt.pad(32).asUInt
  private val dmaJ = dmaCnt(2, 0)
  private val dmaI = dmaCnt(5, 3)
  private val dmaStoreValid = dmaJ < nDimL && dmaI < mDimL
  private val dmaLast = ((mDimL - 1.U) << 3) + nDimL - 1.U
  private val dmaStore = state === DmaSt && dmaStoreValid
  private val ldBufWr = state === DmaLd && io.master.rvalid

  switch(state) {
    is(Idle) {
      when(startPulse) {
        kDimLatch := kDimReg(5, 0)
        kCnt := 0.U
        accClear := true.B
        doneReg := false.B
        aBaseL := aBase
        bBaseL := bBase
        cBaseL := cBase
        mDimL := mDimReg(3, 0)
        nDimL := nDimReg(3, 0)
        dmaStep := 0.U
        dmaCnt := 0.U
        dmaModeL := slave.io.bus.write.bits.data(2)
        state := Mux(slave.io.bus.write.bits.data(2),
          Mux(kDimReg(5, 0) === 0.U, DmaSt, DmaLd), Compute)
      }
    }
    is(DmaLd) {
      when(io.master.rvalid) {
        when(dmaStep === 3.U) {
          when(kCnt >= kDimLatch - 1.U) {
            kCnt := 0.U
            dmaStep := 0.U
            accClear := true.B
            state := Compute
          }.otherwise {
            kCnt := kCnt + 1.U
            dmaStep := 0.U
          }
        }.otherwise {
          dmaStep := dmaStep + 1.U
        }
      }
    }
    is(Compute) {
      when(kDimLatch === 0.U) {
        state := Mux(dmaModeL, DmaSt, Done)
        when(!dmaModeL) { doneReg := true.B }
      }.elsewhen(kCnt >= kDimLatch - 1.U) {
        state := Quant
      }.otherwise {
        kCnt := kCnt + 1.U
      }
    }
    is(Quant) {
      state := Mux(dmaModeL, DmaSt, Done)
      when(!dmaModeL) { doneReg := true.B }
    }
    is(DmaSt) {
      when(dmaCnt >= dmaLast) {
        state := Done
        doneReg := true.B
      }.elsewhen(!dmaStoreValid) {
        dmaCnt := dmaCnt + 1.U // skip j >= N inside a row (C layout is i*8+j)
      }.elsewhen(io.master.rvalid) {
        dmaCnt := dmaCnt + 1.U
      }
    }
    is(Done) {
      when(startPulse) {
        kDimLatch := kDimReg(5, 0)
        kCnt := 0.U
        accClear := true.B
        doneReg := false.B
        aBaseL := aBase
        bBaseL := bBase
        cBaseL := cBase
        mDimL := mDimReg(3, 0)
        nDimL := nDimReg(3, 0)
        dmaStep := 0.U
        dmaCnt := 0.U
        dmaModeL := slave.io.bus.write.bits.data(2)
        state := Mux(slave.io.bus.write.bits.data(2),
          Mux(kDimReg(5, 0) === 0.U, DmaSt, DmaLd), Compute)
      }
    }
  }

  // ---- DMA master request ----
  io.master.req.valid := state === DmaLd || dmaStore
  io.master.req.addr := Mux(state === DmaLd, dmaRowAddr, dmaStAddr)
  io.master.req.wen := dmaStore
  io.master.req.wdata := Mux(dmaStore, dmaStoreData, 0.U)
  io.master.req.wmask := Mux(dmaStore, 0xF.U, 0.U)

  // DMA loads the word into the A/B buffer on rvalid
  aBuf.io.wordWr.valid := ldBufWr && dmaStep < 2.U
  bBuf.io.wordWr.valid := ldBufWr && dmaStep >= 2.U
  aBuf.io.wordWr.k := kCnt
  bBuf.io.wordWr.k := kCnt
  aBuf.io.wordWr.eOff := Mux(dmaStep(0), 4.U, 0.U)
  bBuf.io.wordWr.eOff := Mux(dmaStep(0), 4.U, 0.U)
  aBuf.io.wordWr.data := io.master.rdata
  bBuf.io.wordWr.data := io.master.rdata

  val valid = state === Compute && kDimLatch =/= 0.U

  aBuf.io.rowAddr := kCnt(kIdxW - 1, 0)
  bBuf.io.rowAddr := kCnt(kIdxW - 1, 0)
  mac.io.a := VecInit(aBuf.io.rowData.map(_.asSInt))
  mac.io.b := VecInit(bBuf.io.rowData.map(_.asSInt))
  mac.io.valid := valid
  mac.io.clear := accClear
  mac.io.rowMask := VecInit((0 until as).map(i => i.U(4.W) < mDim))
  mac.io.colMask := VecInit((0 until as).map(j => j.U(4.W) < nDim))

  val busy = state === Compute || state === Quant || state === DmaLd || state === DmaSt

  val perfCnt = RegInit(0.U(32.W))
  when(state === Compute || state === Quant) { perfCnt := perfCnt + 1.U }
  when(startPulse) { perfCnt := 0.U }

  quant.io.acc := mac.io.out
  quant.io.bias := biasBuf
  quant.io.biasEn := quantReg(1)
  quant.io.reluEn := quantReg(0)
  quant.io.shift := quantReg(7, 4)
  for (i <- 0 until as; j <- 0 until as) {
    when(state === Quant) { outBuf(i)(j) := quant.io.out(i)(j) }
  }

  val dataRead = Wire(UInt(32.W))
  dataRead := 0.U
  val rAddr = slave.io.bus.read.bits.addr(7, 0)
  switch(rAddr) {
    is(RegMap.CTRL)     { dataRead := ctrlReg }
    is(RegMap.STATUS)   { dataRead := Cat(0.U(30.W), doneReg, busy) }
    is(RegMap.A_BASE)   { dataRead := aBase }
    is(RegMap.B_BASE)   { dataRead := bBase }
    is(RegMap.C_BASE)   { dataRead := cBase }
    is(RegMap.M_DIM)    { dataRead := mDimReg }
    is(RegMap.K_DIM)    { dataRead := kDimReg }
    is(RegMap.N_DIM)    { dataRead := nDimReg }
    is(RegMap.QUANT)    { dataRead := quantReg }
    is(RegMap.PERF)     { dataRead := perfCnt }
    is(RegMap.BUF_CTRL) { dataRead := Cat(0.U(14.W), bufIdx(11, 0), 0.U(2.W), bufSel) }
    is(RegMap.DATA) {
      switch(bufSel) {
        is(RegMap.SelA)   { dataRead := Cat(0.U(24.W), aBuf.io.pioRdData) }
        is(RegMap.SelB)   { dataRead := Cat(0.U(24.W), bBuf.io.pioRdData) }
        is(RegMap.SelAcc) { dataRead := mac.io.out(accI)(accJ).asUInt }
        is(RegMap.SelOut) { dataRead := outBuf(accI)(accJ).asSInt.pad(32).asUInt }
        is(RegMap.SelBias) { dataRead := biasBuf(accI).asUInt }
      }
    }
  }

  aBuf.io.pioRd.k := Mux(io.dbgBufEn, io.dbgBufAddr(7, 4), kIdx)
  aBuf.io.pioRd.e := Mux(io.dbgBufEn, io.dbgBufAddr(3, 0), eIdx)
  bBuf.io.pioRd.k := Mux(io.dbgBufEn, io.dbgBufAddr(7, 4), kIdx)
  bBuf.io.pioRd.e := Mux(io.dbgBufEn, io.dbgBufAddr(3, 0), eIdx)

  aBuf.io.pioWr.valid := false.B
  aBuf.io.pioWr.k := kIdx
  aBuf.io.pioWr.e := eIdx
  aBuf.io.pioWr.data := 0.U
  bBuf.io.pioWr.valid := false.B
  bBuf.io.pioWr.k := kIdx
  bBuf.io.pioWr.e := eIdx
  bBuf.io.pioWr.data := 0.U

  when(slave.io.bus.write.fire) {
    switch(slave.io.bus.write.bits.addr(7, 0)) {
      is(RegMap.CTRL)     { ctrlReg := slave.io.bus.write.bits.data }
      is(RegMap.A_BASE)   { aBase := slave.io.bus.write.bits.data }
      is(RegMap.B_BASE)   { bBase := slave.io.bus.write.bits.data }
      is(RegMap.C_BASE)   { cBase := slave.io.bus.write.bits.data }
      is(RegMap.M_DIM)    { mDimReg := slave.io.bus.write.bits.data }
      is(RegMap.K_DIM)    { kDimReg := slave.io.bus.write.bits.data }
      is(RegMap.N_DIM)    { nDimReg := slave.io.bus.write.bits.data }
      is(RegMap.QUANT)    { quantReg := slave.io.bus.write.bits.data }
      is(RegMap.BUF_CTRL) {
        bufSel := slave.io.bus.write.bits.data(2, 0)
        bufIdx := slave.io.bus.write.bits.data(15, 4)
      }
      is(RegMap.DATA) {
        switch(bufSel) {
          is(RegMap.SelA) {
            aBuf.io.pioWr.valid := true.B
            aBuf.io.pioWr.data := slave.io.bus.write.bits.data(7, 0)
          }
          is(RegMap.SelB) {
            bBuf.io.pioWr.valid := true.B
            bBuf.io.pioWr.data := slave.io.bus.write.bits.data(7, 0)
          }
          is(RegMap.SelBias) {
            biasBuf(bufIdx(log2Ceil(cfg.arraySize) - 1, 0)) := slave.io.bus.write.bits.data.asSInt
          }
        }
        bufIdx := bufIdx + 1.U
      }
    }
  }

  slave.io.bus.readData.valid := slave.io.bus.read.valid
  slave.io.bus.readData.bits := dataRead
  when(slave.io.bus.readData.fire && slave.io.bus.read.bits.addr === RegMap.DATA) {
    bufIdx := bufIdx + 1.U
  }

  io.dbg.mDim := mDimReg
  io.dbg.kDim := kDimReg
  io.dbg.nDim := nDimReg
  io.dbg.busy := busy
  io.dbg.done := doneReg
  io.dbg.perf := perfCnt
  io.dbg.ha := slave.io.dbg.haveAddr
  io.dbg.hd := slave.io.dbg.haveData
  io.dbg.wv := slave.io.dbg.wv
  io.dbg.bufIdx := bufIdx
  io.dbg.bufSel := bufSel
  io.dbg.wdata := Mux(slave.io.bus.write.valid, slave.io.bus.write.bits.data, 0.U)
  io.dbg.wrAddr := Mux(slave.io.bus.write.valid, slave.io.bus.write.bits.addr(7, 0), 0.U)
  io.dbg.wrFire := slave.io.bus.write.fire
  io.dbgOutData := outBuf(io.dbgOutAddr(2, 0))(io.dbgOutAddr(5, 3)).asSInt.pad(32).asUInt
  io.dbgBufData := Mux(io.dbgBufSel, bBuf.io.pioRdData, aBuf.io.pioRdData)
  io.dbgAccData := mac.io.out(io.dbgAccAddr(2, 0))(io.dbgAccAddr(5, 3)).asUInt
}
