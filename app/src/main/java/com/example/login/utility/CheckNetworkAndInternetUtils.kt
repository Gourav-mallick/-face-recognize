package com.example.login.utility

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.net.HttpURLConnection
import java.net.URL
import kotlin.run

object CheckNetworkAndInternetUtils {

    /**
     * ✅ Check if any network (Wi-Fi, Mobile, etc.) is available.
     */
    @Suppress("DEPRECATION")
    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            val networkInfo = connectivityManager.activeNetworkInfo
            networkInfo != null && networkInfo.isConnected
        }
    }

    /**
     * ✅ Check if the device has real Internet access.
     * we called from a background thread or coroutine.
     */
    fun hasInternetAccess(): Boolean {
        return try {
            val url = URL("https://clients3.google.com/generate_204")
            (url.openConnection() as HttpURLConnection).run {
                connectTimeout = 1500
                readTimeout = 1500
                connect()
                val success = responseCode == 204
                disconnect()
                success
            }
        } catch (e: Exception) {
            false
        }
    }
}
