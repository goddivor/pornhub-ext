package eu.kanade.tachiyomi.animeextension.all.pornhub.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

class PhCdnExtractor(private val client: OkHttpClient) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun videoFromUrl(videoUrl: String): MutableList<Video> {
        val videoList = mutableListOf<Video>()

        try {
            val pattern = Regex("(?<=viewkey=)[^&]+")
            val key = pattern.find(videoUrl)?.value

            if (key == null) {
                return videoList
            }

            val pageUrl = "https://www.pornhub.com/view_video.php?viewkey=$key"
            val document = client.newCall(GET(pageUrl)).execute().asJsoup()

            val scripts = document.select("script")

            val flashvarsRegex = Regex("var\\s+flashvars[_\\w]*\\s*=\\s*\\{")
            var scriptPart: String? = null
            var matchStart = -1
            for (script in scripts) {
                val html = script.html()
                if (!html.contains("mediaDefinitions")) continue
                val match = flashvarsRegex.find(html) ?: continue
                scriptPart = html
                matchStart = match.range.last
                break
            }

            if (scriptPart == null || matchStart == -1) {
                return videoList
            }

            val jsonStart = matchStart
            var braceCount = 0
            var jsonEnd = -1
            var inString = false
            var escapeNext = false

            for (i in jsonStart until scriptPart.length) {
                val char = scriptPart[i]

                if (escapeNext) {
                    escapeNext = false
                    continue
                }

                when (char) {
                    '\\' -> escapeNext = true
                    '"' -> inString = !inString
                    '{' -> if (!inString) braceCount++
                    '}' -> if (!inString) {
                        braceCount--
                        if (braceCount == 0) {
                            jsonEnd = i + 1
                            break
                        }
                    }
                }
            }

            if (jsonEnd == -1) {
                return videoList
            }

            val flashvarsJson = scriptPart.substring(jsonStart, jsonEnd)

            val flashvarsElement = try {
                json.parseToJsonElement(flashvarsJson).jsonObject
            } catch (e: Exception) {
                return videoList
            }

            val mediaDefinitions = flashvarsElement["mediaDefinitions"]?.jsonArray
            if (mediaDefinitions == null || mediaDefinitions.isEmpty()) {
                return videoList
            }

            videoList.addAll(processMediaDefinitions(mediaDefinitions, depth = 0))

            videoList.sortByDescending { extractHeight(it.quality) }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return videoList
    }

    private fun processMediaDefinitions(mediaDefinitions: JsonArray, depth: Int): List<Video> {
        val out = mutableListOf<Video>()
        for (mediaDef in mediaDefinitions) {
            val mediaObj = mediaDef.jsonObject
            val format = mediaObj["format"]?.jsonPrimitive?.content
            val url = mediaObj["videoUrl"]?.jsonPrimitive?.content

            val qualityStr = when (val qualityElement = mediaObj["quality"]) {
                is JsonPrimitive -> qualityElement.content
                else -> null
            }

            if (url.isNullOrEmpty()) continue

            when (format) {
                "hls" -> {
                    if (url.contains("/get_media")) {
                        out.addAll(fetchGetMedia(url, depth))
                    } else {
                        out.addAll(parseHlsVariants(url))
                    }
                }
                "mp4" -> {
                    if (url.contains("/get_media")) {
                        out.addAll(fetchGetMedia(url, depth))
                    } else {
                        val label = if (!qualityStr.isNullOrEmpty()) "${qualityStr}p" else "MP4"
                        out.add(Video(url, label, url))
                    }
                }
            }
        }
        return out
    }

    private fun fetchGetMedia(url: String, depth: Int): List<Video> {
        if (depth >= 2) {
            return emptyList()
        }
        return try {
            val body = client.newCall(GET(url)).execute().body!!.string()
            val element = json.parseToJsonElement(body)
            val arr = when {
                element is JsonArray -> element
                else -> element.jsonObject["mediaDefinitions"]?.jsonArray ?: JsonArray(emptyList())
            }
            processMediaDefinitions(arr, depth + 1)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseHlsVariants(masterUrl: String): List<Video> {
        val variants = mutableListOf<Video>()
        try {
            val masterPlaylist = client.newCall(GET(masterUrl)).execute().body!!.string()
            val lines = masterPlaylist.split("\n").map { it.trim() }
            val baseUrl = masterUrl.substringBeforeLast("/")
            val resolutionRegex = Regex("RESOLUTION=\\d+x(\\d+)")

            for (i in lines.indices) {
                val line = lines[i]
                if (!line.startsWith("#EXT-X-STREAM-INF")) continue
                if (i + 1 >= lines.size) continue

                val variantLine = lines[i + 1]
                if (variantLine.isEmpty() || variantLine.startsWith("#")) continue

                val variantUrl = if (variantLine.startsWith("http")) variantLine else "$baseUrl/$variantLine"
                val height = resolutionRegex.find(line)?.groupValues?.get(1)
                val label = if (height != null) "${height}p (HLS)" else "HLS"

                variants.add(Video(variantUrl, label, variantUrl))
            }
        } catch (e: Exception) {
        }
        return variants
    }

    private fun extractHeight(quality: String): Int {
        return Regex("(\\d+)p").find(quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }
}
