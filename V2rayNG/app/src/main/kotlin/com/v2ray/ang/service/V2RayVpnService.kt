package com.v2ray.ang.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.*
import android.net.VpnService
import android.os.*
import android.support.annotation.RequiresApi
import android.support.v4.app.NotificationCompat
import android.text.TextUtils
import android.util.Log
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.VpnBandwidth
import com.v2ray.ang.extension.defaultDPreference
import com.v2ray.ang.extension.toSpeedString
import com.v2ray.ang.extension.v2RayApplication
import com.v2ray.ang.ui.MainActivity
import com.v2ray.ang.ui.PerAppProxyActivity
import com.v2ray.ang.ui.SettingsActivity
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.Utils
import libv2ray.Libv2ray
import libv2ray.V2RayCallbacks
import libv2ray.V2RayVPNServiceSupportsSet
import rx.Observable
import rx.Subscription
import java.io.FileInputStream
import java.lang.ref.SoftReference
import android.os.Build
import android.annotation.TargetApi
import android.support.v4.os.BuildCompat

class V2RayVpnService : VpnService() {
    companion object {
        const val NOTIFICATION_ID = 1
        const val NOTIFICATION_PENDING_INTENT_CONTENT = 0
        const val NOTIFICATION_PENDING_INTENT_STOP_V2RAY = 1

        fun startV2Ray(context: Context) {
            val intent = Intent(context.applicationContext, V2RayVpnService::class.java)
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val v2rayPoint = Libv2ray.newV2RayPoint()
    private val v2rayCallback = V2RayCallback()
    private lateinit var configContent: String
    private lateinit var mInterface: ParcelFileDescriptor
    val fd: Int get() = mInterface.fd
    private var mBuilder: NotificationCompat.Builder? = null
    private var mSubscription: Subscription? = null
    private var lastVpnBandwidth: VpnBandwidth? = null
    private var mNotificationManager: NotificationManager? = null



    /**
        * Unfortunately registerDefaultNetworkCallback is going to return our VPN interface: https://android.googlesource.com/platform/frameworks/base/+/dda156ab0c5d66ad82bdcf76cda07cbc0a9c8a2e
        *
        * This makes doing a requestNetwork with REQUEST necessary so that we don't get ALL possible networks that
        * satisfies default network capabilities but only THE default network. Unfortunately we need to have
        * android.permission.CHANGE_NETWORK_STATE to be able to call requestNetwork.
        *
        * Source: https://android.googlesource.com/platform/frameworks/base/+/2df4c7d/services/core/java/com/android/server/ConnectivityService.java#887
        */
    private val defaultNetworkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()


    private val connectivity by lazy { getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    @TargetApi(28)
    private val defaultNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            setUnderlyingNetworks(arrayOf(network))
        }
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities?) {
            // it's a good idea to refresh capabilities
            setUnderlyingNetworks(arrayOf(network))
        }
        override fun onLost(network: Network) {
            setUnderlyingNetworks(null)
        }
    }
    private var listeningForDefaultNetwork = false

    override fun onCreate() {
        super.onCreate()

        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)
        v2rayPoint.packageName = Utils.packagePath(applicationContext)
    }

    override fun onRevoke() {
        stopV2Ray()
    }

    override fun onDestroy() {
        super.onDestroy()

        cancelNotification()
    }

    fun setup(parameters: String) {
        // If the old interface has exactly the same parameters, use it!
        // Configure a builder while parsing the parameters.
        val builder = Builder()
        val enableLocalDns = defaultDPreference.getPrefBoolean(SettingsActivity.PREF_LOCAL_DNS_ENABLED, false)

        parameters.split(" ")
                .map { it.split(",") }
                .forEach {
                    when (it[0][0]) {
                        'm' -> builder.setMtu(java.lang.Short.parseShort(it[1]).toInt())
                        's' -> builder.addSearchDomain(it[1])
                        'a' -> builder.addAddress(it[1], Integer.parseInt(it[2]))
                        'r' -> builder.addRoute(it[1], Integer.parseInt(it[2]))
                        'd' -> builder.addDnsServer(it[1])
                    }
                }

        if(!enableLocalDns) {
            Utils.getRemoteDnsServers(defaultDPreference)
                .forEach {
                    builder.addDnsServer(it)
                }
        }

        builder.setSession(defaultDPreference.getPrefString(AppConfig.PREF_CURR_CONFIG_NAME, ""))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
                defaultDPreference.getPrefBoolean(SettingsActivity.PREF_PER_APP_PROXY, false)) {
            val apps = defaultDPreference.getPrefStringSet(PerAppProxyActivity.PREF_PER_APP_PROXY_SET, null)
            val bypassApps = defaultDPreference.getPrefBoolean(PerAppProxyActivity.PREF_BYPASS_APPS, false)
            apps?.forEach {
                try {
                    if (bypassApps)
                        builder.addDisallowedApplication(it)
                    else
                        builder.addAllowedApplication(it)
                } catch (e: PackageManager.NameNotFoundException) {
                    //Logger.d(e)
                }
            }
        }

        // Close the old interface since the parameters have been changed.
        try {
            mInterface.close()
        } catch (ignored: Exception) {
        }


        if (Build.VERSION.SDK_INT >= 28) {
            connectivity.requestNetwork(defaultNetworkRequest, defaultNetworkCallback)
            listeningForDefaultNetwork = true
        }

        // Create a new interface using the builder and save the parameters.
        mInterface = builder.establish()
        //Logger.d("VPNService", "New interface: " + parameters)
        //Logger.d(Libv2ray.checkVersionX())


        if (defaultDPreference.getPrefBoolean(SettingsActivity.PREF_SPEED_ENABLED, false)) {
            mSubscription = Observable.interval(3, java.util.concurrent.TimeUnit.SECONDS)
                    .subscribe {
                        vpnBandwidth?.let {
                            lastVpnBandwidth?.let { last ->
                                val speed = it - last
                                updateNotification("${(speed.txByte / 3).toSpeedString()} ↑  ${(speed.rxByte / 3).toSpeedString()} ↓")
                            }
                            lastVpnBandwidth = it
                        }
                    }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startV2ray()
        return START_STICKY
        //return super.onStartCommand(intent, flags, startId)
    }

    private fun vpnCheckIsReady() {
        val prepare = VpnService.prepare(this)

        if (prepare != null) {
            return
        }
        val enableLocalDns = defaultDPreference.getPrefBoolean(SettingsActivity.PREF_LOCAL_DNS_ENABLED, false)
        val forwardIpv6 = defaultDPreference.getPrefBoolean(SettingsActivity.PREF_FORWARD_IPV6, false)

        v2rayPoint.vpnSupportReady(enableLocalDns, forwardIpv6)
        if (v2rayPoint.isRunning) {
            MessageUtil.sendMsg2UI(this, AppConfig.MSG_STATE_START_SUCCESS, "")
            showNotification()
        } else {
            MessageUtil.sendMsg2UI(this, AppConfig.MSG_STATE_START_FAILURE, "")
            cancelNotification()
        }
    }

    private fun startV2ray() {
        if (!v2rayPoint.isRunning) {

            try {
                registerReceiver(mMsgReceive, IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE))
            } catch (e: Exception) {
            }

            val domainName = defaultDPreference.getPrefString(AppConfig.PREF_CURR_CONFIG_DOMAIN, "")
            configContent = defaultDPreference.getPrefString(AppConfig.PREF_CURR_CONFIG, "")

            v2rayPoint.callbacks = v2rayCallback
            v2rayPoint.setVpnSupportSet(v2rayCallback)

            v2rayPoint.configureFileContent = configContent
            v2rayPoint.domainName = domainName
            try {
                v2rayPoint.runLoop()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        //        showNotification()
    }

    private fun stopV2Ray(isForced: Boolean = true) {
//        val configName = defaultDPreference.getPrefString(PREF_CURR_CONFIG_GUID, "")
//        val emptyInfo = VpnNetworkInfo()
//        val info = loadVpnNetworkInfo(configName, emptyInfo)!! + (lastNetworkInfo ?: emptyInfo)
//        saveVpnNetworkInfo(configName, info)
        if (listeningForDefaultNetwork) {
            connectivity.unregisterNetworkCallback(defaultNetworkCallback)
            listeningForDefaultNetwork = false
        }

        if (v2rayPoint.isRunning) {
            try {
                v2rayPoint.stopLoop()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        MessageUtil.sendMsg2UI(this, AppConfig.MSG_STATE_STOP_SUCCESS, "")
        cancelNotification()

        if (isForced) {
            try {
                unregisterReceiver(mMsgReceive)
            } catch (e: Exception) {
            }
            stopSelf()
        }
    }

    private fun showNotification() {
        val startMainIntent = Intent(applicationContext, MainActivity::class.java)
        val contentPendingIntent = PendingIntent.getActivity(applicationContext,
                NOTIFICATION_PENDING_INTENT_CONTENT, startMainIntent,
                PendingIntent.FLAG_UPDATE_CURRENT)

        val stopV2RayIntent = Intent(AppConfig.BROADCAST_ACTION_SERVICE)
        stopV2RayIntent.`package` = AppConfig.ANG_PACKAGE
        stopV2RayIntent.putExtra("key", AppConfig.MSG_STATE_STOP)

        val stopV2RayPendingIntent = PendingIntent.getBroadcast(applicationContext,
                NOTIFICATION_PENDING_INTENT_STOP_V2RAY, stopV2RayIntent,
                PendingIntent.FLAG_UPDATE_CURRENT)

        val channelId =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    createNotificationChannel()
                } else {
                    // If earlier version channel ID is not used
                    // https://developer.android.com/reference/android/support/v4/app/NotificationCompat.Builder.html#NotificationCompat.Builder(android.content.Context)
                    ""
                }

        mBuilder = NotificationCompat.Builder(applicationContext, channelId)
                .setSmallIcon(R.drawable.ic_v)
                .setContentTitle(defaultDPreference.getPrefString(AppConfig.PREF_CURR_CONFIG_NAME, ""))
                .setContentText(getString(R.string.notification_action_more))
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentPendingIntent)
                .addAction(R.drawable.ic_close_grey_800_24dp,
                        getString(R.string.notification_action_stop_v2ray),
                        stopV2RayPendingIntent)
        //.build()

        //mBuilder?.setDefaults(NotificationCompat.FLAG_ONLY_ALERT_ONCE)  //取消震动,铃声其他都不好使

        startForeground(NOTIFICATION_ID, mBuilder?.build())
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(): String {
        val channelId = "RAY_NG_M_CH_ID"
        val channelName = "V2rayNG Background Service"
        val chan = NotificationChannel(channelId,
                channelName, NotificationManager.IMPORTANCE_HIGH)
        chan.lightColor = Color.DKGRAY
        chan.importance = NotificationManager.IMPORTANCE_NONE
        chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        getNotificationManager().createNotificationChannel(chan)
        return channelId
    }

    private fun cancelNotification() {
        stopForeground(true)
        mBuilder = null
        mSubscription?.unsubscribe()
        mSubscription = null
    }

    private fun updateNotification(contentText: String) {
        if (mBuilder != null) {
            mBuilder?.setContentText(contentText)
            getNotificationManager().notify(NOTIFICATION_ID, mBuilder?.build())
        }
    }

    private fun getNotificationManager(): NotificationManager {
        if (mNotificationManager == null) {
            mNotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        }
        return mNotificationManager!!
    }

    private val vpnBandwidth: VpnBandwidth?
        get() {
            try {
                // val netDev = FileInputStream("/proc/net/dev").bufferedReader()
                // var bandWidth: VpnBandwidth? = null
                // val prefix = "tun0:"
                // while (true) {
                //     val line = netDev.readLine().trim()
                //     if (line.startsWith(prefix)) {
                //         val numbers = line.substring(prefix.length).split(' ')
                //                 .filter(String::isNotEmpty)
                //                 .map(String::toLong)
                //         if (numbers.size > 10)
                //             bandWidth = VpnBandwidth(numbers[0], numbers[8])
                //         break
                //     }
                // }
                // netDev.close()

                var uplink = v2rayPoint.queryStats("socks", "uplink")
                var downlink = v2rayPoint.queryStats("socks", "downlink")
                var bandWidth = VpnBandwidth(downlink, uplink)
                return bandWidth
            } catch (e: Exception) {
                e.printStackTrace()
                return null
            }
        }


    private inner class V2RayCallback : V2RayCallbacks, V2RayVPNServiceSupportsSet {
        override fun shutdown() = 0L

        override fun getVPNFd() = this@V2RayVpnService.fd.toLong()

        override fun prepare(): Long {
            vpnCheckIsReady()
            return 1
        }

        override fun protect(l: Long) = (if (this@V2RayVpnService.protect(l.toInt())) 0 else 1).toLong()

        override fun onEmitStatus(l: Long, s: String?): Long {
            //Logger.d(s)
            return 0
        }

        override fun setup(s: String): Long {
            //Logger.d(s)
            try {
                this@V2RayVpnService.setup(s)
                return 0
            } catch (e: Exception) {
                e.printStackTrace()
                return -1
            }
        }
    }

    private var mMsgReceive = ReceiveMessageHandler(this@V2RayVpnService)

    private class ReceiveMessageHandler(vpnService: V2RayVpnService) : BroadcastReceiver() {
        internal var mReference: SoftReference<V2RayVpnService> = SoftReference(vpnService)

        override fun onReceive(ctx: Context?, intent: Intent?) {
            val vpnService = mReference.get()
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_REGISTER_CLIENT -> {
                    //Logger.e("ReceiveMessageHandler", intent?.getIntExtra("key", 0).toString())

                    val isRunning = vpnService?.v2rayPoint!!.isRunning
                            && VpnService.prepare(vpnService) == null
                    if (isRunning) {
                        MessageUtil.sendMsg2UI(vpnService, AppConfig.MSG_STATE_RUNNING, "")
                    } else {
                        MessageUtil.sendMsg2UI(vpnService, AppConfig.MSG_STATE_NOT_RUNNING, "")
                    }
                }
                AppConfig.MSG_UNREGISTER_CLIENT -> {
//                    vpnService?.mMsgSend = null
                }
                AppConfig.MSG_STATE_START -> {
                    //nothing to do
                }
                AppConfig.MSG_STATE_STOP -> {
                    vpnService?.stopV2Ray()
                }
                AppConfig.MSG_STATE_RESTART -> {
                    vpnService?.startV2ray()
                }
            }
        }
    }
}

