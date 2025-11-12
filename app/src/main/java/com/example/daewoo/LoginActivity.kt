package com.example.daewoo

import com.google.gson.annotations.SerializedName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import android.util.Log
import com.auth0.android.jwt.JWT
import com.example.daewoo.constants.ApiConstants
import com.example.daewoo.utils.PreferenceHelper


// 로그인 요청 시 서버로 보내는 데이터 모델
data class LoginRequest(
    val accountId: String,
    val password: String
)

data class LoginResponseData(
    // @SerializedName("userId") val userId: String,
    @SerializedName("accessToken") val accessToken: String,
    @SerializedName("refreshToken") val refreshToken: String
)

class LoginActivity : AppCompatActivity() {

    private lateinit var idField: EditText
    private lateinit var pwField: EditText
    private lateinit var loginBtn: Button
    private lateinit var registerBtn: Button
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        // UI 요소 초기화 및 연결
        idField = findViewById(R.id.editTextAccountId)
        pwField = findViewById(R.id.editTextPassword)
        loginBtn = findViewById(R.id.buttonLogin)
        registerBtn = findViewById(R.id.buttonGoRegister)

        // SharedPreferences에서 저장된 로그인 자동입력용 계정 ID/PW 불러오기
        // ACCOUNT_ID/ACCOUNT_PW는 마지막 로그인에 사용된 계정 정보로, 입력란 자동 채움용입니다.
        // USER_ID는 JWT에서 추출한 UUID(유저 고유 식별자)입니다.
        val pref = getSharedPreferences("USER_PREF", Context.MODE_PRIVATE)
        val savedId = pref.getString("ACCOUNT_ID", null)
        val savedPw = pref.getString("ACCOUNT_PW", null)
        if (!savedId.isNullOrEmpty()) {
            idField.setText(savedId)
        }
        if (!savedPw.isNullOrEmpty()) {
            pwField.setText(savedPw)
        }


        // 로그인 버튼 클릭 리스너
        loginBtn.setOnClickListener {
            val id = idField.text.toString() // 아이디 입력값 가져오기
            val pw = pwField.text.toString() // 비밀번호 입력값 가져오기
            // 아이디 또는 비밀번호가 비어있는지 확인
            if (TextUtils.isEmpty(id) || TextUtils.isEmpty(pw)) {
                Toast.makeText(this, "아이디와 비밀번호를 입력하세요", Toast.LENGTH_SHORT).show() // 토스트 메시지 표시
            } else {
                performLogin(id, pw) // 로그인 요청 수행
            }
        }

        // 회원가입 화면 이동 버튼 클릭 리스너 설정
        registerBtn.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java)) // RegisterActivity로 이동
        }
    }

    // 로그인 요청을 서버로 전송하는 함수
    private fun performLogin(accountId: String, password: String) {
        val client = OkHttpClient() // OkHttpClient 인스턴스 생성
        val gson = Gson() // Gson 인스턴스 생성 (JSON 파싱 및 생성용)
        val requestData = LoginRequest(accountId, password) // 로그인 요청 데이터 객체 생성
        val json = gson.toJson(requestData) // 요청 데이터를 JSON 문자열로 변환
        val body = json.toRequestBody("application/json".toMediaType()) // JSON을 요청 본문으로 변환

        Log.d("LOGIN", "요청 JSON → $json") // 요청 JSON 로그 출력

        val request = Request.Builder()
            .url("${ApiConstants.SPRING_BASE_URL}/auth/login") // 로그인 API 엔드포인트 설정 (Swagger 기준)
            .post(body) // POST 메소드로 요청 본문 설정
            .build() // 요청 객체 빌드

        // 비동기적으로 서버 요청 실행
        client.newCall(request).enqueue(object : Callback {
            // 네트워크 요청 실패 시 호출
            override fun onFailure(call: Call, e: IOException) {
                Log.e("LOGIN", "서버 연결 실패: ${e.message}") // 에러 로그 출력
                runOnUiThread {
                    Toast.makeText(this@LoginActivity, "서버 연결 실패", Toast.LENGTH_SHORT).show() // UI 스레드에서 토스트 메시지 표시
                }
            }

            // 네트워크 요청 응답 수신 시 호출
            override fun onResponse(call: Call, response: Response) {
                val bodyStr = response.body?.string() // 응답 본문을 문자열로 읽기
                Log.d("LOGIN", "HTTP 응답 코드: ${response.code}") // 응답 코드 로그 출력
                Log.d("LOGIN", "HTTP 응답 바디: $bodyStr") // 응답 본문 로그 출력

                // 응답이 성공적이고 본문이 null이 아닌 경우
                if (response.isSuccessful && bodyStr != null) {
                    try {
                        // CommonResponse<LoginResponseData> 타입으로 응답 파싱 시도
                        val type = object : TypeToken<CommonResponse<LoginResponseData>>() {}.type
                        val result = gson.fromJson<CommonResponse<LoginResponseData>>(bodyStr, type) // LoginResponseData로 파싱

                        Log.d("LOGIN", "파싱된 statusCode: ${result.statusCode}") // 파싱된 상태 코드 로그 출력
                        Log.d("LOGIN", "파싱된 message: ${result.message}") // 파싱된 메시지 로그 출력
                        Log.d("LOGIN", "파싱된 data: ${result.data}") // 파싱된 데이터 로그 출력

                        // 서버 응답 statusCode가 200이고 데이터가 null이 아닌 경우 로그인 성공 처리
                        if (result.statusCode == 200 && result.data != null) { //
                            // Access Token에서 userId (UUID) 추출
                            val jwt = JWT(result.data.accessToken) //
                            val userIdFromToken = jwt.getClaim("sub").asString() // "sub" 클레임에서 userId (UUID) 추출

                            if (userIdFromToken != null) {
                                Log.d("LOGIN", "로그인 성공: userId=${userIdFromToken}, accessToken=${result.data.accessToken}")
                                // 사용자 ID (String), Access Token, Refresh Token을 SharedPreferences에 저장
                                saveUserInfoAndTokens(userIdFromToken, result.data.accessToken, result.data.refreshToken)
                                // 로그인에 사용한 계정 ID/PW 저장 (자동 입력용)
                                getSharedPreferences("USER_PREF", Context.MODE_PRIVATE)
                                    .edit()
                                    .putString("ACCOUNT_ID", accountId)
                                    .putString("ACCOUNT_PW", password)
                                    .apply()
                                navigateToMainPage()
                            } else {
                                Log.w("LOGIN", "로그인 실패: 토큰에서 userId를 추출할 수 없습니다.")
                                runOnUiThread {
                                    Toast.makeText(this@LoginActivity, "로그인 실패: 사용자 정보를 얻을 수 없습니다.", Toast.LENGTH_LONG).show()
                                }
                            }
                        } else {
                            // 로그인 실패 처리 (서버에서 반환된 메시지 사용)
                            Log.w("LOGIN", "로그인 실패: ${result.message}") //
                            runOnUiThread {
                                Toast.makeText(this@LoginActivity, "로그인 실패: ${result.message}", Toast.LENGTH_LONG).show() // UI 스레드에서 토스트 메시지 표시
                            }
                        }

                    } catch (e: Exception) {
                        Log.e("LOGIN", "JSON 파싱 또는 JWT 디코딩 오류: ${e.message}", e) // JSON 파싱 또는 JWT 디코딩 오류 로그 출력
                        runOnUiThread {
                            Toast.makeText(this@LoginActivity, "응답 파싱 오류: ${e.message}", Toast.LENGTH_SHORT).show() // UI 스레드에서 토스트 메시지 표시
                        }
                    }
                } else {
                    // HTTP 응답이 실패했거나 (2xx 외의 코드) 본문이 null인 경우
                    val errorMsg = try {
                        // 에러 응답 본문을 CommonResponse<String>으로 파싱하여 메시지 추출 시도
                        val errorType = object : TypeToken<CommonResponse<String>>() {}.type
                        val errorResult = gson.fromJson<CommonResponse<String>>(bodyStr, errorType) //
                        errorResult.message ?: "알 수 없는 응답 오류" // 메시지가 없으면 기본 메시지 사용
                    } catch (e: Exception) {
                        "응답 오류: ${response.code}" // 파싱 실패 시 응답 코드 사용
                    }
                    Log.w("LOGIN", "서버 응답 실패: code=${response.code}, message=$errorMsg") // 에러 로그 출력
                    runOnUiThread {
                        Toast.makeText(this@LoginActivity, "로그인 실패: $errorMsg (코드: ${response.code})", Toast.LENGTH_LONG).show() // UI 스레드에서 토스트 메시지 표시
                    }
                }
            }
        })
    }

    // 사용자 정보(userId)와 토큰을 SharedPreferences에 저장하는 함수
    // userId 타입이 String으로 변경되었으므로 putString 사용
    private fun saveUserInfoAndTokens(userId: String, accessToken: String, refreshToken: String) {
        val pref = getSharedPreferences("USER_PREF", Context.MODE_PRIVATE) // SharedPreferences 객체 가져오기
        pref.edit() // SharedPreferences 편집기 가져오기
            .putString("USER_ID", userId) // userId를 String으로 저장
            .putString("ACCESS_TOKEN", accessToken) // Access Token 저장
            .putString("REFRESH_TOKEN", refreshToken) // Refresh Token 저장
            .apply() // 변경사항 적용
        Log.d("USER_PREF", "saveUserInfoAndTokens() 호출됨. 저장된 userId = $userId, accessToken = $accessToken") // 저장 로그 출력
    }

    private fun navigateToMainPage() {
        runOnUiThread {
            Toast.makeText(this@LoginActivity, "로그인 성공", Toast.LENGTH_SHORT).show()

            // 1) 재확인: SharedPreferences에 저장된 값 읽기
            val pref = getSharedPreferences("USER_PREF", Context.MODE_PRIVATE)
            val uid = pref.getString("USER_ID", null)
            val at = pref.getString("ACCESS_TOKEN", null)
            val rt = pref.getString("REFRESH_TOKEN", null)

            Log.d("NAV_CHECK", "SharedPref USER_ID=$uid, ACCESS(len)=${at?.length}, REFRESH(len)=${rt?.length}")

            if (uid.isNullOrBlank() || at.isNullOrBlank() || rt.isNullOrBlank()) {
                Log.w("NAV_CHECK", "토큰/유저ID 중 하나가 비어 있음: uid=$uid, atEmpty=${at.isNullOrBlank()}, rtEmpty=${rt.isNullOrBlank()}")
            }

            PreferenceHelper.setLaunchedFrom(this@LoginActivity, "LOGIN")

            val mainPageIntent = Intent(this@LoginActivity, MainPage::class.java)
            Log.d("NAV_CHECK", "Starting MainPage via SharedPreferences bridge.")
            startActivity(mainPageIntent)
            finish()
        }
    }
}
