package com.untar.ultimusic.util

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Saber si el sonido está saliendo por algo **pegado al oído**.
 *
 * Lo usa el amplificador de volumen (ver [com.untar.ultimusic.ui.PlayerViewModel]): subir la
 * ganancia por encima del 150 % solo se permite cuando el sonido sale al aire. Con auriculares
 * puestos, un volumen así va directo al oído y puede dañarlo de verdad, así que ahí el tope se
 * mantiene siempre.
 *
 * La pregunta no es «¿hay algo conectado?» sino «¿ese algo va en la oreja?». Un altavoz Bluetooth
 * en la otra punta del salón está tan conectado como unos auriculares, pero amplificarlo no tiene
 * ningún riesgo, así que ahí sí se deja quitar el tope. Distinguirlos es la parte interesante de
 * este archivo (ver [classifyBluetooth]).
 */
object Headphones {

    /** Qué hay al otro lado del sonido. */
    enum class Check {
        /**
         * Nada pegado al oído: altavoz del móvil (incluido el de llamadas, que no cuenta para
         * música — ver [WIRED_IN_EAR_TYPES]), altavoz Bluetooth, coche… Se puede amplificar.
         */
        NONE,

        /** Auriculares, cascos o audífonos. Hay que mantener el tope. */
        CONNECTED,

        /**
         * Hay un aparato Bluetooth pero **no se sabe cuál**, porque falta el permiso para
         * preguntárselo. Se trata como si fueran auriculares (más vale pasarse de prudente), pero se
         * distingue de [CONNECTED] para que la interfaz pueda ofrecer conceder el permiso en vez de
         * dar un «no» sin explicación.
         */
        UNKNOWN_BLUETOOTH
    }

    /**
     * Salidas por cable que van siempre en la oreja. Estas no tienen ninguna ambigüedad: si están,
     * hay auriculares.
     *
     * Es una lista de constantes de [AudioDeviceInfo] y no un rango, porque los valores no son
     * consecutivos: cada tipo de salida (altavoz, HDMI, jack, Bluetooth...) tiene su propio número.
     *
     * **Ojo:** a propósito no incluye `TYPE_BUILTIN_EARPIECE` (el altavocito de llamadas, el de
     * pegar la oreja al teléfono). Está en cualquier móvil con radio de telefonía, y
     * `getDevices(GET_DEVICES_OUTPUTS)` lo lista siempre como salida **disponible**, esté en uso o
     * no — no hay que estar llamando para que aparezca. La primera versión de esta lista lo incluía,
     * y el resultado era que [check] devolvía `CONNECTED` en **todo** móvil real pasara lo que
     * pasara, incluso sin nada conectado: por eso la casilla nunca funcionaba y desinstalar y
     * reinstalar la aplicación no cambiaba nada, porque el problema no era ningún estado guardado,
     * era que esta lista siempre iba a coincidir con algo que ya estaba ahí de fábrica.
     *
     * Quitarlo es seguro: la reproducción de música (`STREAM_MUSIC`) nunca se enruta sola a ese
     * altavoz — solo lo usa el sistema durante una llamada — así que no hay ningún escenario real en
     * el que el amplificador de UltiMusic esté sonando por él.
     */
    private val WIRED_IN_EAR_TYPES = intArrayOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,      // Jack con micrófono
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,   // Jack sin micrófono
        AudioDeviceInfo.TYPE_USB_HEADSET,        // Auriculares USB-C
        AudioDeviceInfo.TYPE_HEARING_AID         // Audífonos
    )

    /**
     * Los dos perfiles de audio de Bluetooth. **A2DP** es el de música: estéreo, buena calidad, solo
     * de ida. **SCO** es el de las llamadas y el asistente de voz: mono, calidad de teléfono, pero de
     * ida y vuelta porque lleva también el micrófono.
     *
     * Ninguno de los dos dice qué **forma** tiene el aparato: por A2DP se conectan igual unos
     * auriculares que un altavoz o la radio del coche. Por eso hace falta preguntar aparte.
     */
    private val BLUETOOTH_TYPES = intArrayOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    )

    /**
     * Clases de aparato Bluetooth que seguro que **no** van en la oreja.
     *
     * Es una lista de lo permitido y no de lo prohibido a propósito: cualquier clase que no esté
     * aquí se trata como auriculares. Ante la duda, se protege el oído.
     *
     * Agrupada por la clase **mayor** del aparato (ver [BluetoothClass.Device]), que es la parte
     * del número que dice la categoría general (ordenador, teléfono, equipo de audio/vídeo...):
     *
     * - **AUDIO_VIDEO** — solo los subtipos que son equipo de sonido de habitación, no algo que se
     *   pone uno encima: altavoz, equipo de coche, cadena hifi, decodificador, vídeo, monitor,
     *   pantalla con altavoz y videoconferencia. Quedan **fuera** a propósito
     *   `AUDIO_VIDEO_HEADPHONES`, `AUDIO_VIDEO_WEARABLE_HEADSET` y `AUDIO_VIDEO_HANDSFREE`
     *   (auriculares, en cualquiera de sus formas) y `AUDIO_VIDEO_PORTABLE_AUDIO` /
     *   `AUDIO_VIDEO_UNCATEGORIZED` (ambiguos: los usan tanto altavoces baratos como auriculares).
     * - **COMPUTER** — un ordenador (de sobremesa, portátil, servidor...) conectado por Bluetooth
     *   como salida de audio no va pegado a la oreja. Es el caso que faltaba y motivó ampliar esta
     *   lista: un PC emparejado como altavoz Bluetooth se anuncia con esta clase mayor, no con
     *   `AUDIO_VIDEO`, así que la lista original lo dejaba fuera y lo trataba como auriculares.
     * - **PHONE** — mismo razonamiento que un ordenador: otro teléfono actuando de altavoz
     *   Bluetooth no es un auricular.
     *
     * Quedan **fuera** de la lista las clases mayores `WEARABLE` (salvo la nota de abajo) y `TOY`:
     * cosas como `WEARABLE_GLASSES` o `WEARABLE_HELMET` sí pueden llevar altavoces junto al oído, y
     * no hay forma de distinguirlas del resto de esa clase (relojes, chaquetas...) sin arriesgarse.
     * Por el mismo motivo se deja fuera `COMPUTER_WEARABLE`, el único de los "ordenador" que podría
     * ir puesto en la cabeza.
     */
    private val NOT_IN_EAR_CLASSES = intArrayOf(
        // AUDIO_VIDEO: equipo de sonido de habitación, no algo que se pone uno encima.
        BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER,
        BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO,
        BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO,
        BluetoothClass.Device.AUDIO_VIDEO_SET_TOP_BOX,
        BluetoothClass.Device.AUDIO_VIDEO_VCR,
        BluetoothClass.Device.AUDIO_VIDEO_VIDEO_MONITOR,
        BluetoothClass.Device.AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER,
        BluetoothClass.Device.AUDIO_VIDEO_VIDEO_CONFERENCING,

        // COMPUTER: cualquier ordenador emparejado como salida de audio (menos el "wearable", ver
        // arriba). Este bloque es el que faltaba y causaba el bloqueo con un PC por Bluetooth.
        BluetoothClass.Device.COMPUTER_UNCATEGORIZED,
        BluetoothClass.Device.COMPUTER_DESKTOP,
        BluetoothClass.Device.COMPUTER_SERVER,
        BluetoothClass.Device.COMPUTER_LAPTOP,
        BluetoothClass.Device.COMPUTER_HANDHELD_PC_PDA,
        BluetoothClass.Device.COMPUTER_PALM_SIZE_PC_PDA,

        // PHONE: otro teléfono actuando de altavoz Bluetooth.
        BluetoothClass.Device.PHONE_UNCATEGORIZED,
        BluetoothClass.Device.PHONE_CELLULAR,
        BluetoothClass.Device.PHONE_CORDLESS,
        BluetoothClass.Device.PHONE_SMART,
        BluetoothClass.Device.PHONE_MODEM_OR_GATEWAY,
        BluetoothClass.Device.PHONE_ISDN
    )

    /**
     * Mira todas las salidas de audio activas y decide.
     *
     * Se pregunta con `getDevices(GET_DEVICES_OUTPUTS)`, que devuelve **todas** las salidas
     * disponibles ahora mismo, cada una con su `type`. Se usa esto y no el `isWiredHeadsetOn()` de
     * toda la vida porque aquel está obsoleto y, además, no se entera de los auriculares Bluetooth.
     *
     * Basta con **una** salida en la oreja para devolver [Check.CONNECTED]: da igual lo demás que
     * haya conectado.
     */
    fun check(context: Context): Check {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        if (outputs.any { it.type in WIRED_IN_EAR_TYPES }) return Check.CONNECTED

        val bluetoothOutputs = outputs.filter { it.type in BLUETOOTH_TYPES }
        if (bluetoothOutputs.isEmpty()) return Check.NONE

        if (!hasBluetoothPermission(context)) return Check.UNKNOWN_BLUETOOTH
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter ?: return Check.UNKNOWN_BLUETOOTH

        // Puede haber varios aparatos Bluetooth a la vez (unos cascos y el coche, por ejemplo). Unos
        // auriculares mandan sobre todo lo demás; si no los hay pero alguno no se ha podido
        // identificar, se responde que no se sabe.
        var anyUnknown = false
        for (output in bluetoothOutputs) {
            when (classifyBluetooth(adapter, output.address)) {
                Check.CONNECTED -> return Check.CONNECTED
                Check.UNKNOWN_BLUETOOTH -> anyUnknown = true
                Check.NONE -> {}
            }
        }
        return if (anyUnknown) Check.UNKNOWN_BLUETOOTH else Check.NONE
    }

    /**
     * Averigua qué forma tiene el aparato Bluetooth que está en [address] (su dirección MAC, que es
     * lo que [AudioDeviceInfo.getAddress] devuelve para las salidas Bluetooth).
     *
     * Todo aparato Bluetooth anuncia una **clase**: un número que dice qué es (auriculares, altavoz,
     * equipo del coche, teclado, teléfono…). Eso es [BluetoothClass], y es la única forma de saber
     * si lo que hay al otro lado va en la oreja, porque el perfil de audio (A2DP/SCO) no lo dice.
     *
     * El `try/catch` cubre dos cosas: una dirección que el sistema haya ocultado y una
     * `SecurityException` si el permiso se revocó entre la comprobación y esta llamada.
     */
    @SuppressLint("MissingPermission") // El permiso se comprueba en check(), antes de llegar aquí.
    private fun classifyBluetooth(adapter: BluetoothAdapter, address: String?): Check {
        if (address.isNullOrBlank() || !BluetoothAdapter.checkBluetoothAddress(address)) {
            return Check.UNKNOWN_BLUETOOTH
        }
        val deviceClass = try {
            adapter.getRemoteDevice(address).bluetoothClass
        } catch (e: Exception) {
            null
        } ?: return Check.UNKNOWN_BLUETOOTH

        return if (deviceClass.deviceClass in NOT_IN_EAR_CLASSES) Check.NONE else Check.CONNECTED
    }

    /**
     * ¿Se puede preguntar a los aparatos Bluetooth qué son?
     *
     * Desde Android 12 hace falta `BLUETOOTH_CONNECT`, que es un permiso **de tiempo de ejecución**:
     * hay que pedírselo al usuario con un diálogo, como el de almacenamiento. Antes de Android 12
     * bastaba con el permiso `BLUETOOTH` del manifest, que el sistema concede al instalar, así que
     * ahí no hay nada que comprobar.
     */
    fun hasBluetoothPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
}
