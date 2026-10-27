package com.lpkm.signal

import java.util.concurrent.CopyOnWriteArrayList

object TelemetryBus {
    data class Link(val status: Status, val message: String) {
        enum class Status { DISCONNECTED, CONNECTING, CONNECTED, ERROR }
    }

    data class Vehicles(
        val choices: List<VehicleChoice> = emptyList(),
        val currentId: String? = null,
        val currentName: String? = null,
        val loaded: Boolean = false,
    )

    private val telemetryListeners = CopyOnWriteArrayList<(Telemetry) -> Unit>()
    private val linkListeners = CopyOnWriteArrayList<(Link) -> Unit>()
    private val vehicleListeners = CopyOnWriteArrayList<(Vehicles) -> Unit>()
    private val vehicleFailureListeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    var lastTelemetry: Telemetry? = null
        private set

    @Volatile
    var link: Link = Link(Link.Status.DISCONNECTED, "")
        private set

    @Volatile
    var vehicles: Vehicles = Vehicles()
        private set

    fun publish(sample: Telemetry) {
        lastTelemetry = sample
        for (listener in telemetryListeners) listener(sample)
    }

    fun setLink(status: Link.Status, message: String) {
        val next = Link(status, message)
        link = next
        for (listener in linkListeners) listener(next)
    }

    fun addTelemetryListener(listener: (Telemetry) -> Unit) {
        telemetryListeners.add(listener)
    }

    fun removeTelemetryListener(listener: (Telemetry) -> Unit) {
        telemetryListeners.remove(listener)
    }

    fun addLinkListener(listener: (Link) -> Unit) {
        linkListeners.add(listener)
    }

    fun removeLinkListener(listener: (Link) -> Unit) {
        linkListeners.remove(listener)
    }

    fun publishVehicles(next: Vehicles) {
        vehicles = next
        for (listener in vehicleListeners) listener(next)
    }

    fun publishVehicleFailure() {
        for (listener in vehicleFailureListeners) listener()
    }

    fun addVehicleListener(listener: (Vehicles) -> Unit) {
        vehicleListeners.add(listener)
    }

    fun removeVehicleListener(listener: (Vehicles) -> Unit) {
        vehicleListeners.remove(listener)
    }

    fun addVehicleFailureListener(listener: () -> Unit) {
        vehicleFailureListeners.add(listener)
    }

    fun removeVehicleFailureListener(listener: () -> Unit) {
        vehicleFailureListeners.remove(listener)
    }
}
