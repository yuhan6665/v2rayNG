package com.v2ray.ang.util

import android.content.ClipboardManager
import android.content.Context
import android.text.Editable
import android.util.Base64
import com.google.zxing.WriterException
import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.EncodeHintType
import java.util.*
import kotlin.collections.HashMap
import android.app.ActivityManager
import android.content.ClipData
import android.content.Intent
import android.content.res.AssetManager
import android.net.Uri
import android.os.SystemClock
import android.text.TextUtils
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.util.Patterns
import android.view.View
import android.webkit.URLUtil
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.extension.responseLength
import com.v2ray.ang.service.V2RayVpnService
import com.v2ray.ang.ui.SettingsActivity
import kotlinx.android.synthetic.main.activity_logcat.*
import me.dozen.dpreference.DPreference
import org.jetbrains.anko.toast
import org.jetbrains.anko.uiThread
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.*
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.math.BigInteger


object Utils {

    /**
     * convert string to editalbe for kotlin
     *
     * @param text
     * @return
     */
    fun getEditable(text: String): Editable {
        return Editable.Factory.getInstance().newEditable(text)
    }

    /**
     * find value in array position
     */
    fun arrayFind(array: Array<out String>, value: String): Int {
        for (i in array.indices) {
            if (array[i] == value) {
                return i
            }
        }
        return -1
    }

    /**
     * parseInt
     */
    fun parseInt(str: String): Int {
        try {
            return Integer.parseInt(str)
        } catch (e: Exception) {
            e.printStackTrace()
            return 0
        }
    }

    /**
     * get text from clipboard
     */
    fun getClipboard(context: Context): String {
        try {
            val cmb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            return cmb.primaryClip?.getItemAt(0)?.text.toString()
        } catch (e: Exception) {
            e.printStackTrace()
            return ""
        }
    }

    /**
     * set text to clipboard
     */
    fun setClipboard(context: Context, content: String) {
        try {
            val cmb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = ClipData.newPlainText(null, content)
            cmb.primaryClip = clipData
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * base64 decode
     */
    fun decode(text: String): String {
        try {
            return Base64.decode(text, Base64.NO_WRAP).toString(charset("UTF-8"))
        } catch (e: Exception) {
            e.printStackTrace()
            return ""
        }
    }

    /**
     * base64 encode
     */
    fun encode(text: String): String {
        try {
            return Base64.encodeToString(text.toByteArray(charset("UTF-8")), Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            return ""
        }
    }

    /**
     * get remote dns servers from preference
     */
    fun getRemoteDnsServers(defaultDPreference: DPreference): List<String> {
        val remoteDns = defaultDPreference.getPrefString(SettingsActivity.PREF_REMOTE_DNS, "")
        val ret = ArrayList<String>()
        if (!TextUtils.isEmpty(remoteDns)) {
            remoteDns
                    .split(",")
                    .forEach {
                        if (Utils.isIpAddress(it)) {
                            ret.add(it)
                        }
                    }
        }
        ret.add("1.1.1.1")
        return ret
    }

    /**
     * create qrcode using zxing
     */
    fun createQRCode(text: String, size: Int = 800): Bitmap? {
        try {
            val hints = HashMap<EncodeHintType, String>()
            hints.put(EncodeHintType.CHARACTER_SET, "utf-8")
            val bitMatrix = QRCodeWriter().encode(text,
                    BarcodeFormat.QR_CODE, size, size, hints)
            val pixels = IntArray(size * size)
            for (y in 0..size - 1) {
                for (x in 0..size - 1) {
                    if (bitMatrix.get(x, y)) {
                        pixels[y * size + x] = 0xff000000.toInt()
                    } else {
                        pixels[y * size + x] = 0xffffffff.toInt()
                    }

                }
            }
            val bitmap = Bitmap.createBitmap(size, size,
                    Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
            return bitmap
        } catch (e: WriterException) {
            e.printStackTrace()
            return null
        }
    }


    /*
    * Parse IP address from string.
    *  -> https://rosettacode.org/wiki/Parse_an_IP_Address#Kotlin
    */
    enum class AddressSpace { IPv4, IPv6, Invalid }

    data class IPAddressComponents(
            val address: BigInteger,
            val addressSpace: AddressSpace,
            val port: Int  // -1 denotes 'not specified'
    )

    val INVALID = IPAddressComponents(BigInteger.ZERO, AddressSpace.Invalid, 0)

    fun ipAddressParse(ipAddress: String): IPAddressComponents {
        var addressSpace = AddressSpace.IPv4
        var ipa = ipAddress.toLowerCase()
        var port = -1
        var trans = false

        if (ipa.startsWith("::ffff:") && '.' in ipa) {
            addressSpace = AddressSpace.IPv6
            trans = true
            ipa = ipa.drop(7)
        } else if (ipa.startsWith("[::ffff:") && '.' in ipa) {
            addressSpace = AddressSpace.IPv6
            trans = true
            ipa = ipa.drop(8).replace("]", "")
        }
        val octets = ipa.split('.').reversed().toTypedArray()
        var address = BigInteger.ZERO
        if (octets.size == 4) {
            val split = octets[0].split(':')
            if (split.size == 2) {
                val temp = split[1].toIntOrNull()
                if (temp == null || temp !in 0..65535) return INVALID
                port = temp
                octets[0] = split[0]
            }

            for (i in 0..3) {
                val num = octets[i].toLongOrNull()
                if (num == null || num !in 0..255) return INVALID
                val bigNum = BigInteger.valueOf(num)
                address = address.or(bigNum.shiftLeft(i * 8))
            }

            if (trans) address += BigInteger("ffff00000000", 16)
        } else if (octets.size == 1) {
            addressSpace = AddressSpace.IPv6
            if (ipa[0] == '[') {
                ipa = ipa.drop(1)
                val split = ipa.split("]:")
                if (split.size != 2) return INVALID
                val temp = split[1].toIntOrNull()
                if (temp == null || temp !in 0..65535) return INVALID
                port = temp
                ipa = ipa.dropLast(2 + split[1].length)
            }
            val hextets = ipa.split(':').reversed().toMutableList()
            val len = hextets.size

            if (ipa.startsWith("::"))
                hextets[len - 1] = "0"
            else if (ipa.endsWith("::"))
                hextets[0] = "0"

            if (ipa == "::") hextets[1] = "0"
            if (len > 8 || (len == 8 && hextets.any { it == "" }) || hextets.count { it == "" } > 1)
                return INVALID
            if (len < 8) {
                var insertions = 8 - len
                for (i in 0..7) {
                    if (hextets[i] == "") {
                        hextets[i] = "0"
                        while (insertions-- > 0) hextets.add(i, "0")
                        break
                    }
                }
            }
            for (j in 0..7) {
                val num = hextets[j].toLongOrNull(16)
                if (num == null || num !in 0x0..0xFFFF) return INVALID
                val bigNum = BigInteger.valueOf(num)
                address = address.or(bigNum.shiftLeft(j * 16))
            }
        } else return INVALID

        return IPAddressComponents(address, addressSpace, port)
    }

    /**
     * is ip address
     */
    fun isIpAddress(value: String): Boolean {
        try {
            var addr = value
            if (addr.isEmpty() || addr.isBlank()) {
                return false
            }
            //CIDR
            if (addr.indexOf("/") > 0) {
                val arr = addr.split("/")
                if (arr.count() == 2 && Integer.parseInt(arr[1]) > 0) {
                    addr = arr[0]
                }
            }
            return (ipAddressParse(addr) != INVALID)
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    /**
     * is valid url
     */
    fun isValidUrl(value: String?): Boolean {
        try {
            if (Patterns.WEB_URL.matcher(value).matches() || URLUtil.isValidUrl(value)) {
                return true
            }
        } catch (e: WriterException) {
            e.printStackTrace()
            return false
        }
        return false
    }


    /**
     * 判断服务是否后台运行

     * @param context
     * *            Context
     * *
     * @param className
     * *            判断的服务名字
     * *
     * @return true 在运行 false 不在运行
     */
    fun isServiceRun(context: Context, className: String): Boolean {
        var isRun = false
        val activityManager = context
                .getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val serviceList = activityManager
                .getRunningServices(999)
        val size = serviceList.size
        for (i in 0..size - 1) {
            if (serviceList[i].service.className == className) {
                isRun = true
                break
            }
        }
        return isRun
    }

    /**
     * startVService
     */
    fun startVService(context: Context): Boolean {
        context.toast(R.string.toast_services_start)
        if (AngConfigManager.genStoreV2rayConfig(-1)) {
            V2RayVpnService.startV2Ray(context)
            return true
        } else {
            return false
        }
    }

    /**
     * startVService
     */
    fun startVService(context: Context, guid: String): Boolean {
        val index = AngConfigManager.getIndexViaGuid(guid)
        return startVService(context, index)
    }

    /**
     * startVService
     */
    fun startVService(context: Context, index: Int): Boolean {
        AngConfigManager.setActiveServer(index)
        return startVService(context)
    }

    /**
     * stopVService
     */
    fun stopVService(context: Context) {
        context.toast(R.string.toast_services_stop)
        MessageUtil.sendMsg2Service(context, AppConfig.MSG_STATE_STOP, "")
    }

    fun openUri(context: Context, uriString: String) {
        val uri = Uri.parse(uriString)
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    /**
     * uuid
     */
    fun getUuid(): String {
        try {
            return UUID.randomUUID().toString().replace("-", "")
        } catch (e: Exception) {
            e.printStackTrace()
            return ""
        }
    }

    fun urlDecode(url: String): String {
        try {
            return URLDecoder.decode(url, "UTF-8")
        } catch (e: Exception) {
            e.printStackTrace()
            return url
        }
    }

    fun urlEncode(url: String): String {
        try {
            return URLEncoder.encode(url, "UTF-8")
        } catch (e: Exception) {
            e.printStackTrace()
            return url
        }
    }

    /**
     * Based on: https://android.googlesource.com/platform/frameworks/base/+/b19a838/services/core/java/com/android/server/connectivity/NetworkMonitor.java#1071
     */
    fun testConnection(context: Context, port: Int): String {
        var result: String
        var conn: HttpURLConnection? = null

        try {
            val url = URL("https",
                    "www.google.com",
                    "/generate_204")
//        Log.d("testConnection", "222222222222")

            conn = url.openConnection(Proxy(Proxy.Type.SOCKS,
                    InetSocketAddress("localhost", port)))
                    as HttpURLConnection
//        Log.d("testConnection", "333333333333")

            conn.connectTimeout = 30000
            conn.readTimeout = 30000
            conn.setRequestProperty("Connection", "close")
            conn.instanceFollowRedirects = false
            conn.useCaches = false
//        Log.d("testConnection", "444444444444")


            val start = SystemClock.elapsedRealtime()
            val code = conn.responseCode
            val elapsed = SystemClock.elapsedRealtime() - start

            if (code == 204 || code == 200 && conn.responseLength == 0L) {
                result = context.getString(R.string.connection_test_available, elapsed)
            } else {
                throw IOException(context.getString(R.string.connection_test_error_status_code, code))
            }
        } catch (e: IOException) {
            result = context.getString(R.string.connection_test_error, e.message)
        } catch (e: Exception) {
            result = context.getString(R.string.connection_test_error, e.message)
        } finally {
            conn?.disconnect()
        }

        return result
    }

    /**
     * package path
     */
    fun packagePath(context: Context): String {
        var path = context.filesDir.toString()
        path = path.replace("files", "")
        //path += "tun2socks"

        return path
    }


    /**
     * readTextFromAssets
     */
    fun readTextFromAssets(app: AngApplication, fileName: String): String {
        val content = app.assets.open(fileName).bufferedReader().use {
            it.readText()
        }
        return content
    }

    /**
     * ping
     */
    fun ping(url: String): String {
        try {
            val command = "/system/bin/ping -c 1 $url"
            val process = Runtime.getRuntime().exec(command)
            val allText = process.inputStream.bufferedReader().use { it.readText() }
            if (!TextUtils.isEmpty(allText)) {
                val tempInfo = allText.substring(allText.indexOf("min/avg/max/mdev") + 19)
                val temps = tempInfo.split("/".toRegex()).dropLastWhile({ it.isEmpty() }).toTypedArray()
                if (temps.count() > 0 && temps[0].length < 10) {
                    return temps[0].toFloat().toInt().toString() + "ms"
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "-1ms"
    }
}

