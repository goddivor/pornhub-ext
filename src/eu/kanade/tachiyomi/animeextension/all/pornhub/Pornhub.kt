package eu.kanade.tachiyomi.animeextension.all.pornhub

import android.app.Application
import android.content.SharedPreferences
import android.os.Build
import android.text.Html
import android.text.SpannableString
import android.text.Spanned
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.all.pornhub.extractors.PhCdnExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class Pornhub : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Pornhub"

    override val baseUrl = "https://www.pornhub.com"

    override val lang = "all"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.5")
        .add("Referer", baseUrl)

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String {
        Log.d("PornhubExt", "popularAnimeSelector called")
        return "li.pcVideoListItem"
    }

    override fun popularAnimeRequest(page: Int): Request {
        val url = "$baseUrl/video?page=$page"
        Log.d("PornhubExt", "popularAnimeRequest: $url")
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val selector = popularAnimeSelector()
        val elements = document.select(selector)

        Log.d("PornhubExt", "popularAnimeParse - Found ${elements.size} total elements")

        // Filter out invalid videos before parsing
        val validAnimes = elements.mapNotNull { element ->
            try {
                val linkElement = element.selectFirst("a.linkVideoThumb")
                    ?: element.selectFirst("div.wrap div.phimage a")
                    ?: element.selectFirst("a[href^=/view_video]")

                val href = linkElement?.attr("href") ?: ""

                // Skip videos with invalid URLs
                if (href.isEmpty() || href.startsWith("javascript:") || !href.startsWith("/view_video")) {
                    Log.d("PornhubExt", "Filtering out video with invalid URL: $href")
                    return@mapNotNull null
                }

                popularAnimeFromElement(element)
            } catch (e: Exception) {
                Log.d("PornhubExt", "Error parsing element: ${e.message}")
                null
            }
        }

        Log.d("PornhubExt", "popularAnimeParse - ${validAnimes.size} valid videos after filtering")

        val hasNextPage = document.selectFirst(popularAnimeNextPageSelector()) != null
        return AnimesPage(validAnimes, hasNextPage)
    }

    override fun popularAnimeFromElement(element: Element): SAnime {
        Log.d("PornhubExt", "popularAnimeFromElement called with element: ${element.className()}")
        val anime = SAnime.create()

        // Try different selectors to find the link
        var linkElement = element.selectFirst("a.linkVideoThumb")
        if (linkElement == null) {
            linkElement = element.selectFirst("div.wrap div.phimage a")
        }
        if (linkElement == null) {
            linkElement = element.selectFirst("a[href^=/view_video]")
        }

        val href = linkElement?.attr("href") ?: ""
        val title = fromHtml(linkElement?.attr("title")).toString()
        val imgElement = linkElement?.selectFirst("img")
        val thumbnail = imgElement?.attr("data-image")?.ifEmpty {
            imgElement.attr("src")
        } ?: ""

        Log.d("PornhubExt", "Found video - Title: $title, URL: $href, Thumbnail: ${thumbnail.take(50)}...")

        anime.setUrlWithoutDomain(href)
        anime.title = title
        anime.thumbnail_url = thumbnail
        return anime
    }

    override fun popularAnimeNextPageSelector(): String {
        Log.d("PornhubExt", "popularAnimeNextPageSelector called")
        return "div.wrapper"
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val document = response.asJsoup()
        val jsonString = document.selectFirst("script[type=\"application/ld+json\"]").data()
        val jsonData = json.decodeFromString<VideoDetail>(jsonString)
        val epDate = try {
            val dateParts = jsonData.uploadDate.toString().split("-")
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse("${dateParts[0]}-${dateParts[1]}-${dateParts[2]}")
        } catch (_: Exception) { null }
        val episode = SEpisode.create()
        episode.name = "Video"
        if (epDate != null) episode.date_upload = epDate.time
        episode.setUrlWithoutDomain(response.request.url.toString())
        episodes.add(episode)

        return episodes
    }

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeFromElement(element: Element) = throw Exception("not used")

    @OptIn(ExperimentalSerializationApi::class)
    override fun videoListParse(response: Response): List<Video> {
        val url = response.request.url.toString()
        return PhCdnExtractor(client).videoFromUrl(url)
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "720p")
        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality == quality) {
                    newList.add(preferred, video)
                    preferred++
                } else {
                    newList.add(video)
                }
            }
            return newList
        }
        return this
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        // Extract filter values
        var categoryPath = ""
        var pornstarPath = ""
        var sortParam = ""
        var hdParam = ""
        var durationParam = ""

        filters.forEach { filter ->
            when (filter) {
                is CategoryFilter -> {
                    categoryPath = filter.toUriPart()
                }
                is PornstarFilter -> {
                    pornstarPath = filter.toUriPart()
                }
                is SortFilter -> {
                    if (filter.state > 0) {
                        sortParam = "o=${filter.toUriPart()}"
                    }
                }
                is HDFilter -> {
                    if (filter.state) {
                        hdParam = "hd=1"
                    }
                }
                is DurationFilter -> {
                    durationParam = filter.toUriPart()
                }
                else -> {}
            }
        }

        val url = if (query.isNotEmpty()) {
            // Search mode
            val params = mutableListOf("search=$query")
            if (sortParam.isNotEmpty()) params.add(sortParam)
            if (hdParam.isNotEmpty()) params.add(hdParam)
            if (durationParam.isNotEmpty()) params.add(durationParam)
            params.add("page=$page")
            "$baseUrl/video/search?${params.joinToString("&")}"
        } else if (pornstarPath.isNotEmpty()) {
            // Pornstar browse mode
            val params = mutableListOf<String>()
            if (sortParam.isNotEmpty()) params.add(sortParam)
            if (hdParam.isNotEmpty()) params.add(hdParam)
            if (durationParam.isNotEmpty()) params.add(durationParam)
            params.add("page=$page")
            val separator = if (pornstarPath.contains("?")) "&" else "?"
            "$baseUrl$pornstarPath$separator${params.joinToString("&")}"
        } else if (categoryPath.isNotEmpty()) {
            // Category browse mode
            val params = mutableListOf<String>()
            if (sortParam.isNotEmpty()) params.add(sortParam)
            if (hdParam.isNotEmpty()) params.add(hdParam)
            if (durationParam.isNotEmpty()) params.add(durationParam)
            params.add("page=$page")
            // Check if categoryPath already contains ? to decide separator
            val separator = if (categoryPath.contains("?")) "&" else "?"
            "$baseUrl$categoryPath$separator${params.joinToString("&")}"
        } else {
            // Default browse mode
            val params = mutableListOf<String>()
            if (sortParam.isNotEmpty()) params.add(sortParam)
            if (hdParam.isNotEmpty()) params.add(hdParam)
            if (durationParam.isNotEmpty()) params.add(durationParam)
            params.add("page=$page")
            "$baseUrl/video?${params.joinToString("&")}"
        }

        Log.d("PornhubExt", "searchAnimeRequest: $url")
        return GET(url, headers)
    }

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("Les filtres sont ignorés si vous utilisez la recherche de texte"),
        CategoryFilter(),
        PornstarFilter(),
        SortFilter(),
        HDFilter(),
        DurationFilter(),
    )

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val selector = searchAnimeSelector()
        val elements = document.select(selector)

        Log.d("PornhubExt", "searchAnimeParse - Found ${elements.size} total elements")

        // Filter out invalid videos before parsing
        val validAnimes = elements.mapNotNull { element ->
            try {
                val linkElement = element.selectFirst("a.linkVideoThumb")
                    ?: element.selectFirst("div.wrap div.phimage a")
                    ?: element.selectFirst("a[href^=/view_video]")

                val href = linkElement?.attr("href") ?: ""

                // Skip videos with invalid URLs
                if (href.isEmpty() || href.startsWith("javascript:") || !href.startsWith("/view_video")) {
                    Log.d("PornhubExt", "Filtering out video with invalid URL: $href")
                    return@mapNotNull null
                }

                searchAnimeFromElement(element)
            } catch (e: Exception) {
                Log.d("PornhubExt", "Error parsing element: ${e.message}")
                null
            }
        }

        Log.d("PornhubExt", "searchAnimeParse - ${validAnimes.size} valid videos after filtering")

        val hasNextPage = document.selectFirst(searchAnimeNextPageSelector()) != null
        return AnimesPage(validAnimes, hasNextPage)
    }

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = "li.pcVideoListItem"

    @OptIn(ExperimentalSerializationApi::class)
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val jsonString = document.selectFirst("script[type=\"application/ld+json\"]").data()
        val jsonData = json.decodeFromString<VideoDetail>(jsonString)

        anime.title = fromHtml(jsonData.name.toString()).toString()
        anime.author = jsonData.author.toString()
        anime.thumbnail_url = jsonData.thumbnailUrl
        anime.description = fromHtml(jsonData.description.toString()).toString()
        anime.genre = document.select("div.video-info-row div.categoriesWrapper a.item").joinToString { it.text() }
        anime.status = SAnime.COMPLETED
        return anime
    }

    @Suppress("DEPRECATION")
    private fun fromHtml(html: String?): Spanned? {
        return if (html == null) SpannableString("")
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
        else Html.fromHtml(html)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/video?o=cm&page=$page"
        Log.d("PornhubExt", "latestUpdatesRequest: $url")
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val selector = latestUpdatesSelector()
        val elements = document.select(selector)

        Log.d("PornhubExt", "latestUpdatesParse - Found ${elements.size} total elements")

        // Filter out invalid videos before parsing
        val validAnimes = elements.mapNotNull { element ->
            try {
                val linkElement = element.selectFirst("a.linkVideoThumb")
                    ?: element.selectFirst("div.wrap div.phimage a")
                    ?: element.selectFirst("a[href^=/view_video]")

                val href = linkElement?.attr("href") ?: ""

                // Skip videos with invalid URLs
                if (href.isEmpty() || href.startsWith("javascript:") || !href.startsWith("/view_video")) {
                    Log.d("PornhubExt", "Filtering out video with invalid URL: $href")
                    return@mapNotNull null
                }

                latestUpdatesFromElement(element)
            } catch (e: Exception) {
                Log.d("PornhubExt", "Error parsing element: ${e.message}")
                null
            }
        }

        Log.d("PornhubExt", "latestUpdatesParse - ${validAnimes.size} valid videos after filtering")

        val hasNextPage = document.selectFirst(latestUpdatesNextPageSelector()) != null
        return AnimesPage(validAnimes, hasNextPage)
    }

    override fun latestUpdatesSelector(): String = "li.pcVideoListItem"

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "240p")
            entryValues = arrayOf("1080p", "720p", "480p", "240p")
            setDefaultValue("480p")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
    }

    // Filters
    private class SortFilter : AnimeFilter.Select<String>(
        "Trier par",
        arrayOf("Featured", "Most Viewed", "Top Rated", "Hottest", "Most Recent", "Longest")
    ) {
        fun toUriPart() = when (state) {
            1 -> "mv" // Most Viewed
            2 -> "tr" // Top Rated
            3 -> "ht" // Hottest
            4 -> "cm" // Most Recent (Chronological)
            5 -> "lg" // Longest
            else -> ""
        }
    }

    private class CategoryFilter : AnimeFilter.Select<String>(
        "Catégorie",
        arrayOf(
            "Toutes",
            "18-25", "Amateur", "Anal", "Asian", "Babe", "BBW", "Big Ass", "Big Dick", "Big Tits",
            "Blonde", "Blowjob", "Brunette", "Bukkake", "College (18+)", "Compilation", "Creampie",
            "Cumshot", "Ebony", "Feet", "Fetish", "Gangbang", "Hentai", "Indian",
            "Interracial", "Japanese", "Latina", "Lesbian", "Massage", "Masturbation", "Mature",
            "MILF", "Orgy", "Pornstar", "POV", "Public", "Reality", "Red Head", "Russian",
            "Small Tits", "Solo Female", "Solo Male", "Squirt", "Step Fantasy",
            "Threesome", "Transgender", "Vintage", "Webcam"
        )
    ) {
        fun toUriPart() = when (state) {
            1 -> "/categories/teen" // 18-25
            2 -> "/video?c=3" // Amateur
            3 -> "/video?c=35" // Anal
            4 -> "/video?c=1" // Asian
            5 -> "/categories/babe" // Babe
            6 -> "/video?c=6" // BBW
            7 -> "/video?c=4" // Big Ass
            8 -> "/video?c=7" // Big Dick
            9 -> "/video?c=8" // Big Tits
            10 -> "/video?c=9" // Blonde
            11 -> "/video?c=13" // Blowjob
            12 -> "/video?c=11" // Brunette
            13 -> "/video?c=14" // Bukkake
            14 -> "/categories/college" // College (18+)
            15 -> "/video?c=57" // Compilation
            16 -> "/video?c=15" // Creampie
            17 -> "/video?c=16" // Cumshot
            18 -> "/video?c=17" // Ebony
            19 -> "/video?c=93" // Feet
            20 -> "/video?c=18" // Fetish
            21 -> "/video?c=80" // Gangbang
            22 -> "/categories/hentai" // Hentai
            23 -> "/video?c=101" // Indian
            24 -> "/video?c=25" // Interracial
            25 -> "/video?c=111" // Japanese
            26 -> "/video?c=26" // Latina
            27 -> "/video?c=27" // Lesbian
            28 -> "/video?c=78" // Massage
            29 -> "/video?c=22" // Masturbation
            30 -> "/video?c=28" // Mature
            31 -> "/video?c=29" // MILF
            32 -> "/video?c=2" // Orgy
            33 -> "/categories/pornstar" // Pornstar
            34 -> "/video?c=41" // POV
            35 -> "/video?c=24" // Public
            36 -> "/video?c=31" // Reality
            37 -> "/video?c=42" // Red Head
            38 -> "/video?c=99" // Russian
            39 -> "/video?c=59" // Small Tits
            40 -> "/video?c=492" // Solo Female
            41 -> "/video?c=92" // Solo Male
            42 -> "/video?c=69" // Squirt
            43 -> "/video?c=444" // Step Fantasy
            44 -> "/video?c=65" // Threesome
            45 -> "/transgender" // Transgender
            46 -> "/video?c=43" // Vintage
            47 -> "/video?c=61" // Webcam
            else -> ""
        }
    }

    private class PornstarFilter : AnimeFilter.Select<String>(
        "Pornstar",
        arrayOf(
            "Tous",
            "Comatozze", "Sweetie Fox", "Alex Adams", "Gattouz0", "Candy Love",
            "Lana Rhoades", "Angela White", "Bonnie Blue", "Abella Danger", "Violet Myers",
            "Johnny Sins", "Eva Elfie", "Riley Reid", "Lily Phillips", "Diana Rider",
            "Lexi Luna", "Mia Malkova", "Jak Knife", "Jordi El Nino Polla", "Scott Stark",
            "Cory Chase", "Natasha Nice", "Serenity Cox", "Lexi Lore", "Maximo Garcia",
            "Lena Paul", "Yasmina Khan", "Brandi Love", "Martina Smeraldi", "Eliza Ibarra",
            "Anastangel", "Mariana Martix", "Alina Angel", "Melody Marks", "Jenny Kitty",
            "Rae Lil Black", "Rosie Rider", "Elly Clutch", "Blake Blossom", "Tru Kait",
            "HottiesTwo", "Ava Addams", "Skylar Vox", "Hailey Rose", "Savannah Bond",
            "Molly Little", "Lulu Chu", "MewSlut", "Anna Cherry7", "Sisi Rose",
            "Jessica Sodi", "Gabbie Carter", "Valentina Nappi", "Ruth Lee", "Alexis Fawx",
            "Coco Lovelock", "Alina Rai", "Danny D", "Sara Jay", "Kayley Gunner",
            "Kenzie Reeves", "Lauren Phillips", "Amateurtwo", "Cherie DeVille", "Adriana Chechik",
            "Danika Mori", "Kendra Lust", "Cutie Kim", "Lexis Star", "Sky Bri",
            "Nicole Aniston", "Dani Daniels", "Alyx Star", "Autumn Falls", "Alexis Texas",
            "Pamsnusnu", "Frances Bentley", "Kenzie Madison", "Julie Jess", "Vero Buffone",
            "Shinaryen", "Elsa Jean", "Luna Okko", "Solazola", "Salome Gil",
            "Polly Yangs", "Creamy Spot", "Tatiana Alvarez", "Iris Rodriguez", "FantasyBabe",
            "Reislin", "LeoLulu", "Crystal Lust", "Elle Lee", "Hazel Moore",
            "Luna Star", "Miss Ary", "Gina Valentina", "msbreewc", "Anny Walker"
        )
    ) {
        fun toUriPart() = when (state) {
            1 -> "/model/comatozze/videos"
            2 -> "/model/sweetie-fox/videos"
            3 -> "/pornstar/alex-adams/videos"
            4 -> "/model/gattouz0/videos"
            5 -> "/model/candy-love/videos"
            6 -> "/pornstar/lana-rhoades/videos"
            7 -> "/pornstar/angela-white/videos"
            8 -> "/pornstar/bonnie-blue/videos"
            9 -> "/pornstar/abella-danger/videos"
            10 -> "/pornstar/violet-myers/videos"
            11 -> "/pornstar/johnny-sins/videos"
            12 -> "/pornstar/eva-elfie/videos"
            13 -> "/pornstar/riley-reid/videos"
            14 -> "/pornstar/lily-phillips/videos"
            15 -> "/model/diana-rider/videos"
            16 -> "/pornstar/lexi-luna/videos"
            17 -> "/pornstar/mia-malkova/videos"
            18 -> "/pornstar/jak-knife/videos"
            19 -> "/pornstar/jordi-el-nino-polla/videos"
            20 -> "/model/scott-stark/videos"
            21 -> "/pornstar/cory-chase/videos"
            22 -> "/pornstar/natasha-nice/videos"
            23 -> "/model/serenity-cox/videos"
            24 -> "/pornstar/lexi-lore/videos"
            25 -> "/pornstar/maximo-garcia/videos"
            26 -> "/pornstar/lena-paul/videos"
            27 -> "/pornstar/yasmina-khan/videos"
            28 -> "/pornstar/brandi-love/videos"
            29 -> "/pornstar/martina-smeraldi/videos"
            30 -> "/pornstar/eliza-ibarra/videos"
            31 -> "/model/anastangel/videos"
            32 -> "/pornstar/mariana-martix/videos"
            33 -> "/pornstar/alina-angel/videos"
            34 -> "/pornstar/melody-marks/videos"
            35 -> "/model/jenny-kitty/videos"
            36 -> "/pornstar/rae-lil-black/videos"
            37 -> "/model/rosie-rider/videos"
            38 -> "/model/elly-clutch/videos"
            39 -> "/pornstar/blake-blossom/videos"
            40 -> "/pornstar/tru-kait/videos"
            41 -> "/model/hottiestwo/videos"
            42 -> "/pornstar/ava-addams/videos"
            43 -> "/pornstar/skylar-vox/videos"
            44 -> "/pornstar/hailey-rose/videos"
            45 -> "/pornstar/savannah-bond/videos"
            46 -> "/pornstar/molly-little/videos"
            47 -> "/pornstar/lulu-chu/videos"
            48 -> "/model/mewslut/videos"
            49 -> "/model/anna-cherry7/videos"
            50 -> "/pornstar/sisi-rose/videos"
            51 -> "/pornstar/jessica-sodi/videos"
            52 -> "/pornstar/gabbie-carter/videos"
            53 -> "/pornstar/valentina-nappi/videos"
            54 -> "/model/ruth-lee/videos"
            55 -> "/pornstar/alexis-fawx/videos"
            56 -> "/pornstar/coco-lovelock/videos"
            57 -> "/model/alina-rai/videos"
            58 -> "/pornstar/danny-d/videos"
            59 -> "/pornstar/sara-jay/videos"
            60 -> "/pornstar/kayley-gunner/videos"
            61 -> "/pornstar/kenzie-reeves/videos"
            62 -> "/pornstar/lauren-phillips/videos"
            63 -> "/model/amateurtwo/videos"
            64 -> "/pornstar/cherie-deville/videos"
            65 -> "/pornstar/adriana-chechik/videos"
            66 -> "/pornstar/danika-mori/videos"
            67 -> "/pornstar/kendra-lust/videos"
            68 -> "/model/cutie-kim/videos"
            69 -> "/model/lexis-star/videos"
            70 -> "/pornstar/sky-bri/videos"
            71 -> "/pornstar/nicole-aniston/videos"
            72 -> "/pornstar/dani-daniels/videos"
            73 -> "/pornstar/alyx-star/videos"
            74 -> "/pornstar/autumn-falls/videos"
            75 -> "/pornstar/alexis-texas/videos"
            76 -> "/model/pamsnusnu/videos"
            77 -> "/pornstar/frances-bentley/videos"
            78 -> "/pornstar/kenzie-madison/videos"
            79 -> "/model/julie-jess/videos"
            80 -> "/model/vero-buffone/videos"
            81 -> "/model/shinaryen/videos"
            82 -> "/pornstar/elsa-jean/videos"
            83 -> "/model/luna-okko/videos"
            84 -> "/model/solazola/videos"
            85 -> "/pornstar/salome-gil/videos"
            86 -> "/pornstar/polly-yangs/videos"
            87 -> "/model/creamy-spot/videos"
            88 -> "/pornstar/tatiana-alvarez/videos"
            89 -> "/model/iris-rodriguez/videos"
            90 -> "/model/fantasybabe/videos"
            91 -> "/model/reislin/videos"
            92 -> "/pornstar/leolulu/videos"
            93 -> "/model/crystal-lust/videos"
            94 -> "/pornstar/elle-lee/videos"
            95 -> "/pornstar/hazel-moore/videos"
            96 -> "/pornstar/luna-star/videos"
            97 -> "/model/miss-ary/videos"
            98 -> "/pornstar/gina-valentina/videos"
            99 -> "/model/msbreewc/videos"
            100 -> "/model/anny-walker/videos"
            else -> ""
        }
    }

    private class HDFilter : AnimeFilter.CheckBox("HD seulement", false)

    private class DurationFilter : AnimeFilter.Select<String>(
        "Durée",
        arrayOf("Toutes", "0-10 min", "10-20 min", "20+ min")
    ) {
        fun toUriPart() = when (state) {
            1 -> "min_duration=0&max_duration=10"
            2 -> "min_duration=10&max_duration=20"
            3 -> "min_duration=20"
            else -> ""
        }
    }

    @Serializable
    data class VideoDetail(
        @SerialName("@context") var context: String? = null,
        @SerialName("@type") var type: String? = null,
        @SerialName("name") var name: String? = null,
        @SerialName("embedUrl") var embedUrl: String? = null,
        @SerialName("duration") var duration: String? = null,
        @SerialName("thumbnailUrl") var thumbnailUrl: String? = null,
        @SerialName("uploadDate") var uploadDate: String? = null,
        @SerialName("description") var description: String? = null,
        @SerialName("author") var author: String? = null,
        @SerialName("interactionStatistic") var interactionStatistic: ArrayList<InteractionStatistic> = arrayListOf()
    )

    @Serializable
    data class InteractionStatistic(
        @SerialName("@type") var type: String? = null,
        @SerialName("interactionType") var interactionType: String? = null,
        @SerialName("userInteractionCount") var userInteractionCount: String? = null
    )
}
