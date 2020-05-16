package com.github.kisaragieffective.dynmap2image

import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.concurrent.timer
import kotlin.math.ceil
import kotlin.math.floor

object Main {
    private const val tilePixels = 128
    private const val DUMMY_USER_AGENT = "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:76.0) Gecko/20100101 Firefox/76.0"
    const val url = "https://example.com/your_map_path"
    const val world = "your_world"

    const val minX: Int = -10700
    const val minZ: Int = -9000
    const val maxX: Int = 7700
    const val maxZ: Int = 9300
    private val mode: ViewMode = ViewMode.FLAT
    val scale: Scale = Scale.NORMAL
    val imageSaveLocation = File("./image")
    val saveFinally = File(imageSaveLocation, "target.png")

    /**
     * real : tile = n : 1
     */
    private val realScale = 32 shl scale.ordinal
    private val groupedTileStep = 1 shl scale.ordinal

    // (5, 12) -> 12
    fun Int.asOf(maynot: Int): Int {
        return (roundvp(this * 1.0 / maynot) * maynot).toInt()
    }

    // - -> floor
    // + -> ceil
    fun roundvp(s: Double): Double {
        return if (s < 0) {
            floor(s)
        } else {
            ceil(s)
        }
    }

    @JvmStatic
    fun main(s: Array<String>) {
        Test.run()
        check(url)
        require(minX <= maxX) {
            "Bad X param"
        }

        require(minZ <= maxZ) {
            "Bad Z param"
        }

        imageSaveLocation.mkdirs()

        debug("param", "scale: $scale")
        debug("param", "realScale: $realScale")
        debug("param", "x: $minX .. $maxX, z: $minZ .. $maxZ")
        // オーバーフローが怖いし、適当にBigIntegerに投げとけばいいか！ｗ
        debug("task", "Expected tiles: ${1.toBigInteger() * (maxX - minX).toBigInteger() * (maxZ - minZ).toBigInteger() / realScale.toBigInteger() / realScale.toBigInteger() }")
        // normalizeしないとおかしなところから取り始めて動作がおかしくなることがある
        val tileMaxX = convertRawToTile(maxX).asOf(groupedTileStep)
        val tileMinX = convertRawToTile(minX).asOf(groupedTileStep)
        val tileMaxZ = convertRawToTile(maxZ).asOf(groupedTileStep)
        val tileMinZ = convertRawToTile(minZ).asOf(groupedTileStep)
        val xTileRange = tileMinX..tileMaxX step groupedTileStep
        val zTileRange = tileMinZ..tileMaxZ step groupedTileStep
        debug("param", "normalized x: $xTileRange, z: $zTileRange")
        debug("task", "Actual tiles: ${xTileRange.count() * zTileRange.count()}")
        debug("param.tile", "x: $tileMinX .. $tileMaxX, z: $tileMinZ .. $tileMaxZ")
        imageSaveLocation
                .walk()
                .filter { it != imageSaveLocation }
                .forEach { it.delete() }
        val mode = mapOf(
                ViewMode.FLAT to "flat",
                ViewMode.SURFACE to "t",
                ViewMode.XRAY to "ct"
        )[mode] ?: error("")

        val pool = Executors.newFixedThreadPool(64)
        val ai = AtomicInteger(0)
        print("wait...          ")
        val displayProgressTask = timer(period = 100L) {
            print("\b".repeat(8))
            print("${ai.get()}".padStart(8))
        }

        for (x in xTileRange) {
            for (z in zTileRange) {
                pool.execute {
                    // debug("request", "tile: {x: $x, z: $z}")
                    getFile(mode, x, z)
                    ai.incrementAndGet()
                }
            }
            Thread.sleep(2000L)
        }

        debug("request", "main thread: all reqs are sent; waiting join")
        displayProgressTask.cancel()
        pool.shutdown()
        pool.awaitTermination(5L, TimeUnit.MINUTES)
        debug("request", "main thread: joined all!")

        val imageWidth = tilePixels * xTileRange.count()
        val imageHeight = tilePixels * zTileRange.count()
        debug("image" ,"created {w: $imageWidth, h: $imageHeight}")
        val target = BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_ARGB)
        imageSaveLocation
                .walk()
                .filter { it.isFile }
                .filter { it != saveFinally }
                .forEach { file ->
                    debug("image iterate", "writing ${file.path}")
                    val bufferedImage = ImageIO.read(file)
                    val (tileX, tileZ) = Regex("(-?\\d+)_(-?\\d+)")
                            .find(file.nameWithoutExtension)!!
                            .groupValues
                            .drop(1)
                            .map { it.toInt() }
                    debug("image iterate", "tile?: x: $tileX, z: $tileZ")
                    val offsetX = (tileX - tileMinX) / groupedTileStep * tilePixels
                    // 逆ではない。こうしないとタイルが上下対象のスロットに入る。
                    val offsetZ = (tileMaxZ - tileZ) / groupedTileStep * tilePixels
                    debug("image iterate", "fx: $offsetX, fz: $offsetZ")
                    // region copy
                    val subView = target.getSubimage(offsetX, offsetZ, tilePixels, tilePixels)
                    subView.data = bufferedImage.data
                }
        print("saving...  ")


        val saveAnimationTask = run {
            var temp = 0
            timer("...", period = 100L) {
                print("\b")
                print("\\-/"[temp % 3])
                temp++
            }
        }
         //*/

        require(ImageIO.write(target, "PNG", saveFinally)) {
            "SAVE FAILED :P"
        }

        saveAnimationTask.cancel()
    }

    fun debug(cat: String, mes: String) {
        println("[$cat] $mes")
    }

    enum class ViewMode {
        FLAT,
        SURFACE,
        XRAY
    }

    enum class Scale(val str: String) {
        BIGGEST(""),
        BIGGER("z_"),
        BIG("zz_"),
        NORMAL("zzz_"),
        SMALLER("zzzz_"),
        SMALLEST("zzzzz_"),;

    }

    fun HttpURLConnection.ensureGet(mode: String, chunkX: Int, chunkZ: Int): InputStream {
        return try {
            setRequestProperty("User-Agent", DUMMY_USER_AGENT);
            requestMethod = "GET"
            connect()
            this.inputStream
        } catch (e: ConnectException) {
            debug("request", "$url -> couldn't connect. retry.")
            disconnect()
            (url.openConnection() as HttpURLConnection).ensureGet(mode, chunkX, chunkZ)
        } catch (e: IOException) {
            debug("request", "$url -> IOException: ${e.message} retry.")
            disconnect()
            Thread.yield()
            Thread.sleep(300L)
            (url.openConnection() as HttpURLConnection).ensureGet(mode, chunkX, chunkZ)
        }
    }

    fun getFile(mode: String, chunkX: Int, chunkZ: Int) {
        val image = makeConnectionFor(mode, chunkX, chunkZ, scale.str)
                .ensureGet(mode, chunkX, chunkZ)
                .readBytes()
        val file = File(imageSaveLocation, "${chunkX}_${chunkZ}.png").apply {
            createNewFile()
        }
        file.outputStream().use {
            it.write(image)
        }

        // debug("request", "done for ${whereIsTheTile(mode, chunkX, chunkZ, scale.str)}")
    }

    fun makeConnectionFor(mode: String, chunkX: Int, chunkZ: Int, scale: String): HttpURLConnection {
        val url = whereIsTheTile(mode, chunkX, chunkZ, scale)
        //debug("request", "$url")
        return url.openConnection() as HttpURLConnection
    }

    fun whereIsTheTile(mode: String, chunkX: Int, chunkZ: Int, scale: String): URL {
        // 領域ブロックは0_0固定でも動作する?? -> しない。鯖によって厳格だったりする。

        val regionX = convertTileToRegion(chunkX)
        val regionZ = convertTileToRegion(chunkZ)
        return URL("$url/tiles/$world/$mode/${regionX}_${regionZ}/${scale}${chunkX}_$chunkZ.png")
    }

    fun convertTileToRegion(v: Int): Int {
        return floor(v / 32.0).toInt()
    }

    fun convertRawToTile(location: Int): Int {
        return location / 32
    }

    fun check(url: String) {
        try {
            (URL(url).openConnection() as HttpURLConnection).connect()
        } catch (e: UnknownHostException) {
            debug("check", "unknown host")
        }
    }
}

fun IntRange.count(): Int {
    return if (isEmpty()) {
        0
    } else {
        this.last - this.first
    }
}

object Test {
    fun run() {
        /*
        0 -> 0
        -1 -> -1
        -16 -> -1
        -31 -> -1
        -32 -> -2
        1 -> 0
        15 -> 0
        16 -> 0
        31 -> 0
        32 -> 1
        */
        require(Main.convertTileToRegion(0) == 0)
        require(Main.convertTileToRegion(1) == 0)
        require(Main.convertTileToRegion(31) == 0)
        require(Main.convertTileToRegion(32) == 1)
        require(Main.convertTileToRegion(-1) == -1)
        require(Main.convertTileToRegion(-32) == -1)
        require(Main.convertTileToRegion(-33) == -2)
    }
}
