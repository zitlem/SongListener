package songlistener.io

import java.io.File

/** `./gradlew report`: writes a report for every day in ~/.songlistener/logs and prints the newest. */
fun main() {
    val data = File(System.getProperty("user.home"), ".songlistener")
    val logs = System.getProperty("songlistener.logs")?.takeIf { it.isNotBlank() }?.let(::File) ?: File(data, "logs")
    val out = System.getProperty("songlistener.out")?.takeIf { it.isNotBlank() }?.let(::File) ?: File(data, "reports")
    val newest = ServiceReport.writeAll(logs, out)
    println(newest?.absolutePath ?: "No logs yet.")
}
