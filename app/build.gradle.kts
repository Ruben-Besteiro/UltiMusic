// Importado en vez de escribir `java.util.Properties()` ahí abajo: dentro de un build.gradle.kts,
// `java` NO es el paquete java.* sino el accesor que Gradle añade al Project (la
// JavaPluginExtension), y tapa el nombre del paquete. Cualificarlo entero falla con
// «Unresolved reference 'util'».
import java.util.Properties
import java.io.IOException

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

/**
 * Clave de la YouTube Data API v3 (ver `data/remote/YouTubeStatsApi.kt`, las visitas del vídeo de
 * una canción), sacada de `local.properties` por el mismo motivo que [geniusToken]: que no acabe en
 * git.
 *
 * De momento es SOLO de desarrollo, igual que el respaldo de [geniusToken] en `GeniusTokenStore`
 * (ver ese comentario): todavía no existe el diálogo para que cada usuario ponga la suya propia, así
 * que esto es lo único que hay mientras tanto, y por eso en el bloque `debug` de abajo en vez de en
 * `defaultConfig`. Para ponerla: crear una clave de API en https://console.cloud.google.com con la
 * "YouTube Data API v3" habilitada, y añadir a `local.properties` la línea
 * `youtube.api_key=LA_CLAVE`.
 */
val youtubeApiKey: String = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}.getProperty("youtube.api_key").orEmpty()

// La traducción de letras (botón de arriba a la derecha) usa el endpoint no oficial de Google Translate

android {
    namespace = "com.untar.ultimusic"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.untar.ultimusic"
        minSdk = 24
        targetSdk = 37
        versionCode = 11
        versionName = "2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Vacío por defecto, y SOLO se rellena en debug (ver buildTypes). Así el token del
        // desarrollador no puede acabar dentro de un APK publicado: ver el comentario del bloque
        // `release` sobre por qué eso importa tanto.+
        buildConfigField("String", "GENIUS_TOKEN", "\"\"")
        // Mismo motivo que GENIUS_TOKEN de aquí arriba: vacío salvo en debug.
        buildConfigField("String", "YOUTUBE_API_KEY", "\"\"")
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
            buildConfigField("String", "YOUTUBE_API_KEY", "\"$youtubeApiKey\"")
        }
        release {
            // Y NUNCA aquí. Si el token viajara en el APK publicado, todo lo que hace
            // `GeniusTokenStore` sería papel mojado: `shouldOfferSetup` mira si hay token utilizable,
            // así que con uno incrustado el diálogo de configuración no le saldría a nadie y todos
            // los usuarios volverían a compartir la cuota (y la cuenta) del desarrollador, que es
            // justo el problema que se quería resolver. Al no declararse aquí, hereda la cadena
            // vacía de `defaultConfig` y el token ni siquiera existe dentro del binario.
            // R8: ofuscación/optimización del DEX (Play Console exige >25 % en cada categoría) y
            // recorte de recursos sin usar. Las librerías (Room, Coil, Media3, WorkManager, ML Kit)
            // traen sus propias reglas de consumidor.
            isMinifyEnabled = true
            isShrinkResources = true
            // Empaqueta en el AAB los símbolos de las librerías nativas de las dependencias, para
            // que Play Console pueda leer los fallos/ANR que ocurran en código nativo (quita la
            // advertencia de "símbolos de depuración").
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
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

/**
 * Traducción automática de los strings: antes de cada compilación lanza
 * `tools/translate_strings.py`, que compara `values/strings.xml` (español) con su última copia y
 * traduce a los otros 19 idiomas lo nuevo o cambiado con la API de Claude (ver la cabecera del
 * script). Si no ha cambiado nada termina enseguida sin conectarse a nada.
 *
 * Necesita Python con `pip install anthropic` y la clave en `local.properties`
 * (`anthropic.api_key=...`). Si falta algo, o no hay conexión, solo avisa y la compilación sigue:
 * los strings nuevos se verán en español en los demás idiomas hasta la siguiente compilación que
 * consiga traducirlos. Para saltarse la tarea: variable de entorno `ULTIMUSIC_SKIP_TRANSLATE=1`.
 *
 * No declara entradas ni salidas a propósito, así que se ejecuta siempre: si un intento falla, el
 * siguiente build tiene que volver a probar aunque strings.xml no haya cambiado desde entonces.
 */
val translateStrings by tasks.registering {
    group = "ultimusic"
    description = "Traduce los strings nuevos o cambiados de values/strings.xml a todos los idiomas"
    val script = rootProject.file("tools/translate_strings.py")
    val workDir = rootProject.projectDir
    doLast {
        if (System.getenv("ULTIMUSIC_SKIP_TRANSLATE") == "1") return@doLast
        try {
            val process = ProcessBuilder("python", script.absolutePath, "--gradle")
                .directory(workDir)
                .redirectErrorStream(true)
                .apply { environment()["PYTHONIOENCODING"] = "utf-8" }
                .start()
            process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { println(it) }
            process.waitFor()
        } catch (e: IOException) {
            logger.warn("[translateStrings] AVISO: no se ha podido ejecutar Python (${e.message}); no se traduce nada")
        }
    }
}

tasks.named("preBuild") { dependsOn(translateStrings) }

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
    // WorkManager: SOLO para el aviso anual de UltiMusic Recount la última semana de diciembre
    // (ver RecountReminderWorker). Hace falta una de verdad y no el patrón oportunista de
    // YouTubeStatsRefresh.isDue -"compruébalo la próxima vez que se abra la app"- porque la gracia
    // del aviso es justo llegar cuando el usuario NO está usando la app; comprobarlo al abrirla lo
    // dejaría redundante con el diálogo de arranque, que ya sale ahí mismo.
    implementation(libs.androidx.work.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}