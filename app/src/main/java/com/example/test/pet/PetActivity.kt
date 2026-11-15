package com.example.test.pet

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.text.InputFilter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import com.example.test.R
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Puppy screen: reacts to speed, gear and distance coming from the car,
 * and recommends dinner when arriving home in the evening.
 * All pet state is touched on the main thread only.
 */
class PetActivity : AppCompatActivity(), DrivingDataSource.Listener, HomeArrivalSource.Listener {

    private lateinit var repository: PetRepository
    private lateinit var drivingData: DrivingDataSource
    private lateinit var homeArrival: HomeArrivalSource
    private val weatherClient = WeatherClient()
    private lateinit var talker: DogTalker
    private lateinit var dinnerCard: View
    private lateinit var dinnerWeather: TextView
    private lateinit var dinnerMenus: TextView
    private lateinit var dinnerReason: TextView
    private lateinit var sceneryView: SceneryView
    private lateinit var dogView: Dog3DView
    private lateinit var breedButton: TextView
    private lateinit var photoButton: TextView
    private lateinit var resetColorButton: TextView
    private lateinit var photoView: ImageView
    private val photoExecutor = Executors.newSingleThreadExecutor()
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(::usePhoto)
    }
    private lateinit var speechText: TextView
    private lateinit var nameText: TextView
    private lateinit var driveText: TextView
    private lateinit var growthText: TextView
    private lateinit var habitText: TextView
    private lateinit var fullnessBar: ProgressBar
    private lateinit var energyBar: ProgressBar
    private lateinit var happinessBar: ProgressBar
    private lateinit var feedButton: Button
    private lateinit var snackButton: Button

    private var pet = PetState()
    private var breed = Breed.SHIBA
    private val breeds by lazy { Breed.available(this) }
    private var speedKph = 0f
    private var gear = VehicleGear.GEAR_UNKNOWN
    private var lastOdometerKm: Float? = null
    private val harshDetector = HarshDetector()
    private var tripAccels = 0      // 이번 실행 동안의 급가속·급제동 횟수
    private var tripBrakes = 0
    private var droveSinceParked = false
    private var eventSpeech: String? = null
    private var eventSpeechUntil = 0L
    private var lastTickAt = 0L
    private var started = false

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val dt = (now - lastTickAt) / 1000f
            lastTickAt = now
            pet = PetEngine.tick(pet, level(), dt)
            if (PetEngine.smoothRewardDue(pet)) {
                pet = PetEngine.rewardSmooth(pet)
                repository.save(pet)
                dogView.celebrate()
                talk("10분 동안 부드럽게 운전해서 간식을 하나 받았어", "10분 동안 부드럽게 달렸어요! 간식 하나 받았어요")
            }
            render()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pet)
        sceneryView = findViewById(R.id.scenery_view)
        dogView = findViewById(R.id.dog_view)
        breedButton = findViewById(R.id.breed_button)
        photoButton = findViewById(R.id.photo_button)
        resetColorButton = findViewById(R.id.reset_color_button)
        photoView = findViewById(R.id.photo_view)
        speechText = findViewById(R.id.speech_text)
        nameText = findViewById(R.id.name_text)
        driveText = findViewById(R.id.drive_text)
        growthText = findViewById(R.id.growth_text)
        habitText = findViewById(R.id.habit_text)
        dinnerCard = findViewById(R.id.dinner_card)
        dinnerWeather = findViewById(R.id.dinner_weather)
        dinnerMenus = findViewById(R.id.dinner_menus)
        dinnerReason = findViewById(R.id.dinner_reason)
        findViewById<View>(R.id.dinner_close).setOnClickListener { dinnerCard.visibility = View.GONE }
        fullnessBar = findViewById(R.id.fullness_bar)
        energyBar = findViewById(R.id.energy_bar)
        happinessBar = findViewById(R.id.happiness_bar)
        feedButton = findViewById(R.id.feed_button)
        snackButton = findViewById(R.id.snack_button)
        feedButton.setOnClickListener { feed() }
        snackButton.setOnClickListener { giveSnack() }
        breedButton.setOnClickListener { switchBreed() }
        photoButton.setOnClickListener { choosePhoto() }
        resetColorButton.setOnClickListener { resetColors() }

        repository = PetRepository(this)
        pet = repository.load()
        breed = repository.loadBreed().takeIf { it in breeds } ?: Breed.SHIBA
        dogView.furColors = repository.furColors
        showPhoto(repository.loadPhoto())
        drivingData = DrivingDataSource(this, this)
        homeArrival = HomeArrivalSource(this, this)
        homeArrival.start()
        talker = DogTalker(this)
        talker.start()
        nameText.setOnClickListener { askName() }

        if (checkSelfPermission(SPEED_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            startDriving(true)
        } else {
            requestPermissions(arrayOf(SPEED_PERMISSION), REQUEST_SPEED)
        }
        render()
        handleDebugIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugIntent(intent)
    }

    /** Debug builds: `adb shell am start -n com.example.test/.pet.PetActivity --ez debug_arrive_home true` */
    private fun handleDebugIntent(intent: Intent) {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable && intent.getBooleanExtra("debug_arrive_home", false)) {
            intent.removeExtra("debug_arrive_home")
            arriveHome(SEOUL_LAT, SEOUL_LON, force = true)
        }
        if (debuggable && intent.getBooleanExtra("debug_check_home", false)) {
            intent.removeExtra("debug_check_home")
            homeArrival.checkNow()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_SPEED) {
            startDriving(grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
        }
    }

    private fun startDriving(speedGranted: Boolean) {
        if (started) return
        started = true
        drivingData.start(speedGranted)
        if (!speedGranted) toast("차속 권한이 없어 속도에 반응할 수 없어요")
    }

    override fun onResume() {
        super.onResume()
        lastTickAt = SystemClock.elapsedRealtime()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        repository.save(pet)
        super.onPause()
    }

    override fun onDestroy() {
        if (started) drivingData.stop()
        homeArrival.stop()
        talker.stop()
        weatherClient.shutdown()
        photoExecutor.shutdownNow()
        super.onDestroy()
    }

    // --- car signals (binder threads) ---

    override fun onSpeed(kph: Float) = runOnUiThread {
        harshDetector.onSpeed(SystemClock.elapsedRealtime(), kph)?.let { event ->
            Log.d(TAG, "harsh $event: $speedKph -> $kph km/h")
            pet = PetEngine.onHarshEvent(pet, event)
            when (event) {
                HarshEvent.ACCEL -> {
                    tripAccels++
                    dogView.playOnce("Idle_HitReact_Right")
                    talk("운전자가 방금 급출발해서 깜짝 놀랐어", "으앗, 너무 급하게 출발했어요!")
                }
                HarshEvent.BRAKE -> {
                    tripBrakes++
                    dogView.playOnce("Idle_HitReact_Left")
                    talk("운전자가 방금 급브레이크를 밟아서 무서웠어", "깜짝이야! 급브레이크 무서워요…")
                }
            }
            repository.save(pet)
        }
        speedKph = kph
        if (kph >= 1f) droveSinceParked = true
        render()
    }

    override fun onGear(gear: VehicleGear) = runOnUiThread {
        Log.d(TAG, "gear: $gear")
        // Parked after a drive: maybe we are home (also covers trips without route guidance).
        if (gear == VehicleGear.GEAR_PARK && this.gear != VehicleGear.GEAR_PARK && droveSinceParked) {
            droveSinceParked = false
            homeArrival.checkNow()
        }
        this.gear = gear
        render()
    }

    override fun onOdometer(km: Float) = runOnUiThread {
        val last = lastOdometerKm
        lastOdometerKm = km
        // The first value is only a baseline; a smaller value means the odometer was reset.
        if (last == null || km <= last) return@runOnUiThread
        val before = pet.snacks
        pet = PetEngine.onDistance(pet, km - last, level())
        if (pet.snacks > before) {
            dogView.celebrate()
            talk(
                "안전하게 ${PetEngine.KM_PER_SNACK.toInt()}km를 달려서 간식이 생겼어",
                "안전하게 ${PetEngine.KM_PER_SNACK.toInt()}km 달렸어요! 간식이 생겼어요",
            )
        }
        Log.d(TAG, "odometer +${km - last} km, safeKm=${pet.safeKm}")
        repository.save(pet)
        render()
    }

    override fun onArrivedHome(latitude: Double, longitude: Double) = runOnUiThread {
        arriveHome(latitude, longitude, force = false)
    }

    // --- home arrival ---

    /** Evening arrival: once a day, recommend dinner from the weather at home. */
    private fun arriveHome(latitude: Double, longitude: Double, force: Boolean) {
        val today = today()
        val evening = LocalTime.now().hour in DinnerMenu.DINNER_HOURS
        if (!force && (!evening || repository.dinnerDay == today)) {
            dogView.celebrate()
            talk("운전자랑 같이 집에 도착했어", "집에 도착! 오늘도 수고했어요")
            return
        }
        repository.dinnerDay = today
        weatherClient.fetch(latitude, longitude) { weather -> showDinner(weather) }
    }

    private fun showDinner(weather: Weather?) {
        val pick = DinnerMenu.recommend(weather, LocalDate.now())
        dinnerWeather.text = weather?.let { "지금 %.0f°C · %s".format(it.tempC, it.label) } ?: "날씨 정보 없음 · 계절 추천"
        dinnerMenus.text = pick.menus.joinToString("  ·  ")
        dinnerReason.text = pick.reason
        dinnerCard.visibility = View.VISIBLE
        dogView.celebrate()
        talk(
            "퇴근해서 집에 도착했고, 날씨를 보니 저녁으로 ${pick.menus.first()}를 추천하고 싶어",
            "집에 도착! 오늘 저녁은 ${pick.menus.first()} 어때요?",
        )
    }

    // --- actions (only in P) ---

    private fun feed() {
        if (!isParked()) return toast("차를 세우고 P단에서 밥을 줄 수 있어요")
        val fed = PetEngine.feed(pet, today()) ?: return toast("오늘 밥은 다 먹었어요")
        pet = fed
        repository.save(pet)
        dogView.playOnce("Eating")
        talk("운전자가 맛있는 밥을 줬어", "냠냠! 정말 맛있어요")
        render()
    }

    private fun giveSnack() {
        if (!isParked()) return toast("차를 세우고 P단에서 간식을 줄 수 있어요")
        val fed = PetEngine.giveSnack(pet) ?: return toast("간식이 없어요. 안전하게 ${PetEngine.KM_PER_SNACK.toInt()}km 달리면 생겨요")
        pet = fed
        repository.save(pet)
        dogView.playOnce("Jump_ToIdle")
        talk("운전자가 간식을 줬어", "간식 최고! 고마워요")
        render()
    }

    private fun switchBreed() {
        if (!isParked()) return toast("차를 세우고 P단에서 강아지를 바꿀 수 있어요")
        breed = nextBreed()
        repository.saveBreed(breed)
        render()
    }

    // --- owner's dog photo (only in P) ---

    private fun choosePhoto() {
        if (!isParked()) return toast("차를 세우고 P단에서 사진을 고를 수 있어요")
        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    /** Decodes off the main thread: a small avatar for the card and a 64x64 grid for the fur colors. */
    private fun usePhoto(uri: Uri) {
        photoExecutor.execute {
            val result = try {
                val photo = decodeSmall(uri, AVATAR_PX)
                val grid = Bitmap.createScaledBitmap(photo, COLOR_GRID_PX, COLOR_GRID_PX, true)
                val pixels = IntArray(COLOR_GRID_PX * COLOR_GRID_PX)
                grid.getPixels(pixels, 0, COLOR_GRID_PX, 0, 0, COLOR_GRID_PX, COLOR_GRID_PX)
                photo to FurColorExtractor.vivid(FurColorExtractor.extract(pixels, COLOR_GRID_PX, COLOR_GRID_PX))
            } catch (e: Exception) {
                Log.w(TAG, "cannot read photo $uri", e)
                null
            }
            runOnUiThread {
                if (result == null) return@runOnUiThread toast("사진을 읽지 못했어요")
                val (photo, colors) = result
                Log.d(TAG, "fur colors main=%06x light=%06x".format(colors.main, colors.light))
                repository.savePhoto(photo)
                repository.furColors = colors
                dogView.furColors = colors
                showPhoto(photo)
                dogView.celebrate()
                say("짠! 우리 강아지 털색으로 바뀌었어요")
            }
        }
    }

    /** Shorter side scaled to about [targetPx]; software bitmap so pixels can be read. */
    private fun decodeSmall(uri: Uri, targetPx: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
            val scale = targetPx.toFloat() / minOf(info.size.width, info.size.height)
            if (scale < 1f) {
                decoder.setTargetSize((info.size.width * scale).roundToInt(), (info.size.height * scale).roundToInt())
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    private fun resetColors() {
        if (!isParked()) return toast("차를 세우고 P단에서 바꿀 수 있어요")
        repository.furColors = null
        repository.savePhoto(null)
        dogView.furColors = null
        showPhoto(null)
        render()
    }

    private fun showPhoto(photo: Bitmap?) {
        photoView.setImageBitmap(photo)
        photoView.visibility = if (photo != null) View.VISIBLE else View.GONE
    }

    private fun render() {
        val level = level()
        val mood = PetEngine.mood(pet, level)
        dogView.mood = mood
        dogView.scale = PetEngine.scale(pet)
        dogView.speedKph = speedKph
        sceneryView.scale = dogView.scale
        sceneryView.speedKph = speedKph
        speechText.text = eventSpeech?.takeIf { SystemClock.elapsedRealtime() < eventSpeechUntil }
            ?: speech(mood, level)
        dogView.breed = breed
        nameText.text = "${dogName()} · ${PetEngine.stageName(pet)} ✎"
        breedButton.text = "${nextBreed().label}로"
        driveText.text = "${gearLabel()}  ${speedKph.toInt()} km/h  ·  ${levelLabel(level)}"
        fullnessBar.progress = pet.fullness.toInt()
        energyBar.progress = pet.energy.toInt()
        happinessBar.progress = pet.happiness.toInt()
        growthText.text = "안전 주행 %.1f km  ·  성장 %d%%".format(pet.safeKm, (PetEngine.growth(pet) * 100).toInt())
        val smoothMin = (pet.smoothSec / 60f).toInt()
        val toSnackMin = ceil((PetEngine.SMOOTH_SEC_PER_SNACK - pet.smoothSec) / 60f).toInt()
        habitText.text = "부드러운 운전 ${smoothMin}분째 · 간식까지 ${toSnackMin}분\n" +
            "이번 주행 급가속 ${tripAccels}회 · 급제동 ${tripBrakes}회"

        val parked = isParked()
        feedButton.text = "밥 주기 (${PetEngine.mealsLeft(pet, today())}회)"
        snackButton.text = "간식 주기 (${pet.snacks}개)"
        feedButton.isEnabled = parked
        snackButton.isEnabled = parked
        for (chip in listOf(breedButton, photoButton, resetColorButton)) {
            chip.isEnabled = parked
            chip.alpha = if (parked) 1f else 0.4f
        }
        resetColorButton.visibility = if (dogView.furColors != null) View.VISIBLE else View.GONE
    }

    private fun speech(mood: Mood, level: DriveLevel) = when (mood) {
        Mood.HAPPY -> if (level == DriveLevel.SAFE) "신난다! 같이 달려요~ 멍멍!" else "멍멍! 오늘 드라이브 최고야!"
        Mood.NORMAL -> when {
            isParked() -> "쉬는 시간이다! 밥 줄 거야?"
            level == DriveLevel.SAFE -> "영차영차, 잘 달리고 있어요!"
            else -> "어디 가요? 두근두근"
        }
        Mood.HUNGRY -> "배고파요… 다음에 서면 밥 주세요"
        Mood.NERVOUS -> "조금 빠른 것 같아요… 무서워요"
        Mood.EXHAUSTED -> "너무 빨라요! 힘들어요… 헥헥"
    }

    private fun nextBreed() = breeds[(breeds.indexOf(breed) + 1) % breeds.size]

    /** Event line from the dog: the on-device AI's words when it answers in time, else [fallback]. */
    private fun talk(situation: String, fallback: String) {
        val persona = DogTalker.Persona(dogName(), breed.label, PetEngine.stageName(pet), PetEngine.mood(pet, level()))
        talker.say(persona, situation, fallback, ::say)
    }

    private fun dogName() = repository.dogName ?: breed.label

    /** Tap the name (in P) to name the dog. */
    private fun askName() {
        if (!isParked()) return toast("차를 세우고 P단에서 이름을 바꿀 수 있어요")
        val input = EditText(this).apply {
            setText(repository.dogName.orEmpty())
            hint = "예: 초코"
            filters = arrayOf(InputFilter.LengthFilter(MAX_NAME_LENGTH))
            isSingleLine = true
            textSize = 26f
        }
        val title = TextView(this).apply {
            text = "우리 강아지 이름"
            textSize = 30f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(64, 48, 64, 0)
        }
        val dialog = AlertDialog.Builder(this)
            .setCustomTitle(title)
            .setView(FrameLayout(this).apply { setPadding(64, 24, 64, 0); addView(input) })
            .setPositiveButton("저장") { _, _ ->
                repository.dogName = input.text.toString().trim().ifEmpty { null }
                render()
                talk("방금 운전자가 이름을 '${dogName()}'(으)로 지어줬어", "${dogName()}! 멋진 이름 고마워요")
            }
            .setNegativeButton("취소", null)
            .show()
        // The default dialog buttons are phone-sized; make them easy to hit on the car screen.
        for (which in intArrayOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE)) {
            dialog.getButton(which)?.apply { textSize = 26f; setPadding(48, 24, 48, 24) }
        }
    }

    /** Shows an event line in the speech bubble for a few seconds, then the mood line comes back. */
    private fun say(message: String) {
        eventSpeech = message
        eventSpeechUntil = SystemClock.elapsedRealtime() + EVENT_SPEECH_MS
        render()
    }

    private fun level() = PetEngine.classify(speedKph)

    private fun isParked() = gear == VehicleGear.GEAR_PARK

    private fun gearLabel() = when (gear) {
        VehicleGear.GEAR_PARK -> "P"
        VehicleGear.GEAR_REVERSE -> "R"
        VehicleGear.GEAR_NEUTRAL -> "N"
        VehicleGear.GEAR_UNKNOWN -> "?"
        else -> "D"
    }

    private fun levelLabel(level: DriveLevel) = when (level) {
        DriveLevel.STOPPED -> "정차"
        DriveLevel.SAFE -> "안전 주행"
        DriveLevel.OVER -> "과속 주의"
        DriveLevel.DANGER -> "위험 과속"
    }

    private fun today() = LocalDate.now().toEpochDay()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private companion object {
        const val TAG = "PetActivity"
        const val SPEED_PERMISSION = "android.car.permission.CAR_SPEED"
        const val REQUEST_SPEED = 1
        const val TICK_MS = 1000L
        const val EVENT_SPEECH_MS = 5000L
        const val AVATAR_PX = 256
        const val MAX_NAME_LENGTH = 8
        const val COLOR_GRID_PX = 64
        const val SEOUL_LAT = 37.5665
        const val SEOUL_LON = 126.9780
    }
}
