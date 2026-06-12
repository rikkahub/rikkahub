package me.rerere.rikkahub.di

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.HttpHeaders
import io.pebbletemplates.pebble.PebbleEngine
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import io.requery.android.database.sqlite.SQLiteCustomExtension
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.ProviderManager
import me.rerere.common.http.AcceptLanguageBuilder
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.ai.AIRequestInterceptor
import me.rerere.rikkahub.data.ai.RequestLoggingInterceptor
import me.rerere.rikkahub.data.ai.SECRET_HEADER_NAMES
import me.rerere.rikkahub.data.ai.transformers.AssistantTemplateLoader
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.api.RikkaHubAPI
import me.rerere.rikkahub.data.api.SponsorAPI
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.repository.RoomBoardTransactionRunner
import me.rerere.rikkahub.data.repository.TaskBoardRepository
import me.rerere.rikkahub.data.repository.TaskRunRepository
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.db.fts.SimpleDictManager
import me.rerere.rikkahub.data.db.migrations.Migration_6_7
import me.rerere.rikkahub.data.db.migrations.Migration_11_12
import me.rerere.rikkahub.data.db.migrations.Migration_13_14
import me.rerere.rikkahub.data.db.migrations.Migration_14_15
import me.rerere.rikkahub.data.db.migrations.Migration_15_16
import me.rerere.rikkahub.data.db.migrations.Migration_20_21
import me.rerere.rikkahub.data.db.migrations.Migration_21_22
import me.rerere.rikkahub.data.db.migrations.Migration_22_23
import me.rerere.rikkahub.data.db.migrations.Migration_23_24
import me.rerere.rikkahub.data.rag.IngestKnowledgeBaseUseCase
import me.rerere.rikkahub.data.rag.KnowledgeStoreFactory
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import me.rerere.search.SearchService
import me.rerere.rikkahub.data.sync.S3Sync
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.core.qualifier.named
import org.koin.dsl.module
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.Locale
import java.util.concurrent.TimeUnit

val dataSourceModule = module {
    single {
        SettingsStore(context = get(), scope = get())
    }

    single {
        val context: Context = get()
        Room.databaseBuilder(context, AppDatabase::class.java, "rikka_hub")
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(
                Migration_6_7,
                Migration_11_12,
                Migration_13_14,
                Migration_14_15,
                Migration_15_16,
                Migration_20_21,
                Migration_21_22,
                Migration_22_23,
                Migration_23_24,
            )
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    val dictDir = SimpleDictManager.extractDict(context)
                    val cursor = db.query("SELECT jieba_dict(?)", arrayOf(dictDir.absolutePath))
                    cursor.use {
                        if (it.moveToFirst()) {
                            val result = it.getString(0)
                            val success = result?.trimEnd('/') == dictDir.absolutePath.trimEnd('/')
                            if (!success) {
                                android.util.Log.e(
                                    "DataSourceModule",
                                    "jieba_dict failed: $result, path=${dictDir.absolutePath}"
                                )
                            }
                        }
                    }
                    db.execSQL(
                        """
                        CREATE VIRTUAL TABLE IF NOT EXISTS message_fts USING fts5(
                            text,
                            node_id UNINDEXED,
                            message_id UNINDEXED,
                            conversation_id UNINDEXED,
                            title UNINDEXED,
                            update_at UNINDEXED,
                            tokenize = 'simple'
                        )
                        """.trimIndent()
                    )
                }
            })
            .openHelperFactory(
                RequerySQLiteOpenHelperFactory(
                    listOf(
                RequerySQLiteOpenHelperFactory.ConfigurationOptions { options ->
                    options.customExtensions.add(
                        SQLiteCustomExtension(
                            context.applicationInfo.nativeLibraryDir + "/libsimple",
                            null
                        )
                    )
                    options
                }
            )))
            .build()
    }

    single {
        AssistantTemplateLoader(settingsStore = get())
    }

    single {
        PebbleEngine.Builder()
            .loader(get<AssistantTemplateLoader>())
            .defaultLocale(Locale.getDefault())
            .autoEscaping(false)
            .build()
    }

    single { TemplateTransformer(engine = get(), settingsStore = get()) }

    single {
        get<AppDatabase>().conversationDao()
    }

    single {
        get<AppDatabase>().memoryDao()
    }

    single {
        get<AppDatabase>().genMediaDao()
    }

    single {
        get<AppDatabase>().messageNodeDao()
    }

    single {
        get<AppDatabase>().managedFileDao()
    }

    single {
        get<AppDatabase>().favoriteDao()
    }

    single {
        get<AppDatabase>().knowledgeChunkDao()
    }

    single {
        get<AppDatabase>().memoryVectorDao()
    }

    single {
        get<AppDatabase>().workspaceDao()
    }

    single {
        get<AppDatabase>().workItemDao()
    }

    // Per-conversation work-item board (SPEC.md M2/M3). The repository is the SINGLE invariant
    // enforcement point shared by board tools and the board UI (decision #4); a Room transaction
    // wraps every operation so claims are atomic.
    single {
        TaskBoardRepository(
            dao = get(),
            transactions = RoomBoardTransactionRunner(get<AppDatabase>()),
        )
    }

    single {
        get<AppDatabase>().taskRunDao()
    }

    // Summary-only task-run persistence (SPEC.md M2/M4, decision #1). The repository folds every
    // event through the pure TaskStateReducer so the stored state can never disagree with
    // TASK_STATE_LEGAL. Bound as its CONCRETE type so startup recovery + retention (lifecycle
    // operations the coordinator never drives, hence NOT on the narrow TaskRunStore seam) are
    // resolvable; the TaskRunStore binding the coordinator depends on points at the SAME instance.
    single {
        TaskRunRepository(
            dao = get(),
            transactions = RoomBoardTransactionRunner(get<AppDatabase>()),
        )
    }
    single<me.rerere.rikkahub.data.ai.task.TaskRunStore> { get<TaskRunRepository>() }

    // Cold-start recovery + retention composition root (SPEC.md M6, Success Criterion #4). Invoked
    // once from RikkaHubApp.onCreate: marks active task rows Interrupted (no replay) and sweeps
    // expired terminal runs / completed-deleted board items.
    single {
        me.rerere.rikkahub.data.ai.task.TaskRecoveryRunner(
            taskRuns = get(),
            board = get(),
        )
    }

    single {
        KnowledgeStoreFactory(
            providerManager = get(),
            knowledgeChunkDao = get(),
        )
    }

    single {
        IngestKnowledgeBaseUseCase(
            settingsStore = get(),
            storeFactory = get(),
        )
    }

    single {
        MessageFtsManager(get())
    }

    single { McpManager(settingsStore = get(), appScope = get(), filesManager = get()) }

    single {
        GenerationHandler(
            context = get(),
            providerManager = get(),
            json = get(),
            memoryWriter = get(),
            conversationReader = get(),
            modelProviderResolver = get(),
            clock = get(),
            logSink = get(),
            aiLoggingManager = get(),
            // Bound by hooksModule; required by the constructor so this wiring cannot silently
            // regress to a null dispatcher again (#200 review finding 1).
            hookDispatcher = get()
        )
    }

    single<OkHttpClient> {
        val acceptLang = AcceptLanguageBuilder.fromAndroid(get())
            .build()
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(120, TimeUnit.SECONDS)
            .followSslRedirects(true)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val requestBuilder = originalRequest.newBuilder()
                    .addHeader(HttpHeaders.AcceptLanguage, acceptLang)

                if (originalRequest.header(HttpHeaders.UserAgent) == null) {
                    requestBuilder.addHeader(HttpHeaders.UserAgent, "RikkaHub-Android/${BuildConfig.VERSION_NAME}")
                }

                chain.proceed(requestBuilder.build())
            }
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val contentTypeHeader = request.header("Content-Type")
                if (
                    contentTypeHeader != null &&
                    contentTypeHeader.contains(";") &&
                    contentTypeHeader.substringBefore(";").trim().equals("application/json", ignoreCase = true)
                ) {
                    chain.proceed(
                        request.newBuilder()
                            .header("Content-Type", contentTypeHeader.substringBefore(";").trim())
                            .build()
                    )
                } else {
                    chain.proceed(request)
                }
            }
            .addNetworkInterceptor(RequestLoggingInterceptor())
            .addInterceptor(AIRequestInterceptor(remoteConfig = get()))
            // Logcat header logging is debug-only and redacts every credential header:
            // HttpLoggingInterceptor.HEADERS otherwise prints `Authorization: Bearer ...`
            // (and provider API keys) verbatim to logcat — in release builds too —
            // where any process with READ_LOGS or adb can harvest them.
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.HEADERS
                        SECRET_HEADER_NAMES.forEach { redactHeader(it) }
                    })
                }
            }
            .build().also { SearchService.init(it, get()) }
    }

    single {
        SponsorAPI.create(get())
    }

    single<OkHttpClient>(named("stream")) {
        buildStreamClient(get())
    }

    single {
        ProviderManager(
            client = get(),
            context = get(),
            streamClient = get(named("stream")),
        )
    }

    single {
        WebDavSync(
            settingsStore = get(),
            json = get(),
            context = get(),
            httpClient = get()
        )
    }

    single<HttpClient> {
        HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(20, TimeUnit.SECONDS)
                    readTimeout(10, TimeUnit.MINUTES)
                    writeTimeout(120, TimeUnit.SECONDS)
                    followSslRedirects(true)
                    followRedirects(true)
                    retryOnConnectionFailure(true)
                }
            }
        }
    }

    single {
        S3Sync(
            settingsStore = get(),
            json = get(),
            context = get(),
            httpClient = get()
        )
    }

    single<Retrofit> {
        Retrofit.Builder()
            .baseUrl("https://api.rikka-ai.com")
            .addConverterFactory(get<Json>().asConverterFactory("application/json; charset=UTF8".toMediaType()))
            .build()
    }

    single<RikkaHubAPI> {
        get<Retrofit>().create(RikkaHubAPI::class.java)
    }
}

/**
 * 专用流式客户端：从共享 [base] 克隆，针对 SSE 收紧存活检测。
 *
 * readTimeout 设为 600s（10 分钟）：推理模型（如 OpenAI /v1/responses 上的 gpt-5.5-pro）在推理期间
 * 可能传输层完全静默——连续 28s+ 没有任何 SSE 字节，且 Responses API 此时不发心跳——因此把 readTimeout
 * 当作*传输存活*阈值是错的：120s 会误杀一个健康但安静的流。readTimeout 退化为一个宽松的单次 socket 读上限，
 * 死 socket 的快速检测交给下面的 pingInterval。
 * 600s 期间的快速死 socket 检测靠下面的 pingInterval(15s)，但**它只对 HTTP/2 连接有效**：OkHttp 的
 * pingInterval 发送的是 HTTP/2 PING 帧，HTTP/1.1 协议没有 ping 帧，因此在 HTTP/1.1 上它是 no-op。
 * 本 client 未限制 protocols()，会按 ALPN 与各端点协商 HTTP/2 或 HTTP/1.1，且被所有 provider 共享（包含用户
 * 自配的 OpenAI 兼容端点：自建 vLLM/llama.cpp/ollama、明文 http:// 反代、仅 HTTP/1.1 的网关）。因此残留暴露面是：
 * 一条**已建立的 HTTP/1.1 连接在流中途死掉**时，ping 探不到它，要等满 600s readTimeout 才暴露——比 #63 收紧的
 * 120s 慢，UI 最长卡 10 分钟。这是个被接受的权衡：120s 会误杀健康但安静的推理流（本次要修的实际 bug），
 * 而 #63 #1 根因（陈旧连接池复用）已被下面独立的 keepAlive=15s 连接池修复，与协议无关。不把 protocols 钉到
 * HTTP/2 是为了不破坏明文/仅 HTTP/1.1 的自建端点。600s 取自权威先例：OpenAI 官方 SDK 用 600s（openai-python
 * DEFAULT_TIMEOUT，流式同样使用），JetBrains Koog 用 900s socketTimeout 做流式；两者都不用短的流读超时。
 * callTimeout 显式设为 0（不限），让一次完整生成不被整体时长上限误杀——只约束单次 socket 读。
 * 不降低共享 client 的 10 分钟 readTimeout：非流式 generateText/listModels 仍依赖它。
 *
 * 死 socket 卡死的根因有二，仅靠 readTimeout 无法消除：
 *  1. newBuilder() 会继承共享 client 的 ConnectionPool。被中间设备（NAT/代理/LB）静默回收的空闲
 *     连接一旦被 SSE EventSource 复用，响应永远不会到达，只能等 readTimeout 触发才暴露。
 *     给流式客户端一个*独立、短存活*的连接池（keepAlive 15s）可避免复用这类陈旧连接。
 *  2. 缺少 HTTP/2 ping 保活：没有 ping 就无法主动探测死 socket，只能干等 readTimeout。
 *     pingInterval 15s 让 OkHttp 在约 2 个 ping 周期（~30s）内对（HTTP/2 的）死 socket 快速失败，而非卡满 readTimeout。
 */
internal fun buildStreamClient(base: OkHttpClient): OkHttpClient =
    base.newBuilder()
        .readTimeout(600, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(maxIdleConnections = 1, keepAliveDuration = 15, TimeUnit.SECONDS))
        .build()
