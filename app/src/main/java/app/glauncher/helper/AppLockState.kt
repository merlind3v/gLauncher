package app.glauncher.helper

/**
 * Estado en memoria (proceso único) de qué app protegida está autenticada ahora mismo.
 * Vive solo mientras el proceso viva: si el proceso muere, se pierde y exige re-autenticación.
 */
object AppLockState {

    @Volatile
    private var unlockedKey: String? = null

    @Volatile
    private var pendingKey: String? = null

    @Synchronized
    fun markUnlocked(key: String) {
        unlockedKey = key
        pendingKey = null
    }

    @Synchronized
    fun isUnlocked(key: String): Boolean = unlockedKey == key

    @Synchronized
    fun clear() {
        unlockedKey = null
        pendingKey = null
    }

    /**
     * Devuelve true la primera vez que se llama para una key dada (mientras siga pendiente),
     * false en llamadas repetidas — evita relanzar la pantalla de bloqueo en bucle.
     */
    @Synchronized
    fun tryMarkPending(key: String): Boolean {
        if (pendingKey == key) return false
        pendingKey = key
        return true
    }
}
