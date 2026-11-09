package de.jenz.nahtlos.server

import de.jenz.nahtlos.diagnose.DiagnoseText
import de.jenz.nahtlos.diagnose.DiagnoseUpload
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put

fun Route.diagnoseRoutes(database: SyncDatabase) {
    get("/diagnose") {
        call.respond(database.listDiagnose())
    }
    put("/diagnose") {
        val body = call.receive<DiagnoseUpload>()
        val reason = DiagnoseText.reject(body.body)
        if (reason != null) throw IllegalArgumentException(reason)
        val clean = DiagnoseText.scrub(body.body)
        if (body.updatedAt <= 0L) throw IllegalArgumentException("Zeit fehlt")
        val deviceId = when (val auth = call.attributes.getOrNull(NahtlosAuthKey)) {
            is AuthKind.Device -> auth.id
            AuthKind.Admin -> "admin"
            else -> "unknown"
        }
        database.saveDiagnose(deviceId, body.updatedAt, clean)
        call.respond(mapOf("status" to "ok"))
    }
}
