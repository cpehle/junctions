// See LICENSE for license details.

package junctions

import Chisel._
import cde.{Parameters, Field}
import scala.collection.mutable.HashMap

case object PAddrBits extends Field[Int]
case object VAddrBits extends Field[Int]
case object PgIdxBits extends Field[Int]
case object PgLevels extends Field[Int]
case object PgLevelBits extends Field[Int]
case object ASIdBits extends Field[Int]
case object PPNBits extends Field[Int]
case object VPNBits extends Field[Int]

case object GlobalAddrMap extends Field[AddrMap]
case object GlobalAddrHashMap extends Field[AddrHashMap]

trait HasAddrMapParameters {
  implicit val p: Parameters

  val paddrBits = p(PAddrBits)
  val vaddrBits = p(VAddrBits)
  val pgIdxBits = p(PgIdxBits)
  val ppnBits = p(PPNBits)
  val vpnBits = p(VPNBits)
  val pgLevels = p(PgLevels)
  val pgLevelBits = p(PgLevelBits)
  val asIdBits = p(ASIdBits)

  val addrMap = p(GlobalAddrHashMap)
}

case class MemAttr(prot: Int, cacheable: Boolean = false)

abstract class MemRegion {
  def align: BigInt
  def size: BigInt
  def numSlaves: Int
}

case class MemSize(size: BigInt, align: BigInt, attr: MemAttr) extends MemRegion {
  def numSlaves = 1
}
case class MemSubmap(size: BigInt, entries: AddrMap, sharePort: Boolean = false) extends MemRegion {
  val numSlaves = entries.countSlaves
  val align = entries.computeAlign
}

object AddrMapProt {
  val R = 0x1
  val W = 0x2
  val X = 0x4
  val RW = R | W
  val RX = R | X
  val RWX = R | W | X
  val SZ = 3
}

class AddrMapProt extends Bundle {
  val x = Bool()
  val w = Bool()
  val r = Bool()
}

case class AddrMapEntry(name: String, region: MemRegion)

case class AddrHashMapEntry(port: Int, start: BigInt, region: MemRegion)

class AddrMap(entries: Seq[AddrMapEntry]) extends scala.collection.IndexedSeq[AddrMapEntry] {
  private val hash = HashMap(entries.map(e => (e.name, e.region)):_*)

  def apply(index: Int): AddrMapEntry = entries(index)

  def length: Int = entries.size

  def countSlaves: Int = entries.map(_.region.numSlaves).foldLeft(0)(_ + _)

  def countPorts: Int = new AddrHashMap(this).nPorts

  def computeSize: BigInt = new AddrHashMap(this).size

  def computeAlign: BigInt = entries.map(_.region.align).foldLeft(BigInt(1))(_ max _)

  override def tail: AddrMap = new AddrMap(entries.tail)
}

object AddrMap {
  def apply(elems: AddrMapEntry*): AddrMap = new AddrMap(elems)
}

class AddrHashMap(addrmap: AddrMap, start: BigInt = BigInt(0)) {
  private val ports   = HashMap[String, AddrHashMapEntry]()   // all ported entries
  private val mapping = HashMap[String, AddrHashMapEntry]()   // all leaf ends
  private val subMaps = HashMap[String, AddrHashMapEntry]()   // all sub maps

  private def genPairs(am: AddrMap, start: BigInt, prefix: String, addPort: Boolean = true): BigInt = {
    var base = start
    am.foreach { ame =>
      val name = prefix + ame.name
      base = (base + ame.region.align - 1) / ame.region.align * ame.region.align
      val entry = AddrHashMapEntry(ports.size, base, ame.region)
      ame.region match {
      case r: MemSize =>
        mapping += name -> entry
        base += r.size
        if(addPort) {
          ports += name -> entry
        }
      case r: MemSubmap =>
        subMaps += name -> entry
        if(addPort && r.sharePort) { // a shared port for the whole subtree
          ports += name -> entry
        }
        genPairs(r.entries, base, name + ":", addPort && !r.sharePort)
        base += r.size
    }}
    base
  }

  val size = genPairs(addrmap, start, "")

  def nPorts: Int = ports.size
  def nEntries: Int = mapping.size
  def apply(name: String): AddrHashMapEntry = mapping.getOrElse(name, subMaps(name))
  def subMap(name: String): (BigInt, AddrMap) = {
    val m = subMaps(name)
    (m.start, m.region.asInstanceOf[MemSubmap].entries)
  }

  def isInRegion(name: String, addr: UInt): Bool = {
    val start = mapping(name).start
    val size = mapping(name).region.size
    UInt(start) <= addr && addr < UInt(start + size)
  }

  def isCacheable(addr: UInt): Bool = {
    mapping.map { case (_, AddrHashMapEntry(_, base, region)) =>
      Bool(region.asInstanceOf[MemSize].attr.cacheable) && UInt(base) <= addr && addr < UInt(base + region.size)
    }.foldLeft(Bool(false))(_ || _)
  }

  def isValid(addr: UInt): Bool = {
    mapping.map { case (_, AddrHashMapEntry(_, base, region)) =>
      addr >= UInt(base) && addr < UInt(base + region.size)
    }.foldLeft(Bool(false))(_ || _)
  }

  def getProt(addr: UInt): AddrMapProt = {
    val protForRegion = mapping.map { case (_, AddrHashMapEntry(_, base, region)) =>
      val inRegion = addr >= UInt(base) && addr < UInt(base + region.size)
      Mux(inRegion, UInt(region.asInstanceOf[MemSize].attr.prot, AddrMapProt.SZ), UInt(0))
    }
    new AddrMapProt().fromBits(protForRegion.reduce(_|_))
  }

  def as_c_header(): String = {
    mapping.map { case (name, AddrHashMapEntry(_, base, region)) => {
      val devName = name.replace(':','_')
      List(
        "#define DEV_MAP__" + devName + "__BASE 0x%x".format(base),
        "#define DEV_MAP__" + devName + "__MASK 0x%x".format(region.size-1)
      )
    }
    }.flatten.mkString("\n") + "\n"
  }

  def getEntries() = mapping.toSeq

}

/** Every elaborated entry ends up in this global arry so it can be printed
  * out later. */
object AllAddrMapEntries extends GlobalMaps[AddrHashMap]
