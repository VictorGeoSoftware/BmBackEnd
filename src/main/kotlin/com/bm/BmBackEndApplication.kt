package com.bm

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class BmBackEndApplication

fun main(args: Array<String>) {
    runApplication<BmBackEndApplication>(*args)
}
