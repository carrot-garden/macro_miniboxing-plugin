package miniboxing.plugin
package transform
package adapt

import scala.tools.nsc._
import scala.tools.nsc.typechecker._
import scala.tools.nsc.transform.TypingTransformers
import scala.util.DynamicVariable
import scala.collection.immutable.ListMap

trait MiniboxAdaptTreeTransformer extends TypingTransformers {
  self: MiniboxAdaptComponent =>

  import minibox._
  import global._

  implicit class RichType(tpe: Type) {
    def getStorageType: Type = tpe.dealiasWiden.annotations.filter(_.tpe.typeSymbol == StorageClass) match {
      case Nil        => assert(false, "No storage type detected?!?"); ???
      case annot :: _ => annot.tpe.typeArgs(0)
    }
    def isStorage: Boolean = tpe.dealiasWiden.annotations.exists(_.tpe.typeSymbol == StorageClass)
    def withStorage(store: Type): Type = tpe.withAnnotations(List(Annotation.apply(appliedType(StorageClass.tpe, List(store)), Nil, ListMap.empty)))
    def withoutStorage: Type = tpe.filterAnnotations(_.tpe.typeSymbol != StorageClass)
  }

  implicit class RichTree(tree: Tree) {
    def isStorage: Boolean = tree.tpe.isStorage
  }

  class AdaptPhase(prev: Phase) extends StdPhase(prev) {
    override def name = MiniboxAdaptTreeTransformer.this.phaseName
    override def checkable = false
    def apply(unit: CompilationUnit): Unit = {

      // do this before adapting the tree, so we crash'n'burn
      // if any instantiation wasn't done properly.
      adaptClassFieldsAndCtors()

      val tree = afterMiniboxAdapt(new TreeAdapters().adapt(unit))
      tree.foreach(node => assert(node.tpe != null, node))
    }

    /*
     * This removes fields and constructors from a class while leaving the
     * setters and getters in place. The effect is that the class automatically
     * becomes an interface
     */
    private def adaptClassFieldsAndCtors() = {
      import global.Flag._
      import global.definitions._

      // can be applied as many times as necessary
      for (clazz <- metadata.allStemClasses) {
        val decls = clazz.info.decls
        for (mbr <- decls) {
          if ((mbr.isTerm && !mbr.isMethod) || (mbr.isConstructor))
            decls unlink mbr
        }
      }

      // remove dummy constructors
      for (dummy <- metadata.dummyConstructors)
        dummy.owner.info.decls unlink dummy
    }
  }

  class TreeAdapters extends Analyzer {
    var indent = 0
    lazy val global: self.global.type = self.global

    def adapt(unit: CompilationUnit): Tree = {
      val context = rootContext(unit)
      val checker = new TreeAdapter(context)
      unit.body = checker.typed(unit.body)
      unit.body
    }

    override def newTyper(context: Context): Typer =
      new TreeAdapter(context)

    def adaptdbg(ind: Int, msg: => String): Unit = {
      //println("  " * ind + msg)
    }

    class TreeAdapter(context0: Context) extends Typer(context0) {
      override protected def finishMethodSynthesis(templ: Template, clazz: Symbol, context: Context): Template =
        templ

      def supertyped(tree: Tree, mode: Int, pt: Type): Tree =
        super.typed(tree, mode, pt)

      override protected def adapt(tree: Tree, mode: Int, pt: Type, original: Tree = EmptyTree): Tree = {
        val oldTpe = tree.tpe
        val newTpe = pt
        if (tree.isTerm) {
          if ((oldTpe.isStorage ^ newTpe.isStorage) && (!pt.isWildcard)) {
            val conversion = if (oldTpe.isStorage) marker_minibox2box else marker_box2minibox
            val (tpe, storageType) =
              if (oldTpe.isStorage)
                (oldTpe.dealiasWiden.withoutStorage, oldTpe.getStorageType)
              else
                (newTpe.dealiasWiden.withoutStorage, newTpe.getStorageType)
            val tree1 = gen.mkMethodCall(conversion, List(tpe, storageType), List(tree))
            val tree2 = super.typed(tree1, mode, pt)
            assert(tree2.tpe != ErrorType, tree2)
            // super.adapt is automatically executed when calling super.typed
            tree2
          } else if (oldTpe.isStorage && (oldTpe.isStorage == newTpe.isStorage) && !(oldTpe <:< newTpe)) {
            // workaround the isSubType issue with singleton types
            // and annotated types (see mb_erasure_torture10.scala)
            tree.tpe = newTpe
            tree
          } else
            super.adapt(tree, mode, pt, original)
        } else {
          super.adapt(tree, mode, pt, original)
        }
      }

      override def typed(tree: Tree, mode: Int, pt: Type): Tree = {
        val ind = indent
        indent += 1
        adaptdbg(ind, " <== " + tree + ": " + showRaw(pt, true, true, false, false))
        val res = tree match {
          case EmptyTree | TypeTree() =>
            super.typed(tree, mode, pt)
          case _ if tree.tpe == null =>
            super.typed(tree, mode, pt)
          case Select(qual, meth) if qual.isTerm && tree.symbol.isMethod =>
            val qual2 = super.typedQualifier(qual.setType(null), mode, WildcardType)
            if (qual2.isStorage) {
              val tpe2 = if (qual2.tpe.hasAnnotation(StorageClass)) qual2.tpe else qual2.tpe.widen
              val tpe3 = tpe2.removeAnnotation(StorageClass)
              //val qual3 = super.typedQualifier(qual.setType(null), mode, tpe3)
              val storageType = qual2.tpe.getStorageType
              val qual3 =  gen.mkMethodCall(gen.mkAttributedRef(marker_minibox2box), List(tpe3, storageType), List(qual2))
              super.typed(Select(qual3, meth) setSymbol tree.symbol, mode, pt)
            } else {
              tree.tpe = null
              super.typed(tree, mode, pt)
            }
          case _ =>
            tree.tpe = null
            super.typed(tree, mode, pt)
        }
        adaptdbg(ind, " ==> " + res + ": " + res.tpe)
        if (res.tpe == ErrorType)
          adaptdbg(ind, "ERRORS: " + context.errBuffer)
        indent -= 1
        res
      }
    }
  }
}

