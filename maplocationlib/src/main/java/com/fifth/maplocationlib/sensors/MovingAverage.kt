package com.fifth.maplocationlib.sensors

import java.util.LinkedList
import java.util.Queue

class MovingAverage constructor(period : Int){
    private var window : Queue<Double> = LinkedList()
    private val period : Int = if (period > 0) period else 0
    private var sum : Double = 0.0

    fun newData(data: Float) {
        sum += data
        window.add(data.toDouble())
        if (window.size > period)
            sum -= window.poll()
    }

    fun getAvg() : Double {
        if (window.isEmpty())
            return 0.0
        else
            return sum / window.size
    }

    fun reset() {
        window.clear()
        sum = 0.0
    }
}