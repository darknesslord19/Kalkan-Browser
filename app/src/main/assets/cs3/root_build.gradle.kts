import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        // CloudStream Gradle eklentisi: .cs3 dosyalarını üretir
        classpath("com.github.recloudstream:gradle:-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) =
    extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: BaseExtension.() -> Unit) =
    extensions.getByName<BaseExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        // GitHub Actions'ta GITHUB_REPOSITORY otomatik olarak "kullanici/depo" olur
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "kullanici/depo")
    }

    android {
        namespace = "@@PKG@@"

        defaultConfig {
            minSdk = 21
            compileSdkVersion(35)
            targetSdk = 35
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
        }

        tasks.withType<KotlinJvmCompile> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_11)
                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions"
                )
            }
        }
    }

    dependencies {
        val cloudstream by configurations
        val implementation by configurations

        // CloudStream sınıflarının taslakları
        cloudstream("com.lagradost:cloudstream3:pre-release")

        implementation(kotlin("stdlib"))
        implementation("com.github.Blatzar:NiceHttp:0.4.18")
        implementation("org.jsoup:jsoup:1.22.1")
        // jsoup 1.22 tür açıklamaları için gerekli
        implementation("org.jspecify:jspecify:1.0.0")
        // Jackson'ı 2.13.1'in üstüne çıkarma: eski cihazlarda bozulur
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
        // Rhino uygulamada hazır gelir; yalnızca derleme için gerekli (Rhino kullanan referans eklentiler)
        val compileOnly by configurations
        compileOnly("org.mozilla:rhino:1.7.14")
        compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    }
}

task<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
