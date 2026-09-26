package com.studentmemory.copilot

import android.app.Activity
import android.app.AlertDialog
import com.revenuecat.purchases.*
import com.studentmemory.copilot.services.Account

object Billing {
    fun show(activity: Activity) {
        if (BuildConfig.REVENUECAT_PUBLIC_KEY.isBlank()) {
            AlertDialog.Builder(activity).setTitle("Student Memory Pro")
                .setMessage("Free remembers your day. Pro learns how you forget.\n\nPurchases are not configured in this demo. All offline essentials remain free.")
                .setPositiveButton("OK", null).show()
            return
        }
        val user = Account.userId()
        if (user == null) { message(activity, "Sign in before managing a subscription."); return }
        if (!Purchases.isConfigured) Purchases.configure(PurchasesConfiguration.Builder(activity.applicationContext,
            BuildConfig.REVENUECAT_PUBLIC_KEY).appUserID(user).build())
        if (Purchases.sharedInstance.appUserID != user) {
            Purchases.sharedInstance.logInWith(user, onError = { message(activity, it.message) }) { _, _ -> show(activity) }
            return
        }
        Purchases.sharedInstance.getOfferingsWith(onError = { message(activity, it.message) }) { offerings ->
            val packages = offerings.current?.availablePackages.orEmpty()
            val labels = packages.map { "${it.product.title} · ${it.product.price.formatted}" }.toTypedArray()
            AlertDialog.Builder(activity).setTitle("Student Memory Pro").setItems(labels) { _, index ->
                Purchases.sharedInstance.purchaseWith(PurchaseParams.Builder(activity, packages[index]).build(),
                    onError = { error, cancelled -> if (!cancelled) message(activity, error.message) },
                    onSuccess = { _, info -> message(activity, if (info.entitlements["pro"]?.isActive == true) "Pro is active" else "Purchase received. Entitlement is pending.") })
            }.setNeutralButton("Restore purchases") { _, _ ->
                Purchases.sharedInstance.restorePurchasesWith(onError = { message(activity, it.message) }) { info ->
                    message(activity, if (info.entitlements["pro"]?.isActive == true) "Pro restored" else "No active Pro subscription")
                }
            }.setNegativeButton("Close", null).show()
        }
    }
    private fun message(activity: Activity, value: String) {
        activity.runOnUiThread { AlertDialog.Builder(activity).setMessage(value).setPositiveButton("OK", null).show() }
    }
}
