package com.untar.ultimusic.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Registro de cierres inesperados: al morir el proceso por una excepción no capturada, escribe un
 * archivo de texto plano en `files/crash-logs/` (almacenamiento interno, ver [AppStorage]) con la
 * traza, datos del dispositivo y de la app, y las últimas [LOGCAT_LINES] líneas del logcat. El
 * título es la fecha del cierre. No va en la copia automática de Android, pero sí en el `.zip` de
 * Ajustes > Copia de seguridad: es la forma de sacarlos del móvil.
 *
 * El logcat se lee con `logcat -d`: sin el permiso `READ_LOGS` (que solo se concede por adb) Android
 * entrega únicamente las líneas del propio proceso, que es justo lo que interesa.
 *
 * Todo va envuelto en `runCatching`: un fallo escribiendo el informe nunca debe tapar el cierre
 * original, y después siempre se delega en el handler anterior del sistema.
 */
object CrashLogger {

    private const val LOGCAT_LINES = 500

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeReport(app, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeReport(context: Context, thread: Thread, throwable: Throwable) {
        val now = Date()
        val name = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(now)
        val body = buildReport(context, now, thread, throwable)
        saveViaFile(File(context.filesDir, AppStorage.CRASH_LOGS), name, body)
    }

    private fun buildReport(context: Context, now: Date, thread: Thread, throwable: Throwable): String {
        val sb = StringBuilder()
        sb.appendLine("UltiMusic crash report")
        sb.appendLine("Fecha: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(now))
        sb.appendLine()

        sb.appendLine("=== App ===")
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION")
            val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            sb.appendLine("Paquete: ${context.packageName}")
            sb.appendLine("Versión: ${info.versionName} ($code)")
        }
        sb.appendLine("Proceso: ${currentProcessName()} (pid ${android.os.Process.myPid()})")
        sb.appendLine("Hilo: ${thread.name} (id ${thread.id})")
        sb.appendLine("Idioma: ${Locale.getDefault().toLanguageTag()}")
        sb.appendLine()

        sb.appendLine("=== Dispositivo ===")
        sb.appendLine("Modelo: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        sb.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Build: ${Build.DISPLAY}")
        sb.appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            sb.appendLine("RAM libre: ${mi.availMem / MB} MB de ${mi.totalMem / MB} MB (poca memoria: ${mi.lowMemory})")
        }
        val rt = Runtime.getRuntime()
        sb.appendLine("Heap Java: ${(rt.totalMemory() - rt.freeMemory()) / MB} MB usados de ${rt.maxMemory() / MB} MB")
        sb.appendLine("Heap nativo: ${Debug.getNativeHeapAllocatedSize() / MB} MB")
        sb.appendLine("Hilos vivos: ${Thread.getAllStackTraces().size}")
        sb.appendLine()

        sb.appendLine("=== Excepción ===")
        sb.appendLine(stackTraceOf(throwable))

        sb.appendLine("=== Logcat (últimas $LOGCAT_LINES líneas) ===")
        sb.appendLine(readLogcat())
        return sb.toString()
    }

    private fun stackTraceOf(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    private fun currentProcessName(): String =
        if (Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName() else "?"

    private fun readLogcat(): String = runCatching {
        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", LOGCAT_LINES.toString())
            .redirectErrorStream(true)
            .start()
        // logcat -d termina solo; el timeout es por si se cuelga y así no retiene la muerte del proceso.
        val out = StringBuilder()
        val t = Thread {
            runCatching { process.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }
        }
        t.start()
        t.join(TimeUnit.SECONDS.toMillis(3))
        if (t.isAlive) process.destroy()
        out.toString()
    }.getOrElse { "(no se pudo leer el logcat: $it)" }

    private fun saveViaFile(dir: File, name: String, body: String): Boolean = runCatching {
        if (!dir.isDirectory && !dir.mkdirs()) return false
        File(dir, "$name.txt").writeText(body, Charsets.UTF_8)
        true
    }.getOrDefault(false)

    private const val MB = 1024L * 1024L
}
