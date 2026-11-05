package de.jenz.nahtlos.automotive

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * True, solange das Auto Ablenkung vermeiden will. Kopplung und NWC bleiben dann aus.
 * Fehlt der Dienst, gilt der Bildschirm als stehend: das Manifest hat kein
 * distractionOptimized, die Headunit blockiert den Start während der Fahrt trotzdem.
 */
@Composable
fun rememberDriving(): State<Boolean> {
    val context = LocalContext.current
    val driving = remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val gate = ParkedGate(context.applicationContext) { driving.value = it }
        onDispose { gate.close() }
    }
    return driving
}

private class ParkedGate(
    context: Context,
    private val onChange: (Boolean) -> Unit,
) {
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null
    private val listener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { restrictions ->
        onChange(requiresPark(restrictions))
    }

    init {
        runCatching {
            Car.createCar(
                context,
                null,
                Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT,
            ) { created, ready ->
                if (!ready || created == null) return@createCar
                car = created
                val next = created.getCarManager(CarUxRestrictionsManager::class.java)
                manager = next
                next.registerListener(listener)
                onChange(requiresPark(next.currentCarUxRestrictions))
            }
        }
    }

    fun close() {
        runCatching { manager?.unregisterListener() }
        runCatching { car?.disconnect() }
        manager = null
        car = null
    }

    private fun requiresPark(restrictions: CarUxRestrictions?): Boolean =
        restrictions?.isRequiresDistractionOptimization == true
}
