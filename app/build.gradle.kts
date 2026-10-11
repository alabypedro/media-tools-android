import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Assinatura do APK de release: lida de keystore.properties (fora do git).
// Sem esse arquivo o build de release sai sem assinatura e o debug continua funcionando.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.alabypedro.mediatools"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.alabypedro.mediatools"
        minSdk = 29 // Android 10: salvar em Downloads pelo MediaStore sem permissão de armazenamento
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Repositório onde o release.py publica as versões e o app procura atualizações
        buildConfigField("String", "UPDATE_REPO", "\"alabypedro/media-tools-android\"")
    }

    // Um APK por arquitetura: o Python + FFmpeg embutidos pesam ~40 MB por ABI.
    // Celulares atuais usam arm64-v8a.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Sem R8: o Jackson usado pela youtubedl-android depende de reflexão.
            isMinifyEnabled = false
            if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        // O Python e o FFmpeg são executados a partir de nativeLibraryDir: as .so
        // precisam ser extraídas na instalação.
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    val ytdl = "0.18.1"
    implementation("io.github.junkfood02.youtubedl-android:library:$ytdl")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$ytdl")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Conversor
    implementation("androidx.documentfile:documentfile:1.0.1")       // "Adicionar pasta" e "Salvar em"
    implementation("androidx.exifinterface:exifinterface:1.3.7")     // manter EXIF nas imagens
    implementation("androidx.heifwriter:heifwriter:1.0.0")           // gravar HEIC
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")          // PDF achatado e PDF → texto
    implementation("org.commonmark:commonmark:0.24.0")               // Markdown → PDF/documentos
    implementation("org.apache.commons:commons-compress:1.27.1")     // 7z, tar, bz2, xz
    implementation("org.tukaani:xz:1.10")

    testImplementation("junit:junit:4.13.2")
    // android.jar só traz stubs do org.json: os testes JVM precisam da implementação real
    testImplementation("org.json:json:20240303")

    // testes no aparelho/emulador: passam cada amostra por todos os conversores (gradlew connectedDebugAndroidTest)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
