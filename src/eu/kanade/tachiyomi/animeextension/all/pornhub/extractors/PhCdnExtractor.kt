package eu.kanade.tachiyomi.animeextension.all.pornhub.extractors

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
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
                Log.e("PhCdnExtractor", "Unable to extract viewkey from URL: $videoUrl")
                return videoList
            }

            Log.d("PhCdnExtractor", "Extracting video with key: $key")
            val embedUrl = "https://www.pornhub.com/embed/$key"
            val document = client.newCall(GET(embedUrl)).execute().asJsoup()

            // Try to find the script containing video data
            val scripts = document.select("body script")
            Log.d("PhCdnExtractor", "Found ${scripts.size} scripts in embed page")

            var scriptPart: String? = null
            for (script in scripts) {
                val html = script.html()
                if (html.contains("var flashvars") && html.contains("mediaDefinitions")) {
                    scriptPart = html
                    Log.d("PhCdnExtractor", "Found flashvars script")
                    break
                }
            }

            if (scriptPart == null) {
                Log.e("PhCdnExtractor", "Could not find flashvars script in embed page")
                return videoList
            }

            // Extract the flashvars JSON object
            val flashvarsStart = scriptPart.indexOf("var flashvars = ")
            if (flashvarsStart == -1) {
                Log.e("PhCdnExtractor", "Could not find 'var flashvars = ' in script")
                return videoList
            }

            val jsonStart = flashvarsStart + "var flashvars = ".length
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
                Log.e("PhCdnExtractor", "Could not find end of flashvars JSON")
                return videoList
            }

            val flashvarsJson = scriptPart.substring(jsonStart, jsonEnd)
            Log.d("PhCdnExtractor", "Extracted flashvars JSON (first 200 chars): ${flashvarsJson.take(200)}")

            // Parse JSON manually to handle quality field that can be string or array
            val flashvarsElement = try {
                json.parseToJsonElement(flashvarsJson).jsonObject
            } catch (e: Exception) {
                Log.e("PhCdnExtractor", "Error parsing flashvars JSON: ${e.message}")
                Log.d("PhCdnExtractor", "Full JSON: $flashvarsJson")
                return videoList
            }

            val mediaDefinitions = flashvarsElement["mediaDefinitions"]?.jsonArray
            if (mediaDefinitions == null || mediaDefinitions.isEmpty()) {
                Log.e("PhCdnExtractor", "No media definitions found in flashvars")
                return videoList
            }

            // Find HLS or MP4 sources
            for (mediaDef in mediaDefinitions) {
                val mediaObj = mediaDef.jsonObject
                val format = mediaObj["format"]?.jsonPrimitive?.content
                val videoUrl = mediaObj["videoUrl"]?.jsonPrimitive?.content

                // Quality can be either a string or an empty array
                val quality = when (val qualityElement = mediaObj["quality"]) {
                    is JsonPrimitive -> qualityElement.content
                    else -> "Unknown" // If it's an array or null
                }

                if (videoUrl.isNullOrEmpty()) continue

                Log.d("PhCdnExtractor", "Found video: format=$format quality=$quality url=$videoUrl")

                when (format) {
                    "hls" -> {
                        // HLS format - get master playlist
                        try {
                            val masterPlaylist = client.newCall(GET(videoUrl)).execute().body!!.string()
                            val lines = masterPlaylist.split("\n")

                            // Find the best quality variant
                            var bestUrl: String? = null
                            for (i in lines.indices) {
                                val line = lines[i]
                                if (line.startsWith("#EXT-X-STREAM-INF")) {
                                    if (i + 1 < lines.size) {
                                        val variantUrl = lines[i + 1]
                                        if (!variantUrl.startsWith("#")) {
                                            // Make absolute URL if needed
                                            bestUrl = if (variantUrl.startsWith("http")) {
                                                variantUrl
                                            } else {
                                                val baseUrl = videoUrl.substringBeforeLast("/")
                                                "$baseUrl/$variantUrl"
                                            }
                                            break
                                        }
                                    }
                                }
                            }

                            if (bestUrl != null) {
                                videoList.add(Video(bestUrl, "${quality}p (HLS)", bestUrl))
                                Log.d("PhCdnExtractor", "Added HLS video: $bestUrl")
                            }
                        } catch (e: Exception) {
                            Log.e("PhCdnExtractor", "Error processing HLS: ${e.message}")
                        }
                    }
                    "mp4" -> {
                        // Direct MP4 link
                        videoList.add(Video(videoUrl, "${quality}p", videoUrl))
                        Log.d("PhCdnExtractor", "Added MP4 video: $videoUrl")
                    }
                }
            }

            Log.d("PhCdnExtractor", "Total videos found: ${videoList.size}")
        } catch (e: Exception) {
            Log.e("PhCdnExtractor", "Error extracting video: ${e.message}", e)
            e.printStackTrace()
        }

        return videoList
    }
}
