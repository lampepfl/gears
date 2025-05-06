import org.scalajs.jsenv.nodejs._
import org.scalajs.linker.interface.ESVersion
import sbtcrossproject.CrossPlugin.autoImport.{CrossType, crossProject}
import scalanative.build._

val scala = "3.7.0"
ThisBuild / scalaVersion := scala

publish / skip := true

inThisBuild(
  Seq(
    // publish settings
    organization := "ch.epfl.lamp",
    homepage := Some(url("https://lampepfl.github.io/gears")),
    licenses := List(License.Apache2),
    developers := List(
      Developer("natsukagami", "Natsu Kagami", "natsukagami@gmail.com", url("https://github.com/natsukagami"))
    )
  )
)

lazy val root =
  crossProject(JSPlatform, JVMPlatform, NativePlatform)
    .crossType(CrossType.Full)
    .in(file("."))
    .settings(
      Seq(
        name := "Gears",
        versionScheme := Some("early-semver"),
        libraryDependencies += "org.scala-lang" %% "scala2-library-cc-tasty-experimental" % scala,
        libraryDependencies += "org.scalameta" %%% "munit" % "1.1.1" % Test,
        testFrameworks += new TestFramework("munit.Framework")
      )
    )
    .jvmSettings(
      Seq(
        javaOptions += "--version 21"
      )
    )
    .nativeSettings(
      Seq(
        nativeConfig ~= { c =>
          c.withMultithreading(true)
        }
      )
    )
    .nativeSettings(
      Seq(
        nativeConfig ~= { c =>
          c.withMultithreading(true)
        }
      )
    )
    .jsSettings(
      Seq(
        scalaVersion := "3.7.1-RC1-bin-20250425-fb6cc9b-NIGHTLY",
        // Emit ES modules with the Wasm backend
        scalaJSLinkerConfig := {
          scalaJSLinkerConfig.value
            .withESFeatures(_.withESVersion(ESVersion.ES2017)) // enable async/await
            .withExperimentalUseWebAssembly(true) // use the Wasm backend
            .withModuleKind(ModuleKind.ESModule) // required by the Wasm backend
        },
        // Configure Node.js (at least v23) to support the required Wasm features
        jsEnv := {
          val config = NodeJSEnv
            .Config()
            .withArgs(
              List(
                "--experimental-wasm-exnref", // always required
                "--experimental-wasm-jspi", // required for js.async/js.await
                "--experimental-wasm-imported-strings", // optional (good for performance)
                "--turboshaft-wasm" // optional, but significantly increases stability
              )
            )
          new NodeJSEnv(config)
        },

        // Patch the sjsir of JSPI to introduce primitives by hand
        Compile / compile := {
          val analysis = (Compile / compile).value

          val s = streams.value
          val classDir = (Compile / classDirectory).value
          val jspiIRFile = classDir / "gears/async/js/JSPI$.sjsir"
          patchJSPIIR(jspiIRFile, s)

          analysis
        }
      )
    )

def patchJSPIIR(jspiIRFile: File, streams: TaskStreams): Unit = {
  import org.scalajs.ir.Names._
  import org.scalajs.ir.Trees._
  import org.scalajs.ir.Types._
  import org.scalajs.ir.WellKnownNames._

  val content = java.nio.ByteBuffer.wrap(java.nio.file.Files.readAllBytes(jspiIRFile.toPath()))
  val classDef = org.scalajs.ir.Serializers.deserialize(content)

  val newMethods = classDef.methods.mapConserve { m =>
    (m.methodName.simpleName.nameString, m.body) match {
      case ("async", Some(UnaryOp(UnaryOp.Throw, _))) =>
        implicit val pos = m.pos
        val closure = Closure(
          ClosureFlags.arrow.withAsync(true),
          m.args,
          Nil,
          None,
          AnyType,
          Apply(ApplyFlags.empty, m.args.head.ref, MethodIdent(MethodName("apply", Nil, ObjectRef)), Nil)(AnyType),
          m.args.map(_.ref)
        )
        val newBody = Some(JSFunctionApply(closure, Nil))
        m.copy(body = newBody)(m.optimizerHints, m.version)(m.pos)

      case ("await", Some(UnaryOp(UnaryOp.Throw, _))) =>
        implicit val pos = m.pos
        val newBody = Some(JSAwait(m.args.head.ref))
        m.copy(body = newBody)(m.optimizerHints, m.version)(m.pos)

      case _ =>
        m
    }
  }

  if (newMethods ne classDef.methods) {
    streams.log.info("Patching JSPI$.sjsir")
    val newClassDef = {
      import classDef._
      ClassDef(
        name,
        originalName,
        kind,
        jsClassCaptures,
        superClass,
        interfaces,
        jsSuperClass,
        jsNativeLoadSpec,
        fields,
        newMethods,
        jsConstructor,
        jsMethodProps,
        jsNativeMembers,
        topLevelExportDefs
      )(optimizerHints)(pos)
    }
    val baos = new java.io.ByteArrayOutputStream()
    org.scalajs.ir.Serializers.serialize(baos, newClassDef)
    java.nio.file.Files.write(jspiIRFile.toPath(), baos.toByteArray())
  }
}
