package com.okkikki.kkiri.state

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 내 차 꾸미기 값(색·캐릭터·소품)과 광장 배경을 이 기기에 저장한다.
 * 색·캐릭터·소품은 R-47(v1.16 CarLook)로 일행에게도 보내고, 광장 배경은 서버로 보내지 않는다(내 화면에만).
 * 광장·일행 탭·로딩 애니메이션의 내 차가 이 값을 쓴다.
 */
object DecoStore {

    /** 고를 수 있는 색(프로토타입 ITEMS). 이름은 화면에 그대로 나온다 */
    data class Item(val name: String, val color: Int)

    val items = listOf(
        Item("버터 콩", 0xFFF6C343.toInt()),
        Item("레드 로켓", 0xFFE8453C.toInt()),
        Item("블루 웨이브", 0xFF3D8BFD.toInt()),
        Item("그린 디노", 0xFF7BC86C.toInt()),
        Item("라벤더 문", 0xFFB9A8FF.toInt()),
        Item("코코아 번", 0xFF9A6F55.toInt()),
    )

    /** 캐릭터 id → 화면 이름 (ToyCarDrawable이 그린다) */
    val characters = listOf(
        "bear" to "곰", "elephant" to "코끼리", "monkey" to "원숭이", "robot" to "로봇", "owl" to "올빼미",
        "santa" to "산타", "smore" to "스모어", "campfire" to "모닥불", "bigfoot" to "빅풋",
    )

    /** 소품 id → 화면 이름. hat/crown/ribbon은 머리 위 한 자리라 하나만 고른다 */
    val props = listOf(
        "hat" to "고깔모자", "crown" to "왕관", "ribbon" to "리본", "balloon" to "풍선", "glasses" to "안경", "flag" to "깃발",
    )
    val headProps = setOf("hat", "crown", "ribbon")

    /** 광장 배경(내 화면에만) id → 이름 */
    val plazas = listOf("default" to "기본", "camping" to "캠핑", "sea" to "바다", "nightsky" to "밤하늘", "cherry" to "벚꽃", "snow" to "눈")

    private val _character = MutableStateFlow<String?>(null)
    private val _items = MutableStateFlow<List<String>>(emptyList())
    private val _plaza = MutableStateFlow("default")
    val character: StateFlow<String?> = _character
    val items2: StateFlow<List<String>> = _items
    val plaza: StateFlow<String> = _plaza

    private var prefs: SharedPreferences? = null
    private val _color = MutableStateFlow(items.first().color)
    private val _hat = MutableStateFlow(false)
    private val _balloon = MutableStateFlow(false)
    val color: StateFlow<Int> = _color
    val hat: StateFlow<Boolean> = _hat
    val balloon: StateFlow<Boolean> = _balloon

    /** 앱을 열 때 한 번(여러 번 불려도 된다) */
    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("kkiri_deco", Context.MODE_PRIVATE)
        prefs = p
        val saved = p.getInt(KEY_COLOR, items.first().color)
        _color.value = if (items.any { it.color == saved }) saved else items.first().color
        _hat.value = p.getBoolean(KEY_HAT, false)
        _balloon.value = p.getBoolean(KEY_BALLOON, false)
        _character.value = p.getString(KEY_CHARACTER, null)?.takeIf { id -> characters.any { it.first == id } }
        val saved2 = p.getString(KEY_ITEMS, null)?.split(',')?.filter { id -> props.any { it.first == id } }
        _items.value = saved2 ?: buildList { if (_hat.value) add("hat"); if (_balloon.value) add("balloon") }
        _plaza.value = p.getString(KEY_PLAZA, "default")?.takeIf { id -> plazas.any { it.first == id } } ?: "default"
    }

    /** R-47로 보낼 소품 목록(hat·balloon 켜기와 같은 값이 들어 있다) */
    fun itemIds(): List<String> = _items.value

    fun saveAll(color: Int, character: String?, items: List<String>, plaza: String) {
        val clean = items.filter { id -> props.any { it.first == id } }.distinct().take(4)
        _color.value = color
        _character.value = character
        _items.value = clean
        _plaza.value = plaza
        _hat.value = "hat" in clean
        _balloon.value = "balloon" in clean
        prefs?.edit()?.putInt(KEY_COLOR, color)?.putString(KEY_CHARACTER, character)?.putString(KEY_ITEMS, clean.joinToString(","))
            ?.putString(KEY_PLAZA, plaza)?.putBoolean(KEY_HAT, "hat" in clean)?.putBoolean(KEY_BALLOON, "balloon" in clean)?.apply()
    }

    fun save(color: Int, hat: Boolean, balloon: Boolean) {
        _color.value = color
        _hat.value = hat
        _balloon.value = balloon
        saveAll(color, _character.value, _items.value.filter { it != "hat" && it != "balloon" } + listOfNotNull("hat".takeIf { hat }, "balloon".takeIf { balloon }), _plaza.value)
    }

    private const val KEY_COLOR = "color"
    private const val KEY_HAT = "hat"
    private const val KEY_BALLOON = "balloon"
    private const val KEY_CHARACTER = "character"
    private const val KEY_ITEMS = "items"
    private const val KEY_PLAZA = "plaza"
}
