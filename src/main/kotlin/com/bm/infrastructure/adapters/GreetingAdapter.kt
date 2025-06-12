package com.bm.infrastructure.adapters

import com.bm.domain.ports.GreetingPort
import org.springframework.stereotype.Component

@Component
class GreetingAdapter : GreetingPort {
    override fun getGreeting(): String = "Hello, World!"
}
