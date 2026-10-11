package com.aeriotv.android.core.sync

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Minimal Drive v3 REST client scoped to the appDataFolder. Built on OkHttp
 * because the project already pulls it; the official google-api-services-drive
 * artifact would multiply the APK size for what amounts to four endpoints we
 * actually use.
 *
 * Auth: caller supplies a fresh OAuth access token per call. Token rotation is
 * the GoogleSignIn / Authorization client's job (see GoogleDriveAuth).
 *
 * Each "file" stored here is the JSON snapshot for a single SyncCategory. We
 * list to discover the file id, then PATCH to update or POST a multipart for
 * the first upload. AppData files never appear in the user's main Drive UI.
 */
class DriveAppDataClient(private val okHttp: OkHttpClient) {

    private val driveBase = "https://www.googleapis.com/drive/v3/files".toHttpUrl()
    private val uploadBase = "https://www.googleapis.com/upload/drive/v3/files".toHttpUrl()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Look up the file id for [fileName] in the appDataFolder. Returns null
     * when the file doesn't exist yet (first upload path).
     */
    suspend fun findFileId(token: String, fileName: String): String? = withContext(Dispatchers.IO) {
        val url = driveBase.newBuilder()
            .addQueryParameter("spaces", "appDataFolder")
            .addQueryParameter("fields", "files(id,name)")
            .addQueryParameter("q", "name = '$fileName' and trashed = false")
            .build()
        val req = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $token")
            .build()
        runCatching {
            okHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "findFileId $fileName -> HTTP ${resp.code}: ${resp.body?.string()}")
                    return@use null
                }
                val body = resp.body?.string().orEmpty()
                val tree = json.parseToJsonElement(body).jsonObject
                tree["files"]?.jsonArray?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content
            }
        }.onFailure { Log.w(TAG, "findFileId $fileName threw: ${it.message}", it) }
            .getOrNull()
    }

    /** Read a JSON file as a string, or null when missing/unreadable. */
    suspend fun download(token: String, fileId: String): String? = withContext(Dispatchers.IO) {
        val url = driveBase.newBuilder()
            .addPathSegment(fileId)
            .addQueryParameter("alt", "media")
            .build()
        val req = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $token")
            .build()
        runCatching {
            okHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "download $fileId -> HTTP ${resp.code}")
                    null
                } else {
                    resp.body?.string()
                }
            }
        }.getOrNull()
    }

    /**
     * Create-or-update [fileName] with [contentJson]. Single round-trip uses
     * Drive's multipart upload when creating; PATCH /upload for updates.
     */
    suspend fun upload(token: String, fileName: String, contentJson: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val existing = findFileId(token, fileName)
            if (existing == null) {
                createMultipart(token, fileName, contentJson)
            } else {
                updateContent(token, existing, contentJson)
                existing
            }
        }.onFailure { Log.w(TAG, "upload $fileName FAILED: ${it.message}", it) }
            .onSuccess { Log.i(TAG, "upload $fileName OK -> id=$it") }
    }

    private fun createMultipart(token: String, fileName: String, contentJson: String): String {
        val metadata = buildJsonObject {
            put("name", JsonPrimitive(fileName))
            put("parents", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("appDataFolder"))))
        }
        // Drive's multipart/related upload: part 1 = metadata JSON, part 2 =
        // the file content. The per-part Content-Type MUST ride on the body's
        // MediaType -- OkHttp rejects a "Content-Type" entry in the Part
        // headers with IllegalArgumentException("Unexpected header: Content-Type").
        val jsonMedia = "application/json; charset=UTF-8".toMediaType()
        val multipart = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(metadata.toString().toRequestBody(jsonMedia))
            .addPart(contentJson.toRequestBody(jsonMedia))
            .build()
        val req = Request.Builder()
            .url(uploadBase.newBuilder().addQueryParameter("uploadType", "multipart").build())
            .post(multipart)
            .header("Authorization", "Bearer $token")
            .build()
        okHttp.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("create $fileName failed: HTTP ${resp.code} $body")
            val obj = json.parseToJsonElement(body).jsonObject
            return obj["id"]?.jsonPrimitive?.content
                ?: error("create $fileName: missing id in response")
        }
    }

    private fun updateContent(token: String, fileId: String, contentJson: String) {
        val req = Request.Builder()
            .url(uploadBase.newBuilder().addPathSegment(fileId).addQueryParameter("uploadType", "media").build())
            .patch(contentJson.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .header("Authorization", "Bearer $token")
            .build()
        okHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val body = resp.body?.string()
                error("update $fileId failed: HTTP ${resp.code} $body")
            }
        }
    }

    /** Delete a file by id. Idempotent, a 404 is treated as success. */
    suspend fun delete(token: String, fileId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(driveBase.newBuilder().addPathSegment(fileId).build())
                .delete()
                .header("Authorization", "Bearer $token")
                .build()
            okHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 404) {
                    error("delete $fileId failed: HTTP ${resp.code}")
                }
            }
        }
    }

    /** List all AppData file metadata (id + name + modifiedTime). */
    suspend fun listAll(token: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val url = driveBase.newBuilder()
            .addQueryParameter("spaces", "appDataFolder")
            .addQueryParameter("fields", "files(id,name,modifiedTime)")
            .build()
        val req = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $token")
            .build()
        runCatching {
            okHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching emptyList()
                val body = resp.body?.string().orEmpty()
                val tree = json.parseToJsonElement(body).jsonObject
                tree["files"]?.jsonArray.orEmpty().mapNotNull { el ->
                    val o = el.jsonObject
                    val id = o["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val name = o["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    id to name
                }
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val TAG = "DriveAppDataClient"
    }
}
