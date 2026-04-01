package at.planqton.fytfm.dab

interface DabScanListener {
    fun onScanStarted()
    fun onScanProgress(percent: Int, blockLabel: String)
    fun onServiceFound(service: DabStation)
    fun onScanFinished(services: List<DabStation>)
    fun onScanError(error: String)
}
