package com.example.daewoo

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.widget.*
import android.text.TextWatcher
import android.text.Editable
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import com.example.daewoo.constants.ApiConstants
data class SignupV2Request(
    @SerializedName("phoneNumber") val phoneNumber: String,
    @SerializedName("name") val name: String,
    @SerializedName("email") val email: String?,            // "" 또는 null
    @SerializedName("address") val address: String?,        // "" 권장
    @SerializedName("postalCode") val postalCode: String?,  // "" 권장
    @SerializedName("registrationNumber") val registrationNumber: String, // YYMMDD-1
    @SerializedName("nationality") val nationality: String, // "KR"
    @SerializedName("company") val company: String,
    @SerializedName("position") val position: String,
    @SerializedName("role") val role: String,               // "USER"
    @SerializedName("adminCode") val adminCode: String? = null
)

class RegisterActivity : AppCompatActivity() {

    private lateinit var orgCodeField: EditText
    private lateinit var idField: EditText
    private lateinit var nameField: EditText
    private lateinit var birthdateField: EditText
    private lateinit var registerBtn: Button
    private lateinit var cancelBtn: Button
    private fun normalizeDigits(s: String) = s.replace(Regex("\\D"), "")
    private fun isValidKrMobile(digits: String) = Regex("^010\\d{8}$").matches(digits)
    private fun last4(digits: String) = if (digits.length >= 4) digits.takeLast(4) else ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        val idField = findViewById<EditText>(R.id.editTextAccountId)      // 전화번호
        val nameField = findViewById<EditText>(R.id.editTextName)         // 이름
        val birthdateField = findViewById<EditText>(R.id.editTextBirthdate) // YYMMDD
        val companyField = findViewById<EditText>(R.id.editTextEmail)     // 회사 입력칸으로 재사용
        val btnWorker = findViewById<Button>(R.id.btnWorker)
        val btnManager = findViewById<Button>(R.id.btnManager)
        val btnSupervisor = findViewById<Button>(R.id.btnSupervisor)
        val btnInspector = findViewById<Button>(R.id.btnInspector)
        val registerBtn = findViewById<Button>(R.id.buttonRegister)
        val cancelBtn = findViewById<Button>(R.id.buttonCancel)

        fun normalizeDigits(s: String) = s.replace(Regex("\\D"), "")
        fun isValidKrMobile(digits: String) = Regex("^010\\d{8}$").matches(digits)

        // ====== 전화번호 입력: 숫자만 유지 ======
        idField.hint = "전화번호를 입력해주세요 (예: 01012345678)"
        idField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val digits = normalizeDigits(s?.toString().orEmpty())
                if (digits != s.toString()) {
                    idField.removeTextChangedListener(this)
                    idField.setText(digits)
                    idField.setSelection(digits.length)
                    idField.addTextChangedListener(this)
                }
            }
        })

        // ====== 직책(포지션) 선택 버튼 세트업 ======
        var selectedPosition: String = "" // "작업자" | "매니저" | "감독관" | "감리원"

        val buttons = listOf(btnWorker, btnManager, btnSupervisor, btnInspector)
        val positions = listOf("작업자", "매니저", "감독관", "감리원")

        fun applySelectionUI(selected: Button?) {
            buttons.forEach { btn ->
                // 기본 스타일
                btn.alpha = 0.6f
                btn.isSelected = false
            }
            selected?.let {
                it.alpha = 1.0f
                it.isSelected = true
            }
        }

        buttons.forEachIndexed { i, button ->
            button.setOnClickListener {
                selectedPosition = positions[i]
                applySelectionUI(button)
            }
        }

        // ====== 회원가입 버튼 ======
        registerBtn.setOnClickListener {
            try {
                // 값 수집
                val phoneDigits = normalizeDigits(idField.text.toString())
                val name = nameField.text.toString().trim()
                val birth6 = normalizeDigits(birthdateField.text.toString()).take(6)
                val company = companyField.text.toString().trim()

                // ==== 검증 ====
                if (name.isBlank() || phoneDigits.isBlank() || birth6.length != 6 || company.isBlank() || selectedPosition.isBlank()) {
                    Toast.makeText(this, "필수 항목(이름/전화/생년월일/회사/직책)을 모두 입력/선택하세요.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!isValidKrMobile(phoneDigits)) {
                    Toast.makeText(this, "전화번호는 010으로 시작하는 11자리여야 합니다.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val registrationNumber = "$birth6-1" // YYMMDD-1

                // ==== 요청 payload (v2 스펙) ====
                val payload = mapOf(
                    "phoneNumber" to phoneDigits,
                    "name" to name,
                    "email" to "",          // 또는 null
                    "address" to "",
                    "postalCode" to "",
                    "registrationNumber" to registrationNumber,
                    "nationality" to "KR",
                    "company" to company,
                    "position" to selectedPosition,
                    "role" to "USER",       // 고정
                    "adminCode" to null
                )

                // ==== 전송 ====
                val gson = Gson()
                val json = gson.toJson(payload)
                // TEST_LOG
                Log.d("SIGNUP_JSON", json)

                val requestBody = json.toRequestBody("application/json".toMediaType())

                val httpRequest = Request.Builder()
                    .url("${ApiConstants.SPRING_BASE_URL}/v2/users/signup") // https://.../spring/api + /v2/users/signup
                    .post(requestBody)
                    .build()

                OkHttpClient().newCall(httpRequest).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        Log.e("SIGNUP_HTTP", "fail=${e.message}", e)
                        runOnUiThread {
                            Toast.makeText(this@RegisterActivity, "서버 연결 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val bodyStr = try { response.body?.string().orEmpty() } catch (_: Exception) { "" }
                        Log.d("SIGNUP_HTTP", "code=${response.code}, body=$bodyStr")
                        if (response.isSuccessful) {
                            runOnUiThread {
                                Toast.makeText(this@RegisterActivity, "회원가입 성공. 로그인 해주세요.", Toast.LENGTH_SHORT).show()
                                startActivity(Intent(this@RegisterActivity, LoginActivity::class.java))
                                finish()
                            }
                        } else {
                            runOnUiThread {
                                Toast.makeText(
                                    this@RegisterActivity,
                                    "회원가입 실패: ${bodyStr.ifBlank { "응답 오류 ${response.code}" }}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                })
            } catch (e: Exception) {
                Toast.makeText(this, "입력값 오류: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        cancelBtn.setOnClickListener { finish() }
    }
    private fun validateV2(req: SignupV2Request): Boolean {
        if (req.name.isBlank() ||
            req.phoneNumber.isBlank() ||
            req.registrationNumber.isBlank() ||
            req.company.isBlank() ||
            req.position.isBlank()
        ) {
            Toast.makeText(this, "필수 항목(이름/전화/생년월일/회사/직책)을 입력하세요.", Toast.LENGTH_SHORT).show()
            return false
        }

        if (!isValidKrMobile(req.phoneNumber)) {
            Toast.makeText(this, "전화번호는 010으로 시작하는 11자리여야 합니다.", Toast.LENGTH_SHORT).show()
            return false
        }

        if (!req.registrationNumber.matches(Regex("^\\d{6}-1\$"))) {
            Toast.makeText(this, "생년월일은 YYMMDD 형식(예: 990101)으로 입력하세요.", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun sendRegisterRequestV2(request: SignupV2Request) {
        val client = OkHttpClient()
        val gson = Gson()
        val json = gson.toJson(request)
        val requestBody = json.toRequestBody("application/json".toMediaType())

        val httpRequest = Request.Builder()
            .url("${ApiConstants.SPRING_BASE_URL}/v2/users/signup")
            .post(requestBody)
            .build()

        client.newCall(httpRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(
                        this@RegisterActivity,
                        "서버 연결 실패: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val bodyStr = try {
                    response.body?.string().orEmpty()
                } catch (_: Exception) {
                    ""
                }
                if (response.isSuccessful) {
                    runOnUiThread {
                        Toast.makeText(
                            this@RegisterActivity,
                            "회원가입 성공. 로그인 해주세요.",
                            Toast.LENGTH_SHORT
                        ).show()
                        startActivity(Intent(this@RegisterActivity, LoginActivity::class.java))
                        finish()
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(
                            this@RegisterActivity,
                            "회원가입 실패: ${bodyStr.ifBlank { "응답 오류 ${response.code}" }}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        })
    }
}
