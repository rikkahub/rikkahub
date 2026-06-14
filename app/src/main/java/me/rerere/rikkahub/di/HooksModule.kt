package me.rerere.rikkahub.di

import me.rerere.ai.runtime.hooks.HookDispatcher
import me.rerere.ai.runtime.hooks.StaticHookExecutor
import me.rerere.rikkahub.data.ai.hooks.HookExecutorRegistry
import me.rerere.rikkahub.data.ai.hooks.HookSettingsReader
import me.rerere.rikkahub.data.ai.hooks.LlmHookExecutor
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.koin.dsl.module

/**
 * Event-hooks composition root (#200 v1): binds the `llm` handler executor into the registry and
 * exposes the [HookDispatcher] the agent loop consumes. Handler-type bindings live ONLY here —
 * the dispatcher and the loop stay closed to v2 handler additions (DIP).
 */
val hooksModule = module {
    single<HookSettingsReader> {
        val settingsStore = get<SettingsStore>()
        HookSettingsReader { settingsStore.settingsFlow.value }
    }

    single {
        LlmHookExecutor(
            settings = get(),
            providerManager = get(),
        )
    }

    single {
        StaticHookExecutor()
    }

    single {
        HookExecutorRegistry(llm = get(), static = get())
    }

    single {
        HookDispatcher(
            executors = get<HookExecutorRegistry>().executors,
            logSink = get(),
        )
    }
}
