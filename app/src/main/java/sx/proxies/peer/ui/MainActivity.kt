package sx.proxies.peer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import sx.proxies.peer.ProxiesPeerSDK
import sx.proxies.peer.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var sdk: ProxiesPeerSDK

    private val PERMISSION_REQUEST_CODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize SDK (in standalone mode, use empty API key)
        sdk = ProxiesPeerSDK.init(
            this,
            apiKey = "", // Will be fetched from server for standalone app
            config = ProxiesPeerSDK.Config(
                onStatusChange = { status ->
                    runOnUiThread { updateUI(status) }
                },
                onEarningsUpdate = { earnings ->
                    runOnUiThread { updateEarnings(earnings) }
                }
            )
        )

        setupUI()
        checkPermissions()
    }

    private fun setupUI() {
        binding.btnToggle.setOnClickListener {
            if (sdk.isRunning()) {
                sdk.stop()
            } else {
                sdk.start()
            }
        }

        binding.btnRefreshEarnings.setOnClickListener {
            refreshEarnings()
        }

        updateUI(sdk.getStatus())
    }

    private fun updateUI(status: ProxiesPeerSDK.Status) {
        when (status) {
            ProxiesPeerSDK.Status.STOPPED -> {
                binding.statusText.text = "Stopped"
                binding.statusIndicator.setBackgroundResource(android.R.color.darker_gray)
                binding.btnToggle.text = "Start Sharing"
                binding.btnToggle.isEnabled = true
            }
            ProxiesPeerSDK.Status.CONNECTING -> {
                binding.statusText.text = "Connecting..."
                binding.statusIndicator.setBackgroundResource(android.R.color.holo_orange_light)
                binding.btnToggle.text = "Connecting..."
                binding.btnToggle.isEnabled = false
            }
            ProxiesPeerSDK.Status.CONNECTED -> {
                binding.statusText.text = "Connected - Sharing bandwidth"
                binding.statusIndicator.setBackgroundResource(android.R.color.holo_green_light)
                binding.btnToggle.text = "Stop Sharing"
                binding.btnToggle.isEnabled = true
            }
            ProxiesPeerSDK.Status.ERROR -> {
                binding.statusText.text = "Error - Tap to retry"
                binding.statusIndicator.setBackgroundResource(android.R.color.holo_red_light)
                binding.btnToggle.text = "Retry"
                binding.btnToggle.isEnabled = true
            }
        }
    }

    private fun updateEarnings(earnings: ProxiesPeerSDK.EarningsInfo) {
        val totalDollars = earnings.totalEarnedCents / 100.0
        val todayDollars = earnings.todayEarnedCents / 100.0

        binding.totalEarnings.text = String.format("$%.2f", totalDollars)
        binding.todayEarnings.text = String.format("$%.2f", todayDollars)
        binding.totalTraffic.text = String.format("%.2f MB", earnings.totalTrafficMB)
        binding.todayTraffic.text = String.format("%.2f MB", earnings.todayTrafficMB)
    }

    private fun refreshEarnings() {
        lifecycleScope.launch {
            val earnings = sdk.getEarnings()
            runOnUiThread { updateEarnings(earnings) }
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
                Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Some permissions were denied", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI(sdk.getStatus())
        refreshEarnings()
    }
}
