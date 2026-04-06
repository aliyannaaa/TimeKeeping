package com.example.yoshiitimekeeping.database

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object ApiClient {
    // Android emulator to local machine: 10.0.2.2
    private const val BASE_URL = "http://10.0.2.2:3000/"

    val api: TimekeeperApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TimekeeperApi::class.java)
    }

    fun repository(): TimekeeperRepository = TimekeeperRepository(api)
}
