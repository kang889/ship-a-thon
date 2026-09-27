package com.studentmemory.copilot

import android.app.Activity
import android.content.Context
import com.studentmemory.copilot.ui.PackBackDialog as AlertDialog
import com.revenuecat.purchases.*
import com.studentmemory.copilot.services.Account

// RevenueCat is the single source of truth for Pro access. Pro status is only ever
// derived from CustomerInfo entitlement "pro"; it is never inferred from login,
// product id, a purchase button, or a permanent local flag.
object Billing {
    const val ENTITLEMENT = "pro"

    // Last CustomerInfo-derived Pro state. Defaults to false until RevenueCat reports.
    @Volatile
    var isPro: Boolean = false
        private set

    private fun keyConfigured() = BuildConfig.REVENUECAT_PUBLIC_KEY.isNotBlank()

    // Idempotent initialisation. Reads BuildConfig.REVENUECAT_PUBLIC_KEY and, when a
    // Firebase user is signed in, uses the Firebase UID as the RevenueCat appUserID.
    // Fails gracefully (no-op) when the key is blank instead of crashing.
    fun configure(context: Context) {
        if (!keyConfigured()) return
        val user = Account.userId()
        if (!Purchases.isConfigured) {
            val builder = PurchasesConfiguration.Builder(
                context.applicationContext, BuildConfig.REVENUECAT_PUBLIC_KEY
            )
            if (user != null) builder.appUserID(user)
            Purchases.configure(builder.build())
        } else if (user != null && Purchases.sharedInstance.appUserID != user) {
            // A Firebase user signed in after configuration: align RevenueCat identity.
            Purchases.sharedInstance.logInWith(user, onError = {}) { info, _ -> apply(info) }
        }
    }

    // Refresh Pro state from CustomerInfo using the SDK's cache to avoid redundant
    // network calls. Invokes onChanged only when the derived Pro state actually flips.
    fun refreshProStatus(context: Context, onChanged: (Boolean) -> Unit = {}) {
        configure(context)
        if (!Purchases.isConfigured) {
            update(false, onChanged)
            return
        }
        Purchases.sharedInstance.getCustomerInfoWith(onError = {}) { info -> update(active(info), onChanged) }
    }

    private fun active(info: CustomerInfo) = info.entitlements[ENTITLEMENT]?.isActive == true

    private fun apply(info: CustomerInfo) {
        isPro = active(info)
    }

    private fun update(value: Boolean, onChanged: (Boolean) -> Unit) {
        val changed = isPro != value
        isPro = value
        if (changed) onChanged(value)
    }

    fun show(activity: Activity, onChanged: (Boolean) -> Unit = {}) {
        if (!keyConfigured()) {
            // Presentation only: never invent a price or offering when RevenueCat is unconfigured.
            AlertDialog.Builder(activity).presentation(AlertDialog.Layout.Pro)
                .setTitle("Free remembers your day.\nPro learns how you forget.")
                .setMessage("Free remembers your day. Pro learns how you forget.\n\nPurchases are not configured in this demo. All offline essentials remain free.")
                .setPositiveButton("OK", null).show()
            return
        }
        val user = Account.userId()
        if (user == null) { message(activity, "Sign in before managing a subscription."); return }
        configure(activity)
        if (Purchases.sharedInstance.appUserID != user) {
            Purchases.sharedInstance.logInWith(user, onError = { message(activity, it.message) }) { _, _ -> show(activity, onChanged) }
            return
        }
        Purchases.sharedInstance.getOfferingsWith(onError = { message(activity, it.message) }) { offerings ->
            val packages = offerings.current?.availablePackages.orEmpty()
            if (packages.isEmpty()) {
                message(activity, "No subscription offering is available right now. Please try again later.")
                return@getOfferingsWith
            }
            val labels = packages.map { pkg ->
                val product = pkg.product
                val description = product.description.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""
                "${product.title} · ${product.price.formatted}$description"
            }.toTypedArray()
            AlertDialog.Builder(activity).presentation(AlertDialog.Layout.Pro)
                .setTitle("Free remembers your day.\nPro learns how you forget.").setItems(labels) { _, index ->
                    Purchases.sharedInstance.purchaseWith(PurchaseParams.Builder(activity, packages[index]).build(),
                        onError = { error, cancelled -> if (!cancelled) message(activity, error.message) },
                        onSuccess = { _, info ->
                            update(active(info), onChanged)
                            message(activity, if (isPro) "Pro is active" else "Purchase received. Entitlement is pending.")
                        })
                }.setNeutralButton("Restore purchases") { _, _ ->
                    Purchases.sharedInstance.restorePurchasesWith(onError = { message(activity, it.message) }) { info ->
                        update(active(info), onChanged)
                        message(activity, if (isPro) "Pro restored" else "No active Pro subscription")
                    }
                }.setNegativeButton("Close", null).show()
        }
    }
    private fun message(activity: Activity, value: String?) {
        val text = value ?: "Something went wrong. Please try again."
        activity.runOnUiThread { AlertDialog.Builder(activity).setMessage(text).setPositiveButton("OK", null).show() }
    }
}
