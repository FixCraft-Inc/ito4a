import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import java.net.URI
import groovy.json.JsonOutput
import java.util.concurrent.TimeUnit

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val providerFeatureEnabled = providers.gradleProperty("itoProviders").orElse("false").get().let {
    require(it == "true" || it == "false") { "itoProviders must be true or false" }
    it == "true"
}

val providerClientId = providers.gradleProperty("itoProviderClientId").orElse("").get()
val providerCallback = providers.gradleProperty("itoProviderCallback").orElse("").get()
require(providerFeatureEnabled || (providerClientId.isEmpty() && providerCallback.isEmpty())) { "Registration requires enabled providers" }
require(providerClientId.isEmpty() == providerCallback.isEmpty()) { "Incomplete provider registration" }
require(providerClientId.isEmpty() || Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}").matches(providerClientId))
val callbackUri = providerCallback.takeIf { it.isNotEmpty() }?.let(URI::create)
require(callbackUri == null || (callbackUri.scheme == "https" && callbackUri.rawUserInfo == null &&
    callbackUri.port == -1 && callbackUri.rawQuery == null && callbackUri.rawFragment == null &&
    callbackUri.rawPath == "/ito/oauth/callback" && callbackUri.host != null &&
    Regex("[a-z0-9.-]+").matches(callbackUri.host))) { "Invalid provider callback" }

// A distribution may suggest one provider. The user still chooses it and sees
// its origin, issuer and role before signing in. Source builds suggest none.
val suggestedProvider = providers.gradleProperty("itoSuggestedProvider").orElse("").get()
val suggestedIssuer = providers.gradleProperty("itoSuggestedIssuer").orElse("").get()
val canonicalOrigin = Regex("https://[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+")
require(suggestedProvider.isEmpty() || (providerFeatureEnabled && canonicalOrigin.matches(suggestedProvider))) {
    "A suggested provider is a canonical HTTPS origin and requires enabled providers"
}
require(suggestedIssuer.isEmpty() || (suggestedProvider.isNotEmpty() && callbackUri != null && canonicalOrigin.matches(suggestedIssuer))) {
    "A suggested issuer requires a suggested provider and a registered callback"
}

android {
    namespace = "com.fixcraft.ito"
    compileSdk {
        version = release(36) { minorApiLevel = 1 }
    }
    defaultConfig {
        applicationId = "com.fixcraft.ito"
        buildConfigField("String", "PROVIDER_CLIENT_ID", "\"$providerClientId\"")
        buildConfigField("String", "PROVIDER_CALLBACK", "\"$providerCallback\"")
        buildConfigField("String", "SUGGESTED_PROVIDER", "\"$suggestedProvider\"")
        buildConfigField("String", "SUGGESTED_ISSUER", "\"$suggestedIssuer\"")
        manifestPlaceholders["providerCallbackEnabled"] = (callbackUri != null).toString()
        manifestPlaceholders["providerCallbackHost"] = callbackUri?.host ?: "unregistered.invalid"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["notAnnotation"] = "com.fixcraft.ito.transport.RequiresI1Fixture,com.fixcraft.ito.call.RequiresI4bFixture"
        ndk { abiFilters += (providers.gradleProperty("itoAbi").orNull ?: "arm64-v8a,x86_64").split(',') }
        externalNativeBuild {
            ndkBuild {
                arguments += "ITO_YUME_SHARED_ROOT=${layout.buildDirectory.dir("yume-shared-abi").get().asFile.absolutePath}"
                arguments += "ITO_CALL_CORE_ROOT=${layout.buildDirectory.dir("ito-call-core").get().asFile.absolutePath}"
                arguments += "APP_STL=c++_shared"
            }
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    sourceSets {
        getByName("main") {
            kotlin.srcDir(if (providerFeatureEnabled) "src/providers/java" else "src/minimal/java")
            res.srcDir(if (providerFeatureEnabled) "src/providers/res" else "src/minimal/res")
        }
        if (providerFeatureEnabled) {
            getByName("debug") {
                manifest.srcFile("src/providersDebug/AndroidManifest.xml")
                res.srcDir("src/providersDebug/res")
            }
            getByName("release").manifest.srcFile("src/providers/AndroidManifest.xml")
            getByName("test").kotlin.srcDir("src/testProviders/java")
            getByName("androidTest").kotlin.srcDir("src/androidTestProviders/java")
        } else {
            getByName("test").kotlin.srcDir("src/testMinimal/java")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    ndkVersion = "28.2.13676358"
    externalNativeBuild { ndkBuild { path = file("src/main/jni/Android.mk") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    if (providerFeatureEnabled) implementation(project(":provider-library"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.zxing.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

val yumeRoot = providers.gradleProperty("itoYumeRoot").map(::file).getOrElse(rootProject.file("../../yume"))
// The portable call core is its own repository, a sibling checkout as YUME is.
val coreRoot = providers.gradleProperty("itoCoreRoot").map(::file).getOrElse(rootProject.file("../../ito"))
val coreBuilder = coreRoot.resolve("tools/build_call_core.sh")
val unverifiedSource = providers.gradleProperty("itoUnverifiedSource").orNull == "true"
// Provenance records this commit, so a new one rebuilds even when no source changed.
val coreHead = runCatching {
    val process = ProcessBuilder("git", "-C", coreRoot.absolutePath, "rev-parse", "--verify", "HEAD")
        .redirectError(ProcessBuilder.Redirect.DISCARD).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    if (process.waitFor() == 0) output else "unknown"
}.getOrDefault("unknown")
val verifyYumeSource by tasks.registering {
    doLast {
        if (!unverifiedSource) {
            val process = ProcessBuilder("bash", yumeRoot.resolve("tools/build_android_shared_abi.sh").absolutePath, "--verify-source-only").inheritIO().start()
            check(process.waitFor() == 0) { "YUME verification failed" }
        }
    }
}
val verifyCoreSource by tasks.registering {
    doLast {
        if (!unverifiedSource) {
            val process = ProcessBuilder("bash", coreBuilder.absolutePath, "--verify-source-only").inheritIO().start()
            check(process.waitFor() == 0) { "Call core verification failed" }
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyYumeSource, verifyCoreSource) }
tasks.matching { it.name.matches(Regex("pre.*ReleaseBuild")) }.configureEach {
    doFirst { check(!unverifiedSource) { "Release requires verified YUME source" } }
}
val buildYume by tasks.registering(Exec::class) {
    dependsOn(verifyYumeSource)
    val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: "/nonexistent-sdk"
    val abis = providers.gradleProperty("itoAbi").orNull ?: "arm64-v8a,x86_64"
    inputs.file(rootProject.file("tools/build_native.sh"))
    inputs.dir(yumeRoot.resolve("include"))
    inputs.dir(yumeRoot.resolve("src"))
    inputs.dir(yumeRoot.resolve("cmake"))
    inputs.dir(yumeRoot.resolve("patches"))
    inputs.dir(yumeRoot.resolve("third_party/nlohmann_json"))
    inputs.file(yumeRoot.resolve("CMakeLists.txt"))
    inputs.file(yumeRoot.resolve("vcpkg.json"))
    inputs.file(yumeRoot.resolve("config/dependencies.json"))
    inputs.file(yumeRoot.resolve("scripts/ensure-openssl.sh"))
    inputs.file(yumeRoot.resolve("tools/build_android_shared_abi.sh"))
    inputs.property("abis", abis)
    inputs.property("unverified", unverifiedSource)
    outputs.dir(layout.buildDirectory.dir("yume-shared-abi"))
    val args = mutableListOf("bash", rootProject.file("tools/build_native.sh").absolutePath, "--yume", yumeRoot.absolutePath,
        "--ndk", "$sdk/ndk/28.2.13676358", "--vcpkg", System.getenv("VCPKG_ROOT") ?: rootProject.file("../../vcpkg").absolutePath,
        "--out", layout.buildDirectory.dir("yume-shared-abi").get().asFile.absolutePath, "--jobs", "4")
    abis.split(',').forEach { args += listOf("--abi", it) }
    if (unverifiedSource) args += "--unverified-source"
    commandLine(args)
}
// The portable call core and its stock OpenSSL, built from the pinned archive
// for each ABI. The core's tools/build_call_core.sh checks the archive hash and
// the library's exported surface and writes provenance.json.
val openSslArchive = providers.gradleProperty("itoOpenSslArchive")
    .getOrElse("${System.getProperty("user.home")}/.cache/ito/openssl-3.5.9.tar.gz")
val buildCallCore by tasks.registering(Exec::class) {
    val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: "/nonexistent-sdk"
    val abis = providers.gradleProperty("itoAbi").orNull ?: "arm64-v8a,x86_64"
    dependsOn(verifyCoreSource)
    inputs.file(coreBuilder)
    inputs.file(coreRoot.resolve("tools/security-component.json"))
    inputs.dir(coreRoot.resolve("call/src"))
    inputs.dir(coreRoot.resolve("call/include"))
    inputs.file(coreRoot.resolve("call/CMakeLists.txt"))
    inputs.property("coreHead", coreHead)
    inputs.property("unverified", unverifiedSource)
    inputs.file(openSslArchive)
    inputs.property("abis", abis)
    outputs.dir(layout.buildDirectory.dir("ito-call-core"))
    val args = mutableListOf("bash", coreBuilder.absolutePath,
        "--ndk", "$sdk/ndk/28.2.13676358", "--openssl-archive", openSslArchive,
        "--out", layout.buildDirectory.dir("ito-call-core").get().asFile.absolutePath, "--jobs", "4")
    abis.split(',').forEach { args += listOf("--abi", it) }
    if (unverifiedSource) args += "--unverified-source"
    commandLine(args)
}
afterEvaluate {
    tasks.matching { !it.name.contains("Clean") && (it.name.startsWith("externalNativeBuild") || it.name.contains("NdkBuild")) }
        .configureEach { dependsOn(buildYume, buildCallCore) }
}

abstract class GenerateItoDependencyNotices : DefaultTask() {
    @get:InputFile abstract val catalogFile: RegularFileProperty
    @get:InputFile abstract val generatorFile: RegularFileProperty
    @get:InputFile abstract val nativeProvenance: RegularFileProperty
    @get:InputFile abstract val callProvenance: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourceFiles: ConfigurableFileCollection
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE)
    abstract val runtimeFiles: ConfigurableFileCollection
    @get:Input abstract val runtimeCoordinates: MapProperty<String, String>
    @get:Input abstract val providersEnabled: Property<Boolean>
    @get:Input abstract val providerVersion: Property<String>
    @get:Input abstract val providerSourceSha256: Property<String>
    @get:Input abstract val stripTool: Property<String>
    @get:Internal abstract val checkoutDirectory: DirectoryProperty
    @get:Internal abstract val nativeSourceDirectory: DirectoryProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction fun generate() {
        val coordinates = runtimeCoordinates.get()
        val resolved = runtimeFiles.files.sortedBy { coordinates.getValue(it.absolutePath) }
            .map { mapOf("coordinate" to coordinates.getValue(it.absolutePath), "path" to it.absolutePath) }
        val inventory = temporaryDir.resolve("runtime.json")
        val metadata = mutableMapOf<String, Any>("artifacts" to resolved, "providersEnabled" to providersEnabled.get())
        if (providersEnabled.get()) metadata["providerIdentity"] = mapOf("version" to providerVersion.get(), "source_sha256" to providerSourceSha256.get())
        inventory.writeText(JsonOutput.toJson(metadata) + "\n")
        val generatorLog = temporaryDir.resolve("generator.log")
        val command = mutableListOf("python3", generatorFile.get().asFile.absolutePath,
            "--root", checkoutDirectory.get().asFile.absolutePath,
            "--yume-root", nativeSourceDirectory.get().asFile.absolutePath,
            "--catalog", catalogFile.get().asFile.absolutePath,
            "--artifacts", inventory.absolutePath,
            "--native-provenance", nativeProvenance.get().asFile.absolutePath,
            "--call-provenance", callProvenance.get().asFile.absolutePath,
            "--output", outputDirectory.get().asFile.absolutePath)
        stripTool.get().takeIf { it.isNotEmpty() }?.let { command += listOf("--strip-tool", it) }
        val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(generatorLog).start()
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            error("Dependency notice generation timed out")
        }
        logger.lifecycle(generatorLog.readText())
        check(process.exitValue() == 0) { "Dependency notice generation failed" }
    }
}

androidComponents.onVariants { variant ->
    // Debug keeps native symbols for diagnosis. Release packages stripped libraries,
    // and the notice generator hashes the same stripped bytes.
    val debugBuild = variant.buildType == "debug"
    if (debugBuild) variant.packaging.jniLibs.keepDebugSymbols.add("**/*.so")
    val runtime = configurations.named("${variant.name}RuntimeClasspath")
    val resolved = providers.provider { runtime.get().incoming.artifactView {
        componentFilter { it is ModuleComponentIdentifier }
    }.artifacts.artifacts }
    val providerVariant = if (variant.buildType == "debug") "debug" else "release"
    val providerAar = if (providerFeatureEnabled) project(":provider-library").layout.buildDirectory.file("outputs/aar/provider-library-$providerVariant.aar") else null
    val notices = tasks.register<GenerateItoDependencyNotices>(
        "generate${variant.name.replaceFirstChar(Char::uppercase)}DependencyNotices") {
        dependsOn(buildYume, buildCallCore)
        if (providerFeatureEnabled) dependsOn(":provider-library:bundle${providerVariant.replaceFirstChar(Char::uppercase)}Aar")
        catalogFile.set(rootProject.layout.projectDirectory.file("notices/catalog.json"))
        generatorFile.set(rootProject.layout.projectDirectory.file("tools/generate_notices.py"))
        nativeProvenance.set(layout.buildDirectory.file("yume-shared-abi/provenance.json"))
        callProvenance.set(layout.buildDirectory.file("ito-call-core/provenance.json"))
        checkoutDirectory.set(rootProject.layout.projectDirectory)
        nativeSourceDirectory.set(yumeRoot)
        sourceFiles.from(rootProject.fileTree("notices/texts"), rootProject.file("LICENSE"),
            rootProject.file("THIRD_PARTY_LICENSES.md"),
            fileTree(System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: "/nonexistent-sdk") {
                include("ndk/28.2.13676358/source.properties")
                include("ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/**/libc++_shared.so")
            },
            fileTree(layout.buildDirectory.dir("yume-shared-abi")) { include("*/libyume.so") },
            fileTree(layout.buildDirectory.dir("ito-call-core")) { include("*/libitocall.so") },
            yumeRoot.resolve("config/dependencies.json"), yumeRoot.resolve("vcpkg.json"),
            yumeRoot.resolve("scripts/ensure-openssl.sh"),
            yumeRoot.resolve("tools/build_android_shared_abi.sh"),
            yumeRoot.resolve("third_party/nlohmann_json/PROVENANCE.md"))
        runtimeFiles.from(resolved.map { entries -> entries.map { it.file } })
        if (providerAar != null) runtimeFiles.from(providerAar)
        stripTool.set(if (debugBuild) "" else (System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: "/nonexistent-sdk") +
            "/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip")
        this.providersEnabled.set(providerFeatureEnabled)
        providerVersion.set(if (providerFeatureEnabled) providers.gradleProperty("yumeProviderVersion") else providers.provider { "" })
        providerSourceSha256.set(if (providerFeatureEnabled) providers.gradleProperty("yumeProviderSourceSha256") else providers.provider { "" })
        runtimeCoordinates.set(resolved.map { entries ->
            val pairs = (entries.map {
                val id = it.id.componentIdentifier as ModuleComponentIdentifier
                it.file.absolutePath to "${id.group}:${id.module}:${id.version}"
            } + (providerAar?.let { listOf(it.get().asFile.absolutePath to "com.fixcraft.yume:provider-library:${providers.gradleProperty("yumeProviderVersion").get()}") } ?: emptyList())).distinct()
            check(pairs.map { it.first }.distinct().size == pairs.size) { "Ambiguous runtime artifact path" }
            pairs.toMap()
        })
        outputDirectory.set(layout.buildDirectory.dir("generated/dependency-notices/${variant.name}"))
    }
    variant.sources.assets?.addGeneratedSourceDirectory(notices, GenerateItoDependencyNotices::outputDirectory)
}
