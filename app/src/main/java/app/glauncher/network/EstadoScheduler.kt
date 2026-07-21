package app.glauncher.network

/**
 * Espeja el JSON que manda galatea-backend por el evento "estado" del WebSocket
 * (ver EstadoSchedulerDto en el backend).
 */
data class ActividadActual(
    val id: String,
    val nombre: String,
    val origen: String?,
    val estado: String,
    val horaInicio: String,
    val horaFin: String,
    val segundosRestantes: Int,
)

data class EstadoScheduler(
    val actualizadoEn: String,
    val hayActividad: Boolean,
    val actividad: ActividadActual?,
    val acciones: List<String>,
)
