package libv2ray

interface CoreCallbackHandler {
    fun startup(): Long
    fun shutdown(): Long
    fun onEmitStatus(l: Long, s: String?): Long
}
