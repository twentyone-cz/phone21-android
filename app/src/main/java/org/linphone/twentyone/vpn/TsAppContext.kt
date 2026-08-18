/*
 * fork: Android strana libtailscale (rozhraní AppContext). Předloha:
 * tailscale-android App.kt (BSD-3-Clause), zúžená na to, co potřebujeme —
 * bez MDM, Taildropu, hardware attestace a odesílání logů.
 */
package org.linphone.twentyone.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.linphone.core.tools.Log

class TsAppContext(private val context: Context) : libtailscale.AppContext {
    companion object {
        private const val TAG = "[Tunnel Context]"
        private const val PREFS_FILE = "twentyone_tunnel"
    }

    @Volatile
    var defaultNetwork: Network? = null

    @Volatile
    var platformDnsConfig: String = ""

    private val prefs by lazy {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun log(tag: String, logLine: String) {
        Log.i("$TAG [$tag] $logLine")
    }

    override fun encryptToPref(key: String, value: String) {
        prefs.edit().putString(key, value).commit()
    }

    override fun decryptFromPref(key: String): String? {
        return prefs.getString(key, null)
    }

    override fun getStateStoreKeysJSON(): String {
        val prefix = "statestore-"
        val keys = prefs.all.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
        return JSONArray(keys).toString()
    }

    override fun getOSVersion(): String = Build.VERSION.RELEASE

    override fun getSDKInt(): Long = Build.VERSION.SDK_INT.toLong()

    override fun getDeviceName(): String {
        android.provider.Settings.Global.getString(
            context.contentResolver,
            android.provider.Settings.Global.DEVICE_NAME
        )?.let {
            return it
        }
        val manufacturer = Build.MANUFACTURER
        var model = Build.MODEL
        val idx = model.lowercase(Locale.getDefault()).indexOf(
            manufacturer.lowercase(Locale.getDefault())
        )
        if (idx != -1) {
            model = model.substring(idx + manufacturer.length).trim()
        }
        return "$manufacturer $model"
    }

    override fun getInstallSource(): String = ""

    override fun shouldUseGoogleDNSFallback(): Boolean = false

    override fun isChromeOS(): Boolean {
        return context.packageManager.hasSystemFeature("android.hardware.type.pc")
    }

    override fun isClientLoggingEnabled(): Boolean = false

    override fun getPlatformDNSConfig(): String = platformDnsConfig

    override fun getInterfacesAsJson(): String {
        val out = JSONArray()
        val interfaces = try {
            Collections.list(NetworkInterface.getNetworkInterfaces())
        } catch (e: Exception) {
            Log.e("$TAG Failed to list network interfaces: $e")
            return ""
        }
        for (nif in interfaces) {
            try {
                val addrs = JSONArray()
                for (ia in nif.interfaceAddresses) {
                    val host = ia.address?.hostAddress ?: continue
                    addrs.put(
                        JSONObject()
                            .put("ip", host)
                            .put("prefixLen", ia.networkPrefixLength.toInt())
                    )
                }
                out.put(
                    JSONObject()
                        .put("name", nif.name)
                        .put("index", nif.index)
                        .put("mtu", nif.mtu)
                        .put("up", nif.isUp)
                        .put("broadcast", nif.supportsMulticast())
                        .put("loopback", nif.isLoopback)
                        .put("pointToPoint", nif.isPointToPoint)
                        .put("multicast", nif.supportsMulticast())
                        .put("addrs", addrs)
                )
            } catch (e: Exception) {
                continue
            }
        }
        return out.toString()
    }

    // Systémové politiky (MDM) nepoužíváme — Go strana čeká výjimku u nenastaveného klíče.
    override fun getSyspolicyStringValue(key: String): String = throw NoSuchKeyException()

    override fun getSyspolicyBooleanValue(key: String): Boolean = throw NoSuchKeyException()

    override fun getSyspolicyStringArrayJSONValue(key: String): String = throw NoSuchKeyException()

    override fun hardwareAttestationKeySupported(): Boolean = false

    override fun hardwareAttestationKeyCreate(): String = throw UnsupportedOperationException()

    override fun hardwareAttestationKeyRelease(id: String) = throw UnsupportedOperationException()

    override fun hardwareAttestationKeyPublic(id: String): ByteArray =
        throw UnsupportedOperationException()

    override fun hardwareAttestationKeySign(id: String, data: ByteArray): ByteArray =
        throw UnsupportedOperationException()

    override fun hardwareAttestationKeyLoad(id: String) = throw UnsupportedOperationException()

    override fun bindSocketToNetwork(fd: Int): Boolean {
        // Záložní volba sítě nesmí sáhnout po tunelu samotném: při běžícím
        // tunelu je activeNetwork typicky právě on a řídicí sokety jádra
        // by se zacyklily do vlastního rozhraní.
        val net = defaultNetwork ?: run {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.activeNetwork?.takeIf { candidate ->
                cm.getNetworkCapabilities(candidate)
                    ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) != true
            }
        } ?: return false
        return try {
            ParcelFileDescriptor.fromFd(fd).use { pfd -> net.bindSocket(pfd.fileDescriptor) }
            true
        } catch (e: Exception) {
            Log.w("$TAG Failed to bind socket [$fd] to network: $e")
            false
        }
    }

    override fun getUserCACertsPEM(): ByteArray {
        return try {
            val ks = java.security.KeyStore.getInstance("AndroidCAStore")
            ks.load(null)
            val sb = StringBuilder()
            for (alias in ks.aliases()) {
                if (!alias.startsWith("user:")) continue
                val cert = ks.getCertificate(alias) ?: continue
                val encoded = android.util.Base64.encodeToString(
                    cert.encoded,
                    android.util.Base64.NO_WRAP
                )
                sb.append("-----BEGIN CERTIFICATE-----\n")
                encoded.chunked(64).forEach { sb.append(it).append('\n') }
                sb.append("-----END CERTIFICATE-----\n")
            }
            sb.toString().toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w("$TAG Failed to read user CA certificates: $e")
            ByteArray(0)
        }
    }

    // Text zprávy MUSÍ být doslova "no such key" — Go strana knihovny ho
    // porovnává řetězcově (syspolicy_handler.go), jiná zpráva se počítá
    // jako skutečná chyba, ne jako "klíč není nastaven".
    class NoSuchKeyException : Exception("no such key")
}
