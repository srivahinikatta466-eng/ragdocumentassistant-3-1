
package sri.example.ragdocumentassistant

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var tvDocumentStatus: TextView
    private lateinit var tvResult: TextView
    private lateinit var etQuestion: EditText

    private var selectedUri: Uri? = null

    private val executor = Executors.newSingleThreadExecutor()

    // Your laptop's current IPv4 address
    private val SERVER_URL =    "http://10.195.37.200:5000"

    private val client = OkHttpClient()

    private val documentPicker =
        registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->

            if (uri != null) {

                selectedUri = uri

                val fileName = getFileName(uri)

                tvDocumentStatus.text =
                    "Selected: $fileName"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        tvDocumentStatus =
            findViewById(R.id.tvDocumentStatus)

        tvResult =
            findViewById(R.id.tvResult)

        etQuestion =
            findViewById(R.id.etQuestion)

        val btnSelectDocument =
            findViewById<Button>(R.id.btnSelectDocument)

        val btnUpload =
            findViewById<Button>(R.id.btnUpload)

        val btnSearch =
            findViewById<Button>(R.id.btnSearch)

        // Select PDF
        btnSelectDocument.setOnClickListener {

            documentPicker.launch(
                arrayOf("application/pdf")
            )
        }

        // Upload PDF
        btnUpload.setOnClickListener {

            if (selectedUri == null) {

                tvResult.text =
                    "Please select a PDF first."

            } else {

                uploadDocument()
            }
        }

        // Search document
        btnSearch.setOnClickListener {

            val question =
                etQuestion.text.toString().trim()

            if (question.isEmpty()) {

                tvResult.text =
                    "Please enter a question."

            } else {

                searchDocument(question)
            }
        }
    }

    // ------------------------------------------------
    // Upload PDF
    // ------------------------------------------------

    private fun uploadDocument() {

        tvResult.text =
            "Uploading document and creating vector index..."

        val uri = selectedUri ?: return

        executor.execute {

            try {

                val fileName =
                    getFileName(uri)

                val inputStream =
                    contentResolver.openInputStream(uri)

                val tempFile =
                    File(cacheDir, fileName)

                inputStream?.use { input ->

                    tempFile.outputStream().use { output ->

                        input.copyTo(output)
                    }
                }

                val requestBody =
                    tempFile.asRequestBody(
                        "application/pdf".toMediaType()
                    )

                val filePart =
                    MultipartBody.Part.createFormData(
                        "file",
                        fileName,
                        requestBody
                    )

                val multipartBody =
                    MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addPart(filePart)
                        .build()

                val request =
                    Request.Builder()
                        .url("$SERVER_URL/upload")
                        .post(multipartBody)
                        .build()

                val response =
                    client.newCall(request).execute()

                val responseText =
                    response.body?.string() ?: ""

                runOnUiThread {

                    if (response.isSuccessful) {

                        val json =
                            JSONObject(responseText)

                        val message =
                            json.optString(
                                "message",
                                "Document uploaded successfully."
                            )

                        val chunks =
                            json.optInt(
                                "chunks",
                                0
                            )

                        tvResult.text =
                            "$message\nChunks created: $chunks"

                    } else {

                        tvResult.text =
                            "Upload failed:\n$responseText"
                    }
                }

            } catch (e: Exception) {

                runOnUiThread {

                    tvResult.text =
                        "Connection error:\n${e.message}"
                }
            }
        }
    }

    // ------------------------------------------------
    // Search
    // ------------------------------------------------

    private fun searchDocument(question: String) {

        tvResult.text =
            "Searching relevant content..."

        executor.execute {

            try {

                val jsonBody =
                    JSONObject()
                        .put(
                            "query",
                            question
                        )
                        .toString()

                val requestBody =
                    jsonBody.toRequestBody(
                        "application/json".toMediaType()
                    )

                val request =
                    Request.Builder()
                        .url("$SERVER_URL/search")
                        .post(requestBody)
                        .build()

                val response =
                    client.newCall(request).execute()

                val responseText =
                    response.body?.string() ?: ""

                runOnUiThread {

                    if (response.isSuccessful) {

                        val json =
                            JSONObject(responseText)

                        val results =
                            json.optJSONArray("results")

                        if (results == null ||
                            results.length() == 0
                        ) {

                            tvResult.text =
                                "No relevant content found."

                        } else {

                            val output =
                                StringBuilder()

                            output.append(
                                "Relevant content:\n\n"
                            )

                            for (i in 0 until results.length()) {

                                val item =
                                    results.getJSONObject(i)

                                val text =
                                    item.optString(
                                        "text",
                                        ""
                                    )

                                val distance =
                                    item.optDouble(
                                        "distance",
                                        0.0
                                    )

                                output.append(
                                    "${i + 1}. $text\n"
                                )

                                output.append(
                                    "Distance: %.4f\n\n"
                                        .format(distance)
                                )
                            }

                            tvResult.text =
                                output.toString()
                        }

                    } else {

                        tvResult.text =
                            "Search failed:\n$responseText"
                    }
                }

            } catch (e: Exception) {

                runOnUiThread {

                    tvResult.text =
                        "Connection error:\n${e.message}"
                }
            }
        }
    }

    // ------------------------------------------------
    // Get PDF filename
    // ------------------------------------------------

    private fun getFileName(uri: Uri): String {

        var fileName = "document.pdf"

        val cursor =
            contentResolver.query(
                uri,
                null,
                null,
                null,
                null
            )

        cursor?.use {

            val nameIndex =
                it.getColumnIndex(
                    OpenableColumns.DISPLAY_NAME
                )

            if (
                nameIndex >= 0 &&
                it.moveToFirst()
            ) {

                fileName =
                    it.getString(nameIndex)
            }
        }

        return fileName
    }

    // ------------------------------------------------
    // Cleanup
    // ------------------------------------------------

    override fun onDestroy() {

        executor.shutdown()

        super.onDestroy()
    }
}

