package local.appsearch

import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.icu.text.Transliterator
import android.os.UserHandle
import android.os.UserManager
import java.io.File
import java.util.Locale

class AppEntry(
    val key: String,
    val pkg: String,
    val cls: String,
    val serial: Long,
    val stamp: Long,
    val label: String,
    val altLabel: String,
    val names: List<String>,
    val pinyins: List<String>,
    val initials: List<String>,
)

/** 枚举可启动的应用，并为每个应用收集简体中文名、英文名和拼音。结果按包更新时间增量缓存。 */
class AppIndex(private val context: Context) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val cacheFile = File(context.filesDir, "index.txt")
    private val han by lazy { Transliterator.getInstance("Han-Latin; Latin-ASCII") }
    private val hanCache = HashMap<Int, String>()

    val infos = HashMap<String, LauncherActivityInfo>()

    fun userFor(serial: Long): UserHandle? = userManager.getUserForSerialNumber(serial)

    fun readCache(): List<AppEntry> = try {
        val locale = systemLocale()
        val lines = cacheFile.readLines()
        if (lines.firstOrNull() != locale) emptyList() else lines.drop(1).mapNotNull(::decode)
    } catch (_: Exception) {
        emptyList()
    }

    fun refresh(old: List<AppEntry>): List<AppEntry> {
        val oldByKey = old.associateBy { it.key }
        val found = ArrayList<AppEntry>()
        val seenInfos = HashMap<String, LauncherActivityInfo>()
        for (user in launcherApps.profiles) {
            val serial = userManager.getSerialNumberForUser(user)
            for (info in launcherApps.getActivityList(null, user)) {
                val cn = info.componentName
                if (cn.packageName == context.packageName) continue
                val key = "$serial/${cn.packageName}/${cn.className}"
                seenInfos[key] = info
                val stamp = try {
                    context.packageManager.getPackageInfo(cn.packageName, 0).lastUpdateTime
                } catch (_: Exception) {
                    info.firstInstallTime
                }
                val cached = oldByKey[key]
                found += if (cached != null && cached.stamp == stamp && cached.label == info.label.toString()) {
                    cached
                } else {
                    build(key, serial, stamp, info)
                }
            }
        }
        synchronized(infos) {
            infos.clear()
            infos.putAll(seenInfos)
        }
        write(found)
        return found
    }

    private fun build(key: String, serial: Long, stamp: Long, info: LauncherActivityInfo): AppEntry {
        val cn = info.componentName
        val label = info.label.toString().trim()
        val names = linkedSetOf(label)
        val appInfo = info.applicationInfo
        val activityRes = info.activityInfo.labelRes
        val appRes = appInfo.labelRes
        val pkgContext = try {
            context.createPackageContext(cn.packageName, 0)
        } catch (_: Exception) {
            null
        }
        if (pkgContext != null) {
            for (locale in LOCALES) {
                val config = Configuration(pkgContext.resources.configuration)
                config.setLocale(locale)
                val res = pkgContext.createConfigurationContext(config).resources
                for (id in intArrayOf(activityRes, appRes)) {
                    if (id == 0) continue
                    try {
                        res.getString(id).trim().takeIf { it.isNotEmpty() }?.let(names::add)
                    } catch (_: Exception) {
                    }
                }
            }
        }
        val alt = names.firstOrNull { it != label && hasHan(it) != hasHan(label) }
            ?: names.firstOrNull { it != label }
            ?: ""
        val normalized = names.map(::normalize).filter { it.isNotEmpty() }.distinct()
        val pinyins = names.filter(::hasHan).map { toTokens(it).joinToString("") }.distinct()
        val initials = names.flatMap { name ->
            val tokens = toTokens(name)
            listOf(
                tokens.joinToString("") { it.take(1) },
                // 保留英文部分的完整写法，例如 QQ音乐 → qqyy
                toTokens(name, keepLatin = true).joinToString("") { if (it.second) it.first else it.first.take(1) },
            )
        }.filter { it.length >= 2 }.distinct()
        return AppEntry(key, cn.packageName, cn.className, serial, stamp, label, alt, normalized, pinyins, initials)
    }

    /** 每个汉字、每个英文单词（含驼峰分词）各算一个音节。 */
    private fun toTokens(text: String): List<String> = toTokens(text, keepLatin = false).map { it.first }

    private fun toTokens(text: String, keepLatin: Boolean): List<Pair<String, Boolean>> {
        val out = ArrayList<Pair<String, Boolean>>()
        val word = StringBuilder()
        fun flush() {
            if (word.isNotEmpty()) out += word.toString().lowercase(Locale.ROOT) to true
            word.setLength(0)
        }
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val step = Character.charCount(cp)
            when {
                isHan(cp) -> {
                    flush()
                    val py = hanCache.getOrPut(cp) {
                        han.transliterate(String(Character.toChars(cp))).lowercase(Locale.ROOT).filter { it in 'a'..'z' }
                    }
                    if (py.isNotEmpty()) out += py to false
                }
                Character.isLetterOrDigit(cp) -> {
                    val camel = !keepLatin && Character.isUpperCase(cp) && word.isNotEmpty() && Character.isLowerCase(word.last())
                    if (camel) flush()
                    word.appendCodePoint(cp)
                }
                else -> flush()
            }
            i += step
        }
        flush()
        return out
    }

    private fun write(entries: List<AppEntry>) {
        try {
            val tmp = File(cacheFile.path + ".tmp")
            tmp.bufferedWriter().use { w ->
                w.write(systemLocale())
                w.newLine()
                for (e in entries) {
                    w.write(
                        listOf(
                            e.serial.toString(), e.pkg, e.cls, e.stamp.toString(), e.label, e.altLabel,
                            e.names.joinToString(LIST_SEP), e.pinyins.joinToString(LIST_SEP), e.initials.joinToString(LIST_SEP),
                        ).joinToString(FIELD_SEP) { it.replace('\n', ' ') },
                    )
                    w.newLine()
                }
            }
            tmp.renameTo(cacheFile)
        } catch (_: Exception) {
        }
    }

    private fun decode(line: String): AppEntry? {
        val f = line.split(FIELD_SEP)
        if (f.size != 9) return null
        val serial = f[0].toLongOrNull() ?: return null
        fun list(s: String) = if (s.isEmpty()) emptyList() else s.split(LIST_SEP)
        return AppEntry(
            "$serial/${f[1]}/${f[2]}", f[1], f[2], serial, f[3].toLongOrNull() ?: 0L,
            f[4], f[5], list(f[6]), list(f[7]), list(f[8]),
        )
    }

    private fun systemLocale() = context.resources.configuration.locales.toLanguageTags()

    companion object {
        private val LOCALES = listOf(Locale.SIMPLIFIED_CHINESE, Locale.US)
        private const val FIELD_SEP = "\u0001"
        private const val LIST_SEP = "\u0002"

        fun normalize(text: String) = text.lowercase(Locale.ROOT).filterNot { it.isWhitespace() }

        private fun isHan(cp: Int) = Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN
        private fun hasHan(text: String) = text.codePoints().anyMatch(::isHan)

        /** 分数越高越靠前；0 表示不匹配。 */
        fun score(entry: AppEntry, query: String): Int {
            var best = 0
            for (name in entry.names) {
                best = maxOf(
                    best,
                    when {
                        name == query -> 100
                        name.startsWith(query) -> 90
                        name.contains(query) -> 60
                        else -> 0
                    },
                )
            }
            for (code in entry.initials) {
                best = maxOf(
                    best,
                    when {
                        code == query -> 85
                        code.startsWith(query) -> 75
                        query.length >= 2 && code.contains(query) -> 30
                        else -> 0
                    },
                )
            }
            for (py in entry.pinyins) {
                best = maxOf(
                    best,
                    when {
                        py == query -> 85
                        py.startsWith(query) -> 80
                        query.length >= 3 && py.contains(query) -> 50
                        else -> 0
                    },
                )
            }
            return best
        }
    }
}
