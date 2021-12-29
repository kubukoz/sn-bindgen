package bindgen

import bindgen.Def.Binding
import scala.scalanative.unsafe.Tag

object render:
  private def escape(name: String) =
    name match
      case "type"   => "`type`"
      case "class"  => "`class`"
      case "object" => "`object`"
      case other    => other

  def enumeration(model: Def.Enum, line: Appender)(using
      Config
  ) =
    val values = List.newBuilder[(String, String)]
    val opaqueType = model.name.get
    val finalizer = if model.values.nonEmpty then ":" else ""
    val numericType = model.intType.getOrElse(CType.Int)
    val enumSuffix = if numericType.sign == SignType.Unsigned then "U" else ""
    val underlyingTypeRender = scalaType(numericType)
    line(s"opaque type $opaqueType = $underlyingTypeRender")
    line(s"object $opaqueType extends CEnum$enumSuffix[$opaqueType]$finalizer")

    nest {
      if numericType.sign == SignType.Signed then
        line(
          s"inline def define(inline a: $underlyingTypeRender): $opaqueType = a"
        )
      else
        val extension = numericType.base match
          case IntegralBase.Int   => "Int"
          case IntegralBase.Long  => "Long"
          case IntegralBase.Short => "Short"
          case IntegralBase.Char  => "Byte"
        line(
          s"inline def define(inline a: Long): $opaqueType = a.toU$extension"
        )
      end if
      model.values.foreach { case (constName, value) =>
        val lhs = s"val $constName"
        val rhs = "define(" + value.toString + ")"

        line(lhs + " = " + rhs)
      }
    }
  end enumeration

  def struct(model: Def.Struct, line: Appender)(using Config) =
    val structName = model.name
    val structType = CType.Struct(model.fields.map(_._2).toList)
    val tpe = scalaType(structType)
    line(s"opaque type $structName = $tpe")
    line(s"object $structName:")
    if model.fields.nonEmpty then
      val paramTypes = model.fields.map(_._2).map(scalaType).mkString(", ")
      val tag =
        s"given Tag[$structName] = ${scalaTag(structType)}" // Tag.materializeCStruct${model.fields.size}Tag[$paramTypes]"
      nest {
        line(tag)
        line(s"extension (struct: $structName)")
        nest {
          model.fields.zipWithIndex.foreach {
            case ((fieldName, fieldType), idx) =>
              line(
                s"def ${escape(fieldName)}: ${scalaType(fieldType)} = struct._${idx + 1}"
              )
          }
        }
      }
    else line(s"given Tag[$structName] = Tag.materializeCStruct0Tag")
    end if
  end struct

  def function(model: Def.Function, line: Appender)(using Config) =
    import model.*

    val arglist = parameters
      .map((name, ctype) => s"${escape(name)}: ${scalaType(ctype)}")
      .mkString(", ")
    line(s"def $name($arglist): ${scalaType(returnType)} = extern")

  end function

  private def scalaTag(typ: CType): String =
    import CType.*
    typ match
      case model: Struct =>
        val paramTypes = model.fields.map(scalaType).mkString(", ")
        s"Tag.materializeCStruct${model.fields.size}Tag[$paramTypes]"
      case n: NumericIntegral =>
        import IntegralBase.*
        import SignType.*
        val base = n.base match
          case Char            => "Byte"
          case Int             => "Int"
          case Long | LongLong => "Long"
          case Short           => "Short"

        val sign = n.sign match
          case Unsigned => "U"
          case _        => ""

        s"Tag.$sign$base"
      case Typedef(n)   => s"Tag[$n]"
      case RecordRef(n) => s"Tag[$n]"
      case n: NumericReal =>
        n.base match
          case FloatingBase.Float => "Tag.Float"
          case _                  => "Tag.Double"
      case Pointer(of) =>
        s"Tag.Ptr[${scalaType(of)}](${scalaTag(of)})"

      case Arr(tpe, Some(n)) =>
        s"Tag.CArray[${scalaType(tpe)}, ${natDigits(n)}](${scalaTag(tpe)}, Tag[${natDigits(n)}])"
    end match
  end scalaTag

  private def scalaType(typ: CType): String =
    import CType.*
    typ match
      case Typedef(name)   => name
      case RecordRef(name) => name
      case Pointer(to) =>
        to match
          case Void         => "Ptr[Byte]" // there's no void type on SN
          case UnsignedChar => "CString"
          case other        => s"Ptr[${scalaType(other)}]"
      case NumericReal(base) =>
        base match
          case FloatingBase.Float      => "Float"
          case FloatingBase.Double     => "Double"
          case FloatingBase.LongDouble => "Double"
      case NumericIntegral(base, signType) =>
        import IntegralBase.*
        import SignType.*
        val bs =
          base match
            case Char     => "Char"
            case Short    => "Short"
            case Int      => "Int"
            case Long     => "LongInt"
            case LongLong => "LongLong"

        val prefix = signType match
          case Signed   => "C"
          case Unsigned => "CUnsigned"

        prefix + bs

      case Struct(fields) =>
        val parameters = fields.map(scalaType).mkString("[", ", ", "]")
        s"CStruct${fields.size}$parameters"

      case Arr(of, size) =>
        size match
          case Some(cnt) => s"CArray[${scalaType(of)}, ${natDigits(cnt)}]"
          case None      => s"Ptr[${scalaType(of)}]"

      case Void =>
        "Unit"

    end match
  end scalaType

  def alias(model: Def.Alias, line: Appender)(using Config) =
    val underlyingType = scalaType(model.underlying)
    import CType.*
    val isOpaque = model.underlying match
      case _: Typedef | _: RecordRef => false
      case _                         => true

    val modifier = if isOpaque then "opaque " else ""
    line(s"${modifier}type ${model.name} = $underlyingType")
    if isOpaque then
      line(s"object ${model.name}: ")
      nest {
        line(s"given Tag[${model.name}] = ${scalaTag(model.underlying)}")
        line(s"def apply(o: $underlyingType): ${model.name} = o")
      }
  end alias

  def binding(packageName: String, binding: Def.Binding, sb: StringBuilder)(
      using Config
  ) =
    sb.append(s"package $packageName\n\n")
    sb.append("""
      |import scala.scalanative.unsafe.*
      |import scala.scalanative.unsigned.*
      |import scalanative.libc.*
      """.stripMargin.trim)
    sb.append("\n\n")
    sb.append("object predef:")
    render.nest {
      val predef = """
      |abstract class CEnum[T](using eq: T =:= Int):
      |  given Tag[T] = Tag.Int.asInstanceOf[Tag[T]]
      |  extension (t: T) def int: CInt = eq.apply(t)
      |
      |abstract class CEnumU[T](using eq: T =:= UInt):
      |  given Tag[T] = Tag.UInt.asInstanceOf[Tag[T]]
      """.stripMargin.linesIterator
      predef.foreach(render.to(sb))
    }

    type Scope = "predef" | "aliases" | "enumerations" | "structs" | "functions"
    def define(s: Scope) = s"object $s"
    def imports(scopes: Scope*) =
      val filtered = scopes.filter {
        case "aliases"      => binding.aliases.nonEmpty
        case "enumerations" => binding.enums.nonEmpty
        case "structs"      => binding.structs.nonEmpty
        case "functions"    => binding.functions.nonEmpty
        case _              => true
      }
      s"import ${filtered.map { sc => sc + ".*" }.mkString(", ")}"

    if binding.enums.nonEmpty then
      sb.append("object enumerations:\n")
      render.nest {
        render.to(sb)("import predef.*")
        binding.enums.filter(_.name.isDefined).zipWithIndex.foreach {
          case (en, idx) =>
            try render.enumeration(
              en,
              render.to(sb)
            )
            catch exc => System.err.println(s"Failed to render $en: $exc")
            if idx != binding.enums.size - 1 then sb.append("\n")
        }
      }
    end if

    if binding.aliases.nonEmpty then
      sb.append("object aliases:\n")
      render.nest {
        // render.to(sb)(imports)
        binding.aliases.zipWithIndex.foreach { case (en, idx) =>
          try render.alias(
            en,
            render.to(sb)
          )
          catch exc => System.err.println(s"Failed to render $en: $exc")
          if idx != binding.aliases.size - 1 then sb.append("\n")
        }
      }
    end if

    if binding.structs.nonEmpty then
      sb.append("\nobject structs:\n")
      render.nest {
        render.to(sb)(imports("aliases", "structs", "enumerations"))

        binding.structs.zipWithIndex.foreach { case (en, idx) =>
          try render.struct(
            en,
            render.to(sb)
          )
          catch exc => System.err.println(s"Failed to render $en: $exc")
          if idx != binding.structs.size - 1 then sb.append("\n")
        }
      }
    end if

    if binding.functions.nonEmpty then
      sb.append("\n@extern\nobject functions: \n")
      render.nest {
        render.to(sb)(imports("aliases", "structs", "enumerations"))
        binding.functions.zipWithIndex.foreach { case (func, idx) =>
          try render.function(
            func,
            render.to(sb)
          )
          catch exc => System.err.println(s"Failed to render $func: $exc")
          if idx != binding.functions.size - 1 then sb.append("\n")
        }
      }
    end if
  end binding

  private def natDigits(i: Int): String =
    if i <= 9 then s"Nat._$i"
    else
      val digits = i.toString.toIterator.toList
      val rendered = digits.map("Nat._" + _).mkString(", ")
      s"Nat.Digit${digits.size}[$rendered]"

  private def indent(using c: Config): String =
    (" " * (c.indentSize * c.indents))

  private def nest(f: Config ?=> Unit)(using config: Config) = f(using
    Config(config.indentSize, indents = config.indents + 1)
  )

  private def to(sb: StringBuilder)(using config: Config): Appender =
    line => sb.append(indent(using config) + line + "\n")

  type Appender = Config ?=> String => Unit
end render

case class Config(indentSize: Int = 2, indents: Int = 0)
