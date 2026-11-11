import java.nio.file.{Files, Path}
import java.util.regex.Pattern

import scala.collection.mutable.ArrayBuffer
import scala.scalanative.sbtplugin.ScalaNativePlugin

import sbt.*
import sbt.Keys.*
import xsbti.compile.AuxiliaryClassFiles

/** Backport of scala-native/scala-native#5096 (fixes #5084), unreleased as of sbt-scala-native 0.5.12: Zinc does not
  * know `.nir` files are products, so it neither deletes them with their source nor rolls them back with the class
  * files after a failed compile. A stale `.nir` then links (and sbt 2's cache keeps restoring it, even after `clean`).
  * Remove once on a release that includes the fix.
  */
object NirAuxiliaryClassFiles extends AuxiliaryClassFiles {
  private final val LambdaSuffix = """\$\$Lambda\$[0-9]+"""
  private final val ReflectionSuffix =
    """(?:\$[0-9]+)?\$(?:SN|scalanative)\$ReflectivelyInstantiate\$"""
  private val GeneratedSuffixPattern =
    Pattern.compile(raw"""(?:$LambdaSuffix|$ReflectionSuffix)\.nir""")

  override def associatedFiles(classFile: Path): Array[Path] = {
    val filename = classFile.getFileName.toString
    if (!filename.endsWith(".class")) Array.empty
    else {
      val name = filename.stripSuffix(".class")
      val nirFile = classFile.resolveSibling(name + ".nir")
      val directory = classFile.getParent
      if (!Files.isDirectory(directory)) Array(nirFile)
      else {
        // These classes are generated only by the Native backend, so Zinc cannot discover them through the JVM
        // compiler's products.
        val files = Files.list(directory)
        try {
          val associated = ArrayBuffer(nirFile)
          val iterator = files.iterator()
          while (iterator.hasNext) {
            val path = iterator.next()
            val other = path.getFileName.toString
            if (other.startsWith(name) &&
                GeneratedSuffixPattern.matcher(other.substring(name.length)).matches())
              associated += path
          }
          associated.toArray
        } finally files.close()
      }
    }
  }
}

/** Applies [[NirAuxiliaryClassFiles]] to every project with Scala Native enabled. */
object NirIncrementalFix extends AutoPlugin {
  override def requires = ScalaNativePlugin
  override def trigger = allRequirements

  private def settingsIn(config: Configuration) = config / auxiliaryClassFiles := Def.uncached {
    // `Test` may inherit `Compile`'s value, already holding it.
    ((config / auxiliaryClassFiles).value :+ NirAuxiliaryClassFiles).distinct
  }

  override def projectSettings = Seq(settingsIn(Compile), settingsIn(Test))
}
