import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.lucasbrandaocabral.regua"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.lucasbrandaocabral.regua"
        minSdk = 26
        targetSdk = 35
        // No CI cada build ganha um número maior, então o APK novo instala por cima do antigo.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.0.$versionCode"
    }

    signingConfigs {
        // Chave sem segredo, versionada de propósito: todo APK gerado (local ou no CI) sai com a
        // mesma assinatura e atualiza por cima do anterior sem perder a calibração.
        // Para distribuir o app para outras pessoas, gere uma chave própria e guarde-a fora do repo.
        create("shared") {
            storeFile = file("regua.keystore")
            storePassword = "regua-android"
            keyAlias = "regua"
            keyPassword = "regua-android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
