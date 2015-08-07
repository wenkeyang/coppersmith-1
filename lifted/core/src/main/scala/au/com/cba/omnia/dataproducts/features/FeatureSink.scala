package au.com.cba.omnia.dataproducts.features

import org.joda.time.DateTime

import scalaz.std.list.listInstance
import scalaz.syntax.bind.ToBindOps
import scalaz.syntax.functor.ToFunctorOps
import scalaz.syntax.foldable.ToFoldableOps

import org.apache.hadoop.fs.Path

import com.twitter.scalding.{Execution, TupleSetter, TupleConverter}
import com.twitter.scalding.typed.{PartitionedTextLine, TypedPipe}

import com.twitter.scrooge.ThriftStruct

import au.com.cba.omnia.maestro.api._, Maestro._

import au.com.cba.omnia.etl.util.ParseUtils

import Feature.Value.{Integral, Decimal, Str}

import au.com.cba.omnia.dataproducts.features.thrift.Eavt

trait FeatureSink {
  def write(features: TypedPipe[FeatureValue[_, _]], jobConfig: FeatureJobConfig): Execution[Unit]
}

object HydroSink {
  type DatabaseName = String
  type TableName    = String

  val NullValue = "\\N"

  import HiveSupport.HiveConfig

  val partition = Partition.byDate(Fields[Eavt].Time, "yyyy-MM-dd")
  def config(maestro: MaestroConfig, dbRawPrefix: String) = Config(
      HiveConfig(
        partition,
        s"${dbRawPrefix}_features",
        new Path(s"${maestro.hdfsRoot}/view/warehouse/features/${maestro.source}/${maestro.tablename}"),
        maestro.tablename
      )
  )

  def config(databaseName: DatabaseName, databasePath: Path, tableName: TableName) =
    Config(HiveConfig(partition, databaseName, databasePath, tableName))

  case class Config(hiveConfig: HiveConfig[Eavt, (String, String, String)])
}

case class HydroSink(conf: HydroSink.Config) extends FeatureSink {

  def toEavt(fv: FeatureValue[_, _]) = {
    val featureValue = (fv.value match {
      case Integral(v) => v.map(_.toString)
      case Decimal(v)  => v.map(_.toString)
      case Str(v)      => v
    }).getOrElse(HydroSink.NullValue)

    // TODO: Does time format need to be configurable?
    val featureTime = new DateTime(fv.time).toString("yyyy-MM-dd")
    Eavt(fv.entity, fv.feature.metadata.name, featureValue, featureTime)
  }

  def write(features: TypedPipe[FeatureValue[_, _]], jobConfig: FeatureJobConfig) = {
    val hiveConfig = conf.hiveConfig
    val eavtPipe = features.map(toEavt)
    for {
      _ <- HiveSupport.writeTextTable(conf.hiveConfig, eavtPipe)
      _ <- Execution.fromHdfs {
           for {
             // FIXME: Derive path(s) from job
             files <- Hdfs.glob(new Path(hiveConfig.path, "*/*/*"))
             _     <- files.traverse_[Hdfs](eachPath => for {
                        r1  <- Hdfs.create(s"${eachPath.toString}/_SUCCESS".toPath)
                      } yield ())
             } yield()
      }
    } yield()
  }
}

// Maestro's HiveTable currently assumes the underlying format to be Parquet. This code generalises
// code from different feature gen projects, which supports storing the final EAVT records as text.
// Won't be required once Hydro moves to Parquet format
object HiveSupport {
  case class HiveConfig[T <: ThriftStruct with Product : Manifest, P](
    partition: Partition[T, P],
    database:  String,
    path:      Path,
    tablename: String,
    delimiter: String = "|"
  )

  def writeTextTable[T <: ThriftStruct with Product : Manifest, P : TupleSetter : TupleConverter](
    conf: HiveConfig[T, P],
    pipe: TypedPipe[T]
  ): Execution[Unit] = {

    import conf.partition
    val partitioned = pipe.map(v =>
      partition.extract(v) -> ParseUtils.mkStringThrift[T](v, conf.delimiter)
    )
    val partitionPath = partition.fieldNames.map(_ + "=%s").mkString("/")
    for {
      _ <- partitioned.writeExecution(PartitionedTextLine[P](conf.path.toString, partitionPath))
      _ <- Execution.fromHive(createTextTable(conf))
    } yield ()
  }

  def createTextTable[T <: ThriftStruct with Product : Manifest](conf: HiveConfig[T, _]): Hive[Unit] =
    for {
    _ <- Hive.createTextTable[T](
          database         = conf.database,
          table            = conf.tablename,
          partitionColumns = conf.partition.fieldNames.map(_ -> "string"),
          location         = Option(conf.path),
          delimiter        = conf.delimiter
        )
    _ <- Hive.queries(List(s"use ${conf.database}", s"msck repair table ${conf.tablename}"))
  } yield ()
}
