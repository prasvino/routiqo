package com.routiqo.safehttp

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Enforces the credential transport boundary below React Native's redirecting fetch. */
class RoutiqoSafeHttpModule : Module() {
  private val client = OkHttpClient.Builder()
    .followRedirects(false)
    .followSslRedirects(false)
    .cookieJar(CookieJar.NO_COOKIES)
    .callTimeout(12, TimeUnit.SECONDS)
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(7, TimeUnit.SECONDS)
    .writeTimeout(7, TimeUnit.SECONDS)
    .build()

  override fun definition() = ModuleDefinition {
    Name("RoutiqoSafeHttp")
    AsyncFunction("request") { origin: String, path: String, method: String,
        credential: String?, account: String?, payload: String? ->
      try {
        val base = URI(origin)
        require(base.scheme == "https" && base.host != null && base.rawUserInfo == null &&
          base.rawPath.isNullOrEmpty() && base.rawQuery == null && base.rawFragment == null &&
          base.port == -1) { "Invalid origin" }
        val authPath = path.matches(Regex("/api/v1/native/auth/(google/(challenge|exchange)|session(/renew)?|logout|account/delete)"))
        val uuid = "[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"
        val journalPath = path.matches(Regex("/api/v1/native/journeys/$uuid/journal"))
        val journeyPath = path.matches(Regex("/api/v1/native/journeys(?:/history|/$uuid(?:/(?:complete|journal))?)?"))
        require((authPath || journeyPath) && !path.contains('?') && !path.contains('#')) { "Invalid path" }
        require(method == "GET" || method == "POST") { "Invalid method" }
        require(path != "/api/v1/native/journeys/history" || method == "POST") { "Invalid history method" }
        require(!journalPath || method == "GET") { "Invalid journal method" }
        require((method == "POST") == (payload != null)) { "Invalid body" }
        require(payload == null || payload.toByteArray(StandardCharsets.UTF_8).size <= 20 * 1024) { "Body too large" }
        require(credential == null || credential.matches(Regex("[A-Za-z0-9_-]{43}"))) { "Invalid credential" }
        require(account == null || account.matches(Regex(uuid))) { "Invalid account" }
        require(!journeyPath || credential != null && account != null) { "Journey requires identity" }
        require(!authPath || account == null) { "Auth cannot send account" }
        if (authPath) {
          val anonymous = path.startsWith("/api/v1/native/auth/google/")
          require(anonymous == (credential == null)) { "Invalid auth identity" }
        }
        val url = base.resolve(path).toURL().toString()
        val builder = Request.Builder().url(url)
          .header("Accept", "application/json")
          .header("Cache-Control", "no-store")
        if (credential != null) builder.header("Authorization", "Bearer $credential")
        if (account != null) builder.header("X-Routiqo-Account", account)
        if (method == "POST") {
          builder.post(payload!!.toRequestBody("application/json".toMediaType()))
        } else builder.get()
        client.newCall(builder.build()).execute().use { response ->
          require(!response.isRedirect && response.code !in 300..399) { "Redirect denied" }
          if (journalPath && response.code == 200) {
            val contentType = response.header("Content-Type") ?: ""
            require(contentType.matches(Regex("(?i)application/(?:[a-z0-9!#$&^_.+-]+\\+)?json(?:\\s*;.*)?"))) { "Invalid journal content type" }
          }
          val bytes = response.body?.byteStream()?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
              val count = input.read(buffer)
              if (count < 0) break
              require(output.size() + count <= (if (journalPath) 32 else 64) * 1024) { "Response too large" }
              output.write(buffer, 0, count)
            }
            output.toByteArray()
          } ?: byteArrayOf()
          val body = if (journalPath) StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString() else String(bytes, StandardCharsets.UTF_8)
          mapOf("status" to response.code, "body" to body)
        }
      } catch (_: Exception) {
        throw IllegalStateException("Native request unavailable.")
      }
    }
  }
}
