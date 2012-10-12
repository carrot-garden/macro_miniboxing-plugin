package plugin

import scala.tools.nsc.Global

trait MiniboxLogic {
  self: MiniboxLogging =>

  val global: Global
  import global._
  import definitions._
  import scala.collection.immutable

  lazy val MinispecedClass = rootMirror.getRequiredClass("plugin.minispec")

  /**
   * A `TypeEnv` maps each type parameter of the original class to the 
   * actual type used in the specialized version to which this environment
   * correspond. This type may be either `Long` or a fresh type parameter
   *  `Tsp`.
   */
  type TypeEnv = immutable.Map[Symbol, Type]

  /**
   * A `PartialSpec` provides us information about the representation used
   * for values of a type parameter: either `Boxed` (as AnyRef) or 
   * `Miniboxed` (as Long).
   */
  sealed trait SpecInfo
  case object Miniboxed extends SpecInfo
  case object Boxed extends SpecInfo
  type PartialSpec = immutable.Map[Symbol, SpecInfo]

  /**
   * For a set of type parameters, get all the possible partial specializations.
   *
   * A partial specialization is represented as a list that gives the types that
   * specifies what a type parameter is instantiated to.
   */
  def specializations(tParams: List[Symbol]): List[PartialSpec] = {
    var envs: List[List[SpecInfo]] = List(Nil)

    for (tParam <- tParams)
      envs = envs.flatMap(rest => List(Miniboxed :: rest, Boxed :: rest))

    envs.map((types: List[SpecInfo]) => (tParams zip types).toMap)
  }

  /**
   * Creates the name of the interface which corresponds to class `className`
   */
  def interfaceName(className: Name): TypeName = {
    newTypeName(className.toString + "_interface")
  }

  /**
   * Specialize name for the two list of types.
   */
  def specializedName(name: Name, types: List[Type]): TermName = {
    if (nme.INITIALIZER == name || (types.isEmpty))
      name
    else if (nme.isSetterName(name))
      nme.getterToSetter(specializedName(nme.setterToGetter(name), types))
    else if (nme.isLocalName(name))
      nme.getterToLocal(specializedName(nme.localToGetter(name), types))
    else {
      newTermName(name.toString + "_" + types.map(t => definitions.abbrvTag(t.typeSymbol)).mkString(""))
    }
  }

  /**
   * The name of the field carrying the type tag of corresponding to a type
   * parameter `tparam`
   */
  def typeTagName(tparam: Symbol): TermName = {
    tparam.name.append("_TypeTag").toTermName
  }
  def isTypeTagField(field: Symbol): Boolean = {
    field.name.endsWith("_TypeTag")
  }

  /**
   * Find the variable that should be accessed by some specialized accessor
   */
  def accessed(m: Symbol): Symbol = {
    val getterName = m.getterName
    val originalGetterName = if (getterName.containsChar('_'))
      getterName.subName(0, getterName.indexOf('_'))
    else
      getterName

    m.owner.info.decl(nme.getterToLocal(originalGetterName))
  }
  
  def typeParamValues(clazz: Symbol, env: PartialSpec): List[Type] =
    clazz.typeParams.map(env) map {
      case Boxed => AnyRefClass.tpe
      case Miniboxed => LongClass.tpe
    }

  def needsSpecialization(clazz: Symbol, method: Symbol) = true

  def isAllAnyRef(env: PartialSpec) = env.forall(_._2 == Boxed)

  /**
   * Tells whether a class must be specialized by looking at the annotations
   */
  def isSpecializableClass(clazz: Symbol) =
    clazz.isClass &&
    !clazz.typeParams.isEmpty && 
    (clazz.typeParams forall (_ hasAnnotation MinispecedClass))
    
}