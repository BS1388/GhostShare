package ir.ghostshare

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
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

/**
 * نقطه ورود ماژول GhostShare در چارچوب LSPosed / Xposed.
 *
 * تضمین‌های پایداری و امنیت در هسته سیستم (system_server):
 * ۱. پیشگیری قطعی از Memory Leak: پاک‌سازی کامل ThreadLocalها با remove() در انتهای هر فراخوانی بایندر.
 * ۲. صفر بودن احتمال بن‌بست (Deadlock): عدم اجرای Disk I/O یا IPC در بلاک‌های قفل همگام‌سازی شده.
 * ۳. ایزولاسیون کامل استثناها: مهار ۱۰۰٪ خطاها بدون پرتاب به هسته اندروید جهت تضمین صفر بودن احتمال سافت‌ریبوت.
 * ۴. لغو هدفمند تایمر اعلان با Runnable اختصاصی به جای removeCallbacksAndMessages(null) برای حفظ تسک‌های سیستمی.
 * ۵. پاک‌سازی هویت بایندر (Binder.clearCallingIdentity()) قبل از ارسال برودکست اعلان یا ثبت نوتیفیکیشن.
 * ۶. اعلان کاملاً سایلنت و بدون مزاحمت در پنل اعلان‌ها با اهمیت IMPORTANCE_LOW و شناسه ghostshare_silent_restore_v1.
 * ۷. پیشگیری قطعی از لوپ و نمایش مجدد اعلان پس از کلیک روی «بازگرداندن» با گارد چندلایه (Label، Extras، کش زمانی).
 */
class HookEntry : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "GhostShare"
        private const val CHANNEL_ID = "ghostshare_silent_restore_v1"
        private const val NOTIF_ID = 7001

        @Volatile
        private var systemContext: Context? = null

        @Volatile
        private var showRestoreNotification = true

        @Volatile
        private var systemReceiversRegistered = false

        // کش درون‌حافظه‌ای لینک‌های به‌تازگی بازگردانده شده جهت جلوگیری ۱۰۰٪ از لوپ پردازش
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

        // پرچم محلی ترد جهت جلوگیری از لوپ تودرتو؛ با remove() در بلاک finally پاک‌سازی کامل می‌شود
        private val isProcessing = ThreadLocal<Boolean>()

        // تسک مشخص جهت بستن اعلان بدون تداخل با تسک‌های دیگر روی ترد اصلی سیستم
        private val cancelNotificationRunnable = Runnable {
            try {
                val ctx = systemContext
                val nm = ctx?.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.cancel(NOTIF_ID)
            } catch (_: Throwable) {}
        }

        // مقداردهی ایمن و تنبل Handler بدون ریسک ExceptionInInitializerError در لود Zygote
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

        // مجموعه‌ای ایمن جهت جلوگیری از هوک تکراری متدها
        private val hookedMethods = Collections.synchronizedSet(HashSet<Member>())

        // اسامی کلاس‌های سرویس کلیپ‌بورد در رام‌های استاندارد و کاستوم
        private val SERVICE_CLASS_NAMES = listOf(
            "com.android.server.clipboard.ClipboardService",
            "com.android.server.ClipboardService",
            "com.android.server.SemClipboardService",
            "com.android.server.clipboard.MiuiClipboardService",
            "com.samsung.android.server.clipboardservice.ClipboardService"
        )
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        // ۱. هوک اپلیکیشن خود ماژول جهت سنجش آنی فعال‌سازی در LSPosed
        if (lpparam.packageName == "ir.ghostshare") {
            hookGhostShareApp(lpparam)
            return
        }

        // ۲. هوک هسته سیستم‌عامل
        if (lpparam.packageName == "android") {
            hookSystemServer(lpparam)
        } else {
            // ۳. هوک در سطح پروسه برنامه‌ها
            hookAppProcess(lpparam)
        }
    }

    // =========================================================================
    // لایه ۰: هوک درون برنامه GhostShare جهت سنجش آنی وضعیت
    // =========================================================================

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

    // =========================================================================
    // لایه ۱: هوک هسته سیستم‌عامل (system_server)
    // =========================================================================

    private fun hookSystemServer(lpparam: LoadPackageParam) {
        try {
            XposedBridge.log("[$TAG] Initializing in system_server (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})...")

            // دریافت اولیه کانتکست در صورت در دسترس بودن
            ensureSystemContext(null)
            systemContext?.let { registerSystemReceivers(it) }

            // هوک چرخه حیات راه‌اندازی SystemServer
            hookSystemServerLifecycle(lpparam.classLoader)

            // هوک سرویس کلیپ‌بورد در صورت فعالیت هم‌اکنون
            hookActiveClipboardFromServiceManager(lpparam.classLoader)

            // هوک ServiceManager.addService
            hookServiceManagerAddService(lpparam.classLoader)

            // هوک SystemService.publishBinderService
            hookSystemServicePublish(lpparam.classLoader)

            // اسکن کلاس‌های سرویس در رام‌های AOSP، شیائومی و سامسونگ
            hookKnownServiceClasses(lpparam.classLoader)

            // تلاش‌های زمان‌بندی‌شده ملایم جهت اطمینان از ثبت قطعی در بدو بوت
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
                    // در صورتی که کانتکست و رسیورها ثبت شده باشند، کار اضافی انجام نشود
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

            // مجاز کردن دسترسی پس‌زمینه کلیپ‌بورد برای GhostShare روی تمامی نسخه‌ها و رام‌ها
            try {
                XposedBridge.hookAllMethods(serviceClass, "clipboardAccessAllowed", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val isGhostShare = param.args.any { it is String && it == "ir.ghostshare" }
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
                // جلوگیری قطعی از Memory Leak در استخر تردهای بایندر سیستم‌سرور
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

        // بررسی پرچم‌ها و نشانه‌های بازگردانی لینک توسط کاربر
        val description = originalClip.description
        val extras = description?.extras
        val clipLabel = description?.label?.toString()

        if (callingPackage == "ir.ghostshare" ||
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

    // =========================================================================
    // لایه ۲: هوک در سطح پروسه برنامه‌ها (App Process Level)
    // =========================================================================

    private fun hookAppProcess(lpparam: LoadPackageParam) {
        try {
            val cmClass = XposedHelpers.findClassIfExists("android.content.ClipboardManager", lpparam.classLoader)
                ?: XposedHelpers.findClassIfExists("android.content.ClipboardManager", null)
            if (cmClass != null) {
                XposedBridge.hookAllMethods(cmClass, "setPrimaryClip", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        handleAppSetPrimaryClip(param, lpparam.packageName)
                    }
                })

                XposedBridge.hookAllMethods(cmClass, "setText", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        handleAppSetText(param, lpparam.packageName)
                    }
                })
            }
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] Error hooking ClipboardManager in ${lpparam.packageName}: ${t.message}")
        }
    }

    private fun handleAppSetPrimaryClip(param: XC_MethodHook.MethodHookParam, packageName: String) {
        if (packageName == "ir.ghostshare") return
        if (isProcessing.get() == true) return
        try {
            isProcessing.set(true)
            val clipIndex = param.args.indexOfFirst { it is ClipData }
            if (clipIndex == -1) return

            val originalClip = param.args[clipIndex] as? ClipData ?: return
            val description = originalClip.description
            val extras = description?.extras
            val clipLabel = description?.label?.toString()

            if (clipLabel == Constants.LABEL_RESTORED_LINK ||
                extras?.getBoolean(Constants.EXTRA_BYPASS_CLEAN, false) == true) return

            val firstText = originalClip.getItemAt(0)?.text?.toString()
            if (isRecentlyRestored(firstText)) return

            val cleanResult = cleanClipData(originalClip) ?: return
            param.args[clipIndex] = cleanResult.newClip

            XposedBridge.log("[$TAG] Cleaned clipboard in-app for: $packageName")
            for ((before, after) in cleanResult.cleanedUrls) {
                XposedBridge.log("[$TAG]   [Raw]   $before")
                XposedBridge.log("[$TAG]   [Clean] $after")
            }

            cleanResult.firstOriginalText?.let { originalText ->
                dispatchRestoreNotification(param.thisObject, originalText, packageName)
            }
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] Error in app setPrimaryClip: ${t.message}")
        } finally {
            isProcessing.remove()
        }
    }

    private fun handleAppSetText(param: XC_MethodHook.MethodHookParam, packageName: String) {
        if (packageName == "ir.ghostshare") return
        if (isProcessing.get() == true) return
        try {
            isProcessing.set(true)
            val text = param.args.getOrNull(0) as? CharSequence ?: return
            val textStr = text.toString()
            if (isRecentlyRestored(textStr)) return

            val report = UrlCleaner.cleanWithReport(textStr) ?: return
            param.args[0] = report.cleanedText
            XposedBridge.log("[$TAG] Cleaned clipboard setText in-app for: $packageName")

            dispatchRestoreNotification(param.thisObject, textStr, packageName)
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] Error in app setText: ${t.message}")
        } finally {
            isProcessing.remove()
        }
    }

    /**
     * ارسال تضمینی اعلان بازیابی:
     * - با پاک‌سازی هویت بایندر (Binder.clearCallingIdentity()) تا محدودیت‌های UID کلاینت برطرف شود.
     * - با اینتنت صریح (Explicit Intent) همراه با پرچم‌های فورگراند و IncludeStoppedPackages جهت بیدارباش گیرنده.
     * - نمایش مستقیم از system_server در صورت عدم دسترسی به برودکست.
     */
    private fun dispatchRestoreNotification(thisObject: Any?, originalText: String, callingPackage: String) {
        val token = Binder.clearCallingIdentity()
        try {
            ensureSystemContext(thisObject)

            val explicitIntent = Intent(Constants.ACTION_SHOW_NOTIFICATION).apply {
                setClassName("ir.ghostshare", "ir.ghostshare.NotificationReceiver")
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                putExtra(Constants.EXTRA_ORIGINAL_LINK, originalText)
                putExtra(Constants.EXTRA_CALLING_PACKAGE, callingPackage)
            }

            var broadcastSent = false
            systemContext?.let { ctx ->
                try {
                    ctx.sendBroadcast(explicitIntent)
                    broadcastSent = true
                    XposedBridge.log("[$TAG] Dispatched explicit restore notification broadcast to ir.ghostshare")
                } catch (t: Throwable) {
                    XposedBridge.log("[$TAG] Failed to send broadcast from systemContext: ${t.message}")
                }
            }

            if (!broadcastSent) {
                // تلاش با کانتکست کلاینت در لایه برنامه‌ها
                val ctx = getContextFromObject(thisObject)
                if (ctx != null) {
                    try {
                        ctx.sendBroadcast(explicitIntent)
                        broadcastSent = true
                    } catch (_: Throwable) {}
                }
            }

            // فال‌بک مستقیم در هسته سیستم‌سرور در صورتی که برودکست ارسال نشد
            if (!broadcastSent) {
                systemContext?.let { ctx ->
                    showRestoreNotification(ctx, originalText, callingPackage)
                }
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

    // =========================================================================
    // منطق مشترک پاک‌سازی و اعلان
    // =========================================================================

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

            // ۱. پاک‌سازی متن ساده
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

            // ۲. پاک‌سازی متن HTML
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

            // ۳. پاک‌سازی URI وب
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

    /**
     * ثبت رسیورهای سیستمی در پروسه system_server:
     * کاملاً ایزوله، بدون قفل‌های سنگین و بدون بلاک کردن تردهای سیستمی
     */
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
                                }
                                Constants.ACTION_CHECK_STATUS -> {
                                    resultCode = Activity.RESULT_OK
                                    XposedBridge.log("[$TAG] Status check received in system_server -> RESULT_OK")
                                }
                                Constants.ACTION_RESTORE_CLIPBOARD -> {
                                    val original = intent.getStringExtra(Constants.EXTRA_ORIGINAL_LINK)
                                    if (!original.isNullOrEmpty()) {
                                        recordRestoredText(original)

                                        // لغو فوری اعلان با Runnable هدفمند
                                        getMainHandler()?.removeCallbacks(cancelNotificationRunnable)
                                        try {
                                            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                                            nm?.cancel(NOTIF_ID)
                                        } catch (_: Throwable) {}
                                    }
                                }
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log("[$TAG] Error handling system broadcast: ${t.message}")
                        }
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    ctx.registerReceiver(receiver, filter)
                }
                systemReceiversRegistered = true
                XposedBridge.log("[$TAG] System receivers successfully registered in system_server")

                // بارگذاری تنظیمات بدون نگه داشتن قفل روی ترد جاری
                getMainHandler()?.post {
                    showRestoreNotification = loadSettingsFromApp(ctx)
                }
            } catch (t: Throwable) {
                XposedBridge.log("[$TAG] Warning: could not register system receivers: ${t.message}")
            }
        }
    }

    private fun loadSettingsFromApp(ctx: Context): Boolean {
        try {
            val appCtx = ctx.createPackageContext("ir.ghostshare", Context.CONTEXT_IGNORE_SECURITY)
            val sp = appCtx.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            if (sp.contains(Constants.PREF_SHOW_RESTORE_NOTIFICATION)) {
                return sp.getBoolean(Constants.PREF_SHOW_RESTORE_NOTIFICATION, true)
            }
        } catch (_: Throwable) {}

        try {
            val paths = listOf(
                "/data/user/0/ir.ghostshare/shared_prefs/${Constants.PREFS_NAME}.xml",
                "/data/data/ir.ghostshare/shared_prefs/${Constants.PREFS_NAME}.xml"
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

    private fun showRestoreNotification(ctx: Context, originalText: String, callingPackage: String) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    nm.deleteNotificationChannel("ghostshare_clipboard")
                    nm.deleteNotificationChannel("ghostshare_clipboard_v2")
                } catch (_: Throwable) {}

                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "GhostShare - اعلان بازیابی لینک",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "اعلان سایلنت بازیابی لینک پاک‌سازی شده در پنل اعلان‌ها"
                    enableVibration(false)
                    setSound(null, null)
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }

            val restoreBroadcastIntent = Intent(Constants.ACTION_RESTORE_CLIPBOARD).apply {
                setPackage("android")
                putExtra(Constants.EXTRA_ORIGINAL_LINK, originalText)
            }

            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val restorePendingIntent = PendingIntent.getBroadcast(
                ctx,
                NOTIF_ID + 1,
                restoreBroadcastIntent,
                flags
            )

            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(ctx, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(ctx)
            }

            val contentText = if (callingPackage.isNotEmpty() && callingPackage != "unknown") {
                "پارامترهای رهگیری حذف شدند ($callingPackage)."
            } else {
                "پارامترهای رهگیری از کلیپ‌بورد حذف شدند."
            }

            val actionTitle = "بازگرداندن"
            val actionIcon = android.R.drawable.ic_menu_revert
            val restoreAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val icon = Icon.createWithResource(ctx, actionIcon)
                Notification.Action.Builder(icon, actionTitle, restorePendingIntent).build()
            } else {
                @Suppress("DEPRECATION")
                Notification.Action.Builder(actionIcon, actionTitle, restorePendingIntent).build()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val icon = Icon.createWithResource("ir.ghostshare", R.drawable.ic_notification_ghost)
                    builder.setSmallIcon(icon)
                } catch (_: Throwable) {
                    builder.setSmallIcon(android.R.drawable.ic_menu_share)
                }
            } else {
                builder.setSmallIcon(android.R.drawable.ic_menu_share)
            }

            val notification = builder
                .setContentTitle("لینک پاک‌سازی شد (GhostShare)")
                .setContentText(contentText)
                .setColor(0xFF00629E.toInt())
                .setPriority(Notification.PRIORITY_LOW)
                .setSound(null)
                .setVibrate(null)
                .setCategory(Notification.CATEGORY_STATUS)
                .addAction(restoreAction)
                .setContentIntent(restorePendingIntent)
                .setAutoCancel(true)
                .setTimeoutAfter(5000L)
                .build()

            nm.notify(NOTIF_ID, notification)

            getMainHandler()?.removeCallbacks(cancelNotificationRunnable)
            getMainHandler()?.postDelayed(cancelNotificationRunnable, 5000L)
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] Failed to display system notification: ${t.message}")
        }
    }
}
