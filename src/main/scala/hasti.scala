package bridge

import Chisel._

abstract trait HASTIConstants
{
  val SZ_HTRANS     = 2
  val HTRANS_IDLE   = UInt(0, SZ_HTRANS)
  val HTRANS_BUSY   = UInt(1, SZ_HTRANS)
  val HTRANS_NONSEQ = UInt(2, SZ_HTRANS)
  val HTRANS_SEQ    = UInt(3, SZ_HTRANS)

  val SZ_HBURST     = 3
  val HBURST_SINGLE = UInt(0, SZ_HBURST)
  val HBURST_INCR   = UInt(1, SZ_HBURST)
  val HBURST_WRAP4  = UInt(2, SZ_HBURST)
  val HBURST_INCR4  = UInt(3, SZ_HBURST)
  val HBURST_WRAP8  = UInt(4, SZ_HBURST)
  val HBURST_INCR8  = UInt(5, SZ_HBURST)
  val HBURST_WRAP16 = UInt(6, SZ_HBURST)
  val HBURST_INCR16 = UInt(7, SZ_HBURST)

  val SZ_HRESP      = 1
  val HRESP_OKAY    = UInt(0, SZ_HRESP)
  val HRESP_ERROR   = UInt(1, SZ_HRESP)

  val SZ_HSIZE = 3
  val SZ_HPROT = 4

  // TODO: Parameterize
  val SZ_HADDR = 32
  val SZ_HDATA = 32

  def dgate(valid: Bool, b: Bits) = Fill(b.getWidth, valid) & b
}

abstract class HASTIBundle extends Bundle with HASTIConstants
abstract class HASTIModule extends Module with HASTIConstants

class HASTIMasterIO extends HASTIBundle
{
  val haddr     = UInt(OUTPUT, SZ_HADDR)
  val hwrite    = Bool(OUTPUT)
  val hsize     = UInt(OUTPUT, SZ_HSIZE)
  val hburst    = UInt(OUTPUT, SZ_HBURST)
  val hprot     = UInt(OUTPUT, SZ_HPROT)
  val htrans    = UInt(OUTPUT, SZ_HTRANS)
  val hmastlock = Bool(OUTPUT)

  val hwdata = Bits(OUTPUT, SZ_HDATA)
  val hrdata = Bits(INPUT, SZ_HDATA)

  val hready = Bool(INPUT)
  val hresp  = UInt(INPUT, SZ_HRESP)
}

class HASTISlaveIO extends HASTIBundle
{
  val haddr     = UInt(INPUT, SZ_HADDR)
  val hwrite    = Bool(INPUT)
  val hsize     = UInt(INPUT, SZ_HSIZE)
  val hburst    = UInt(INPUT, SZ_HBURST)
  val hprot     = UInt(INPUT, SZ_HPROT)
  val htrans    = UInt(INPUT, SZ_HTRANS)
  val hmastlock = Bool(INPUT)

  val hwdata = Bits(INPUT, SZ_HDATA)
  val hrdata = Bits(OUTPUT, SZ_HDATA)

  val hsel      = Bool(INPUT)
  val hreadyin  = Bool(INPUT)
  val hreadyout = Bool(OUTPUT)
  val hresp     = UInt(OUTPUT, SZ_HRESP)
}

class HASTIBus(amap: Seq[UInt=>Bool]) extends HASTIModule
{
  val io = new Bundle {
    val master = new HASTIMasterIO().flip
    val slaves = Vec.fill(amap.size){new HASTISlaveIO}.flip
  }

  val s1_hsels = Vec.fill(amap.size){Reg(Bool())}

  val hsels = PriorityEncoderOH(
    (io.slaves zip amap) map { case (s, afn) => {
      s.haddr := io.master.haddr
      s.hwrite := io.master.hwrite
      s.hsize := io.master.hsize
      s.hburst := io.master.hburst
      s.hprot := io.master.hprot
      s.htrans := io.master.htrans
      s.hmastlock := io.master.hmastlock
      s.hwdata := io.master.hwdata
      afn(io.master.haddr)
    }})

  (io.slaves zip hsels) foreach { case (s, hsel) => {
    s.hsel := hsel
    s.hreadyin := io.master.hready
  } }

  (io.slaves zip s1_hsels zip hsels) foreach { case ((s, r_hsel), hsel) =>
    when (s.hreadyout) { r_hsel := hsel }
  }

  io.master.hrdata := Mux1H(s1_hsels, io.slaves.map(_.hrdata))
  io.master.hready := Mux1H(s1_hsels, io.slaves.map(_.hreadyout))
  io.master.hresp := Mux1H(s1_hsels, io.slaves.map(_.hresp))
}

class HASTISlaveMux(n: Int) extends HASTIModule
{
  val io = new Bundle {
    val ins = Vec.fill(n){new HASTISlaveIO}
    val out = new HASTISlaveIO().flip
  }

  val s1_valid = Vec.fill(n){Reg(init = Bool(false))}
  val s1_haddr = Vec.fill(n){Reg(UInt(width = SZ_HADDR))}
  val s1_hwrite = Vec.fill(n){Reg(Bool())}
  val s1_hsize = Vec.fill(n){Reg(UInt(width = SZ_HSIZE))}
  val s1_hburst = Vec.fill(n){Reg(UInt(width = SZ_HBURST))}
  val s1_hprot = Vec.fill(n){Reg(UInt(width = SZ_HPROT))}
  val s1_htrans = Vec.fill(n){Reg(UInt(width = SZ_HTRANS))}
  val s1_hmastlock = Vec.fill(n){Reg(Bool())}

  val requests = (io.ins zip s1_valid) map { case (in, v) => in.hsel && in.htrans.orR || v }
  val grants = PriorityEncoderOH(requests)
  val s1_grants = Vec.fill(n){Reg(init = Bool(true))}

  (s1_grants zip grants) foreach { case (g1, g) =>
    when (io.out.hreadyout) { g1 := g }
  }

  def sel[T <: Data](in: Vec[T], s1: Vec[T]) =
    Vec((s1_valid zip s1 zip in) map { case ((v, s), in) => Mux(v, s, in) })

  io.out.haddr := Mux1H(grants, sel(Vec(io.ins.map(_.haddr)), s1_haddr))
  io.out.hwrite := Mux1H(grants, sel(Vec(io.ins.map(_.hwrite)), s1_hwrite))
  io.out.hsize := Mux1H(grants, sel(Vec(io.ins.map(_.hsize)), s1_hsize))
  io.out.hburst := Mux1H(grants, sel(Vec(io.ins.map(_.hburst)), s1_hburst))
  io.out.hprot := Mux1H(grants, sel(Vec(io.ins.map(_.hprot)), s1_hprot))
  io.out.htrans := Mux1H(grants, sel(Vec(io.ins.map(_.htrans)), s1_htrans))
  io.out.hmastlock := Mux1H(grants, sel(Vec(io.ins.map(_.hmastlock)), s1_hmastlock))
  io.out.hsel := grants.reduce(_||_)

  (io.ins zip grants zip s1_grants zipWithIndex) map { case (((in, g), g1), i) => {
    when (io.out.hreadyout && g) {
      s1_valid(i) := Bool(false)
    }
    when (io.out.hreadyout && g1 && !g) {
      s1_valid(i) := in.hsel && in.htrans.orR
      s1_haddr(i) := in.haddr
      s1_hwrite(i) := in.hwrite
      s1_hsize(i) := in.hsize
      s1_hburst(i) := in.hburst
      s1_hprot(i) := in.hprot
      s1_htrans(i) := in.htrans
      s1_hmastlock(i) := in.hmastlock
    }
  } }

  io.out.hwdata := Mux1H(s1_grants, io.ins.map(_.hwdata))
  io.out.hreadyin := Mux1H(s1_grants, io.ins.map(_.hreadyin))

  (io.ins zip s1_grants) foreach { case (in, g1) => {
    in.hrdata := dgate(g1, io.out.hrdata)
    in.hreadyout := io.out.hreadyout && g1
    in.hresp := dgate(g1, io.out.hresp)
  } }
}

class HASTIXbar(n: Int, amap: Seq[UInt=>Bool]) extends HASTIModule
{
  val io = new Bundle {
    val masters = Vec.fill(n){new HASTIMasterIO}.flip
    val slaves = Vec.fill(amap.size){new HASTISlaveIO}.flip
  }

  val buses = io.masters map { m => Module(new HASTIBus(amap)).io }
  val muxes = io.slaves map { s => Module(new HASTISlaveMux(n)).io }

  (buses.map(_.master) zip io.masters) foreach { case (b, m) => b <> m }
  (0 until n) map { m => (0 until amap.size) map { s => buses(m).slaves(s) <> muxes(s).ins(m) } }
  (io.slaves zip muxes.map(_.out)) foreach { case (s, x) => s <> x }
}

class HASTISlaveToMaster extends HASTIModule
{
  val io = new Bundle {
    val in = new HASTISlaveIO
    val out = new HASTIMasterIO
  }

  io.out.haddr := io.in.haddr
  io.out.hwrite := io.in.hwrite
  io.out.hsize := io.in.hsize
  io.out.hburst := io.in.hburst
  io.out.hprot := io.in.hprot
  io.out.htrans := Mux(io.in.hsel && io.in.hreadyin, io.in.htrans, HTRANS_IDLE)
  io.out.hmastlock := io.in.hmastlock
  io.out.hwdata := io.in.hwdata
  io.in.hrdata := io.out.hrdata
  io.in.hreadyout := io.out.hready
  io.in.hresp := io.out.hresp
}
