import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/** What the packages, the launcher and the settings footer all say. */
val appVersion: String = providers.gradleProperty("oto.version").orNull
    ?.removePrefix("v")
    ?.takeIf { it.isNotBlank() }
    ?: "1.0"

/** Oto is built for Windows x64 only. */
val hostIsWindows = System.getProperty("os.name").contains("Windows", ignoreCase = true)
val targetOs: String = "windows"

val hostArch = System.getProperty("os.arch").lowercase()
val isArm64 = hostArch == "aarch64" || hostArch == "arm64"

// Windows installer metadata requires MAJOR.MINOR.BUILD even though the app's public version is
// intentionally displayed without a patch number (1.7 rather than 1.7.0).
// The build number is the desktop version code; it also keeps "-beta1" out of the numeric installer version.
val desktopVersionCode = 29
val nativePackageVersion = appVersion.substringBefore('-').split('.').take(2).joinToString(".") + ".$desktopVersionCode"

// FFmpeg decodes audio; see DesktopAudioDecoder.
val javacppVersion = "1.5.12"
val ffmpegVersion = "7.1.1-$javacppVersion"
val nativeClassifier = "windows-x86_64"
private fun localProperty(name: String): String = rootProject.file("local.properties")
    .takeIf { it.isFile }
    ?.readLines()
    ?.firstOrNull { it.startsWith("$name=") }
    ?.substringAfter('=')
    ?.trim()
    ?.takeIf { it.isNotBlank() }
    ?: System.getenv(name).orEmpty().trim()

val desktopModuleIndexUrl = localProperty("MODULE_INDEX_URL")


plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

dependencies {

    implementation(project(":shared"))
    // Its own copy of the player and pages; the phone and TV keep :sharedUi.
    implementation(project(":desktopUi"))
    // BotGuard, for the PoTokens YouTube's web clients need: the phone runs it in an Android
    // WebView, the desktop in JavaFX's (WebKit). Per-platform jars carry the natives.
    val javafxClassifier = "win"
    listOf("base", "graphics", "controls", "media", "web").forEach { module ->
        implementation("org.openjfx:javafx-$module:21.0.10:$javafxClassifier")
    }

    // The shared player loads artwork through Coil, as the phone does.
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")

    // Automix runs the same two models the Android build does.
    implementation("com.microsoft.onnxruntime:onnxruntime:1.29.0")

    // Decoding, resampling and demuxing for the audio engine.
    implementation("org.bytedeco:javacpp:$javacppVersion")
    implementation("org.bytedeco:javacpp:$javacppVersion:$nativeClassifier")
    implementation("org.bytedeco:ffmpeg:$ffmpegVersion")
    implementation("org.bytedeco:ffmpeg:$ffmpegVersion:$nativeClassifier")

    // The same backdrop blur the Android build uses for its floating bars.
    // On Windows, 1.7.2 keeps this Compose Desktop 1.10.3 app on the matching UI/text/animation
    // runtime. Preserve Linux's existing dependency graph exactly as it was.
    val desktopHazeVersion = if (targetOs == "windows") "1.7.2" else "1.7.3"
    implementation("dev.chrisbanes.haze:haze:$desktopHazeVersion")
    implementation("dev.chrisbanes.haze:haze-materials:$desktopHazeVersion")

    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.components.resources)
    implementation(compose.materialIconsExtended)
    // The Skiko runtime rides in on this, and Skiko is per-platform.
    implementation(compose.desktop.windows_x64)

    implementation("io.ktor:ktor-client-core:3.0.3")
    implementation("io.ktor:ktor-client-cio:3.0.3")
    implementation("io.ktor:ktor-client-content-negotiation:3.0.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.slf4j:slf4j-nop:2.0.16")

    // DPAPI, for the settings kept protected on disk.
    implementation("net.java.dev.jna:jna-platform:5.19.1")

    // Same URL deobfuscation helper used by Android's StreamResolver.
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.3")

    // JVM equivalent of Android's QuickJS module host.
    implementation("org.graalvm.polyglot:polyglot:24.1.2")
    implementation("org.graalvm.polyglot:js:24.1.2")
    implementation("org.graalvm.js:js-language:24.1.2")

    testImplementation(kotlin("test"))
}

tasks.withType<Test> {
    systemProperty("java.awt.headless", "true")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

/** Builds the Automix analyser from the same C++ the Android app compiles. */
// Per target, because both write the library under the same name and a Windows cross-build
// otherwise left a `.dll` where the next Linux run looked for its `.so`.
val analysisNativeDir = layout.buildDirectory.dir("native/$targetOs")

/** The tiny Windows frame bridge is independent of the optional analyser and SMTC libraries. */
val windowNativeDir = layout.buildDirectory.dir("native-window/$targetOs")

/**
 * Whether this build is producing the analyser for a platform that is not the one running the
 * build.
 */
val crossBuildingForWindows = targetOs == "windows" && !hostIsWindows

/**
 * An executable's absolute path, from the PATH this build was invoked with.
 *
 * Resolved here rather than left to the child process. Gradle runs external
 * commands from the daemon, whose own PATH is whatever it was started with, so
 * a daemon reused from a shell that had no cmake cannot find the one the
 * current shell does — it fails with a bare "No such file or directory" while
 * `cmake --version` works fine in the terminal beside it.
 */
/**
 * Whether a tool on the PATH can actually be started.
 *
 * A PATH entry can be a wrapper script whose own interpreter is gone; it is a file, it is
 * executable, and it fails only at exec time — which surfaces as a build failure rather than as the
 * missing-tool path this build already handles.
 */
fun runs(tool: File): Boolean = runCatching {
    val process = ProcessBuilder(tool.absolutePath, "--version")
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    if (!process.waitFor(10, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        false
    } else {
        process.exitValue() == 0
    }
}.getOrDefault(false)

fun findOnPath(tool: String): File? {
    // Windows keeps the extension on the file and off the command line, so a bare name matches
    // nothing on disk: PATHEXT is the list a shell would have tried.
    val names = if (!hostIsWindows) {
        listOf(tool)
    } else {
        val extensions = System.getenv("PATHEXT").orEmpty()
            .ifBlank { ".COM;.EXE;.BAT;.CMD" }
            .split(';')
            .filter(String::isNotBlank)
        listOf(tool) + extensions.map { tool + it.lowercase() }
    }
    return System.getenv("PATH").orEmpty()
        .split(File.pathSeparator)
        .filter(String::isNotBlank)
        .flatMap { directory -> names.map { File(directory, it) } }
        // A PATHEXT match is executable by definition, and Windows' own canExecute is unreliable.
        .firstOrNull { it.isFile && (hostIsWindows || it.canExecute()) && runs(it) }
}

fun cmakeBinary(): String = findOnPath("cmake")?.absolutePath ?: "cmake"

val buildAnalysisNative by tasks.registering {
    val cmakeSource = project.file("native")
    val outputDir = analysisNativeDir.get().asFile
    val crossing = crossBuildingForWindows
    val toolchain = project.file("native/mingw-w64.cmake")
    inputs.dir(rootProject.file("native/analyzer"))
    inputs.dir(rootProject.file("native/jni"))
    inputs.dir(project.file("native"))
    inputs.property("target", targetOs)
    outputs.dir(outputDir)
    onlyIf {
        val cmake = findOnPath("cmake")
        if (cmake == null) {
            logger.lifecycle(
                "no usable cmake — building without the Automix analyser. If cmake is installed, " +
                    "a Gradle daemon started before it was on PATH may be in use: ./gradlew --stop",
            )
        }
        val compilerName = if (crossing) "x86_64-w64-mingw32-g++" else "g++"
        val compiler = targetOs != "windows" || findOnPath(compilerName) != null
        val ninja = targetOs != "windows" || findOnPath("ninja") != null
        if (cmake != null && (!compiler || !ninja)) {
            logger.lifecycle(
                "$compilerName or Ninja not found — the Windows build will have no Automix analyser. " +
                    "Install a MinGW C++ compiler and Ninja, then rebuild.",
            )
        }
        cmake != null && compiler && ninja
    }
    doLast {
        val javaHome = System.getProperty("java.home")
        val compiler = if (targetOs == "windows") {
            findOnPath(if (crossing) "x86_64-w64-mingw32-g++" else "g++")
                ?: error("the Windows C++ compiler disappeared while building the analyser")
        } else {
            null
        }
        val ninja = if (targetOs == "windows") {
            findOnPath("ninja") ?: error("Ninja disappeared while building the analyser")
        } else {
            null
        }
        providers.exec {
            commandLine(
                buildList {
                    addAll(listOf(cmakeBinary(), "-S", cmakeSource.absolutePath, "-B", outputDir.absolutePath))
                    if (targetOs == "windows") {
                        // The host may not have Visual Studio/nmake. Select the MinGW toolchain we
                        // checked above and discard any generator cached by an earlier configure.
                        addAll(
                            listOf(
                                "--fresh",
                                "-G", "Ninja",
                                "-DCMAKE_MAKE_PROGRAM=${ninja!!.absolutePath}",
                                "-DCMAKE_CXX_COMPILER=${compiler!!.absolutePath}",
                            ),
                        )
                    }
                    add("-DCMAKE_BUILD_TYPE=Release")
                    if (crossing) add("-DCMAKE_TOOLCHAIN_FILE=${toolchain.absolutePath}")
                },
            )
            environment("JAVA_HOME", javaHome)
        }.result.get().assertNormalExitValue()
        providers.exec {
            commandLine(cmakeBinary(), "--build", outputDir.absolutePath, "--config", "Release", "-j", "4")
            environment("JAVA_HOME", javaHome)
        }.result.get().assertNormalExitValue()
    }
}

/**
 * Builds the Win32/DWM frame bridge even when the much larger analyser build is skipped during a
 * desktop UI run. It is intentionally absent from Linux builds, whose window manager remains the
 * sole owner of decoration and behaviour.
 */
val buildWindowNative by tasks.registering {
    val cmakeSource = project.file("native/window")
    val outputDir = windowNativeDir.get().asFile
    val crossing = crossBuildingForWindows
    val toolchain = project.file("native/mingw-w64.cmake")
    inputs.dir(cmakeSource)
    inputs.property("target", targetOs)
    outputs.dir(outputDir)
    onlyIf {
        if (targetOs != "windows") return@onlyIf false
        val cmake = findOnPath("cmake")
        val compiler = findOnPath(if (crossing) "x86_64-w64-mingw32-g++" else "g++")
        val ninja = findOnPath("ninja")
        if (cmake == null || compiler == null || ninja == null) {
            logger.lifecycle(
                "cmake, Ninja, or a MinGW C++ compiler is unavailable — the Windows build will " +
                    "fall back to Compose's frameless window behaviour.",
            )
        }
        cmake != null && compiler != null && ninja != null
    }
    doLast {
        val javaHome = System.getProperty("java.home")
        val compiler = findOnPath(if (crossing) "x86_64-w64-mingw32-g++" else "g++")
            ?: error("MinGW C++ compiler disappeared while building the Windows frame")
        val ninja = findOnPath("ninja")
            ?: error("Ninja disappeared while building the Windows frame")
        providers.exec {
            commandLine(
                buildList {
                    addAll(
                        listOf(
                            cmakeBinary(),
                            "-S", cmakeSource.absolutePath,
                            "-B", outputDir.absolutePath,
                            "-G", "Ninja",
                            "-DCMAKE_MAKE_PROGRAM=${ninja.absolutePath}",
                            "-DCMAKE_CXX_COMPILER=${compiler.absolutePath}",
                            "-DCMAKE_BUILD_TYPE=Release",
                        ),
                    )
                    if (crossing) add("-DCMAKE_TOOLCHAIN_FILE=${toolchain.absolutePath}")
                },
            )
            environment("JAVA_HOME", javaHome)
        }.result.get().assertNormalExitValue()
        providers.exec {
            commandLine(cmakeBinary(), "--build", outputDir.absolutePath, "-j", "4")
            environment("JAVA_HOME", javaHome)
        }.result.get().assertNormalExitValue()
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(buildAnalysisNative, buildWindowNative)
    from(analysisNativeDir) {
        include("*.so", "*.dll", "*.dylib")
        into("native")
    }
    from(windowNativeDir) {
        include("*.dll")
        into("native")
    }
    // The Automix models.
    from(project.file("models")) {
        include("*.onnx")
        into("models")
    }
}

// Kotlin 2.4 no longer contributes this JVM target's compiler output to Gradle's plain `jar`
// task in this project. Compose's dev runner and the portable launcher both use that jar, so add
// the output explicitly instead of producing a resources-only application archive.
tasks.named<Jar>("jar") {
    val kotlinClasses = tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin")
    dependsOn(kotlinClasses)
    from(kotlinClasses.flatMap { it.destinationDirectory })
}

/*
 * Slimming the shipped libraries.
 *
 * ONNX Runtime's jar carries its native library for every OS; only the Windows x64 one is kept.
 * The extended Material icon set is ~37 MB of icons, of which the app uses a handful; only the
 * icon classes the app's own code actually references are kept, found by reading its bytecode.
 */
val slimDir = layout.buildDirectory.dir("slim-libs")

fun isOnnx(file: File) = file.name.startsWith("onnxruntime-")
fun isIconsExtended(file: File) = file.name.startsWith("material-icons-extended")
fun isOwnModule(file: File) = file.name.startsWith("desktopUi") || file.name.startsWith("shared")

val slimOnnx by tasks.registering(Jar::class) {
    val runtime = configurations.named("runtimeClasspath")
    archiveFileName.set("onnxruntime-windows-x64.jar")
    destinationDirectory.set(layout.buildDirectory.dir("slim-work"))
    from({ runtime.get().files.filter(::isOnnx).map { zipTree(it) } }) {
        exclude("ai/onnxruntime/native/linux-*/**")
        exclude("ai/onnxruntime/native/osx-*/**")
        exclude("ai/onnxruntime/native/win-arm64/**")
        exclude("ai/onnxruntime/native/android*/**")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

val slimIcons by tasks.registering(Jar::class) {
    val runtime = configurations.named("runtimeClasspath")
    val appClasses = tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin")
        .flatMap { it.destinationDirectory }
    dependsOn("compileKotlin")
    archiveFileName.set("material-icons-used.jar")
    destinationDirectory.set(layout.buildDirectory.dir("slim-work"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    val iconRef = Regex("androidx/compose/material/icons/[A-Za-z/]+?/[A-Za-z0-9_]+Kt")
    val used = mutableSetOf<String>()
    doFirst {
        fun scan(bytes: ByteArray) {
            iconRef.findAll(String(bytes, Charsets.ISO_8859_1)).forEach { used += it.value + ".class" }
        }
        appClasses.get().asFile.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }
            .forEach { scan(it.readBytes()) }
        runtime.get().files.filter(::isOwnModule).forEach { jar ->
            ZipFile(jar).use { zip ->
                zip.entries().asSequence().filter { it.name.endsWith(".class") }
                    .forEach { entry -> zip.getInputStream(entry).use { scan(it.readBytes()) } }
            }
        }
        logger.lifecycle("slimIcons: keeping ${used.size} icon classes")
    }
    from({ runtime.get().files.filter(::isIconsExtended).map { zipTree(it) } }) {
        include { element -> element.isDirectory || element.path in used || element.path.startsWith("META-INF") }
    }
}

val slimLibs by tasks.registering(Sync::class) {
    val runtime = configurations.named("runtimeClasspath")
    from({ runtime.get().files.filterNot { isOnnx(it) || isIconsExtended(it) } })
    from(slimOnnx)
    from(slimIcons)
    into(slimDir)
}

val composeMainClass = "app.oto.desktop.MainKt"

compose.desktop {
    application {
        javaHome = javaToolchains.launcherFor(java.toolchain).map {
            it.metadata.installationPath.asFile.absolutePath
        }.get()
        mainClass = composeMainClass
        // The app jar plus the slimmed libraries above, instead of the raw runtime classpath.
        disableDefaultConfiguration()
        mainJar.set(tasks.named<Jar>("jar").flatMap { it.archiveFile })
        fromFiles(tasks.named<Jar>("jar").flatMap { it.archiveFile })
        fromFiles(project.fileTree(slimDir) { include("*.jar") })
        dependsOn("jar", "slimLibs")
        jvmArgs("-Xmx512m")
        jvmArgs("-XX:+UseG1GC", "-XX:G1PeriodicGCInterval=20000", "-XX:G1PeriodicGCSystemLoadThreshold=0")
        // Give heap back to Windows after a collection instead of keeping it reserved, and share
        // identical strings (titles, artist names, JSON keys) rather than holding copies.
        jvmArgs("-XX:MinHeapFreeRatio=10", "-XX:MaxHeapFreeRatio=25", "-XX:+UseStringDeduplication")
        // FFmpeg's bindings (JavaCPP) refuse every new decoder once the whole process passes four
        // times the heap limit — 2 GB here — whatever the machine has. Hitting it after a long
        // session of music videos is what left Oto unable to play anything until restarted.
        jvmArgs("-Dorg.bytedeco.javacpp.maxPhysicalBytes=0")
        jvmArgs("-Doto.version=$appVersion")
        if (desktopModuleIndexUrl.isNotBlank()) {
            jvmArgs("-Doto.module.index=$desktopModuleIndexUrl")
        }

        buildTypes.release.proguard {
            isEnabled.set(false)
            obfuscate.set(false)
            optimize.set(false)
            configurationFiles.from(project.file("packaging/proguard-rules.pro"))
        }

        nativeDistributions {
            targetFormats(TargetFormat.Exe)
            packageName = "Oto"
            packageVersion = nativePackageVersion
            description = "Oto"
            vendor = "Oto"
            copyright = "Oto"

            modules(
                "java.base",
                "java.desktop",
                "java.instrument",
                "java.logging",
                "java.management",
                "java.naming",
                "java.net.http",
                "java.prefs",
                "java.scripting",
                "java.sql",
                "java.xml",
                "jdk.crypto.ec",
                "jdk.dynalink",
                // JavaFX WebEngine returns JavaScript objects through netscape.javascript.JSObject.
                "jdk.jsobject",
                "jdk.localedata",
                "jdk.security.auth",
                "jdk.unsupported",
                "jdk.zipfs",
            )

            windows {
                iconFile.set(project.file("packaging/icons/AppIcon.ico"))
                upgradeUuid = "95d48c8f-821c-4593-98a0-a27da3cbfcbe"
                menuGroup = "Oto"
                shortcut = true
                dirChooser = false
                perUserInstall = true
            }
        }
    }

}

// Kept at the package the sources import from, independent of the root project's name.
compose.resources {
    packageOfResClass = "app.oto.desktop.resources"
}
