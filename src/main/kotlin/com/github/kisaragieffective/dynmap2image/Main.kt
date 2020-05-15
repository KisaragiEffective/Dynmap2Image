package com.github.kisaragieffective.dynmap2image

import java.awt.image.BufferedImage
import java.io.File
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.concurrent.timer
import kotlin.math.floor

object Main {
    private const val tilePixels = 128
    val url = "http://example.net:8123"
    val minX: Int = -5000
    val maxX: Int = 13000
    val minZ: Int = -13000
    val maxZ: Int = 5000
    val world = "main"
    private val mode: ViewMode = ViewMode.FLAT
    val scale: Scale = Scale.NORMAL
    val imageSaveLocation = File("./image")
    val saveFinally = File(imageSaveLocation, "target.png")
    private val realScale = 32 shl scale.ordinal
    private val sliceScale = realScale / 32
    @JvmStatic
    fun main(s: Array<String>) {
        require(minX <= maxX) {
            "Bad X param"
        }

        require(minZ <= maxZ) {
            "Bad Z param"
        }

        debug("param", "scale: $scale")
        debug("param", "realScale: $realScale")
        debug("task", "tiles: ${1.toBigInteger() * (maxX - minX).toBigInteger() * (maxZ - minZ).toBigInteger() / realScale.toBigInteger() / realScale.toBigInteger() }")
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
        val unsure = timer(period = 100L) {
            print("\b".repeat(8))
            print("${ai.get()}".padStart(8))
        }

        // normalizeしないとおかしなところから取り始めて動作がおかしくなることがある
        val xRange = (floor(minX / realScale * 1.0) * realScale).toInt()..((floor(maxX / realScale * 1.0) * realScale).toInt())
        val zRange = (floor(minZ / realScale * 1.0) * realScale).toInt()..((floor(maxZ / realScale * 1.0) * realScale).toInt())

        for (x in xRange step realScale) {
            for (z in zRange step realScale) {
                pool.execute {
                    // Note: chunkは32単位！！！かつZ方向は座標と正負が逆！！！！！
                    getFile(mode, convertRawXToDynmap(x), convertRawZToDynmap(z))
                    ai.incrementAndGet()
                }
            }
            Thread.sleep(300L)
        }

        debug("request", "main thread: waiting join")
        unsure.cancel()
        pool.shutdown()
        pool.awaitTermination(300L, TimeUnit.MINUTES)
        debug("request", "main thread: joined all!")

        val tileMaxX = convertRawXToTile(maxX)
        val tileMinX = convertRawXToTile(minX)
        val tileMaxZ = convertRawZToTile(maxZ)
        val tileMinZ = convertRawZToTile(minZ)
        debug("general", "x: $tileMinX .. $tileMaxX, z: $tileMinZ .. $tileMaxZ")
        val imageWidth = tilePixels * (tileMaxX - tileMinX) + tilePixels
        val imageHeight = tilePixels * (kotlin.math.abs(tileMaxZ - tileMinZ)) + tilePixels
        // 〽 Shut up exception! - The Monkeys
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
                            .map { it.toInt() / sliceScale }
                    val offsetX = (tileX - tileMinX) * tilePixels
                    val offsetZ = -(tileZ - tileMinZ) * tilePixels
                    debug("image iterate", "tx: $tileX, tz: $tileZ")
                    debug("image iterate", "fx: $offsetX, fz: $offsetZ")
                    val subView = target.getSubimage(offsetX, offsetZ, tilePixels, tilePixels)
                    subView.data = bufferedImage.data
                }
        print("saving...  ")


        val hoge = run {
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

        hoge.cancel()
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

    // FIXME: bad name
    fun HttpURLConnection.yes(mode: String, chunkX: Int, chunkZ: Int): InputStream {
        return try {
            requestMethod = "GET"
            connect()
            this.inputStream
        } catch (e: ConnectException) {
            debug("request", "$url -> couldn't connect. retry.")
            disconnect()
            (url.openConnection() as HttpURLConnection).yes(mode, chunkX, chunkZ)
        }
    }

    fun getFile(mode: String, chunkX: Int, chunkZ: Int) {
        val image = makeConnectionFor(mode, chunkX, chunkZ, scale.str)
                .yes(mode, chunkX, chunkZ)
                .readBytes()
        // println(image.contentToString())
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
        // 領域ブロックは0_0固定でも動作する??
        val regionX = chunkX / 16
        val regionZ = chunkZ / 16
        return URL("$url/tiles/$world/$mode/${regionX}_${regionZ}/${scale}${chunkX}_$chunkZ.png")
    }

    fun convertRawXToDynmap(locationX: Int): Int {
        return locationX / 32
    }

    fun convertRawZToDynmap(locationZ: Int): Int {
        return -locationZ / 32
    }

    fun convertRawXToTile(locationX: Int): Int {
        return locationX / realScale
    }

    fun convertRawZToTile(locationZ: Int): Int {
        return -locationZ / realScale
    }
}
