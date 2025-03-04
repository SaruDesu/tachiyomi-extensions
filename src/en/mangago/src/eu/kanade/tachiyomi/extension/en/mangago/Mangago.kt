package eu.kanade.tachiyomi.extension.en.mangago

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.util.Base64
import app.cash.quickjs.QuickJs
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Mangago : ParsedHttpSource() {

    override val name = "Mangago"

    override val baseUrl = "https://www.mangago.me"

    override val lang = "en"

    override val supportsLatest = true

    override val client = network.cloudflareClient.newBuilder().addInterceptor { chain ->
        val response = chain.proceed(chain.request())

        val key = response.request.url.queryParameter("desckey") ?: return@addInterceptor response
        val cols = response.request.url.queryParameter("cols")?.toIntOrNull() ?: return@addInterceptor response

        val image = unscrambleImage(response.body!!.byteStream(), key, cols)
        val body = image.toResponseBody("image/jpeg".toMediaTypeOrNull())
        return@addInterceptor response.newBuilder()
            .body(body)
            .build()
    }.build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Cookie", cookiesHeader)

    private val cookiesHeader by lazy {
        val cookies = mutableMapOf<String, String>()

        // Needed for correct page ordering
        cookies["_m_superu"] = "1"

        buildCookies(cookies)
    }

    private val genreListingSelector = ".updatesli"

    private val genreListingNextPageSelector = ".current+li > a"

    private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.ENGLISH)

    private fun mangaFromElement(element: Element) = SManga.create().apply {
        val linkElement = element.selectFirst(".thm-effect")

        setUrlWithoutDomain(linkElement.attr("href"))
        title = linkElement.attr("title")

        val thumbnailElem = linkElement.selectFirst("img")
        thumbnail_url = thumbnailElem.attr("abs:data-src").ifBlank { thumbnailElem.attr("abs:src") }
    }

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/genre/all/$page/?f=1&o=1&sortby=view&e=", headers)

    override fun popularMangaSelector(): String = genreListingSelector

    override fun popularMangaFromElement(element: Element) = mangaFromElement(element)

    override fun popularMangaNextPageSelector() = genreListingNextPageSelector

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/genre/all/$page/?f=1&o=1&sortby=update_date&e=", headers)

    override fun latestUpdatesSelector() = genreListingSelector

    override fun latestUpdatesFromElement(element: Element) = mangaFromElement(element)

    override fun latestUpdatesNextPageSelector() = genreListingNextPageSelector

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = if (query.isNotBlank()) {
            "$baseUrl/r/l_search".toHttpUrl().newBuilder()
                .addQueryParameter("name", query)
                .addQueryParameter("page", page.toString())
                .build().toString()
        } else {
            "$baseUrl/genre/".toHttpUrl().newBuilder().apply {
                val genres = mutableListOf<String>()
                val genresEx = mutableListOf<String>()

                filters.ifEmpty { getFilterList() }.forEach {
                    when (it) {
                        is UriFilter -> it.addToUrl(this)
                        is GenreFilterGroup -> it.state.forEach { genre ->
                            when (genre.state) {
                                Filter.TriState.STATE_EXCLUDE -> genresEx.add(genre.name)
                                Filter.TriState.STATE_INCLUDE -> genres.add(genre.name)
                                else -> {}
                            }
                        }
                        else -> {}
                    }
                }

                if (genres.isEmpty()) {
                    addPathSegment("all")
                } else {
                    addPathSegment(genres.joinToString(","))
                }
                addPathSegment(page.toString())

                addQueryParameter("e", genresEx.joinToString(","))
            }.build().toString()
        }
        return GET(url, headers)
    }

    override fun searchMangaSelector() = "$genreListingSelector, .pic_list .box"

    override fun searchMangaFromElement(element: Element) = mangaFromElement(element)

    override fun searchMangaNextPageSelector() = genreListingNextPageSelector

    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        val coverElement = document.select(".left.cover > img")

        title = coverElement.attr("alt")
        thumbnail_url = coverElement.attr("src")
        document.select(".manga_right td").forEach {
            when (it.getElementsByTag("label").text().trim().lowercase()) {
                "status:" -> {
                    status = when (it.selectFirst("span").text().trim().lowercase()) {
                        "ongoing" -> SManga.ONGOING
                        "completed" -> SManga.COMPLETED
                        else -> SManga.UNKNOWN
                    }
                }
                "author:" -> {
                    author = it.selectFirst("a").text()
                }
                "genre(s):" -> {
                    genre = it.getElementsByTag("a").joinToString { it.text() }
                }
            }
        }
        description = document.selectFirst(".manga_summary").ownText().trim()
    }

    override fun chapterListSelector() = "#chapter_table > tbody > tr"

    override fun chapterFromElement(element: Element) = SChapter.create().apply {
        val link = element.getElementsByTag("a")

        setUrlWithoutDomain(link.attr("href"))
        name = link.text().trim()
        date_upload = kotlin.runCatching {
            dateFormat.parse(element.getElementsByClass("no").text().trim())?.time
        }.getOrNull() ?: 0L
    }

    override fun pageListParse(document: Document): List<Page> {
        val imgsrcsScript = document.selectFirst("script:containsData(imgsrcs)")?.html()
            ?: throw Exception("Could not find imgsrcs")
        val imgsrcRaw = imgSrcsRegex.find(imgsrcsScript)?.groupValues?.get(1)
            ?: throw Exception("Could not extract imgsrcs")
        val imgsrcs = Base64.decode(imgsrcRaw, Base64.DEFAULT)

        val chapterJsUrl = document.getElementsByTag("script").first {
            it.attr("src").contains("chapter.js", ignoreCase = true)
        }.attr("abs:src")

        val obfuscatedChapterJs = client.newCall(GET(chapterJsUrl, headers)).execute().body!!.string()
        val deobfChapterJs = SoJsonV4Deobfuscator.decode(obfuscatedChapterJs)

        val key = findHexEncodedVariable(deobfChapterJs, "key").decodeHex()
        val iv = findHexEncodedVariable(deobfChapterJs, "iv").decodeHex()
        val cipher = Cipher.getInstance(hashCipher)
        val keyS = SecretKeySpec(key, aes)
        cipher.init(Cipher.DECRYPT_MODE, keyS, IvParameterSpec(iv))

        var imageList = cipher.doFinal(imgsrcs).toString(Charsets.UTF_8)

        try {
            val keyLocations = keyLocationRegex.findAll(deobfChapterJs).map {
                it.groupValues[1].toInt()
            }.distinct()

            val unscrambleKey = keyLocations.map {
                imageList[it].toString().toInt()
            }.toList()

            keyLocations.forEachIndexed { idx, it ->
                imageList = imageList.removeRange(it - idx..it - idx)
            }

            imageList = imageList.unscramble(unscrambleKey)
        } catch (e: NumberFormatException) {
            // Only call where it should throw is imageList[it].toString().toInt().
            // This usually means that the list is already unscrambled.
        }

        val cols = deobfChapterJs
            .substringAfter("var widthnum=heightnum=")
            .substringBefore(";")

        return imageList
            .split(",")
            .mapIndexed { idx, it ->
                val url = if (it.contains("cspiclink")) {
                    "$it?desckey=${getDescramblingKey(deobfChapterJs, it)}&cols=$cols"
                } else {
                    it
                }

                Page(idx, imageUrl = url)
            }
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException("Not used")

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Ignored if using text search"),
        StatusFilterGroup(),
        SortFilter(),
        GenreFilterGroup(),
    )

    private interface UriFilter {
        fun addToUrl(builder: HttpUrl.Builder)
    }

    private class StatusFilter(name: String, val query: String) : UriFilter, Filter.CheckBox(name) {
        override fun addToUrl(builder: HttpUrl.Builder) {
            builder.addQueryParameter(query, if (state) "1" else "0")
        }
    }

    private class StatusFilterGroup : UriFilter, Filter.Group<StatusFilter>(
        "Status",
        listOf(
            StatusFilter("Completed", "f"),
            StatusFilter("Ongoing", "o")
        )
    ) {
        override fun addToUrl(builder: HttpUrl.Builder) {
            state.forEach {
                it.addToUrl(builder)
            }
        }
    }

    open class UriPartFilter(
        name: String,
        private val query: String,
        private val vals: Array<Pair<String, String>>,
        private val firstIsUnspecified: Boolean = true,
        state: Int = 0
    ) : UriFilter, Filter.Select<String>(name, vals.map { it.first }.toTypedArray(), state) {
        override fun addToUrl(builder: HttpUrl.Builder) {
            if (state != 0 || !firstIsUnspecified) {
                builder.addQueryParameter(query, vals[state].second)
            }
        }
    }

    private class SortFilter : UriPartFilter(
        "Sort",
        "sortby",
        arrayOf(
            Pair("Random", "random"),
            Pair("Views", "view"),
            Pair("Comment Count", "comment_count"),
            Pair("Creation Date", "create_date"),
            Pair("Update Date", "update_date")
        ),
        state = 1,
    )

    private class GenreFilter(name: String) : Filter.TriState(name)

    private class GenreFilterGroup : Filter.Group<GenreFilter>(
        "Genres",
        listOf(
            GenreFilter("Yaoi"),
            GenreFilter("Doujinshi"),
            GenreFilter("Shounen Ai"),
            GenreFilter("Shoujo"),
            GenreFilter("Yuri"),
            GenreFilter("Romance"),
            GenreFilter("Fantasy"),
            GenreFilter("Comedy"),
            GenreFilter("Smut"),
            GenreFilter("Adult"),
            GenreFilter("School Life"),
            GenreFilter("Mystery"),
            GenreFilter("One Shot"),
            GenreFilter("Ecchi"),
            GenreFilter("Shounen"),
            GenreFilter("Martial Arts"),
            GenreFilter("Shoujo Ai"),
            GenreFilter("Supernatural"),
            GenreFilter("Drama"),
            GenreFilter("Action"),
            GenreFilter("Adventure"),
            GenreFilter("Harem"),
            GenreFilter("Historical"),
            GenreFilter("Horror"),
            GenreFilter("Josei"),
            GenreFilter("Mature"),
            GenreFilter("Mecha"),
            GenreFilter("Psychological"),
            GenreFilter("Sci-fi"),
            GenreFilter("Seinen"),
            GenreFilter("Slice Of Life"),
            GenreFilter("Sports"),
            GenreFilter("Gender Bender"),
            GenreFilter("Tragedy"),
            GenreFilter("Bara"),
            GenreFilter("Shotacon"),
            GenreFilter("Webtoons")
        )
    )

    private fun findHexEncodedVariable(input: String, variable: String): String {
        val regex = Regex("""var $variable\s*=\s*CryptoJS\.enc\.Hex\.parse\("([0-9a-zA-Z]+)"\)""")
        return regex.find(input)?.groupValues?.get(1) ?: ""
    }

    private fun String.unscramble(keys: List<Int>): String {
        var s = this
        keys.reversed().forEach {
            for (i in s.length - 1 downTo it) {
                if (i % 2 != 0) {
                    val temp = s[i - it]
                    s = s.replaceRange(i - it..i - it, s[i].toString())
                    s = s.replaceRange(i..i, temp.toString())
                }
            }
        }
        return s
    }

    private fun unscrambleImage(image: InputStream, key: String, cols: Int): ByteArray {
        val bitmap = BitmapFactory.decodeStream(image)

        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        val unitWidth = bitmap.width / cols
        val unitHeight = bitmap.height / cols

        val keyArray = key.split("a")

        for (idx in 0 until cols * cols) {
            val keyval = keyArray[idx].ifEmpty { "0" }.toInt()

            val heightY = keyval.floorDiv(cols)
            val dy = heightY * unitHeight
            val dx = (keyval - heightY * cols) * unitWidth

            val widthY = idx.floorDiv(cols)
            val sy = widthY * unitHeight
            val sx = (idx - widthY * cols) * unitWidth

            val srcRect = Rect(sx, sy, sx + unitWidth, sy + unitHeight)
            val dstRect = Rect(dx, dy, dx + unitWidth, dy + unitHeight)

            canvas.drawBitmap(bitmap, srcRect, dstRect, null)
        }

        val output = ByteArrayOutputStream()
        result.compress(Bitmap.CompressFormat.JPEG, 100, output)

        return output.toByteArray()
    }

    private fun buildCookies(cookies: Map<String, String>) = cookies.entries.joinToString(separator = "; ", postfix = ";") {
        "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
    }

    private fun String.decodeHex(): ByteArray {
        check(length % 2 == 0) { "Must have an even length" }

        return chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }

    private fun getDescramblingKey(deobfChapterJs: String, imageUrl: String): String {
        val imgkeys = deobfChapterJs
            .substringAfter("var renImg = function(img,width,height,id){")
            .substringBefore("key = key.split(")
            .split("\n")
            .filter { jsFilters.all { filter -> !it.contains(filter) } }
            .joinToString("\n")
            .replace("img.src", "url")

        val js = """
            function getDescramblingKey(url) { $imgkeys; return key; }
            getDescramblingKey("$imageUrl");
        """.trimIndent()

        return QuickJs.create().use {
            it.execute(replacePosBytecode)

            it.evaluate(js).toString()
        }
    }

    private val jsFilters = listOf("jQuery", "document", "getContext", "toDataURL", "getImageData", "width", "height")

    private val hashCipher = "AES/CBC/ZEROBYTEPADDING"

    private val aes = "AES"

    private val keyLocationRegex by lazy {
        Regex("""str\.charAt\(\s*(\d+)\s*\)""")
    }

    private val imgSrcsRegex by lazy {
        Regex("""var imgsrcs\s*=\s*['"]([a-zA-Z0-9+=/]+)['"]""")
    }

    private val replacePosBytecode by lazy {
        QuickJs.create().use {
            it.compile(
                """
                function replacePos(strObj, pos, replacetext) {
                    var str = strObj.substr(0, pos) + replacetext + strObj.substring(pos + 1, strObj.length);
                    return str;
                }
                """.trimIndent(),
                "?"
            )
        }
    }
}
