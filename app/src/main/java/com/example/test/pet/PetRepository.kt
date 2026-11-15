package com.example.test.pet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/** Keeps the pet between app launches. */
class PetRepository(context: Context) {

    private val prefs = context.getSharedPreferences("pet", Context.MODE_PRIVATE)
    private val photoFile = File(context.filesDir, "pet_photo.jpg")

    /** Fur colors taken from the owner's photo, null when the model's own colors are used. */
    var furColors: FurColors?
        get() = if (prefs.contains("furMain")) FurColors(prefs.getInt("furMain", 0), prefs.getInt("furLight", 0)) else null
        set(value) {
            val e = prefs.edit()
            if (value == null) e.remove("furMain").remove("furLight")
            else e.putInt("furMain", value.main).putInt("furLight", value.light)
            e.apply()
        }

    /** The name the owner gave the dog; null until named. */
    var dogName: String?
        get() = prefs.getString("dogName", null)
        set(value) = prefs.edit().putString("dogName", value).apply()

    fun loadPhoto(): Bitmap? = if (photoFile.exists()) BitmapFactory.decodeFile(photoFile.path) else null

    fun savePhoto(photo: Bitmap?) {
        if (photo == null) photoFile.delete()
        else photoFile.outputStream().use { photo.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }

    fun load(): PetState {
        val d = PetState()
        return PetState(
            fullness = prefs.getFloat("fullness", d.fullness),
            energy = prefs.getFloat("energy", d.energy),
            happiness = prefs.getFloat("happiness", d.happiness),
            safeKm = prefs.getFloat("safeKm", d.safeKm),
            snacks = prefs.getInt("snacks", d.snacks),
            snackProgressKm = prefs.getFloat("snackProgressKm", d.snackProgressKm),
            mealsToday = prefs.getInt("mealsToday", d.mealsToday),
            mealDay = prefs.getLong("mealDay", d.mealDay),
            smoothSec = prefs.getFloat("smoothSec", d.smoothSec),
        )
    }

    fun loadBreed(): Breed =
        Breed.entries.firstOrNull { it.name == prefs.getString("breed", null) } ?: Breed.SHIBA

    fun saveBreed(breed: Breed) {
        prefs.edit().putString("breed", breed.name).apply()
    }

    /** The day (epoch day) the dinner card was last shown, so it pops up once a day. */
    var dinnerDay: Long
        get() = prefs.getLong("dinnerDay", -1L)
        set(value) = prefs.edit().putLong("dinnerDay", value).apply()

    fun save(s: PetState) {
        prefs.edit()
            .putFloat("fullness", s.fullness)
            .putFloat("energy", s.energy)
            .putFloat("happiness", s.happiness)
            .putFloat("safeKm", s.safeKm)
            .putInt("snacks", s.snacks)
            .putFloat("snackProgressKm", s.snackProgressKm)
            .putInt("mealsToday", s.mealsToday)
            .putLong("mealDay", s.mealDay)
            .putFloat("smoothSec", s.smoothSec)
            .apply()
    }
}
