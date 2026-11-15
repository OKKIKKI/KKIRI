package com.okkikki.kkiri.state

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import android.content.Context
import android.util.Log
import com.okkikki.kkiri.contract.DriveState

/**
 * V2-S1·S2: Pleos Vehicle SDK(2.3.2)로 실제 차량 값(기어·핸들 각도)을 읽어 기존 제공자에 넣는다.
 * 선언은 docs/pleos/VEHICLE_SDK_SIGNATURES.md. 화면 코드는 이전과 같은 DriveStates/Steerings를 본다.
 * SDK가 없는 기기·연결 실패 때는 로그만 남기고 기존(디버그 패널) 값이 유지된다.
 */
object PleosVehicle {
    private const val TAG = "KkiriVehicle"

    @Volatile private var vehicle: Vehicle? = null

    /**
     * false면 실제 차량 값을 무시한다(디버그 패널의 "실차값 끔"). 켜 둔 채로는 Virtual Console 값이
     * 디버그 버튼으로 넣은 값을 곧바로 덮어써서 디버그 입력이 안 먹는 것처럼 보인다.
     */
    @Volatile var realEnabled: Boolean = true
        private set
    private var started = false

    /** 앱 프로세스에서 한 번만 연결한다(중복 호출 무시). */
    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        try {
            val v = Vehicle(context.applicationContext)
            v.initialize()
            vehicle = v
            AmbientTrigger.adapter = AmbientLights.get(context)
            watchGear(v)
            watchSteering(v)
        } catch (e: Throwable) {
            Log.w(TAG, "Vehicle SDK 시작 실패: ${e.message}")
        }
    }

    /** 디버그 패널용. 다시 켜면 지금 실제 값을 한 번 읽어 맞춘다 */
    fun setRealEnabled(on: Boolean) {
        realEnabled = on
        val v = vehicle ?: return
        if (!on) return
        runCatching {
            @Suppress("DEPRECATION")
            v.getGear().getCurrentGearSelectionState({ DriveStates.mock.setDriveState(driveStateOf(it)) }, {})
            v.getSteeringWheel().getCurrentSteeringWheelAngle({ it?.let { a -> Steerings.mock.setAngle(a) } }, {})
        }
    }

    /** 다른 기능(앰비언트)이 같은 연결을 쓴다. 아직 연결 전이면 null */
    fun get(): Vehicle? = vehicle

    /** P → 정차, 그 외 기어 → 주행. 값을 모르면 UNKNOWN(주행처럼 취급) */
    internal fun driveStateOf(gear: VehicleGear?): DriveState = when (gear) {
        VehicleGear.GEAR_PARK -> DriveState.PARKED
        null, VehicleGear.GEAR_UNKNOWN -> DriveState.UNKNOWN
        else -> DriveState.DRIVING
    }

    @Suppress("DEPRECATION") // 소스 주석은 AAOS GEAR_SELECTION을 권하나, Pleos 쪽이 같은 값을 주고 선언이 확인됨
    private fun watchGear(v: Vehicle) {
        val gear = v.getGear()
        gear.getCurrentGearSelectionState(
            onComplete = { if (realEnabled) DriveStates.mock.setDriveState(driveStateOf(it)); Log.i(TAG, "기어 초기값 $it") },
            onFailed = { Log.w(TAG, "기어 읽기 실패: ${it.message}") },
        )
        gear.registerGearSelection(object : GearSelectionListener {
            override fun onSelected(gear: VehicleGear?) {
                Log.i(TAG, "기어 변경 $gear")
                if (realEnabled) DriveStates.mock.setDriveState(driveStateOf(gear))
            }
            override fun onFailed(e: Exception) { Log.w(TAG, "기어 구독 실패: ${e.message}") }
        })
    }

    private fun watchSteering(v: Vehicle) {
        val sw = v.getSteeringWheel()
        sw.getCurrentSteeringWheelAngle(
            onComplete = { if (realEnabled) it?.let { a -> Steerings.mock.setAngle(a) } },
            onFailed = { Log.w(TAG, "각도 읽기 실패: ${it.message}") },
        )
        sw.registerSteeringWheelAngle(object : SteeringWheelAngleListener {
            // 앞바퀴 각도(도), 왼쪽이 음수 = contract/SteeringProvider(오른쪽 양수)와 같은 부호
            override fun onSteeringWheelAngleUpdated(angle: Float?) { if (realEnabled) angle?.let { Steerings.mock.setAngle(it) } }
            override fun onFailed(e: Exception) { Log.w(TAG, "각도 구독 실패: ${e.message}") }
        })
    }
}
