package sx.proxies.peer.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import sx.proxies.peer.ProxiesPeerSDK
import sx.proxies.peer.databinding.ActivityMainBinding
import sx.proxies.peer.databinding.DialogWalletConfigBinding
import sx.proxies.peer.util.DebugLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var sdk: ProxiesPeerSDK

    private val PERMISSION_REQUEST_CODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup debug logger listener
        DebugLogger.setListener { logEntry ->
            runOnUiThread {
                appendLog(logEntry)
            }
        }

        DebugLogger.i("App started - version ${getAppVersion()}")
        DebugLogger.i("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        DebugLogger.i("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")

        // Initialize SDK (in standalone mode, use empty API key)
        sdk = ProxiesPeerSDK.init(
            this,
            apiKey = "", // Will be fetched from server for standalone app
            config = ProxiesPeerSDK.Config(
                onStatusChange = { status ->
                    runOnUiThread { updateUI(status) }
                },
                onEarningsUpdate = { earnings ->
                    runOnUiThread {
                        updateSimpleEarnings(earnings)
                        updateLastUpdateTime()
                    }
                }
            )
        )

        setupUI()
        checkPermissions()

        // Initial earnings fetch
        refreshDetailedEarnings()
    }

    private fun getAppVersion(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun appendLog(entry: String) {
        val currentText = binding.debugLogs.text.toString()
        val newText = currentText + entry + "\n"
        binding.debugLogs.text = newText

        // Auto-scroll to bottom
        binding.logScrollView.post {
            binding.logScrollView.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }

    private fun setupUI() {
        // Toggle button
        binding.btnToggle.setOnClickListener {
            if (sdk.isRunning()) {
                DebugLogger.i("User tapped Stop")
                sdk.stop()
            } else {
                DebugLogger.i("User tapped Start")
                DebugLogger.i("Auto-polling enabled: Earnings will refresh every 60 seconds")
                sdk.start()
            }
        }

        // Refresh earnings button
        binding.btnRefreshEarnings.setOnClickListener {
            DebugLogger.i("Manual earnings refresh requested")
            refreshDetailedEarnings()
        }

        // Configure wallet button
        binding.btnConfigureWallet.setOnClickListener {
            showWalletConfigDialog()
        }

        // Request payout button
        binding.btnRequestPayout.setOnClickListener {
            requestPayout()
        }

        // Copy logs button
        binding.btnCopyLogs.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Debug Logs", DebugLogger.getAllLogs())
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        // Clear logs button
        binding.btnClearLogs.setOnClickListener {
            DebugLogger.clear()
            binding.debugLogs.text = "Logs cleared\n"
        }

        updateUI(sdk.getStatus())
    }

    private fun updateUI(status: ProxiesPeerSDK.Status) {
        val indicator = binding.statusIndicator.background as? GradientDrawable

        when (status) {
            ProxiesPeerSDK.Status.STOPPED -> {
                binding.statusText.text = "Offline"
                binding.statusSubtext.text = "Tap Start to begin sharing"
                indicator?.setColor(Color.parseColor("#9CA3AF"))
                binding.btnToggle.text = "Start"
                binding.btnToggle.isEnabled = true
            }
            ProxiesPeerSDK.Status.CONNECTING -> {
                binding.statusText.text = "Connecting..."
                binding.statusSubtext.text = "Establishing connection"
                indicator?.setColor(Color.parseColor("#F59E0B"))
                binding.btnToggle.text = "Connecting..."
                binding.btnToggle.isEnabled = false
            }
            ProxiesPeerSDK.Status.CONNECTED -> {
                binding.statusText.text = "Online"
                binding.statusSubtext.text = "Sharing bandwidth - earning money"
                indicator?.setColor(Color.parseColor("#10B981"))
                binding.btnToggle.text = "Stop"
                binding.btnToggle.isEnabled = true
            }
            ProxiesPeerSDK.Status.ERROR -> {
                binding.statusText.text = "Connection Error"
                binding.statusSubtext.text = "Tap Retry to reconnect"
                indicator?.setColor(Color.parseColor("#EF4444"))
                binding.btnToggle.text = "Retry"
                binding.btnToggle.isEnabled = true
            }
        }
    }

    private fun updateSimpleEarnings(earnings: ProxiesPeerSDK.EarningsInfo) {
        val totalDollars = earnings.totalEarnedCents / 100.0

        binding.totalEarnings.text = String.format("$%.2f", totalDollars)
        binding.totalTraffic.text = String.format("📊 Traffic: %.1f MB", earnings.totalTrafficMB)

        // Log update for debugging
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        DebugLogger.i("Earnings updated at $timestamp: $${"%.2f".format(totalDollars)}")
    }

    private fun refreshDetailedEarnings() {
        lifecycleScope.launch {
            try {
                val detailed = sdk.getDetailedEarnings()
                runOnUiThread {
                    updateDetailedEarnings(detailed)
                    updateWalletStatus(detailed)
                }
            } catch (e: Exception) {
                DebugLogger.e("Failed to fetch detailed earnings: ${e.message}")
            }
        }
    }

    private fun updateDetailedEarnings(earnings: ProxiesPeerSDK.DetailedEarningsInfo) {
        val totalDollars = earnings.totalEarnedCents / 100.0
        val pendingDollars = earnings.pendingPayoutCents / 100.0
        val paidDollars = earnings.totalPaidOutCents / 100.0

        binding.totalEarnings.text = String.format("$%.2f", totalDollars)
        binding.pendingPayout.text = String.format("$%.2f", pendingDollars)
        binding.paidOut.text = String.format("$%.2f", paidDollars)
        binding.totalTraffic.text = String.format("📊 Traffic: %.1f MB", earnings.totalTrafficMB)

        // Update payout button
        binding.btnRequestPayout.isEnabled = earnings.canRequestPayout
        if (earnings.canRequestPayout) {
            binding.btnRequestPayout.text = "Request Payout ($${String.format("%.2f", pendingDollars)})"
            binding.payoutInfo.text = "✅ You can request a payout now!"
            binding.payoutInfo.setTextColor(Color.parseColor("#10B981"))
        } else {
            val remaining = 10.0 - pendingDollars
            binding.btnRequestPayout.text = "Request Payout"
            binding.payoutInfo.text = "$${"%.2f".format(remaining)} until minimum payout ($10.00)"
            binding.payoutInfo.setTextColor(Color.parseColor("#6B7280"))
        }

        DebugLogger.i("Detailed earnings: Total=${"%.2f".format(totalDollars)}, Pending=${"%.2f".format(pendingDollars)}, Paid=${"%.2f".format(paidDollars)}")
    }

    private fun updateWalletStatus(earnings: ProxiesPeerSDK.DetailedEarningsInfo) {
        val hasWallet = earnings.walletAddresses.usdt != null ||
                        earnings.walletAddresses.btc != null ||
                        earnings.walletAddresses.sol != null

        if (hasWallet) {
            binding.walletStatus.text = "✅ Wallet configured"
            binding.walletStatus.setTextColor(Color.parseColor("#10B981"))
        } else {
            binding.walletStatus.text = "⚠️ No wallet configured"
            binding.walletStatus.setTextColor(Color.parseColor("#F59E0B"))
        }
    }

    private fun updateLastUpdateTime() {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        binding.lastUpdate.text = "⏱️ Last update: $time"
    }

    private fun showWalletConfigDialog() {
        val dialogBinding = DialogWalletConfigBinding.inflate(LayoutInflater.from(this))

        // Load existing wallet addresses
        lifecycleScope.launch {
            try {
                val earnings = sdk.getDetailedEarnings()
                val wallets = earnings.walletAddresses

                runOnUiThread {
                    dialogBinding.etUsdtAddress.setText(wallets.usdt ?: "")
                    dialogBinding.etBtcAddress.setText(wallets.btc ?: "")
                    dialogBinding.etSolAddress.setText(wallets.sol ?: "")
                }
            } catch (e: Exception) {
                DebugLogger.e("Failed to load wallet addresses: ${e.message}")
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Configure Wallet")
            .setView(dialogBinding.root)
            .setPositiveButton("Save") { _, _ ->
                saveWalletAddresses(
                    dialogBinding.etUsdtAddress.text.toString(),
                    dialogBinding.etBtcAddress.text.toString(),
                    dialogBinding.etSolAddress.text.toString()
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveWalletAddresses(usdt: String, btc: String, sol: String) {
        lifecycleScope.launch {
            try {
                val success = sdk.updateWallets(
                    usdt = usdt.takeIf { it.isNotBlank() },
                    btc = btc.takeIf { it.isNotBlank() },
                    sol = sol.takeIf { it.isNotBlank() }
                )

                runOnUiThread {
                    if (success) {
                        Toast.makeText(
                            this@MainActivity,
                            "✅ Wallet addresses updated successfully",
                            Toast.LENGTH_SHORT
                        ).show()
                        DebugLogger.i("Wallet addresses updated")
                        refreshDetailedEarnings()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "❌ Failed to update wallet addresses",
                            Toast.LENGTH_LONG
                        ).show()
                        DebugLogger.e("Failed to update wallet addresses")
                    }
                }
            } catch (e: Exception) {
                DebugLogger.e("Error updating wallet: ${e.message}")
            }
        }
    }

    private fun requestPayout() {
        lifecycleScope.launch {
            try {
                val earnings = sdk.getDetailedEarnings()
                val hasWallet = earnings.walletAddresses.usdt != null ||
                                earnings.walletAddresses.btc != null ||
                                earnings.walletAddresses.sol != null

                runOnUiThread {
                    if (!hasWallet) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Wallet Required")
                            .setMessage("Please configure your wallet address before requesting a payout.")
                            .setPositiveButton("Configure Now") { _, _ ->
                                showWalletConfigDialog()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                        return@runOnUiThread
                    }

                    // Show currency selection
                    showCurrencySelection(earnings)
                }
            } catch (e: Exception) {
                DebugLogger.e("Error checking wallet: ${e.message}")
            }
        }
    }

    private fun showCurrencySelection(earnings: ProxiesPeerSDK.DetailedEarningsInfo) {
        val availableCurrencies = mutableListOf<String>()
        val currencyCodes = mutableListOf<String>()

        if (earnings.walletAddresses.usdt != null) {
            availableCurrencies.add("USDT (TRC20)")
            currencyCodes.add("usdt")
        }
        if (earnings.walletAddresses.btc != null) {
            availableCurrencies.add("Bitcoin")
            currencyCodes.add("btc")
        }
        if (earnings.walletAddresses.sol != null) {
            availableCurrencies.add("Solana")
            currencyCodes.add("sol")
        }

        if (availableCurrencies.isEmpty()) {
            Toast.makeText(this, "No wallet addresses configured", Toast.LENGTH_LONG).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Select Payout Currency")
            .setItems(availableCurrencies.toTypedArray()) { _, which ->
                confirmPayout(currencyCodes[which], earnings.pendingPayoutCents / 100.0)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmPayout(currency: String, amount: Double) {
        AlertDialog.Builder(this)
            .setTitle("Confirm Payout")
            .setMessage("Request payout of $${"%.2f".format(amount)} to your ${currency.uppercase()} wallet?\n\nPayouts are processed within 24-48 hours.")
            .setPositiveButton("Confirm") { _, _ ->
                processPayout(currency)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun processPayout(currency: String) {
        lifecycleScope.launch {
            try {
                val result = sdk.requestPayout(currency)

                runOnUiThread {
                    if (result.success) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("✅ Payout Requested")
                            .setMessage("Your payout request has been submitted successfully!\n\n${result.message}")
                            .setPositiveButton("OK") { _, _ ->
                                refreshDetailedEarnings()
                            }
                            .show()
                        DebugLogger.i("Payout requested: $currency")
                    } else {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("❌ Payout Failed")
                            .setMessage(result.message)
                            .setPositiveButton("OK", null)
                            .show()
                        DebugLogger.e("Payout failed: ${result.message}")
                    }
                }
            } catch (e: Exception) {
                DebugLogger.e("Error requesting payout: ${e.message}")
            }
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()

        // Notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Phone state for carrier info
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.READ_PHONE_STATE)
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissions.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                DebugLogger.i("All permissions granted")
            } else {
                DebugLogger.w("Some permissions denied")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI(sdk.getStatus())
        refreshDetailedEarnings()
    }

    override fun onDestroy() {
        super.onDestroy()
        DebugLogger.clearListener()
    }
}
