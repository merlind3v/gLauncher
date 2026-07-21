package app.glauncher.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Consulta por HTTP (GET /scheduler/estado) el estado del scheduler de
 * galatea-backend. Es de solo lectura, sin conexión persistente: se llama
 * periódicamente desde SchedulerPollingClient, igual que el bot de Telegram
 * consulta a galatea-backend por HTTP en vez de mantener un socket abierto.
 */
suspend fun fetchEstadoScheduler(serverUrl: String, apiKey: String): EstadoScheduler? =
    withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(serverUrl.trimEnd('/') + "/scheduler/estado")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("x-api-key", apiKey)
                connectTimeout = 5000
                readTimeout = 5000
            }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "GET /scheduler/estado -> HTTP ${connection.responseCode}")
                return@withContext null
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parseEstado(JSONObject(body))
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo consultar el estado del scheduler", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

/**
 * Manda una acción (confirmar / completar / extender) por POST, igual que
 * los botones inline del bot de Telegram. Devuelve el estado ya actualizado
 * que responde el backend, o null si falló.
 */
suspend fun postAccionScheduler(
    serverUrl: String,
    apiKey: String,
    accion: String,
    agendaId: String,
    minutos: Int? = null,
): EstadoScheduler? = withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    try {
        val url = URL(serverUrl.trimEnd('/') + "/scheduler/$accion")
        val body = JSONObject().apply {
            put("agendaId", agendaId)
            if (minutos != null) put("minutos", minutos)
        }

        connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 5000
            readTimeout = 5000
            doOutput = true
        }
        connection.outputStream.use { it.write(body.toString().toByteArray()) }

        if (connection.responseCode != HttpURLConnection.HTTP_CREATED &&
            connection.responseCode != HttpURLConnection.HTTP_OK
        ) {
            Log.w(TAG, "POST /scheduler/$accion -> HTTP ${connection.responseCode}")
            return@withContext null
        }

        val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
        parseEstado(JSONObject(responseBody))
    } catch (e: Exception) {
        Log.w(TAG, "No se pudo mandar la acción \"$accion\" del scheduler", e)
        null
    } finally {
        connection?.disconnect()
    }
}

private fun parseEstado(json: JSONObject): EstadoScheduler {
    val actividadJson = json.optJSONObject("actividad")
    val actividad = actividadJson?.let {
        ActividadActual(
            id = it.getString("id"),
            nombre = it.getString("nombre"),
            origen = if (it.isNull("origen")) null else it.optString("origen"),
            estado = it.getString("estado"),
            horaInicio = it.getString("horaInicio"),
            horaFin = it.getString("horaFin"),
            segundosRestantes = it.optInt("segundosRestantes", 0),
        )
    }

    val acciones = mutableListOf<String>()
    json.optJSONArray("acciones")?.let { array ->
        for (i in 0 until array.length()) acciones.add(array.getString(i))
    }

    return EstadoScheduler(
        actualizadoEn = json.getString("actualizadoEn"),
        hayActividad = json.getBoolean("hayActividad"),
        actividad = actividad,
        acciones = acciones,
    )
}

private const val TAG = "SchedulerHttpClient"
