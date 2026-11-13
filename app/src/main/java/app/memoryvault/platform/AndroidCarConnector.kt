package app.memoryvault.platform

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarInternalErrorException
import android.car.hardware.property.CarPropertyManager
import android.car.hardware.property.PropertyNotAvailableAndRetryException
import android.car.hardware.property.PropertyNotAvailableException
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/** Public optional android.car library; SDK stubs are never packaged as an implementation. */
internal class AndroidCarConnector(private val context: Context) : CarConnector {
    override fun connect(listener: CarConnectionListener): AutoCloseable {
        val active = AtomicBoolean(true)
        val car = Car.createCar(context, Handler(Looper.getMainLooper()), Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { car, ready ->
            if (!active.get()) return@createCar
            if (!ready) {
                listener.onDisconnected()
                return@createCar
            }
            try {
                val ux = car.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
                    ?: throw CarReadFailure(DriveReadinessReason.UNSUPPORTED)
                val properties = car.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
                    ?: throw CarReadFailure(DriveReadinessReason.UNSUPPORTED)
                listener.onReady(AndroidCarAccess(ux, properties))
            } catch (failure: RuntimeException) {
                listener.onFailure(readFailureReason(failure))
            } catch (_: LinkageError) {
                listener.onFailure(DriveReadinessReason.UNSUPPORTED)
            }
        } ?: throw CarReadFailure(DriveReadinessReason.UNSUPPORTED)
        return AutoCloseable {
            if (active.getAndSet(false)) car.disconnect()
        }
    }
}

private class AndroidCarAccess(
    private val ux: CarUxRestrictionsManager,
    private val properties: CarPropertyManager,
) : CarAccess {
    private val callbacks = mutableMapOf<VehicleListener, CarPropertyManager.CarPropertyEventCallback>()

    override fun isSupported(property: DriveProperty) = properties.getCarPropertyConfig(property.id) != null

    override fun registerUxListener(listener: (UxReading) -> Unit) {
        ux.registerListener { reading -> listener(reading.toReading()) }
    }

    override fun unregisterUxListener() = ux.unregisterListener()

    override fun registerPropertyListener(property: DriveProperty, listener: VehicleListener): Boolean {
        val callback = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                if (value.propertyId == property.id && value.areaId == 0) {
                    try {
                        listener.onChange(value.toReading())
                    } catch (_: RuntimeException) {
                        listener.onError()
                    } catch (_: LinkageError) {
                        listener.onError()
                    }
                }
            }

            override fun onErrorEvent(propertyId: Int, areaId: Int) {
                if (propertyId == property.id && areaId == 0) listener.onError()
            }
        }
        callbacks[listener] = callback // Retain even when registration partially succeeds then throws.
        val rate = if (property == DriveProperty.GEAR) CarPropertyManager.SENSOR_RATE_ONCHANGE
        else CarPropertyManager.SENSOR_RATE_NORMAL
        return properties.registerCallback(callback, property.id, rate)
    }

    override fun unregisterPropertyListener(listener: VehicleListener) {
        callbacks.remove(listener)?.let { properties.unregisterCallback(it) }
    }

    override fun readUx() = ux.currentCarUxRestrictions?.toReading()

    override fun readProperty(property: DriveProperty): VehicleReading? = try {
        properties.getProperty<Any>(property.id, 0)?.let {
            if (it.propertyId != property.id || it.areaId != 0) {
                throw CarReadFailure(DriveReadinessReason.UNSUPPORTED)
            }
            it.toReading()
        }
    } catch (_: PropertyNotAvailableException) {
        throw CarReadFailure(DriveReadinessReason.PROPERTY_UNAVAILABLE)
    } catch (_: PropertyNotAvailableAndRetryException) {
        throw CarReadFailure(DriveReadinessReason.PROPERTY_UNAVAILABLE)
    } catch (_: CarInternalErrorException) {
        throw CarReadFailure(DriveReadinessReason.PROPERTY_ERROR)
    }
}

private val DriveProperty.id: Int
    get() = when (this) {
        DriveProperty.GEAR -> VehiclePropertyIds.GEAR_SELECTION
        DriveProperty.SPEED -> VehiclePropertyIds.PERF_VEHICLE_SPEED
    }

private fun CarUxRestrictions.toReading() = UxReading(isRequiresDistractionOptimization, activeRestrictions)

private fun CarPropertyValue<*>.toReading(): VehicleReading {
    val status = when (status) {
        CarPropertyValue.STATUS_AVAILABLE -> VehicleStatus.AVAILABLE
        CarPropertyValue.STATUS_UNAVAILABLE -> VehicleStatus.UNAVAILABLE
        else -> VehicleStatus.ERROR
    }
    return VehicleReading(if (status == VehicleStatus.AVAILABLE) value else null, status, timestamp)
}
