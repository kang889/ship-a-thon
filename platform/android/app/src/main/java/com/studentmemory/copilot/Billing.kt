package com.studentmemory.copilot

import android.app.Activity
import android.content.Context
import com.revenuecat.purchases.*
import com.studentmemory.copilot.services.Account
import com.studentmemory.copilot.ui.PackBackDialog as AlertDialog

/** RevenueCat's active "pro" entitlement is the only paid-access signal. */
object Billing {
    const val ENTITLEMENT = "pro"
    @Volatile var isPro: Boolean = false
        private set

    private fun configured() = BuildConfig.REVENUECAT_PUBLIC_KEY.isNotBlank()

    fun configure(context: Context) {
        if (!configured() || Purchases.isConfigured) return
        val builder = PurchasesConfiguration.Builder(context.applicationContext, BuildConfig.REVENUECAT_PUBLIC_KEY)
        Account.userId()?.let { builder.appUserID(it) }
        Purchases.configure(builder.build())
    }

    fun clearSession() { isPro = false }

    // Always align RevenueCat with the current Firebase account before checking or purchasing.
    private fun aligned(context: Context, ready: () -> Unit, failed: (String) -> Unit) {
        val user = Account.userId()
        if (user == null) { failed("Sign in first."); return }
        if (!configured()) { failed("Subscriptions are not configured yet."); return }
        configure(context)
        if (Purchases.sharedInstance.appUserID == user) ready()
        else Purchases.sharedInstance.logInWith(user,
            onError = { failed(it.message) }, onSuccess = { info, _ ->
                isPro = info.entitlements[ENTITLEMENT]?.isActive == true
                ready()
            })
    }

    /** Null means verification failed: callers must neither unlock nor show a purchase button. */
    fun checkPro(context: Context, result: (Boolean?) -> Unit) {
        aligned(context, {
            Purchases.sharedInstance.getCustomerInfoWith(
                onError = { result(null) },
                onSuccess = { info ->
                    isPro = info.entitlements[ENTITLEMENT]?.isActive == true
                    result(isPro)
                })
        }, { result(null) })
    }

    fun refreshProStatus(context: Context, onChanged: (Boolean) -> Unit = {}) {
        if (Account.userId() == null) {
            val changed = isPro
            clearSession()
            if (changed) onChanged(false)
            return
        }
        val before = isPro
        checkPro(context) { value -> if (value != null && before != value) onChanged(value) }
    }

    fun show(activity: Activity, onUnlocked: () -> Unit, onChanged: () -> Unit = {}) {
        if (Account.userId() == null) { message(activity, "Sign in before subscribing."); return }
        aligned(activity, {
            Purchases.sharedInstance.getOfferingsWith(
                onError = { retry(activity, it.message, onUnlocked, onChanged) },
                onSuccess = { offerings ->
                    val packages = offerings.current?.availablePackages.orEmpty()
                    if (packages.isEmpty()) {
                        retry(activity, "No subscription offering is available right now.", onUnlocked, onChanged)
                        return@getOfferingsWith
                    }
                    activity.runOnUiThread {
                        val features = ProFeature.values().joinToString("\n") { "✓ ${it.title}" }
                        val options = packages.map { "${it.product.title} · ${it.product.price.formatted}" }.toTypedArray()
                        AlertDialog.Builder(activity).presentation(AlertDialog.Layout.Pro)
                            .setTitle("Student Memory Pro")
                            .setMessage("Free remembers your day.\nPro learns how you forget.\n\n$features\n\nChoose a subscription:")
                            .setItems(options) { _, index ->
                                Purchases.sharedInstance.purchaseWith(
                                    PurchaseParams.Builder(activity, packages[index]).build(),
                                    onError = { error, cancelled ->
                                        if (cancelled) activity.runOnUiThread { show(activity, onUnlocked, onChanged) }
                                        else retry(activity, error.message, onUnlocked, onChanged)
                                    }, onSuccess = { _, _ -> verifyPurchase(activity, onUnlocked, onChanged) })
                            }
                            .setNeutralButton("Restore purchases") { _, _ -> restore(activity, onUnlocked, onChanged) }
                            .setNegativeButton("Close", null).show()
                    }
                })
        }, { retry(activity, it, onUnlocked, onChanged) })
    }

    fun restore(activity: Activity, onUnlocked: () -> Unit = {}, onChanged: () -> Unit = {}) {
        aligned(activity, {
            Purchases.sharedInstance.restorePurchasesWith(
                onError = { message(activity, it.message) },
                onSuccess = { _ -> verifyPurchase(activity, onUnlocked, onChanged) })
        }, { message(activity, it) })
    }

    private fun verifyPurchase(activity: Activity, onUnlocked: () -> Unit, onChanged: () -> Unit) {
        // Purchase and restore must both re-read CustomerInfo before granting access.
        checkPro(activity) { active -> activity.runOnUiThread {
            onChanged()
            when (active) {
                true -> onUnlocked()
                false -> message(activity, "No active Pro entitlement was found. Try restoring purchases.")
                null -> message(activity, "Could not verify the subscription. Please retry.")
            }
        } }
    }

    private fun retry(activity: Activity, reason: String, unlocked: () -> Unit, changed: () -> Unit) {
        activity.runOnUiThread {
            AlertDialog.Builder(activity).presentation(AlertDialog.Layout.Pro)
                .setTitle("Student Memory Pro")
                .setMessage("$reason\n\nSubscription offerings are temporarily unavailable.")
                .setPositiveButton("Retry") { _, _ -> show(activity, unlocked, changed) }
                .setNeutralButton("Restore purchases") { _, _ -> restore(activity, unlocked, changed) }
                .setNegativeButton("Close", null).show()
        }
    }

    private fun message(activity: Activity, value: String) {
        activity.runOnUiThread {
            AlertDialog.Builder(activity).setMessage(value).setPositiveButton("OK", null).show()
        }
    }
}
