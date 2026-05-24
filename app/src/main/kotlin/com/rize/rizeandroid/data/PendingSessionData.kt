package com.rize.rizeandroid.data

import com.rize.rizeandroid.data.entity.BenchSessionDetails
import com.rize.rizeandroid.data.entity.CurlSessionDetails
import com.rize.rizeandroid.data.entity.RepBenchDetails
import com.rize.rizeandroid.data.entity.RepCurlDetails
import com.rize.rizeandroid.data.entity.RepSquatDetails
import com.rize.rizeandroid.data.entity.SessionRep
import com.rize.rizeandroid.data.entity.SquatSessionDetails
import com.rize.rizeandroid.data.entity.WorkoutSession

/**
 * Bundle con todo lo necesario para persistir una repetición completa. Se usa para
 * pasar datos entre CameraActivity y SummaryActivity (modo manual) y para insertar en una sola transacción.
 */
data class PendingRep(
    val rep: SessionRep,                   
    val squatDetails: RepSquatDetails? = null, 
    val curlDetails: RepCurlDetails? = null,
    val benchDetails: RepBenchDetails? = null
)

/**
 * Bundle con todo lo necesario para persistir una sesión completa. Se usa para pasar datos entre CameraActivity y SummaryActivity (modo manual) y para 
 * insertar en una sola transacción. Intentamos mantenerlo lo más plano posible para facilitar la serialización, aunque eso signifique repetir algunos datos 
 * (como los detalles de sesión para cada repetición).
 */
data class PendingSessionData(
    val session: WorkoutSession,
    val squatDetails: SquatSessionDetails? = null,
    val curlDetails: CurlSessionDetails? = null,
    val benchDetails: BenchSessionDetails? = null,
    val reps: List<PendingRep>,
    val attemptedRepCount: Int = 0
)
