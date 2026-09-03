package libv2ray

interface ProcessFinder {
    fun findProcessByConnection(
        network: String,
        srcIP: String,
        srcPort: Long,
        destIP: String,
        destPort: Long
    ): Long
}
