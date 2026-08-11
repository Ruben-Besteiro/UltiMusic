// Importado en vez de escribir `java.util.Properties()` ahí abajo: dentro de un build.gradle.kts,
// `java` NO es el paquete java.* sino el accesor que Gradle añade al Project (la
// JavaPluginExtension), y tapa el nombre del paquete. Cualificarlo entero falla con
// «Unresolved reference 'util'».
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

/**
 * Token de la API de Genius (ver `data/remote/GeniusApi.kt`), sacado de `local.properties` para que
 * NO acabe en git: ese archivo está en el .gitignore desde que lo generó Android Studio.
 *
 * Si no está puesto, queda como cadena vacía y el enriquecimiento con Genius se desactiva solo
 * (`GeniusApi.isConfigured`): la app compila y el autorrelleno sigue funcionando con iTunes, solo
 * que sin productores, sin datos de la canción original y sin carátula específica de canción.
 *
 * Para ponerlo: registrar una aplicación en https://genius.com/api-clients, copiar su "Client
 * Access Token" y añadir a `local.properties` la línea `genius.token=EL_TOKEN`.
 */
val geniusToken: String = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}.getProperty("genius.token").orEmpty()

android {
    namespace = "com.untar.ultimusic"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.untar.ultimusic"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Vacío por defecto, y SOLO se rellena en debug (ver buildTypes). Así el token del
        // desarrollador no puede acabar dentro de un APK publicado: ver el comentario del bloque
        // `release` sobre por qué eso importa tanto.
        buildConfigField("String", "GENIUS_TOKEN", "\"\"")
    }

    // Desde AGP 8 la clase BuildConfig no se genera si no se pide expresamente; hace falta para
    // GENIUS_TOKEN.
    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            // El token de `local.properties` entra ÚNICAMENTE aquí, como comodidad para quien
            // desarrolla: así no hay que pasar por el diálogo de configuración en cada instalación
            // limpia mientras se prueba la aplicación.
            buildConfigField("String", "GENIUS_TOKEN", "\"$geniusToken\"")
        }
        release {
            // Y NUNCA aquí. Si el token viajara en el APK publicado, todo lo que hace
            // `GeniusTokenStore` sería papel mojado: `shouldOfferSetup` mira si hay token utilizable,
            // así que con uno incrustado el diálogo de configuración no le saldría a nadie y todos
            // los usuarios volverían a compartir la cuota (y la cuenta) del desarrollador, que es
            // justo el problema que se quería resolver. Al no declararse aquí, hereda la cadena
            // vacía de `defaultConfig` y el token ni siquiera existe dentro del binario.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.coil)
    implementation(libs.androidx.palette)
    // Custom Tabs, para mandar al usuario a genius.com/api-clients a sacar su token (ver
    // GeniusTokenDialogFragment). Hace falta la librería y no vale un WebView: Google bloquea sus
    // inicios de sesión OAuth dentro de WebViews embebidos, y muchos usuarios entran en Genius con
    // Google.
    implementation(libs.androidx.browser)
    // Envoltorio del IFrame Player API de YouTube: expone el reproductor oficial como una View de
    // Android y nos da callbacks de posición/estado en Kotlin (ver VideoScreenController).
    // Mínimo la 13.0.0: desde julio de 2025 YouTube exige que el embebido se identifique con un
    // `origin`, y las versiones 12.x (que no lo mandan) fallan con el error 152.
    implementation(libs.youtube.player)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // Identificador de idioma de ML Kit: modelo pequeño (unos cientos de KB) que se descarga solo
    // la primera vez a través de Google Play Services y a partir de ahí funciona en el dispositivo,
    // sin mandar la letra a ningún servidor ni necesitar un proyecto de Firebase (ver LanguageDetector).
    implementation(libs.mlkit.language.id)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}