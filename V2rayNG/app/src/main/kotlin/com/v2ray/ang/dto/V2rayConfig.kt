package com.v2ray.ang.dto

data class V2rayConfig(
        val log: LogBean,
        val inbounds: ArrayList<InboundBean>,
        var outbounds: ArrayList<OutboundBean>,
        var dns: DnsBean,
        val routing: RoutingBean) {

    data class LogBean(val access: String,
                       val error: String,
                       val loglevel: String)

    data class InboundBean(
            var port: Int,
            var protocol: String,
            val settings: Any,
            val listen: String? = null,
            val tag: String? = null,
            val sniffing: SniffingBean?) {

        data class InSettingsBean(val auth: String,
                                  val udp: Boolean)

        data class DokodemoInSettingsBean(val address: String,
                                          val port: Int,
                                          val network: String)

        data class SniffingBean(val enabled: Boolean,
                                val destOverride: List<String>)
    }

    data class OutboundBean(val tag: String,
                            var protocol: String,
                            var settings: OutSettingsBean?,
                            var streamSettings: StreamSettingsBean?,
                            var mux: MuxBean?) {

        data class OutSettingsBean(var vnext: List<VnextBean>?,
                                   var servers: List<ServersBean>?,
                                   var response: Response) {

            data class VnextBean(var address: String,
                                 var port: Int,
                                 var users: List<UsersBean>) {

                data class UsersBean(var id: String,
                                     var alterId: Int,
                                     var security: String)
            }

            data class ServersBean(var address: String,
                                   var method: String,
                                   var ota: Boolean,
                                   var password: String,
                                   var port: Int,
                                   var level: Int)

            data class Response(var type: String)
        }

        data class StreamSettingsBean(var network: String,
                                      var security: String,
                                      var tcpSettings: TcpsettingsBean?,
                                      var kcpsettings: KcpsettingsBean?,
                                      var wssettings: WssettingsBean?,
                                      var httpsettings: HttpsettingsBean?,
                                      var tlssettings: TlssettingsBean?
        ) {

            data class TcpsettingsBean(var connectionReuse: Boolean = true,
                                       var header: HeaderBean = HeaderBean()) {
                data class HeaderBean(var type: String = "none",
                                      var request: Any? = null,
                                      var response: Any? = null)
            }

            data class KcpsettingsBean(var mtu: Int = 1350,
                                       var tti: Int = 20,
                                       var uplinkCapacity: Int = 12,
                                       var downlinkCapacity: Int = 100,
                                       var congestion: Boolean = false,
                                       var readBufferSize: Int = 1,
                                       var writeBufferSize: Int = 1,
                                       var header: HeaderBean = HeaderBean()) {
                data class HeaderBean(var type: String = "none")
            }

            data class WssettingsBean(var connectionReuse: Boolean = true,
                                      var path: String = "",
                                      var headers: HeadersBean = HeadersBean()) {
                data class HeadersBean(var Host: String = "")
            }

            data class HttpsettingsBean(var host: List<String> = ArrayList<String>(), var path: String = "")

            data class TlssettingsBean(var allowInsecure: Boolean = true,
                                       var serverName: String = "")
        }

        data class MuxBean(var enabled: Boolean)
    }

    //data class DnsBean(var servers: List<String>)
    data class DnsBean(var servers: List<Any>,
                       var hosts: Map<String, String>?
    ) {
        data class ServersBean(var address: String = "",
                               var port: Int = 0,
                               var domains: List<String>?)
    }

    data class RoutingBean(var domainStrategy: String,
                           var rules: ArrayList<RulesBean>) {

        data class RulesBean(var type: String,
                //var port: String,
                             var ip: ArrayList<String>?,
                             var domain: ArrayList<String>?,
                             var outboundTag: String,
                             var inboundTag: String?,
                             var port: String? = null)
    }
}