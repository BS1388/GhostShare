package ir.ghostshare

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale
import java.util.concurrent.Executors

class AppPickerActivity : AppCompatActivity() {

    private data class AppEntry(
        val packageName: String,
        val label: String,
        val isHiddenSystem: Boolean
    )

    private class AppHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.iv_icon)
        val label: TextView = view.findViewById(R.id.tv_label)
        val packageText: TextView = view.findViewById(R.id.tv_package)
        val check: CheckBox = view.findViewById(R.id.cb_selected)
    }

    private lateinit var mode: String
    private lateinit var adapter: AppAdapter
    private lateinit var progress: ProgressBar
    private lateinit var tvEmpty: TextView

    private val selected = LinkedHashSet<String>()
    private var allApps: List<AppEntry> = emptyList()
    private var visibleApps: List<AppEntry> = emptyList()
    private var showSystem = false
    private var query = ""

    private val loaderExecutor = Executors.newSingleThreadExecutor()
    private val iconExecutor = Executors.newFixedThreadPool(3)
    private val iconCache = HashMap<String, Drawable>()

    @Volatile
    private var destroyed = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        mode = intent.getStringExtra(Constants.EXTRA_PICKER_MODE) ?: Constants.MODE_PER_APP
        val isBypass = mode == Constants.MODE_BYPASS

        setContentView(R.layout.activity_app_picker)
        UiUtils.setupSystemBars(this)

        val config = SettingsStore.load(this)
        selected.addAll(if (isBypass) config.bypassPackages else config.perAppPackages)

        findViewById<TextView>(R.id.tv_title).setText(
            if (isBypass) R.string.title_pick_bypass else R.string.title_pick_per_app
        )
        findViewById<TextView>(R.id.tv_subtitle).setText(
            if (isBypass) R.string.desc_pick_bypass else R.string.desc_pick_per_app
        )
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        progress = findViewById(R.id.progress)
        tvEmpty = findViewById(R.id.tv_empty)

        adapter = AppAdapter()
        val recycler = findViewById<RecyclerView>(R.id.rv_apps)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        findViewById<EditText>(R.id.et_search).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                applyFilter()
            }
        })

        findViewById<Switch>(R.id.switch_system).setOnCheckedChangeListener { _, checked ->
            showSystem = checked
            applyFilter()
        }

        loadApps()
    }

    override fun onDestroy() {
        destroyed = true
        loaderExecutor.shutdownNow()
        iconExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun loadApps() {
        progress.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE
        val chosen = selected.toSet()
        loaderExecutor.execute {
            val pm = packageManager
            val entries = try {
                pm.getInstalledApplications(0)
                    .filter { it.packageName != packageName }
                    .map { info ->
                        val systemFlag = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        val updatedSystem = (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                        val launchable = try {
                            pm.getLaunchIntentForPackage(info.packageName) != null
                        } catch (_: Throwable) {
                            false
                        }
                        AppEntry(
                            packageName = info.packageName,
                            label = info.loadLabel(pm).toString(),
                            isHiddenSystem = systemFlag && !updatedSystem && !launchable
                        )
                    }
            } catch (_: Throwable) {
                emptyList()
            }

            val sorted = entries.sortedWith(
                compareByDescending<AppEntry> { it.packageName in chosen }
                    .thenBy { it.label.lowercase(Locale.ROOT) }
            )

            runOnUiThread {
                if (destroyed) return@runOnUiThread
                allApps = sorted
                progress.visibility = View.GONE
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        val q = query.trim().lowercase(Locale.ROOT)
        visibleApps = allApps.filter { app ->
            val visibleByType = showSystem || !app.isHiddenSystem || app.packageName in selected
            val matches = q.isEmpty() ||
                app.label.lowercase(Locale.ROOT).contains(q) ||
                app.packageName.lowercase(Locale.ROOT).contains(q)
            visibleByType && matches
        }
        adapter.notifyDataSetChanged()
        tvEmpty.visibility =
            if (visibleApps.isEmpty() && progress.visibility != View.VISIBLE) View.VISIBLE else View.GONE
    }

    private fun toggle(packageName: String) {
        if (!selected.add(packageName)) selected.remove(packageName)
        val snapshot = selected.toSet()
        SettingsStore.update(this) { config ->
            if (mode == Constants.MODE_BYPASS) {
                config.copy(bypassPackages = snapshot)
            } else {
                config.copy(perAppPackages = snapshot)
            }
        }
    }

    private fun bindIcon(view: ImageView, packageName: String) {
        view.tag = packageName
        val cached = iconCache[packageName]
        if (cached != null) {
            view.setImageDrawable(cached.constantState?.newDrawable(resources) ?: cached)
            return
        }
        view.setImageDrawable(null)
        iconExecutor.execute {
            val icon = try {
                packageManager.getApplicationIcon(packageName)
            } catch (_: Throwable) {
                null
            }
            runOnUiThread {
                if (destroyed || icon == null) return@runOnUiThread
                iconCache[packageName] = icon
                if (view.tag == packageName) {
                    view.setImageDrawable(icon.constantState?.newDrawable(resources) ?: icon)
                }
            }
        }
    }

    private inner class AppAdapter : RecyclerView.Adapter<AppHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppHolder =
            AppHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

        override fun getItemCount(): Int = visibleApps.size

        override fun onBindViewHolder(holder: AppHolder, position: Int) {
            val app = visibleApps[position]
            holder.label.text = app.label
            holder.packageText.text = app.packageName
            holder.check.isChecked = app.packageName in selected
            bindIcon(holder.icon, app.packageName)
            holder.itemView.setOnClickListener {
                val index = holder.bindingAdapterPosition
                if (index == RecyclerView.NO_POSITION) return@setOnClickListener
                val packageName = visibleApps[index].packageName
                toggle(packageName)
                holder.check.isChecked = packageName in selected
            }
        }
    }
}
