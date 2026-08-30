package com.rsplwe.esurfing.fixture

import org.apache.log4j.Appender
import org.apache.log4j.Layout
import org.apache.log4j.spi.ErrorHandler
import org.apache.log4j.spi.Filter
import org.apache.log4j.spi.LoggingEvent
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A log4j 1.x [Appender] that records every rendered message it receives, so
 * tests can assert on what the production loggers actually emitted.
 */
class TestLogAppender : Appender {

    val messages: MutableList<String> = CopyOnWriteArrayList()

    private var layout: Layout? = null
    private var name: String? = "TestLogAppender"
    private var errorHandler: ErrorHandler? = null
    private var filter: Filter? = null

    override fun doAppend(event: LoggingEvent) {
        val rendered = event.message?.toString() ?: ""
        messages.add(rendered)
    }

    override fun close() = Unit

    override fun getLayout(): Layout? = layout
    override fun setLayout(layout: Layout?) {
        this.layout = layout
    }

    override fun getName(): String? = name
    override fun setName(name: String?) {
        this.name = name
    }

    override fun requiresLayout(): Boolean = false

    override fun addFilter(filter: Filter?) {
        this.filter = filter
    }

    override fun clearFilters() {
        this.filter = null
    }

    override fun getFilter(): Filter? = filter

    override fun getErrorHandler(): ErrorHandler? = errorHandler

    override fun setErrorHandler(errorHandler: ErrorHandler?) {
        this.errorHandler = errorHandler
    }
}
