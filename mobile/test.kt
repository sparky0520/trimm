import kotlin.math.*

fun main() {
    val sourceDurationMs = 1000L
    val sourceStart = 0L
    val sourceEnd = sourceStart + sourceDurationMs
    val rangeEndMs = 1500L
    val end = minOf(rangeEndMs, sourceEnd)
    val localEnd = end - sourceStart
    println("localEnd: $localEnd, sourceDurationMs: $sourceDurationMs")
}
