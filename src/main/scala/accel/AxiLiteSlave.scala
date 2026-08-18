package accel

import chisel3._
import chisel3.util._

object AxiLite4 {
  val OKAY = 0.U(2.W)
}

class AxiLite4Addr(addrW: Int) extends Bundle {
  val addr = UInt(addrW.W)
  val prot = UInt(3.W)
}

class AxiLite4WriteData(dataW: Int) extends Bundle {
  val data = UInt(dataW.W)
  val strb = UInt((dataW / 8).W)
}

class AxiLite4ReadData(dataW: Int) extends Bundle {
  val data = UInt(dataW.W)
  val resp = UInt(2.W)
}

class AxiLite4Resp extends Bundle {
  val resp = UInt(2.W)
}

class AxiLite4(addrW: Int = 32, dataW: Int = 32) extends Bundle {
  val aw = Flipped(Decoupled(new AxiLite4Addr(addrW)))
  val w  = Flipped(Decoupled(new AxiLite4WriteData(dataW)))
  val b  = Decoupled(new AxiLite4Resp)
  val ar = Flipped(Decoupled(new AxiLite4Addr(addrW)))
  val r  = Decoupled(new AxiLite4ReadData(dataW))
}

class RegBusIO(addrW: Int = 32, dataW: Int = 32) extends Bundle {
  val write = Decoupled(new Bundle {
    val addr = UInt(addrW.W)
    val data = UInt(dataW.W)
  })
  val read = Decoupled(new Bundle {
    val addr = UInt(addrW.W)
  })
  val readData = Flipped(Decoupled(UInt(dataW.W)))
}

class AxiLiteSlave(addrW: Int = 32, dataW: Int = 32) extends Module {
  val io = IO(new Bundle {
    val axi = new AxiLite4(addrW, dataW)
    val bus = new RegBusIO(addrW, dataW)
    val dbg = Output(new Bundle {
      val haveAddr = Bool()
      val haveData = Bool()
      val wv = Bool()
    })
  })

  val awAddr = Reg(UInt(addrW.W))
  val wData  = Reg(UInt(dataW.W))
  val haveAddr = RegInit(false.B)
  val haveData = RegInit(false.B)

  io.axi.aw.ready := !haveAddr
  io.axi.w.ready  := !haveData

  when(io.axi.aw.fire) {
    awAddr := io.axi.aw.bits.addr
    haveAddr := true.B
  }
  when(io.axi.w.fire) {
    wData := io.axi.w.bits.data
    haveData := true.B
  }

  val writeComplete = haveAddr && haveData
  io.axi.b.valid := writeComplete
  io.axi.b.bits.resp := AxiLite4.OKAY
  when(io.axi.b.fire) {
    haveAddr := false.B
    haveData := false.B
  }

  val rAddr = Reg(UInt(addrW.W))
  val rPending = RegInit(false.B)

  io.axi.ar.ready := !rPending
  when(io.axi.ar.fire) {
    rAddr := io.axi.ar.bits.addr
    rPending := true.B
  }
  io.axi.r.valid := rPending
  io.axi.r.bits.data := io.bus.readData.bits
  io.axi.r.bits.resp := AxiLite4.OKAY
  when(io.axi.r.fire) {
    rPending := false.B
  }

  io.bus.write.valid := writeComplete
  io.bus.write.bits.addr := awAddr
  io.bus.write.bits.data := wData

  io.bus.read.valid := rPending
  io.bus.read.bits.addr := rAddr
  io.bus.readData.ready := io.axi.r.fire

  io.dbg.haveAddr := haveAddr
  io.dbg.haveData := haveData
  io.dbg.wv := io.bus.write.valid
}
