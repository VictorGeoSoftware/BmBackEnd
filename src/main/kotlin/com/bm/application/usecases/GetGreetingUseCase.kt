package com.bm.application.usecases

import com.bm.domain.ports.GreetingPort
import org.springframework.stereotype.Service

@Service
class GetGreetingUseCase(
    private val greetingPort: GreetingPort
) {
    operator fun invoke(): String = greetingPort.getGreeting()
}
