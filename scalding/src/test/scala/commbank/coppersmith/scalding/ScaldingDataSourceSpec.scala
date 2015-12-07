package commbank.coppersmith.scalding

import org.scalacheck.Prop.forAll

import cascading.flow.FlowException

import scalaz.syntax.std.list.ToListOpsFromList
import scalaz.syntax.std.option.ToOptionIdOps

import au.com.cba.omnia.maestro.api._, Maestro._

import au.com.cba.omnia.thermometer.core.Thermometer._
import au.com.cba.omnia.thermometer.core.ThermometerSpec

import commbank.coppersmith.test.thrift.{Customer, Account}
import ScaldingDataSource.Partitions

class HiveTextSourceSpec extends ThermometerSpec { def is = s2"""
  HiveTextSource
    should read multiple listed partitions   $multiplePartitions
    should read multiple globbed partitions  $globPartitions
    should read unpartitioned table          $unpartitioned
    should throw exception on decode failure $decodeFailure
"""
  def multiplePartitions = {
    val partitions = Partitions("status=%s", "ACTIVE", "INACTIVE")
    val dataSource = HiveTextSource[Customer, String](path("multiplePartitions"), partitions)

    // TODO: Use scalacheck instead of relying on these values being in sync with test files
    val expected = List(
      Customer("active_id", "active_name", 19, 1.5, None, 12345),
      Customer("inactive_id", "inactive_name", 21, 1.6, None, 54321)
    )

    withEnvironment(path(getClass.getResource("/hiveTextSource").toString)) {
      runsSuccessfully(dataSource.load).toSet must_== expected.toSet
    }
  }

  def globPartitions = {
    val partitions = Partitions("status=%s", "*")
    val dataSource = HiveTextSource[Customer, String](path("multiplePartitions"), partitions)

    val expected = List(
      Customer("active_id", "active_name", 19, 1.5, None, 12345),
      Customer("inactive_id", "inactive_name", 21, 1.6, None, 54321)
    )

    withEnvironment(path(getClass.getResource("/hiveTextSource").toString)) {
      runsSuccessfully(dataSource.load).toSet must_== expected.toSet
    }
  }

  def unpartitioned = {
    val partitions = Partitions.unpartitioned
    val dataSource = HiveTextSource[Customer, Nothing](path("unpartitioned"), partitions)

    val expected = List(
      Customer("unpart_id", "unpart_name", 19, 1.5, None, 12345)
    )

    withEnvironment(path(getClass.getResource("/hiveTextSource").toString)) {
      runsSuccessfully(dataSource.load).toSet must_== expected.toSet
    }
  }

  def decodeFailure = {
    // reuse input data from [[unpartitioned]], but attempt to decode using Account thrift

    val dataSource = HiveTextSource[Account, Nothing](path("unpartitioned"), Partitions.unpartitioned)

    withEnvironment(path(getClass.getResource("/hiveTextSource").toString)) {
      run(dataSource.load) must beFailedTry.withThrowable[FlowException]  // this wraps the actual coppersmith exception
    }
  }
}
