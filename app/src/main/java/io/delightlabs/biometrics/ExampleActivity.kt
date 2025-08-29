package io.delightlabs.biometrics

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import io.delightlabs.biometrics.ui.theme.BiometricsTheme
import io.delightlabs.biometricslib.BiometricsAvailability
import io.delightlabs.biometricslib.BiometricsCallback
import io.delightlabs.biometricslib.BiometricsConfig
import io.delightlabs.biometricslib.BiometricsManager
import io.delightlabs.biometricslib.BiometricsType

class ExampleActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BiometricsTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    BiometricsDemo(
                        activity = this,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun BiometricsDemo(activity: FragmentActivity, modifier: Modifier = Modifier) {
    var statusText by remember { mutableStateOf("생체인증 라이브러리를 테스트해보세요") }
    var availability by remember { mutableStateOf<BiometricsAvailability?>(null) }
    
    val biometricsManager = remember {
        BiometricsManager.create(
            activity = activity,
            config = BiometricsConfig(
                title = "본인 인증",
                description = "지문이나 얼굴 인식으로 본인을 확인해주세요",
                negativeButtonText = "취소",
                biometricsType = BiometricsType.BIOMETRIC_OR_CREDENTIAL
            )
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier.padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "생체인증 라이브러리 예제",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyLarge
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                availability?.let { avail ->
                    Text(
                        text = "가용성 상태: ${getAvailabilityText(avail)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                }
                
                Button(
                    onClick = {
                        val avail = biometricsManager.checkAvailability()
                        availability = avail
                        statusText = "상태 확인 완료"
                    }
                ) {
                    Text("인증 가용성 확인")
                }
                
                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        // Example with biometric enabled
                        // 생체인증 활성화된 예제
                        biometricsManager.authenticate(
                            callback = object : BiometricsCallback {
                                override fun onSuccess() {
                                    statusText = "인증 성공! (콜백 방식)"
                                    Toast.makeText(activity, "콜백 인증 성공", Toast.LENGTH_SHORT).show()
                                }
                                
                                override fun onError(errorCode: Int, errorMessage: String) {
                                    statusText = "인증 실패: $errorMessage"
                                    Toast.makeText(activity, "인증 실패: $errorMessage", Toast.LENGTH_SHORT).show()
                                }
                                
                                override fun onCancel() {
                                    statusText = "인증이 취소되었습니다"
                                    Toast.makeText(activity, "인증 취소", Toast.LENGTH_SHORT).show()
                                }
                            },
                            isBiometricEnabled = true
                        )
                    }
                ) {
                    Text("생체인증")
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Button(
                    onClick = {
                        // Example with biometric disabled - goes to wallet password
                        // 생체인증 비활성화 예제 - 지갑 비밀번호로 이동
                        biometricsManager.authenticate(
                            callback = object : BiometricsCallback {
                                override fun onSuccess() {
                                    statusText = "인증 성공!"
                                    Toast.makeText(activity, "인증 성공", Toast.LENGTH_SHORT).show()
                                }
                                
                                override fun onError(errorCode: Int, errorMessage: String) {
                                    statusText = "인증 실패: $errorMessage"
                                    Toast.makeText(activity, "인증 실패: $errorMessage", Toast.LENGTH_SHORT).show()
                                }
                                
                                override fun onCancel() {
                                    statusText = "인증 취소"
                                    Toast.makeText(activity, "인증 취소", Toast.LENGTH_SHORT).show()
                                }
                            },
                            isBiometricEnabled = false,
                            onBiometricDisabled = {
                                statusText = "생체인증 비활성화 - 지갑 비밀번호로 이동"
                                Toast.makeText(activity, "지갑 비밀번호 화면으로 이동", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                ) {
                    Text("생체인증 비활성화 예제")
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Button(
                    onClick = {
                        biometricsManager.openSecuritySettings()
                    }
                ) {
                    Text("보안 설정 열기")
                }
            }
        }
    }
}

fun getAvailabilityText(availability: BiometricsAvailability): String {
    return when (availability) {
        BiometricsAvailability.BIOMETRIC_AVAILABLE -> "생체인증 사용 가능"
        BiometricsAvailability.CREDENTIAL_ONLY_AVAILABLE -> "기기 패스워드만 사용 가능"
        BiometricsAvailability.BIOMETRIC_NOT_ENROLLED -> "생체인증 미등록"
        BiometricsAvailability.NO_SECURITY_CONFIGURED -> "기기 보안 미설정"
        BiometricsAvailability.HARDWARE_NOT_SUPPORTED -> "하드웨어 미지원"
        BiometricsAvailability.TEMPORARILY_LOCKED -> "일시적 잠김"
        BiometricsAvailability.PERMANENTLY_LOCKED -> "영구적 잠김"
    }
}

@Preview(showBackground = true)
@Composable
fun BiometricsDemoPreview() {
    BiometricsTheme {
        Text("생체인증 라이브러리 예제")
    }
}