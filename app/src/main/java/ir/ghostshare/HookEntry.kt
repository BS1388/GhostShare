package ir.ghostshare

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import android.provider.Settings
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.io.File
import java.lang.reflect.Member
import java.lang.reflect.Modifier
import java.util.Collections

class HookEntry : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "GhostShare"

        @Volatile
        private var systemContext: Context? = null

        @Volatile
        private var showRestoreNotification = true

        @Volatile
        private var config = GhostConfig()

        @Volatile
        private var systemReceiversRegistered = false

        @Volatile
        private var lastRestoredText: String? = null
        @Volatile
        private var lastRestoredTimestamp: Long = 0L

        private fun isRecentlyRestored(text: String?): Boolean {
            if (text.isNullOrEmpty()) return false
            val now = SystemClock.elapsedRealtime()
            return (now - lastRestoredTimestamp < 4000L) && (text == lastRestoredText)
        }

        private fun recordRestoredText(text: String) {
            lastRestoredText = text
            lastRestoredTimestamp = SystemClock.elapsedRealtime()
        }

        private val isProcessing = ThreadLocal<Boolean>()

        @Volatile
        private var mainHandler: Handler? = null

        private fun getMainHandler(): Handler? {
            val looper = Looper.getMainLooper() ?: return null
            if (mainHandler == null) {
                synchronized(this) {
                    if (mainHandler == null) {
                        mainHandler = Handler(looper)
                    }
                }
            }
            return mainHandler
        }

        private val hookedMethods = Collections.synchronizedSet(HashSet<Member>())

        private val configHandler: Handler by lazy {
            val thread = HandlerThread("ghostshare-config")
            thread.start()
            Handler(thread.looper)
        }

        private val SERVICE_CLASS_NAMES = listOf(
            "com.android.server.clipboard.ClipboardService",
            "com.android.server.ClipboardService",
            "com.android.server.SemClipboardService",
            "com.android.server.clipboard.MiuiClipboardService",
            "com.samsung.android.server.clipboardservice.ClipboardService"
        )
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        when (lpparam.packageName) {
            Constants.APP_PACKAGE -> hookGhostShareApp(lpparam)
            "android" -> hookSystemServer(lpparam)
        }
    }

    private fun hookGhostShareApp(lpparam: LoadPackageParam) {
        try {
            val mainActivityClass = XposedHelpers.findClassIfExists("ir.ghostshare.MainActivity", lpparam.classLoader)
            if (mainActivityClass != null) {
                XposedBridge.hookAllMethods(mainActivityClass, "isModuleActive", object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any = true
                })
                XposedBridge.log("[$TAG] Successfully hooked MainActivity.isModuleActive() -> true")
            }
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] Failed to hook MainActivity.isModuleActive: ${t.message}")
        }
    }

    private fun hookSystemServer(lpparam: LoadPackageParam) {
        try {
            XposedBridge.log("[$TAG] Initializing in system_server (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})...")

            ensureSystemContext(null)
            systemContext?.let { registerSystemReceivers(it) }

            hookSystemServerLifecycle(lpparam.classLoader)

            hookActiveClipboardFromServiceManager(lpparam.classLoader)

            hookServiceManagerAddService(lpparam.classLoader)

            hookSystemServicePublish(lpparam.classLoader)

            hookKnownServiceClasses(lpparam.classLoader)

            scheduleContextRetries(lpparam.classLoader)
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] Safe isolation: error in hookSystemServer: ${t.message}")
        }
    }

    private fun hookSystemServerLifecycle(classLoader: ClassLoader) {
        try {
            val atClass = Class.forName("android.app.ActivityThread")
            XposedBridge.hookAllMethods(atClass, "systemMain", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val thread = param.result
                    if (thread != null) {
                        try {
                            val getCtx = thread.javaClass.getMethod("getSystemContext")
                            val ctx = getCtx.invoke(thread) as? Context
                            if (ctx != null) {
                                systemContext = ctx
                                registerSystemReceivers(ctx)
                            }
                        } catch (_: Throwable) {}
                    }
                }
            })
        } catch (_: Throwable) {}

        try {
            val systemServerClass = XposedHelpers.findClassIfExists("com.android.server.SystemServer", classLoader)
            if (systemServerClass != null) {
                val lifecycleHook = object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        ensureSystemContext(null)
                        systemContext?.let { registerSystemReceivers(it) }
                        hookActiveClipboardFromServiceManager(classLoader)
                    }
                }
                XposedBridge.hookAllMethods(systemServerClass, "run", lifecycleHook)
                XposedBridge.hookAllMethods(systemServerClass, "startOtherServices", lifecycleHook)
                XposedBridge.hookAllMethods(systemServerClass, "startBootstrapServices", lifecycleHook)
            }
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] Warning: could not hook SystemServer lifecycle: ${t.message}")
        }
    }

    private fun scheduleContextRetries(classLoader: ClassLoader) {
        val retries = listOf(500L, 1500L, 3000L, 6000L)
        for (delay in retries) {
            getMainHandler()?.postDelayed({
                try {

                    if (systemContext != null && systemReceiversRegistered) {
                        return@postDelayed
                    }
                    if (systemContext == null) {
                        ensureSystemContext(null)
                    }
                    systemContext?.let { registerSystemReceivers(it) }
                    hookActiveClipboardFromServiceManager(classLoader)
                } catch (_: Throwable) {}
            }, delay)
        }
    }

    private fun hookActiveClipboardFromServiceManager(classLoader: ClassLoader) {
        try {
            val smClass = XposedHelpers.findClassIfExists("android.os.ServiceManager", classLoader)
                ?: XposedHelpers.findClassIfExists("android.os.ServiceManager", null)
            if (smClass != null) {
                val binder = XposedHelpers.callStaticMethod(smClass, "getService", Context.CLIPBOARD_SERVICE)
                    ?: XposedHelpers.callStaticMethod(smClass, "checkService", Context.CLIPBOARD_SERVICE)
                    ?: XposedHelpers.callStaticMethod(smClass, "getService", "clipboard")
                    ?: XposedHelpers.callStaticMethod(smClass, "checkService", "clipboard")

                if (binder != null) {
                    XposedBridge.log("[$TAG] Found active clipboard binder: ${binder.javaClass.name}")
                    hookClipboardMethods(binder.javaClass)
                }
            }
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] Warning: could not inspect active ServiceManager binder: ${t.message}")
        }
    }

    private fun hookServiceManagerAddService(classLoader: ClassLoader) {
        try {
            val smClass = XposedHelpers.findClassIfExists("android.os.ServiceManager", classLoader)
                ?: XposedHelpers.findClassIfExists("android.os.ServiceManager", null)
            if (smClass != null) {
                XposedBridge.hookAllMethods(smClass, "addService", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val name = param.args.getOrNull(0) as? String
                            if (name == Context.CLIPBOARD_SERVICE || name == "clipboard") {
                                val binderObj = param.args.getOrNull(1)
                                if (binderObj != null) {
                                    XposedBridge.log("[$TAG] ServiceManager.addService detected: ${binderObj.javaClass.name}")
                                    hookClipboardMethods(binderObj.javaClass)
                                }
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log("[$TAG] Error in ServiceManager hook: ${t.message}")
                        }
                    }
                })
            }
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] Warning: could not hook ServiceManager.addService: ${t.message}")
        }
    }

    private fun hookSystemServicePublish(classLoader: ClassLoader) {
        try {
            val systemServiceClass = XposedHelpers.findClassIfExists("com.android.server.SystemService", classLoader)
            if (systemServiceClass != null) {
                XposedBridge.hookAllMethods(systemServiceClass, "publishBinderService", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val svc = param.thisObject
                            if (svc != null && systemContext == null) {
                                try {
                                    val getCtx = svc.javaClass.getMethod("getContext")
                                    val ctx = getCtx.invoke(svc) as? Context
                                    if (ctx != null) {
                                        systemContext = ctx
                                        registerSystemReceivers(ctx)
                                    }
                                } catch (_: Throwable) {}
                            }
                            val name = param.args.getOrNull(0) as? String
                            if (name == Context.CLIPBOARD_SERVICE || name == "clipboard") {
                                val binderObj = param.args.getOrNull(1)
                                if (binderObj != null) {
                                    XposedBridge.log("[$TAG] publishBinderService: ${binderObj.javaClass.name}")
                                    hookClipboardMethods(binderObj.javaClass)
                                }
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log("[$TAG] Error in publishBinderService: ${t.message}")
                        }
                    }
                })
            }
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] Warning: could not hook SystemService: ${t.message}")
        }
    }

    private fun hookKnownServiceClasses(classLoader: ClassLoader) {
        for (serviceName in SERVICE_CLASS_NAMES) {
            val serviceClass = XposedHelpers.findClassIfExists(serviceName, classLoader) ?: continue

            try {
                XposedBridge.hookAllConstructors(serviceClass, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val svc = param.thisObject
                            if (svc != null) {
                                try {
                                    val getCtx = svc.javaClass.getMethod("getContext")
                                    val ctx = getCtx.invoke(svc) as? Context
                                    if (ctx != null) {
                                        systemContext = ctx
                                        registerSystemReceivers(ctx)
                                    }
                                } catch (_: Throwable) {
                                    val ctx = param.args.firstOrNull { it is Context } as? Context
                                    if (ctx != null) {
                                        systemContext = ctx
                                        registerSystemReceivers(ctx)
                                    }
                                }
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log("[$TAG] Failed to capture context from constructor: ${t.message}")
                        }
                    }
                })
            } catch (_: Throwable) {}

            try {
                XposedBridge.hookAllMethods(serviceClass, "clipboardAccessAllowed", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val isGhostShare = param.args.any { it is String && it == Constants.APP_PACKAGE }
                            if (isGhostShare) {
                                param.result = true
                            }
                        } catch (_: Throwable) {}
                    }
                })
            } catch (_: Throwable) {}

            hookClipboardMethods(serviceClass)

            val implClass = XposedHelpers.findClassIfExists("$serviceName\$ClipboardImpl", classLoader)
            if (implClass != null) {
                hookClipboardMethods(implClass)
            }

            try {
                for (declared in serviceClass.declaredClasses) {
                    hookClipboardMethods(declared)
                }
            } catch (_: Throwable) {}
        }
    }

    private fun hookClipboardMethods(clazz: Class<*>) {
        try {
            val allMethods = (clazz.declaredMethods.asSequence() + clazz.methods.asSequence()).distinct()

            for (method in allMethods) {
                val isTargetMethodName = method.name == "setPrimaryClip" ||
                        method.name == "setPrimaryClipAsPackage" ||
                        method.name.startsWith("setPrimaryClip")

                if (isTargetMethodName && !Modifier.isAbstract(method.modifiers)) {
                    val hasClipDataParam = method.parameterTypes.any { ClipData::class.java.isAssignableFrom(it) }
                    if (hasClipDataParam && hookedMethods.add(method)) {
                        try { method.isAccessible = true } catch (_: Throwable) {}
                        XposedBridge.hookMethod(method, clipMethodHook)
                        val paramSignature = method.parameterTypes.joinToString(", ") { it.simpleName }
                        XposedBridge.log("[$TAG] Hooked system method: ${method.declaringClass.name}.${method.name}($paramSignature)")
                    }
                }
            }
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] Failed to inspect methods on class ${clazz.name}: ${t.message}")
        }
    }

    private val clipMethodHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (isProcessing.get() == true) return
            try {
                isProcessing.set(true)
                handleSystemSetPrimaryClip(param)
            } catch (t: Throwable) {
                XposedBridge.log("[$TAG] Error inside system setPrimaryClip hook: ${t.message}")
            } finally {

                isProcessing.remove()
            }
        }
    }

    private fun handleSystemSetPrimaryClip(param: XC_MethodHook.MethodHookParam) {
        val clipIndex = param.args.indexOfFirst { it is ClipData }
        if (clipIndex == -1) return

        val originalClip = param.args[clipIndex] as? ClipData ?: return

        val callingPackage = (param.args.getOrNull(1) as? String)
            ?: (param.args.firstOrNull { it is String } as? String)
            ?: ""

        val description = originalClip.description
        val extras = description?.extras
        val clipLabel = description?.label?.toString()

        if (callingPackage == Constants.APP_PACKAGE ||
            clipLabel == Constants.LABEL_RESTORED_LINK ||
            extras?.getBoolean(Constants.EXTRA_BYPASS_CLEAN, false) == true) {
            XposedBridge.log("[$TAG] Restored clip detected. Preserving without cleaning or notifications.")
            try { extras?.remove(Constants.EXTRA_BYPASS_CLEAN) } catch (_: Throwable) { description?.extras = null }
            return
        }

        val firstText = originalClip.getItemAt(0)?.text?.toString()
        if (isRecentlyRestored(firstText)) {
            XposedBridge.log("[$TAG] Recently restored link detected via memory cache. Skipping.")
            return
        }

        if (!systemReceiversRegistered) {
            ensureSystemContext(param.thisObject)
            systemContext?.let { registerSystemReceivers(it) }
        }

        if (!config.shouldClean(callingPackage)) {
            XposedBridge.log("[$TAG] Per-app rule: skipping ${if (callingPackage.isNotEmpty()) callingPackage else "unknown"}")
            return
        }

        val cleanResult = cleanClipData(originalClip) ?: return

        param.args[clipIndex] = cleanResult.newClip

        XposedBridge.log("[$TAG] Cleaned clipboard (system_server) for: ${if (callingPackage.isNotEmpty()) callingPackage else "unknown"}")

        ensureSystemContext(param.thisObject)
        systemContext?.let { registerSystemReceivers(it) }

        if (showRestoreNotification) {
            cleanResult.firstOriginalText?.let { originalText ->
                dispatchRestoreNotification(param.thisObject, originalText, callingPackage)
            }
        }
    }

    private fun dispatchRestoreNotification(thisObject: Any?, originalText: String, callingPackage: String) {
        val token = Binder.clearCallingIdentity()
        try {
            ensureSystemContext(thisObject)
            val ctx = systemContext ?: getContextFromObject(thisObject) ?: return

            try {
                val explicitIntent = Intent(Constants.ACTION_SHOW_NOTIFICATION).apply {
                    setClassName(Constants.APP_PACKAGE, "ir.ghostshare.NotificationReceiver")
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    putExtra(Constants.EXTRA_ORIGINAL_LINK, originalText)
                    putExtra(Constants.EXTRA_CALLING_PACKAGE, callingPackage)
                }
                ctx.sendBroadcast(explicitIntent)
            } catch (t: Throwable) {
                XposedBridge.log("[$TAG] Failed to send restore notification: ${t.message}")
            }
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] Error dispatching restore notification: ${t.message}")
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun getContextFromObject(obj: Any?): Context? {
        if (obj == null) return null
        if (obj is Context) return obj
        try {
            val field = obj.javaClass.declaredFields.firstOrNull { it.name == "mContext" }
            if (field != null) {
                field.isAccessible = true
                val ctx = field.get(obj) as? Context
                if (ctx != null) return ctx
            }
        } catch (_: Throwable) {}

        try {
            val atClass = Class.forName("android.app.ActivityThread")
            val at = atClass.getMethod("currentActivityThread").invoke(null)
            if (at != null) {
                return (atClass.getMethod("getApplication").invoke(at) as? Context)
                    ?: (atClass.getMethod("getSystemContext").invoke(at) as? Context)
            }
        } catch (_: Throwable) {}

        return null
    }

    private class CleanResult(
        val newClip: ClipData,
        val cleanedUrls: List<Pair<String, String>>,
        val firstOriginalText: String?
    )

    private fun cleanClipData(clip: ClipData): CleanResult? {
        val itemCount = clip.itemCount
        if (itemCount <= 0) return null

        var hasChanges = false
        val cleanedUrlsList = mutableListOf<Pair<String, String>>()
        val newItems = ArrayList<ClipData.Item>(itemCount)
        var firstOriginalText: String? = null

        for (i in 0 until itemCount) {
            val item = clip.getItemAt(i) ?: continue
            var itemModified = false

            val currentText = item.text?.toString()
            val cleanedText = if (!currentText.isNullOrEmpty()) {
                val report = UrlCleaner.cleanWithReport(currentText)
                if (report != null) {
                    itemModified = true
                    cleanedUrlsList.addAll(report.cleanedPairs)
                    if (firstOriginalText == null) {
                        firstOriginalText = currentText
                    }
                    report.cleanedText
                } else {
                    null
                }
            } else {
                null
            }

            val currentHtml = item.htmlText
            val cleanedHtml = if (!currentHtml.isNullOrEmpty()) {
                val report = UrlCleaner.cleanWithReport(currentHtml)
                if (report != null) {
                    itemModified = true
                    cleanedUrlsList.addAll(report.cleanedPairs)
                    if (firstOriginalText == null) {
                        firstOriginalText = currentHtml
                    }
                    report.cleanedText
                } else {
                    currentHtml
                }
            } else {
                null
            }

            val currentUri = item.uri
            val cleanedUri = if (currentUri != null &&
                (currentUri.scheme.equals("http", ignoreCase = true) || currentUri.scheme.equals("https", ignoreCase = true))) {
                val uriStr = currentUri.toString()
                val cleanedUriStr = UrlCleaner.cleanUrlString(uriStr)
                if (cleanedUriStr != null) {
                    itemModified = true
                    cleanedUrlsList.add(uriStr to cleanedUriStr)
                    if (firstOriginalText == null) {
                        firstOriginalText = uriStr
                    }
                    try { Uri.parse(cleanedUriStr) } catch (_: Throwable) { currentUri }
                } else {
                    currentUri
                }
            } else {
                currentUri
            }

            if (itemModified) {
                hasChanges = true
                val finalText = cleanedText ?: item.text
                newItems.add(ClipData.Item(finalText, cleanedHtml, item.intent, cleanedUri))
            } else {
                newItems.add(item)
            }
        }

        if (!hasChanges || newItems.isEmpty()) return null

        val description = clip.description ?: ClipDescription("", arrayOf(ClipDescription.MIMETYPE_TEXT_PLAIN))
        val newClip = ClipData(description, newItems[0])
        for (i in 1 until newItems.size) {
            newClip.addItem(newItems[i])
        }

        return CleanResult(newClip, cleanedUrlsList, firstOriginalText)
    }

    private fun registerSystemReceivers(ctx: Context) {
        if (systemReceiversRegistered) return
        synchronized(this) {
            if (systemReceiversRegistered) return
            try {
                systemContext = ctx

                val filter = IntentFilter().apply {
                    addAction(Constants.ACTION_UPDATE_SETTINGS)
                    addAction(Constants.ACTION_CHECK_STATUS)
                    addAction(Constants.ACTION_RESTORE_CLIPBOARD)
                }

                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        try {
                            when (intent?.action) {
                                Constants.ACTION_UPDATE_SETTINGS -> {
                                    val enabled = intent.getBooleanExtra(
                                        Constants.EXTRA_SHOW_RESTORE_NOTIFICATION,
                                        true
                                    )
                                    showRestoreNotification = enabled
                                    XposedBridge.log("[$TAG] Settings updated live: showRestoreNotification = $enabled")
                                    GhostConfig.decode(intent.getStringExtra(Constants.EXTRA_CONFIG))?.let { applyConfig(it) }
                                }
                                Constants.ACTION_CHECK_STATUS -> {
                                    resultCode = Activity.RESULT_OK
                                    XposedBridge.log("[$TAG] Status check received in system_server -> RESULT_OK")
                                }
                                Constants.ACTION_RESTORE_CLIPBOARD -> {
                                    val original = intent.getStringExtra(Constants.EXTRA_ORIGINAL_LINK)
                                    if (!original.isNullOrEmpty()) {
                                        recordRestoredText(original)

                                        try {
                                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                            val clipData = ClipData.newPlainText(Constants.LABEL_RESTORED_LINK, original)
                                            val bypassExtras = PersistableBundle().apply {
                                                putBoolean(Constants.EXTRA_BYPASS_CLEAN, true)
                                            }
                                            clipData.description.extras = bypassExtras
                                            cm?.setPrimaryClip(clipData)
                                            XposedBridge.log("[$TAG] Successfully restored original link to clipboard from system_server")
                                        } catch (t: Throwable) {
                                            XposedBridge.log("[$TAG] Failed to restore clip in system_server: ${t.message}")
                                        }

                                        if (!intent.getBooleanExtra("from_app", false)) {
                                            try {
                                                val toastIntent = Intent(Constants.ACTION_RESTORE_CLIPBOARD).apply {
                                                    setClassName(Constants.APP_PACKAGE, "ir.ghostshare.NotificationReceiver")
                                                    putExtra(Constants.EXTRA_ORIGINAL_LINK, original)
                                                    putExtra("from_system", true)
                                                }
                                                ctx.sendBroadcast(toastIntent)
                                            } catch (_: Throwable) {}
                                        }
                                    }
                                }
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log("[$TAG] Error handling system broadcast: ${t.message}")
                        }
                    }
                }

                val exportedFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_EXPORTED else 0
                ctx.registerReceiver(receiver, filter, Constants.CONTROL_PERMISSION, configHandler, exportedFlag)

                val unlockReceiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        reloadFromApp(ctx)
                    }
                }
                val privateFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_NOT_EXPORTED else 0
                ctx.registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_UNLOCKED), null, configHandler, privateFlag)

                systemReceiversRegistered = true
                XposedBridge.log("[$TAG] System receivers successfully registered in system_server")

                configHandler.post {
                    try {
                        Settings.Global.putString(ctx.contentResolver, Constants.GLOBAL_SETTING_KEY, null)
                    } catch (_: Throwable) {}
                    reloadFromApp(ctx)
                }
            } catch (t: Throwable) {
                XposedBridge.log("[$TAG] Warning: could not register system receivers: ${t.message}")
            }
        }
    }

    private fun applyConfig(newConfig: GhostConfig) {
        config = newConfig
        newConfig.applyToCleaner()
        XposedBridge.log("[$TAG] Config applied: perApp=${newConfig.perAppEnabled}(${newConfig.perAppPackages.size}) bypass=${newConfig.bypassEnabled}(${newConfig.bypassPackages.size}) redirect=${newConfig.redirectUnwrap} params=+${newConfig.addedParameters.size}/-${newConfig.removedParameters.size} exceptions=${newConfig.exceptionDomains.size}")
    }

    private fun reloadFromApp(ctx: Context) {
        try {
            showRestoreNotification = loadSettingsFromApp(ctx)
            applyConfig(loadConfigFromApp(ctx) ?: config)
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] Failed to reload config: ${t.message}")
        }
    }

    private fun loadConfigFromApp(ctx: Context): GhostConfig? {
        for (pkg in listOf(Constants.APP_PACKAGE)) {
            try {
                val appCtx = ctx.createPackageContext(pkg, Context.CONTEXT_IGNORE_SECURITY)
                val sp = appCtx.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                if (GhostConfig.hasStoredValues(sp)) return GhostConfig.fromPrefs(sp)
            } catch (_: Throwable) {}

            try {
                val paths = listOf(
                    "/data/user/0/$pkg/shared_prefs/${Constants.PREFS_NAME}.xml",
                    "/data/data/$pkg/shared_prefs/${Constants.PREFS_NAME}.xml"
                )
                for (path in paths) {
                    val file = File(path)
                    if (file.exists() && file.canRead()) {
                        val parsed = GhostConfig.fromPrefsXml(file.readText())
                        if (parsed != null) return parsed
                    }
                }
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun loadSettingsFromApp(ctx: Context): Boolean {
        for (pkg in listOf(Constants.APP_PACKAGE)) {
            try {
                val appCtx = ctx.createPackageContext(pkg, Context.CONTEXT_IGNORE_SECURITY)
                val sp = appCtx.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                if (sp.contains(Constants.PREF_SHOW_RESTORE_NOTIFICATION)) {
                    return sp.getBoolean(Constants.PREF_SHOW_RESTORE_NOTIFICATION, true)
                }
            } catch (_: Throwable) {}

            try {
                val paths = listOf(
                    "/data/user/0/$pkg/shared_prefs/${Constants.PREFS_NAME}.xml",
                    "/data/data/$pkg/shared_prefs/${Constants.PREFS_NAME}.xml"
                )
                for (path in paths) {
                    val file = File(path)
                    if (file.exists() && file.canRead()) {
                        val text = file.readText()
                        if (text.contains("name=\"${Constants.PREF_SHOW_RESTORE_NOTIFICATION}\" value=\"false\"")) {
                            return false
                        }
                        if (text.contains("name=\"${Constants.PREF_SHOW_RESTORE_NOTIFICATION}\" value=\"true\"")) {
                            return true
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

        return true
    }

    private fun ensureSystemContext(thisObject: Any?) {
        if (systemContext != null) return

        if (thisObject != null) {
            try {
                if (thisObject is Context) {
                    systemContext = thisObject
                    return
                }
                var target: Any = thisObject
                for (field in thisObject.javaClass.declaredFields) {
                    if (field.name.startsWith("this$")) {
                        field.isAccessible = true
                        val outer = field.get(thisObject)
                        if (outer != null) {
                            target = outer
                            break
                        }
                    }
                }
                val getContextMethod = target.javaClass.methods.firstOrNull {
                    it.name == "getContext" && it.parameterTypes.isEmpty()
                }
                val ctx = getContextMethod?.invoke(target) as? Context
                if (ctx != null) {
                    systemContext = ctx
                    return
                }
            } catch (_: Throwable) {}
        }

        try {
            val atClass = Class.forName("android.app.ActivityThread")
            val at = atClass.getMethod("currentActivityThread").invoke(null)
            if (at != null) {
                val ctx = atClass.getMethod("getSystemContext").invoke(at) as? Context
                if (ctx != null) {
                    systemContext = ctx
                    return
                }
            }
        } catch (_: Throwable) {}
    }
}
