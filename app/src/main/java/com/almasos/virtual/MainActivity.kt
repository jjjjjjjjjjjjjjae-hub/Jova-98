package com.almasos.virtual

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity() {

    companion object {
        private const val ROM_URL = "https://pub-8b6ce4385256436a961cfdd5381ca969.r2.dev/Rom.zip"
        private const val ROM_FILE_NAME = "Rom.zip"
        private const val REQUIRED_SPACE_BYTES = 1.5 * 1024 * 1024 * 1024 // 1.5 GB
    }

    private var downloadId: Long = -1
    private lateinit var downloadReceiver: BroadcastReceiver
    
    private lateinit var btnStartVM: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStartVM = findViewById(R.id.btnStartVM)
        progressBar = findViewById(R.id.progressBar)
        tvStatus = findViewById(R.id.tvStatus)

        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    checkDownloadStatus(id)
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }

        btnStartVM.setOnClickListener {
            val localRomFile = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), ROM_FILE_NAME)

            if (!localRomFile.exists()) {
                if (hasEnoughSpace()) {
                    btnStartVM.isEnabled = false
                    progressBar.isIndeterminate = true
                    progressBar.visibility = View.VISIBLE
                    tvStatus.text = "Бұлттан жүктеліп жатыр (1.4 ГБ)..."
                    startRealDownload(ROM_URL)
                } else {
                    Toast.makeText(this, "❌ Қате: Жадта орын жеткіліксіз!", Toast.LENGTH_LONG).show()
                }
            } else {
                extractRomAsync(localRomFile)
            }
        }
    }

    private fun hasEnoughSpace(): Boolean {
        val path = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return false
        val stat = StatFs(path.absolutePath)
        return (stat.availableBlocksLong * stat.blockSizeLong) >= REQUIRED_SPACE_BYTES
    }

    private fun startRealDownload(url: String) {
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("AlmasOS ROM")
            .setDescription("Жүктелуде...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, ROM_FILE_NAME)

        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadId = downloadManager.enqueue(request)
    }

    private fun checkDownloadStatus(id: Long) {
        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(id)
        
        downloadManager.query(query).use { cursor ->
            if (cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                if (columnIndex >= 0) {
                    when (cursor.getInt(columnIndex)) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            val localRomFile = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), ROM_FILE_NAME)
                            extractRomAsync(localRomFile)
                        }
                        DownloadManager.STATUS_FAILED -> {
                            btnStartVM.isEnabled = true
                            progressBar.visibility = View.GONE
                            tvStatus.text = "Жүйе дайын емес"
                            Toast.makeText(this, "❌ Жүктеу сәтсіз аяқталды.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    private fun extractRomAsync(zipFile: File) {
        val destDir = File(filesDir, "AlmasOS_System")
        if (!destDir.exists()) destDir.mkdirs()

        btnStartVM.isEnabled = false
        progressBar.isIndeterminate = false
        progressBar.max = 100
        progressBar.progress = 0
        progressBar.visibility = View.VISIBLE
        tvStatus.text = "Орнатуға дайындық..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                var totalUncompressedSize: Long = 0
                ZipFile(zipFile).use { zf ->
                    val entries = zf.entries()
                    while (entries.hasMoreElements()) {
                        totalUncompressedSize += entries.nextElement().size
                    }
                }

                var bytesProcessed: Long = 0
                var lastProgress = -1 
                val buffer = ByteArray(4096)

                ZipInputStream(FileInputStream(zipFile)).use { zis ->
                    var zipEntry = zis.nextEntry
                    while (zipEntry != null) {
                        val newFile = File(destDir, zipEntry.name)
                        if (!newFile.canonicalPath.startsWith(destDir.canonicalPath)) {
                            throw SecurityException("Қауіпті архив!")
                        }

                        if (zipEntry.isDirectory) {
                            newFile.mkdirs()
                        } else {
                            newFile.parentFile?.mkdirs()
                            FileOutputStream(newFile).use { fos ->
                                var len = zis.read(buffer)
                                while (len > 0) {
                                    fos.write(buffer, 0, len)
                                    bytesProcessed += len
                                    
                                    if (totalUncompressedSize > 0) {
                                        val progress = ((bytesProcessed * 100) / totalUncompressedSize).toInt()
                                        if (progress > lastProgress) {
                                            lastProgress = progress
                                            withContext(Dispatchers.Main) {
                                                progressBar.progress = progress
                                                tvStatus.text = "Орнатылуда: $progress%"
                                            }
                                        }
                                    }
                                    len = zis.read(buffer)
                                }
                            }
                        }
                        zipEntry = zis.nextEntry
                    }
                }
                
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    tvStatus.text = "AlmasOS сәтті орнатылды!"
                    btnStartVM.isEnabled = true
                    Toast.makeText(this@MainActivity, "🎉 Дайын!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    btnStartVM.isEnabled = true
                    progressBar.visibility = View.GONE
                    tvStatus.text = "Орнату қатесі"
                    Toast.makeText(this@MainActivity, "❌ Қате: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(downloadReceiver) } catch (e: Exception) {}
    }
}
