package com.bm.interfaces.controllers

import com.bm.application.usecases.GetGreetingUseCase
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class GreetingController(
    private val getGreetingUseCase: GetGreetingUseCase
) {
    @GetMapping("/greeting")
    fun getGreeting(): String = getGreetingUseCase()
}
