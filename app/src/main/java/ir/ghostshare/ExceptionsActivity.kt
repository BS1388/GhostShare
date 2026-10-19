package ir.ghostshare

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ExceptionsActivity : AppCompatActivity() {

    private class DomainHolder(view: View) : RecyclerView.ViewHolder(view) {
        val domain: TextView = view.findViewById(R.id.tv_domain)
        val remove: ImageButton = view.findViewById(R.id.btn_remove)
    }

    private val domains = ArrayList<String>()
    private lateinit var adapter: DomainAdapter
    private lateinit var etDomain: EditText
    private lateinit var tvEmpty: TextView

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        setContentView(R.layout.activity_exceptions)
        UiUtils.setupSystemBars(this)

        domains.addAll(SettingsStore.load(this).userExceptions.sorted())

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tv_builtin_note).text =
            getString(R.string.fmt_builtin_note, ClearUrlsData.EXCEPTIONS.size)

        etDomain = findViewById(R.id.et_domain)
        tvEmpty = findViewById(R.id.tv_empty)

        adapter = DomainAdapter()
        val recycler = findViewById<RecyclerView>(R.id.rv_exceptions)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        findViewById<TextView>(R.id.btn_add).setOnClickListener { addDomain() }
        etDomain.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addDomain()
                true
            } else {
                false
            }
        }

        refreshEmptyState()
    }

    private fun addDomain() {
        val domain = GhostConfig.normalizeDomain(etDomain.text.toString())
        if (domain == null) {
            Toast.makeText(this, R.string.msg_invalid_domain, Toast.LENGTH_SHORT).show()
            return
        }
        if (domain in domains) {
            Toast.makeText(this, R.string.msg_domain_exists, Toast.LENGTH_SHORT).show()
            return
        }
        domains.add(domain)
        domains.sort()
        persist()
        adapter.notifyDataSetChanged()
        etDomain.text.clear()
        refreshEmptyState()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(etDomain.windowToken, 0)
    }

    private fun removeAt(index: Int) {
        if (index < 0 || index >= domains.size) return
        domains.removeAt(index)
        persist()
        adapter.notifyDataSetChanged()
        refreshEmptyState()
    }

    private fun persist() {
        val snapshot = domains.toSet()
        SettingsStore.update(this) { config -> config.copy(userExceptions = snapshot) }
    }

    private fun refreshEmptyState() {
        tvEmpty.visibility = if (domains.isEmpty()) View.VISIBLE else View.GONE
    }

    private inner class DomainAdapter : RecyclerView.Adapter<DomainHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DomainHolder =
            DomainHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_exception, parent, false))

        override fun getItemCount(): Int = domains.size

        override fun onBindViewHolder(holder: DomainHolder, position: Int) {
            holder.domain.text = domains[position]
            holder.remove.setOnClickListener {
                val index = holder.bindingAdapterPosition
                if (index != RecyclerView.NO_POSITION) removeAt(index)
            }
        }
    }
}
