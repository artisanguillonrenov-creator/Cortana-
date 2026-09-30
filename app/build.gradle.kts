import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "io.github.artisanguillonrenov.cortana"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.artisanguillonrenov.cortana"
        minSdk = 30
        targetSdk = 36
        versionCode = 7
        versionName = "2.0.0-rc6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            // No META-INF/version-control-info.textproto: the release must rebuild byte for byte from
            // the source archive (which has no .git), and a build of uncommitted changes would record a
            // revision it was not built from (D-20260928-064). Provenance: RELEASE.md §7 and the archive.
            vcsInfo { include = false }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
        // ./gradlew testDebugUnitTest -Dcortana.regenerateDocs=true regenerates the generated docs (TOOL_CAPABILITIES.md).
        unitTests.all {
            it.systemProperty("cortana.regenerateDocs", System.getProperty("cortana.regenerateDocs") ?: "false")
            // ~330 Robolectric tests (real app container, SQLite, workers) in one JVM exhausted the
            // default 512 MB heap (phase 32): a bounded heap and a fresh JVM every 60 test classes.
            it.maxHeapSize = "2g"
            it.forkEvery = 60
        }
    }

    // No Play "dependency info" block in the signing block: it is encrypted with a fresh random key at
    // every build, so two builds of the same sources would never be byte-identical (D-20260928-064).
    // Cortana is not distributed through Play; the SBOM (./gradlew cortanaSbom) lists the dependencies.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // §2.1 — an arm64-v8a APK for the Galaxy Tab A11 plus a universal fallback.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = true
        }
    }

    // Exported Room schemas are shipped in debug assets so MigrationTestHelper (Robolectric) can read them.
    sourceSets {
        getByName("debug").assets.srcDir("$projectDir/schemas")
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*", "/META-INF/NOTICE*")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(project(":contracts"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime.ktx)
    implementation(libs.biometric)
    implementation(libs.androidx.fragment.ktx) // align the old fragment pulled by biometric with activity 1.11
    implementation(libs.documentfile)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.jgit)
    implementation(libs.tesseract4android) // on-device OCR, Apache-2.0, no telemetry (phase 16, D-20260927-036)
    implementation(libs.jsoup) // HTML parser for the browser executor (MIT, no dependencies; phase 19)
    // PDF read/create/edit (phase 24, Apache-2.0). BouncyCastle 1.72 is excluded: it is only used for
    // PDF encryption, which Cortana refuses (encrypted PDFs are reported, never opened).
    implementation(libs.pdfbox.android) { exclude(group = "org.bouncycastle") }

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.room.testing)
    testImplementation(project(":worker")) // in-process worker for the pairing/job integration gate
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.greenmail) // real IMAP/SMTP server: the e-mail connector fixture (test only)
    // Chat Workspace UI tests under Robolectric (phone, tablet, composer, accessibility semantics).
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit) // aligns ui-test-junit4 on the versions already verified for androidTest
    testImplementation(libs.androidx.test.runner)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

// Release discipline (phase 30): a release build must carry a versionCode higher than every
// published one (or be the very release recorded last), under the same signing certificate —
// and be signed by the Cortana release key: never silently fall back to the debug key.
val verifyReleaseVersion by tasks.registering {
    val history = rootProject.file("release/released.json")
    val releaseKey = keystoreProps.getProperty("storeFile")?.let { rootProject.file(it) }
    val code = android.defaultConfig.versionCode ?: 0
    val name = android.defaultConfig.versionName
    inputs.file(history)
    doLast {
        @Suppress("UNCHECKED_CAST")
        val json = groovy.json.JsonSlurper().parse(history) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val releases = json["releases"] as List<Map<String, Any?>>
        val last = releases.maxByOrNull { (it["versionCode"] as Number).toInt() }!!
        val lastCode = (last["versionCode"] as Number).toInt()
        require(code > lastCode || (code == lastCode && name == last["versionName"])) {
            "versionCode $code ($name) n'est pas supérieur à la dernière version publiée $lastCode (${last["versionName"]})"
        }
        require(releases.all { it["signingCertSha256"] == json["signingCertSha256"] }) { "historique incohérent : certificat de signature différent" }
        require(releaseKey?.isFile == true) { "clé de publication absente (keystore/keystore.properties) : une version publiée n'est jamais signée par la clé de debug" }
    }
}
// Android's regex engine is ICU4C, not the JVM's the unit tests use (D-20260928-066: 2.0.0-rc1
// crashed at startup on a pattern only the JVM accepts). Every pattern is compiled with ICU4C
// before a release, and the list compiled on the device by the instrumented test must be current.
val androidRegexCheck by tasks.registering(Exec::class) {
    commandLine("python3", rootProject.file("tools/check_android_regex.py").path, "--check-asset")
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(verifyReleaseVersion, androidRegexCheck) }
