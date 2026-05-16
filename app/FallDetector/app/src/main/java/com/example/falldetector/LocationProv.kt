package com.example.falldetector

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object EmergencyLocationProvider {

    private const val LOCATION_TIMEOUT_MS = 7000L

    fun hasLocationPermission(context: Context): Boolean {
        val fineGranted =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        val coarseGranted =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        return fineGranted || coarseGranted
    }

    fun getEmergencyLocation(
        context: Context,
        onResult: (Location?) -> Unit
    ) {
        if (!hasLocationPermission(context)) {
            onResult(null)
            return
        }

        val locationManager =
            context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val bestLastKnownLocation = getBestLastKnownLocation(context, locationManager)

        val providers = getUsableProviders(context, locationManager)

        if (providers.isEmpty()) {
            onResult(bestLastKnownLocation)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getCurrentLocationApi30Plus(
                context = context,
                locationManager = locationManager,
                providers = providers,
                fallbackLocation = bestLastKnownLocation,
                onResult = onResult
            )
        } else {
            getCurrentLocationLegacy(
                locationManager = locationManager,
                provider = providers.first(),
                fallbackLocation = bestLastKnownLocation,
                onResult = onResult
            )
        }
    }

    private fun getUsableProviders(
        context: Context,
        locationManager: LocationManager
    ): List<String> {
        val fineGranted =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        val coarseGranted =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        val result = mutableListOf<String>()

        if (
            fineGranted &&
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        ) {
            result.add(LocationManager.GPS_PROVIDER)
        }

        if (
            coarseGranted &&
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        ) {
            result.add(LocationManager.NETWORK_PROVIDER)
        }

        return result
    }

    private fun getBestLastKnownLocation(
        context: Context,
        locationManager: LocationManager
    ): Location? {
        if (!hasLocationPermission(context)) return null

        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        )

        val locations = providers.mapNotNull { provider ->
            try {
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.getLastKnownLocation(provider)
                } else {
                    null
                }
            } catch (e: SecurityException) {
                null
            } catch (e: Exception) {
                null
            }
        }

        if (locations.isEmpty()) return null

        return locations.maxByOrNull { it.time }
    }

    private fun getCurrentLocationApi30Plus(
        context: Context,
        locationManager: LocationManager,
        providers: List<String>,
        fallbackLocation: Location?,
        onResult: (Location?) -> Unit
    ) {
        val completed = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        val cancellationSignal = CancellationSignal()

        val timeoutRunnable = Runnable {
            if (completed.compareAndSet(false, true)) {
                cancellationSignal.cancel()
                onResult(fallbackLocation)
            }
        }

        handler.postDelayed(timeoutRunnable, LOCATION_TIMEOUT_MS)

        providers.forEach { provider ->
            try {
                locationManager.getCurrentLocation(
                    provider,
                    cancellationSignal,
                    ContextCompat.getMainExecutor(context)
                ) { location ->
                    if (location != null && completed.compareAndSet(false, true)) {
                        handler.removeCallbacks(timeoutRunnable)
                        onResult(location)
                    }
                }
            } catch (e: SecurityException) {
                if (completed.compareAndSet(false, true)) {
                    handler.removeCallbacks(timeoutRunnable)
                    onResult(fallbackLocation)
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun getCurrentLocationLegacy(
        locationManager: LocationManager,
        provider: String,
        fallbackLocation: Location?,
        onResult: (Location?) -> Unit
    ) {
        val completed = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())

        lateinit var listener: LocationListener

        val timeoutRunnable = Runnable {
            if (completed.compareAndSet(false, true)) {
                try {
                    locationManager.removeUpdates(listener)
                } catch (_: Exception) {
                }

                onResult(fallbackLocation)
            }
        }

        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (completed.compareAndSet(false, true)) {
                    handler.removeCallbacks(timeoutRunnable)

                    try {
                        locationManager.removeUpdates(this)
                    } catch (_: Exception) {
                    }

                    onResult(location)
                }
            }

            override fun onProviderEnabled(provider: String) {}

            override fun onProviderDisabled(provider: String) {}

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }

        handler.postDelayed(timeoutRunnable, LOCATION_TIMEOUT_MS)

        try {
            locationManager.requestSingleUpdate(
                provider,
                listener,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            handler.removeCallbacks(timeoutRunnable)
            onResult(fallbackLocation)
        } catch (e: Exception) {
            handler.removeCallbacks(timeoutRunnable)
            onResult(fallbackLocation)
        }
    }

    fun buildMapsLink(location: Location): String {
        val latitude = String.format(Locale.US, "%.6f", location.latitude)
        val longitude = String.format(Locale.US, "%.6f", location.longitude)

        return "https://maps.google.com/?q=$latitude,$longitude"
    }
}