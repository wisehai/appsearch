package local.appsearch

import android.app.Activity
import android.app.SearchManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val indexThread = Executors.newSingleThreadExecutor()
    private val iconThread = Executors.newSingleThreadExecutor()
    private val icons = LruCache<String, Drawable>(120)
    private val prefs by lazy { getSharedPreferences("usage", MODE_PRIVATE) }

    private lateinit var index: AppIndex
    private lateinit var query: EditText
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var clear: View

    private var entries: List<AppEntry> = emptyList()
    private var loaded = false
    private var results: List<AppEntry> = emptyList() // 最佳结果排第一；显示时反转
    private val adapter = ResultAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.setDecorFitsSystemWindows(false)
        index = AppIndex(applicationContext)
        query = findViewById(R.id.query)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        clear = findViewById(R.id.clear)

        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout(),
            )
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsets.CONSUMED
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> open(adapter.getItem(position)) }
        clear.setOnClickListener { query.setText("") }
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = update()
        })
        query.setOnEditorActionListener { _, action, event ->
            val enter = action == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (enter) results.firstOrNull()?.let(::open)
            enter
        }
        applyIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntent(intent)
    }

    private fun applyIntent(intent: Intent?) {
        intent?.getStringExtra(SearchManager.QUERY)?.let {
            query.setText(it)
            query.setSelection(it.length)
        }
    }

    override fun onStart() {
        super.onStart()
        indexThread.execute {
            val cached = if (loaded) entries else index.readCache()
            if (!loaded && cached.isNotEmpty()) main.post { setEntries(cached) }
            val fresh = index.refresh(cached)
            main.post { setEntries(fresh) }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) showKeyboard()
    }

    override fun onStop() {
        super.onStop()
        // 下次打开时从空白开始
        query.setText("")
    }

    override fun onDestroy() {
        indexThread.shutdown()
        iconThread.shutdown()
        super.onDestroy()
    }

    private fun showKeyboard() {
        query.requestFocus()
        query.post { getSystemService(InputMethodManager::class.java).showSoftInput(query, 0) }
    }

    private fun setEntries(value: List<AppEntry>) {
        entries = value
        loaded = true
        update()
    }

    private fun update() {
        val q = AppIndex.normalize(query.text.toString())
        clear.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
        val now = System.currentTimeMillis()
        results = if (q.isEmpty()) {
            entries.sortedWith(
                compareByDescending<AppEntry> { frecency(it, now) }
                    .thenByDescending { lastUsed(it) }
                    .thenBy { it.names.firstOrNull() ?: it.label },
            )
        } else {
            val qp = index.queryPinyin(q)
            entries.map { it to AppIndex.score(it, q, qp) }
                .filter { it.second > 0 }
                .sortedWith(
                    compareByDescending<Pair<AppEntry, Int>> { it.second }
                        .thenByDescending { frecency(it.first, now) }
                        .thenBy { it.first.label.length },
                )
                .map { it.first }
        }
        empty.visibility = if (loaded && q.isNotEmpty() && results.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
        if (results.isNotEmpty()) list.setSelection(results.size - 1)
    }

    private fun lastUsed(e: AppEntry) = prefs.getLong("t:${e.key}", 0L)
    private fun useCount(e: AppEntry) = prefs.getInt("n:${e.key}", 0)

    // 常用度：每次打开 +1，随时间按半衰期衰减。旧版本只有次数，就拿次数当起始分。
    private fun storedScore(e: AppEntry) =
        if (prefs.contains("s:${e.key}")) prefs.getFloat("s:${e.key}", 0f).toDouble() else useCount(e).toDouble()

    private fun decay(since: Long, now: Long) =
        if (since <= 0L) 0.0 else Math.pow(0.5, (now - since).coerceAtLeast(0L).toDouble() / HALF_LIFE_MS)

    private fun frecency(e: AppEntry, now: Long) = storedScore(e) * decay(lastUsed(e), now)

    private fun open(entry: AppEntry) {
        val user = index.userFor(entry.serial)
        try {
            getSystemService(LauncherApps::class.java)
                .startMainActivity(ComponentName(entry.pkg, entry.cls), user, null, null)
            val now = System.currentTimeMillis()
            prefs.edit()
                .putFloat("s:${entry.key}", (frecency(entry, now) + 1).toFloat())
                .putLong("t:${entry.key}", now)
                .putInt("n:${entry.key}", useCount(entry) + 1)
                .apply()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private inner class ResultAdapter : BaseAdapter() {
        override fun getCount() = results.size
        override fun getItem(position: Int) = results[results.size - 1 - position]
        override fun getItemId(position: Int) = getItem(position).key.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: layoutInflater.inflate(R.layout.row, parent, false)
            val e = getItem(position)
            row.findViewById<TextView>(R.id.label).text = e.label
            val sub = row.findViewById<TextView>(R.id.sub)
            val isClone = e.serial != serialSelf
            val subText = listOf(e.altLabel, if (isClone) getString(R.string.clone_suffix) else "")
                .filter { it.isNotEmpty() }.joinToString(" · ")
            sub.text = subText
            sub.visibility = if (subText.isEmpty()) View.GONE else View.VISIBLE
            // 高亮按回车会打开的那一项
            val isTop = position == results.size - 1 && query.text.isNotEmpty()
            row.setBackgroundResource(if (isTop) R.drawable.row_top_bg else R.drawable.row_bg)
            bindIcon(row.findViewById(R.id.icon), e)
            return row
        }
    }

    private val serialSelf by lazy {
        getSystemService(android.os.UserManager::class.java).getSerialNumberForUser(android.os.Process.myUserHandle())
    }

    private fun bindIcon(view: ImageView, e: AppEntry) {
        view.tag = e.key
        val cached = icons.get(e.key)
        view.setImageDrawable(cached)
        if (cached != null) return
        val density = resources.displayMetrics.densityDpi
        iconThread.execute {
            val info = synchronized(index.infos) { index.infos[e.key] } ?: return@execute
            val icon = try {
                info.getBadgedIcon(density)
            } catch (_: Exception) {
                null
            } ?: return@execute
            main.post {
                icons.put(e.key, icon)
                if (view.tag == e.key) view.setImageDrawable(icon)
            }
        }
    }
}

private const val HALF_LIFE_MS = 5 * 24 * 60 * 60 * 1000.0 // 5 天
