import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.marat.albion"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.marat.albion"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = providers.gradleProperty("albion.testRunner")
            .getOrElse("androidx.test.runner.AndroidJUnitRunner")
        ndk {
            abiFilters += providers.gradleProperty("albion.abis").getOrElse("arm64-v8a,x86_64")
                .split(",").map(String::trim)
        }
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=Release") }
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Для личной установки подписываем debug-ключом; замените на свой keystore для публикации.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    sourceSets.getByName("androidTest").assets.srcDir("schemas")

    androidResources {
        // mp3/xlsx уже сжаты — не тратим время на повторное сжатие.
        noCompress += listOf("mp3", "m4a", "aac", "ogg", "opus", "flac", "xlsx", "epub", "pdf")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// ---------------------------------------------------------------------------
// Материалы: collections/*.xlsx, audio/<колода>/*.mp3, grammar/*.md,
// listening/<трек>/{track.json, transcript.json, audio.mp3} (scripts/listening)
// копируются в assets/content при albion.bundleContent=true.
// ---------------------------------------------------------------------------
abstract class SyncAlbionContent @Inject constructor(
    private val fs: FileSystemOperations,
) : DefaultTask() {
    /** Корень с материалами; сам по себе не вход — входы ниже (только нужные файлы). */
    @get:Internal
    abstract val sourceDir: DirectoryProperty

    /** Только нужные файлы из collections/, audio/, grammar/, listening/ — от них зависит пересборка. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun sync() {
        val src = sourceDir.orNull?.asFile
        if (src != null && !File(src, "collections").isDirectory) {
            throw GradleException("Не найдена папка с материалами: ${File(src, "collections")}. Укажите albion.contentDir в gradle.properties.")
        }
        fs.sync {
            into(outputDir.dir("content"))
            if (src != null) {
                from(File(src, "collections")) {
                    include("*.xlsx")
                    exclude("~$*")
                    into("collections")
                }
                from(File(src, "audio")) {
                    include("**/*.mp3")
                    into("audio")
                }
                from(File(src, "grammar")) {
                    include("*.md")
                    into("grammar")
                }
                from(File(src, "library")) {
                    include("*.txt", "*.md", "*.epub", "*.pdf")
                    into("library")
                }
                from(File(src, "listening")) {
                    include("*/track.json", "*/transcript.json", "*/audio.*")
                    into("listening")
                }
            }
        }
    }
}

val bundleContent = providers.gradleProperty("albion.bundleContent").map(String::toBoolean).getOrElse(true)
val contentDir = rootProject.layout.projectDirectory.dir(
    providers.gradleProperty("albion.contentDir").getOrElse("..")
)

val syncAlbionContent = tasks.register<SyncAlbionContent>("syncAlbionContent") {
    group = "albion"
    description = "Копирует xlsx, озвучку, грамматику и треки с текстом в assets/content."
    if (bundleContent) {
        sourceDir.set(contentDir)
        sources.from(
            contentDir.dir("collections").asFileTree.matching { include("*.xlsx"); exclude("~$*") },
            contentDir.dir("audio").asFileTree.matching { include("**/*.mp3") },
            contentDir.dir("grammar").asFileTree.matching { include("*.md") },
            contentDir.dir("library").asFileTree.matching { include("*.txt", "*.md", "*.epub", "*.pdf") },
            contentDir.dir("listening").asFileTree.matching { include("*/track.json", "*/transcript.json", "*/audio.*") },
        )
    }
    outputDir.set(layout.buildDirectory.dir("generated/albionContent"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(syncAlbionContent, SyncAlbionContent::outputDir)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.window)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.haze)
    implementation(libs.haze.blur)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.media3.exoplayer)
    // Фоновое воспроизведение «Аудио с текстом»: уведомление и кнопки на экране блокировки.
    implementation(libs.media3.session)
    // Текст из PDF для библиотеки (Apache 2.0).
    implementation(libs.pdfbox.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.mlkit.translate)

    testImplementation(libs.junit)
    testImplementation(libs.kxml2)
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}
