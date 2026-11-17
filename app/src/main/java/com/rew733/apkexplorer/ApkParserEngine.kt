package com.rew733.apkexplorer

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile

class ApkParserEngine(private val context: Context) {

    private var currentApkFile: File? = null

    fun getInstalledAppsJson(): String {
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val jsonArray = JSONArray()

        for (appInfo in packages) {
            // التركيز على التطبيقات المثبتة من المستخدم أو التطبيقات القابلة للتشغيل
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val launchIntent = pm.getLaunchIntentForPackage(appInfo.packageName)
            if (isSystem && launchIntent == null) continue

            try {
                val obj = JSONObject()
                obj.put("name", pm.getApplicationLabel(appInfo).toString())
                obj.put("packageName", appInfo.packageName)
                obj.put("sourceDir", appInfo.sourceDir)
                obj.put("isSystem", isSystem)
                obj.put("icon", drawableToBase64(pm.getApplicationIcon(appInfo)))
                jsonArray.put(obj)
            } catch (_: Exception) {
            }
        }
        return jsonArray.toString()
    }

    fun loadApkFromPath(path: String): String {
        val file = File(path)
        if (!file.exists()) {
            return JSONObject().put("error", "الملف غير موجود").toString()
        }
        currentApkFile = file
        return inspectApkFile(file)
    }

    fun loadApkFromUri(uri: Uri): String {
        return try {
            val tempFile = File(context.cacheDir, "selected_target.apk")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            currentApkFile = tempFile
            inspectApkFile(tempFile)
        } catch (e: Exception) {
            JSONObject().put("error", "فشل قراءة الملف: ${e.message}").toString()
        }
    }

    private fun inspectApkFile(file: File): String {
        val result = JSONObject()
        val filesArray = JSONArray()

        try {
            val pm = context.packageManager
            val pkgInfo = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES)
            if (pkgInfo != null) {
                pkgInfo.applicationInfo?.sourceDir = file.absolutePath
                pkgInfo.applicationInfo?.publicSourceDir = file.absolutePath
                result.put("appName", pkgInfo.applicationInfo?.let { pm.getApplicationLabel(it).toString() } ?: file.name)
                result.put("packageName", pkgInfo.packageName ?: "unknown")
                result.put("versionName", pkgInfo.versionName ?: "1.0")
                result.put("minSdk", pkgInfo.applicationInfo?.minSdkVersion ?: 0)
                result.put("targetSdk", pkgInfo.applicationInfo?.targetSdkVersion ?: 0)

                val permsArray = JSONArray()
                pkgInfo.requestedPermissions?.forEach { permsArray.put(it) }
                result.put("permissions", permsArray)
            } else {
                result.put("appName", file.name)
                result.put("packageName", "حزمة APK خارجية")
            }

            ZipFile(file).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.isDirectory) {
                        val fObj = JSONObject()
                        fObj.put("path", entry.name)
                        fObj.put("size", entry.size)
                        fObj.put("type", classifyFileType(entry.name))
                        filesArray.put(fObj)
                    }
                }
            }
            result.put("files", filesArray)
        } catch (e: Exception) {
            result.put("error", "خطأ في تحليل الحزمة: ${e.message}")
        }

        return result.toString()
    }

    fun readEntryContent(entryPath: String): String {
        val file = currentApkFile ?: return "// لم يتم تحديد أي ملف APK بعد."
        return try {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry(entryPath) ?: return "// الملف غير موجود داخل الحزمة: $entryPath"
                val bytes = zip.getInputStream(entry).use { it.readBytes() }

                when {
                    entryPath.endsWith(".xml", ignoreCase = true) -> {
                        if (isBinaryXml(bytes)) {
                            BinaryXmlDecoder.decode(bytes)
                        } else {
                            String(bytes, StandardCharsets.UTF_8)
                        }
                    }
                    entryPath.endsWith(".dex", ignoreCase = true) -> {
                        DexInspector.extractStructure(bytes, entryPath)
                    }
                    isTextFile(entryPath) -> {
                        String(bytes, StandardCharsets.UTF_8)
                    }
                    else -> {
                        "// ملف ثنائي (Binary File)\n// المسار: $entryPath\n// الحجم: ${bytes.size} بايت\n\n" +
                                bytes.take(512).joinToString(" ") { String.format("%02X", it) }
                    }
                }
            }
        } catch (e: Exception) {
            "// تعذر قراءة محتوى الملف: ${e.message}"
        }
    }

    private fun classifyFileType(path: String): String {
        return when {
            path == "AndroidManifest.xml" -> "manifest"
            path.endsWith(".dex") -> "dex"
            path.startsWith("assets/") -> "asset"
            path.startsWith("res/layout/") -> "layout"
            path.endsWith(".xml") -> "xml"
            path.endsWith(".html") || path.endsWith(".js") || path.endsWith(".css") || path.endsWith(".json") -> "code"
            path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".webp") -> "image"
            path.endsWith(".so") -> "native"
            else -> "other"
        }
    }

    private fun isTextFile(path: String): Boolean {
        val lower = path.lowercase()
        return lower.endsWith(".html") || lower.endsWith(".htm") ||
                lower.endsWith(".js") || lower.endsWith(".css") ||
                lower.endsWith(".json") || lower.endsWith(".txt") ||
                lower.endsWith(".properties") || lower.endsWith(".mf") ||
                lower.endsWith(".sf") || lower.endsWith(".gradle") ||
                lower.endsWith(".kt") || lower.endsWith(".java") ||
                lower.endsWith(".svg") || lower.endsWith(".md")
    }

    private fun isBinaryXml(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val magic = ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        return (magic and 0xFFFF) == 0x0003
    }

    private fun drawableToBase64(drawable: Drawable): String {
        return try {
            val bitmap = if (drawable is BitmapDrawable && drawable.bitmap != null) {
                drawable.bitmap
            } else {
                val w = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 64
                val h = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 64
                val bmp = Bitmap.createBitmap(w.coerceAtMost(96), h.coerceAtMost(96), Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bmp
            }
            val scaled = Bitmap.createScaledBitmap(bitmap, 64, 64, true)
            val stream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.PNG, 85, stream)
            "data:image/png;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) {
            ""
        }
    }
}

/**
 * مفكك ملفات Android Binary XML (AXML) لعرض AndroidManifest.xml وملفات الواجهات بصيغة مقروءة
 */
object BinaryXmlDecoder {
    fun decode(xml: ByteArray): String {
        return try {
            val buf = ByteBuffer.wrap(xml).order(ByteOrder.LITTLE_ENDIAN)
            if (xml.size < 8) return String(xml, StandardCharsets.UTF_8)

            val strings = parseStringPool(buf)
            val sb = StringBuilder()
            sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")

            var indent = 0
            while (buf.remaining() >= 8) {
                val chunkStart = buf.position()
                val type = buf.short.toInt() and 0xFFFF
                val headerSize = buf.short.toInt() and 0xFFFF
                val chunkSize = buf.int

                if (chunkSize <= 0 || chunkStart + chunkSize > xml.size) break

                when (type) {
                    0x0102 -> { // START_TAG
                        if (chunkSize >= 36) {
                            buf.position(chunkStart + 16)
                            val nsIdx = buf.int
                            val nameIdx = buf.int
                            val attrStart = buf.short.toInt() and 0xFFFF
                            val attrSize = buf.short.toInt() and 0xFFFF
                            val attrCount = buf.short.toInt() and 0xFFFF

                            val tagName = strings.getOrNull(nameIdx) ?: "tag"
                            sb.append("  ".repeat(indent)).append("<").append(tagName)

                            var attrPos = chunkStart + 16 + attrStart
                            for (i in 0 until attrCount) {
                                if (attrPos + 20 > chunkStart + chunkSize) break
                                buf.position(attrPos)
                                val aNs = buf.int
                                val aNameIdx = buf.int
                                val aRawValIdx = buf.int
                                val aValSize = buf.short.toInt() and 0xFFFF
                                buf.get() // res0
                                val aValType = buf.get().toInt() and 0xFF
                                val aValData = buf.int

                                val attrName = strings.getOrNull(aNameIdx) ?: "attr"
                                val prefix = if (aNs >= 0) "android:" else ""
                                val valueStr = when {
                                    aRawValIdx >= 0 -> strings.getOrNull(aRawValIdx) ?: ""
                                    aValType == 0x03 -> strings.getOrNull(aValData) ?: ""
                                    aValType == 0x12 -> if (aValData != 0) "true" else "false"
                                    aValType == 0x10 || aValType == 0x11 -> aValData.toString()
                                    aValType == 0x01 -> "@0x${Integer.toHexString(aValData)}"
                                    else -> "0x${Integer.toHexString(aValData)}"
                                }
                                sb.append("\n").append("  ".repeat(indent + 1))
                                    .append(prefix).append(attrName).append("=\"").append(valueStr).append("\"")
                                attrPos += attrSize.coerceAtLeast(20)
                            }
                            sb.append(">\n")
                            indent++
                        }
                    }
                    0x0103 -> { // END_TAG
                        indent = (indent - 1).coerceAtLeast(0)
                        buf.position(chunkStart + 20)
                        val nameIdx = buf.int
                        val tagName = strings.getOrNull(nameIdx) ?: "tag"
                        sb.append("  ".repeat(indent)).append("</").append(tagName).append(">\n")
                    }
                }
                buf.position(chunkStart + chunkSize)
            }
            if (sb.length > 45) sb.toString() else String(xml, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            "// تعذر فك ترميز XML الثنائي: ${e.message}"
        }
    }

    private fun parseStringPool(buf: ByteBuffer): List<String> {
        val list = mutableListOf<String>()
        buf.position(8)
        if (buf.remaining() < 28) return list
        val chunkStart = buf.position()
        val type = buf.short.toInt() and 0xFFFF
        if (type != 0x0001) return list
        buf.short // headerSize
        val chunkSize = buf.int
        val stringCount = buf.int
        buf.int // styleCount
        val flags = buf.int
        val stringsStart = buf.int
        buf.int // stylesStart

        val isUtf8 = (flags and (1 shl 8)) != 0
        val offsets = IntArray(stringCount) { buf.int }
        val poolDataStart = chunkStart + stringsStart

        for (offset in offsets) {
            val pos = poolDataStart + offset
            if (pos < 0 || pos >= buf.limit()) {
                list.add("")
                continue
            }
            buf.position(pos)
            if (isUtf8) {
                var u16len = buf.get().toInt() and 0xFF
                if ((u16len and 0x80) != 0) buf.get()
                var u8len = buf.get().toInt() and 0xFF
                if ((u8len and 0x80) != 0) {
                    u8len = ((u8len and 0x7F) shl 8) or (buf.get().toInt() and 0xFF)
                }
                val bytes = ByteArray(u8len.coerceAtMost(buf.remaining()))
                buf.get(bytes)
                list.add(String(bytes, StandardCharsets.UTF_8))
            } else {
                var len = buf.short.toInt() and 0xFFFF
                if ((len and 0x8000) != 0) {
                    len = ((len and 0x7FFF) shl 16) or (buf.short.toInt() and 0xFFFF)
                }
                val byteLen = (len * 2).coerceAtMost(buf.remaining())
                val bytes = ByteArray(byteLen)
                buf.get(bytes)
                list.add(String(bytes, StandardCharsets.UTF_16LE))
            }
        }
        buf.position(chunkStart + chunkSize)
        return list
    }
}

/**
 * مستخرج هيكلة الفئات والدوال البرمجية من ملفات classes.dex
 */
object DexInspector {
    fun extractStructure(dexBytes: ByteArray, fileName: String): String {
        return try {
            val buf = ByteBuffer.wrap(dexBytes).order(ByteOrder.LITTLE_ENDIAN)
            if (dexBytes.size < 112) return "// ملف DEX غير صالح"

            val magic = ByteArray(8)
            buf.get(magic)
            val magicStr = String(magic, StandardCharsets.UTF_8).replace("\n", " ")

            buf.position(56)
            val stringIdsSize = buf.int
            val stringIdsOff = buf.int
            val typeIdsSize = buf.int
            val typeIdsOff = buf.int
            val protoIdsSize = buf.int
            val protoIdsOff = buf.int
            val fieldIdsSize = buf.int
            val fieldIdsOff = buf.int
            val methodIdsSize = buf.int
            val methodIdsOff = buf.int
            val classDefsSize = buf.int
            val classDefsOff = buf.int

            val strings = Array(stringIdsSize) { idx ->
                val strDataOff = buf.getInt(stringIdsOff + idx * 4)
                readMutf8(dexBytes, strDataOff)
            }

            val types = Array(typeIdsSize) { idx ->
                val descIdx = buf.getInt(typeIdsOff + idx * 4)
                strings.getOrElse(descIdx) { "" }
            }

            val sb = StringBuilder()
            sb.append("// ==========================================\n")
            sb.append("// تحليل ملف Dalvik Executable: $fileName\n")
            sb.append("// الإصدار: $magicStr\n")
            sb.append("// عدد الفئات (Classes): $classDefsSize | الدوال (Methods): $methodIdsSize\n")
            sb.append("// ==========================================\n\n")

            val classesList = mutableListOf<String>()
            for (i in 0 until classDefsSize) {
                val classIdx = buf.getInt(classDefsOff + i * 32)
                val rawType = types.getOrElse(classIdx) { "" }
                if (rawType.startsWith("L") && rawType.endsWith(";")) {
                    val javaName = rawType.substring(1, rawType.length - 1).replace('/', '.')
                    // استبعاد مكتبات أندرويد القياسية للتركيز على كود التطبيق نفسه
                    if (!javaName.startsWith("android.") &&
                        !javaName.startsWith("androidx.") &&
                        !javaName.startsWith("kotlin.") &&
                        !javaName.startsWith("kotlinx.") &&
                        !javaName.startsWith("java.")
                    ) {
                        classesList.add(javaName)
                    }
                }
            }

            sb.append("// --- فئات التطبيق البرمجية (App Classes: ${classesList.size}) ---\n")
            classesList.sorted().forEach { cls ->
                sb.append("public class ").append(cls).append(" { ... }\n")
            }

            sb.append("\n// --- عينة من الدوال المستدعاة (Methods) ---\n")
            val maxMethods = methodIdsSize.coerceAtMost(400)
            for (i in 0 until maxMethods) {
                val classIdx = buf.getShort(methodIdsOff + i * 8).toInt() and 0xFFFF
                val nameIdx = buf.getInt(methodIdsOff + i * 8 + 4)
                val owner = types.getOrElse(classIdx) { "" }
                    .removePrefix("L").removeSuffix(";").replace('/', '.')
                val methodName = strings.getOrElse(nameIdx) { "" }
                if (!owner.startsWith("java.") && !owner.startsWith("android.")) {
                    sb.append(owner).append(".").append(methodName).append("();\n")
                }
            }

            sb.toString()
        } catch (e: Exception) {
            "// خطأ أثناء تحليل ملف DEX: ${e.message}"
        }
    }

    private fun readMutf8(data: ByteArray, offset: Int): String {
        if (offset < 0 || offset >= data.size) return ""
        var pos = offset
        // تخطي طول ULEB128
        while (pos < data.size && (data[pos].toInt() and 0x80) != 0) {
            pos++
        }
        pos++
        val start = pos
        while (pos < data.size && data[pos].toInt() != 0) {
            pos++
        }
        return if (pos > start) {
            String(data, start, pos - start, StandardCharsets.UTF_8)
        } else {
            ""
        }
    }
}
