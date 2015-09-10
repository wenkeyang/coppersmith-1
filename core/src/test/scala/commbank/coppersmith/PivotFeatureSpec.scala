package commbank.coppersmith

import org.scalacheck.Prop.forAll

import org.specs2._

import au.com.cba.omnia.maestro.api.{Field, Maestro}, Maestro.Fields

import Feature._, Value._
import FeatureMetadata.ValueType._

import Arbitraries._

import commbank.coppersmith.test.thrift.Customer

/* More of an integration test based on a semi-realistic example. Individual feature components
 * are tested in PivotFeatureSpec that follows.
 */
object PivotFeatureSetSpec extends Specification with ScalaCheck { def is = s2"""
  PivotFeatureSet - Test an example set of features based on pivoting a record
  ===========
  An example feature set
    must generate expected metadata       $generateMetadata
    must generate expected feature values $generateFeatureValues

  Macro feature set
    must generate same metadata as test example $generateMetadataCompareMacro
    must generate same values as test example $generateFeatureValuesCompareMacro
"""

  import Type.{Categorical, Continuous}

  object CustomerFeatureSet extends PivotFeatureSet[Customer] {
    val namespace = "test.namespace"

    def entity(c: Customer) = c.id
    def time(c: Customer)   = c.time

    val name:   Feature[Customer, Str]      = pivot(Fields[Customer].Name,  "Customer name", Categorical)
    val age:    Feature[Customer, Integral] = pivot(Fields[Customer].Age,    "Customer age", Categorical)
    val height: Feature[Customer, Decimal]  = pivot(Fields[Customer].Height, "Customer height", Continuous)

    def features = List(name, age, height)
  }

  def generateMetadata = {
    val metadata = CustomerFeatureSet.metadata
    import CustomerFeatureSet.namespace
    val fields = Fields[Customer]

    metadata must_== List(
      FeatureMetadata[Customer, Str]     (namespace, fields.Name.name,   "Customer name",   Categorical),
      FeatureMetadata[Customer, Integral](namespace, fields.Age.name,    "Customer age",    Categorical),
      FeatureMetadata[Customer, Decimal] (namespace, fields.Height.name, "Customer height", Continuous)
    )
  }

  def generateMetadataCompareMacro = {
    def copyNoDesc(oldMetadata: FeatureMetadata[Customer, Value]) = {
      FeatureMetadata[Customer, Value](
        namespace   = oldMetadata.namespace,
        name        = oldMetadata.name,
        description = "",
        featureType = oldMetadata.featureType
      )
    }
    val macroMetadata = CustomerFeatureSet.metadata.toSeq.map(copyNoDesc)

    val metadata = PivotMacro.pivotThrift[Customer](
      CustomerFeatureSet.namespace,
      CustomerFeatureSet.entity,
      CustomerFeatureSet.time
    ).features.map(_.metadata).map(copyNoDesc _)

    metadata must containAllOf(macroMetadata)
  }

  def generateFeatureValuesCompareMacro = forAll { (c:Customer) =>
    val values = CustomerFeatureSet.generate(c)
    val macroValues = PivotMacro.pivotThrift[Customer](CustomerFeatureSet.namespace, CustomerFeatureSet.entity, CustomerFeatureSet.time).generate(c)

    macroValues.map(it => (it.entity, it.value, it.time)) must containAllOf(values.toSeq.map(it => (it.entity, it.value, it.time)))
  }

  def generateFeatureValues = forAll { (c: Customer) => {
    val featureValues = CustomerFeatureSet.generate(c)

    featureValues must_== List(
      FeatureValue[Str]     (c.id, CustomerFeatureSet.name.metadata.name,   c.name,   c.time),
      FeatureValue[Integral](c.id, CustomerFeatureSet.age.metadata.name,    c.age,    c.time),
      FeatureValue[Decimal] (c.id, CustomerFeatureSet.height.metadata.name, c.height, c.time)
    )
  }}
}

object PivotFeatureSpec extends Specification with ScalaCheck { def is = s2"""
  Pivot Features - Test individual pivot feature components
  ===========
  Creating pivot feature metadata
    must pass namespace through         $metadataNamespace
    must pass description through       $metadataDescription
    must use field name as feature name $metadataName
    must pass feature type through      $metadataFeatureType
    must derive value type from field   $metadataValueType

  Generating pivot feature values
    must use specified id as entity      $valueEntity
    must use field name as name          $valueName
    must use field's value as value      $valueValue
    must use specified time as time      $valueTime
"""

  def pivot(
    ns:    Namespace,
    desc:  Description,
    ft:    Type,
    e:     Customer => EntityId,
    t:     Customer => Time,
    field: Field[Customer, _]
  ) = {
    // Work around fact that Patterns.pivot requires field's value type,
    // which we don't get from fields arbitrary
    if (field == Fields[Customer].Name) {
      Patterns.pivot[Customer, Str, String](ns, ft, e, t, Fields[Customer].Name, desc)
    } else if (field == Fields[Customer].Age) {
      Patterns.pivot[Customer, Integral, Int](ns, ft, e, t, Fields[Customer].Age, desc)
    } else if (field == Fields[Customer].Height) {
      Patterns.pivot[Customer, Decimal, Double](ns, ft, e, t, Fields[Customer].Height, desc)
    } else sys.error("Unknown field generated by arbitrary: " + field)
  }

  def metadataNamespace = forAll {
    (namespace: Namespace, desc: Description, fType: Type, field: Field[Customer, _]) => {
      val feature = pivot(namespace, desc, fType, _.id, _.time, field)
      feature.metadata.namespace must_== namespace
    }
  }

  def metadataName = forAll {
    (namespace: Namespace, desc: Description, fType: Type, field: Field[Customer, _]) => {
      val feature = pivot(namespace, desc, fType, _.id, _.time, field)
      feature.metadata.name must_== field.name
    }
  }

  def metadataDescription = forAll {
    (namespace: Namespace, desc: Description, fType: Type, field: Field[Customer, _]) => {
      val feature = pivot(namespace, desc, fType, _.id, _.time, field)
      feature.metadata.description must_== desc
    }
  }

  def metadataFeatureType = forAll {
    (namespace: Namespace, desc: Description, fType: Type, field: Field[Customer, _]) => {
      val feature = pivot(namespace, desc, fType, _.id, _.time, field)
      feature.metadata.featureType must_== fType
    }
  }

  def metadataValueType = forAll {
    (namespace: Namespace, desc: Description, fType: Type, field: Field[Customer, _]) => {
      val feature = pivot(namespace, desc, fType, _.id, _.time, field)

      val expectedValueType = field match {
        case f if f == Fields[Customer].Name => StringType
        case f if f == Fields[Customer].Age => IntegralType
        case f if f == Fields[Customer].Height => DecimalType
      }
      feature.metadata.valueType must_== expectedValueType
    }
  }

  def valueEntity = forAll {
    (namespace: Namespace, desc: Description, fType: Type, field: Field[Customer, _], c: Customer) => {
      val feature = pivot(namespace, desc, fType, _.id, _.time, field)
      feature.generate(c) must beSome.like { case v => v.entity must_== c.id }
    }
  }

  def valueName = forAll {
    (namespace: Namespace, desc: Description, fType: Type, field: Field[Customer, _], c: Customer) => {
      val feature = pivot(namespace, desc, fType, _.id, _.time, field)
      feature.generate(c) must beSome.like { case v => v.name must_== field.name }
    }
  }

  def valueValue = forAll {
    (namespace: Namespace, desc: Description, fType: Type, field: Field[Customer, _], c: Customer) => {
      val feature = pivot(namespace, desc, fType, _.id, _.time, field)

      val expectedValue = field match {
        case f if f == Fields[Customer].Name => Str(Option(c.name))
        case f if f == Fields[Customer].Age => Integral(Option(c.age))
        case f if f == Fields[Customer].Height => Decimal(Option(c.height))
      }
      feature.generate(c) must beSome.like { case v => v.value must_== expectedValue }
    }
  }

  def valueTime = forAll {
    (namespace: Namespace, desc: Description, fType: Type, field: Field[Customer, _], c: Customer) => {
      val feature = pivot(namespace, desc, fType, _.id, _.time, field)
      feature.generate(c) must beSome.like { case v => v.time must_== c.time }
    }
  }
}
