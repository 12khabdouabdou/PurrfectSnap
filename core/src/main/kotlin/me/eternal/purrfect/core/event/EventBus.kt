package me.eternal.purrfect.core.event

import me.eternal.purrfect.core.ModContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass

abstract class Event {
    lateinit var context: ModContext
    var canceled = false
}

interface IListener<T> {
    fun handle(event: T)
}

class Subscription(
    val priority: Int,
    val listener: IListener<out Event>
) : Comparable<Subscription> {
    override fun compareTo(other: Subscription): Int {
        return priority.compareTo(other.priority)
    }
}

class EventBus(
    val context: ModContext
) {
    private val subscribers = ConcurrentHashMap<KClass<out Event>, CopyOnWriteArrayList<Subscription>>()

    fun <T : Event> subscribe(event: KClass<T>, listener: IListener<T>, priority: Int? = null) {
        synchronized(subscribers) {
            val list = subscribers.getOrPut(event) { CopyOnWriteArrayList() }
            val nextPriority = priority ?: ((list.lastOrNull()?.priority ?: 0) + 1)
            val sorted = (list + Subscription(nextPriority, listener)).sortedBy { it.priority }
            
            // Atomically replace list reference to prevent empty list reads on concurrent posts
            subscribers[event] = CopyOnWriteArrayList(sorted)
        }
    }

    fun <T : Event> subscribe(event: KClass<T>, priority: Int? = null, listener: (T) -> Unit) = subscribe(event, { true }, priority, listener)

    fun <T : Event> subscribe(event: KClass<T>, filter: (T) -> Boolean, priority: Int? = null, listener: (T) -> Unit): () -> Unit {
        val obj = object : IListener<T> {
            override fun handle(event: T) {
                if (!filter(event)) return
                runCatching {
                    listener(event)
                }.onFailure {
                    context.log.error("Error while handling event ${event::class.simpleName}", it)
                }
            }
        }
        subscribe(event, obj, priority)
        return { unsubscribe(event, obj) }
    }

    fun <T : Event> unsubscribe(event: KClass<T>, listener: IListener<T>) {
        synchronized(subscribers) {
            val list = subscribers[event] ?: return
            val updated = list.filter { it.listener != listener }
            subscribers[event] = CopyOnWriteArrayList(updated)
        }
    }

    fun <T : Event> post(event: T, afterBlock: T.() -> Unit = {}): T? {
        val list = subscribers[event::class] ?: return null
        event.context = context

        list.forEach { subscription ->
            @Suppress("UNCHECKED_CAST")
            runCatching {
                (subscription.listener as IListener<T>).handle(event)
            }.onFailure { t ->
                context.log.error("Error while handling event ${event::class.simpleName} by ${subscription.listener::class.simpleName}", t)
            }
        }
        afterBlock(event)
        return event
    }

    fun clear() {
        subscribers.clear()
    }
}