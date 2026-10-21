## 1. Main thread blocking, offloading, and caching

Move non-UI work off the main thread, avoid heavy object allocations directly
inside Composable bodies, and cache expensive calculations across recompositions
using `remember`.

### 1. Cache expensive computations in composable body

If a Composable body performs sorting, filtering, or heavy string manipulation
on a collection, wrap the calculation in `remember`:

- **Bad (Recalculated on every recomposition pass)**:


  ```kotlin
  @Composable
  fun FilteredFeed(rawList: List<Post>, query: String) {
      // Avoid: Heavy filtering and sorting on every recomposition
      val filteredList = rawList
          .filter { it.title.contains(query) }
          .sortedBy { it.timestamp }
      PostList(posts = filteredList)
  }
  ```

  <br />

- **Optimized (Cached calculation using remember)**:


  ```kotlin
  @Composable
  fun FilteredFeed(rawList: List<Post>, query: String) {
      val filteredList = remember(rawList, query) {
          rawList
              .filter { it.title.contains(query) }
              .sortedBy { it.timestamp }
      }
      PostList(posts = filteredList)
  }
  ```

  <br />

For collections with very few elements (3 to 5 items), wrapping calculations in
`remember` introduces allocation overhead without measurable benefit. Benchmark
caching changes using Macro benchmark.

### 2. Avoid heavy object allocations in composition

Never allocate or compute heavy objects (such as `RoundedPolygon`, complex
paths, `Paint`, or date formatters) directly in the Composable body. Wrap them
in `remember { ... }` or hoist them out of the Composable.

- **Bad (New date formatter allocated on every frame/recomposition)**:


  ```kotlin
  @Composable
  fun DateBadge(timestamp: Long) {
      val formatter = SimpleDateFormat("MMM dd, yyyy", LocalLocale.current.platformLocale)
      Text(text = formatter.format(Date(timestamp)))
  }
  ```

  <br />

- **Optimized (Formatter allocated once and reused across timestamp
  updates)**:


  ```kotlin
  // Reusable formatter allocated once at top-level or remembered
  private val dateFormatter =
      SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())

  @Composable
  fun DateBadge(timestamp: Long) {
      val formattedDate = dateFormatter.format(Date(timestamp))

      Text(text = formattedDate)
  }
  ```

  <br />

Conversion functions like `.toShape()` or `painterResource()` are `@Composable`
themselves and internally handle caching.

### 3. Offload I/O and heavy work to `Dispatchers.IO` with `produceState`

Never perform blocking operations (like database queries, disk I/O, heavy file
parsing, or blocking binder transactions) directly on the main thread. Offload
them to `Dispatchers.IO` using `produceState`:

- **Bad (Blocking I/O on main UI thread)**:


  ```kotlin
  @Composable
  fun ProfileScreen(fileUri: Uri) {
      var state by remember { mutableStateOf<Data?>(null) }
      LaunchedEffect(fileUri) {
          val data = parseJsonFromDisk(fileUri) // Blocks main dispatcher!
          state = data
      }
  }
  ```

  <br />

- **Optimized (Offloaded to IO dispatcher via `produceState`)**:


  ```kotlin
  @Composable
  fun ProfileScreen(fileUri: Uri) {
      val state by produceState<Data?>(initialValue = null, fileUri) {
          value = withContext(Dispatchers.IO) { parseJsonFromDisk(fileUri) }
      }
  }
  ```

  <br />

## 2. Side effect execution and lifecycle rules

Structure side effects to be lifecycle-aware, idempotent, and properly scoped.
Prohibit direct side-effects in the Composable body, prefer `SideEffect` for
non-suspending operations, hoist flow or receiver subscriptions out of item
composables, and always use `collectAsStateWithLifecycle()`.

### 1. Prohibit direct side-effects in composable bodies

The Composable body must remain idempotent and free of direct side effects. Side
effects placed directly in composition run on every recomposition attempt
(including speculative, interrupted, or discarded passes).

- **Bad (Side-effect runs directly in composition pass)**:


  ```kotlin
  @Composable
  fun UserProfile(userId: String, viewModel: ProfileViewModel) {
      // Avoid: Runs on every recomposition and speculative pass
      viewModel.trackProfileImpression(userId)
      Text(text = "User: $userId")
  }
  ```

  <br />

- **Optimized (Wrapped in SideEffect or user event handler)**:


  ```kotlin
  @Composable
  fun UserProfile(userId: String, viewModel: ProfileViewModel) {
      // Compose 1.12+: keyed SideEffect
      SideEffect(userId) {
          viewModel.trackProfileImpression(userId)
      }
      Text(text = "User: $userId")
  }
  ```

  <br />

### 2. Prohibit `LaunchedEffect` for non-suspend operations

Never wrap synchronous, non-suspending operations in `LaunchedEffect`.
`LaunchedEffect` creates a standalone coroutine, allocates coroutine context
machinery, and dispatches to a thread even when no suspension occurs.

- **Bad (Unnecessary coroutine allocation for synchronous call)**:


  ```kotlin
  LaunchedEffect(itemId) {
      analyticsTracker.trackScreenView(itemId) // Non-suspend function!
  }
  ```

  <br />

- **Optimized (Use `SideEffect` for synchronous execution on successful
  composition)**:


  ```kotlin
  // Compose 1.12+ (supports keys directly):
  SideEffect(itemId) {
      analyticsTracker.trackScreenView(itemId)
  }
  ```

  <br />

- **Warning** : Never use `DisposableEffect(keys) { ... onDispose {} }` with an
  empty `onDispose` block to trigger a keyed synchronous effect. Check the
  project's Compose version in `gradle/libs.versions.toml` or
  `build.gradle(.kts)`: use `SideEffect(keys)` on Compose 1.12+, or
  `SideEffect { ... }` with a `remember`ed key check on pre-1.12 versions.

### 3. Lifecycle-aware flow collection (`collectAsStateWithLifecycle`)

In Android UI development, use `collectAsStateWithLifecycle()` when collecting
Kotlin `Flow`s that actively produce background work.
`collectAsStateWithLifecycle` (from
`androidx.lifecycle:lifecycle-runtime-compose`) automatically subscribes and
cancels flow collection based on the Android `Lifecycle` state (defaulting to
`Lifecycle.State.STARTED`), preventing background CPU usage, battery drain,
resource leaks, and unnecessary UI updates when the app or screen is stopped.

- **Performance Nuance \& When to Prefer `collectAsState()`**:

  - **Benchmark Overhead** : `collectAsStateWithLifecycle()` is \~3-4x slower to subscribe and dispatch compared to `collectAsState()` due to Android `Lifecycle` observer registration and lifecycle-aware state machine overhead.
  - **In-Memory \& Scoped StateFlows** : For lightweight, in-memory `StateFlow`s (such as UI states emitted by a ViewModel already scoped with `SharingStarted.WhileSubscribed(5000)`), `collectAsState()` is often more performant and avoids this overhead.
  - **When to Require `collectAsStateWithLifecycle()`** : Reserve `collectAsStateWithLifecycle()` for cold flows, database observation streams, sensors, location updates, or external polling streams that must halt emission when the screen is not visible.
  - **Non-Android platforms** : In Compose Multiplatform (Desktop, Web, iOS) where the Android `Lifecycle` library is not present, use `collectAsState()`.
  - **Headless / Unit Test environments** : In non-UI scopes or pure unit tests where no `LifecycleOwner` is available.
- **Bad (Continues collecting in background on Android, leaking resources and
  CPU cycles)**:


  ```kotlin
  @Composable
  fun HomeFeed(viewModel: FeedViewModel) {
      // Avoid on Android: Flow stays active when activity is stopped
      val uiState by viewModel.feedState.collectAsState()
      FeedContent(uiState = uiState)
  }
  ```

  <br />

- **Optimized (Lifecycle-aware: pauses collection when backgrounded)**:


  ```kotlin
  // import androidx.lifecycle.compose.collectAsStateWithLifecycle

  @Composable
  fun HomeFeed(viewModel: FeedViewModel) {
      val uiState by viewModel.feedState.collectAsStateWithLifecycle()
      FeedContent(uiState = uiState)
  }
  ```

  <br />

### 4. Hoist `BroadcastReceiver`s and listeners to prevent per-item registration

Never register a `BroadcastReceiver` or system listener (such as
`ACTION_TIMEZONE_CHANGED` or network connectivity changes) inside list item
Composables. This registers a separate receiver for every visible item on the
main thread, causing severe scrolling latency, memory leaks, and frame drops.

Register a single receiver in a parent or screen-level Composable using
`DisposableEffect`, and propagate the state down using parameters or
`CompositionLocalProvider`.

- **Bad (Registers a separate BroadcastReceiver for every visible item in
  LazyColumn)**:


  ```kotlin
  @Composable
  fun TimeZoneListItem(timeZoneId: String) {
      val context = LocalContext.current
      DisposableEffect(Unit) {
          // Avoid: Multiplied BroadcastReceivers per visible item!
          val receiver = object : BroadcastReceiver() {
              // ...
          }
          context.registerReceiver(
              receiver,
              IntentFilter(Intent.ACTION_TIMEZONE_CHANGED),
          )
          onDispose { context.unregisterReceiver(receiver) }
      }
      ItemRow(timeZoneId)
  }
  ```

  <br />

- **Optimized (Single BroadcastReceiver hoisted to parent container)**:


  ```kotlin
  @Composable
  fun TimeZoneList(timeZoneIds: List<String>) {
      val context = LocalContext.current
      var currentTimeZone by remember {
          mutableStateOf(TimeZone.getDefault())
      }

      DisposableEffect(context) {
          val receiver = object : BroadcastReceiver() {
              override fun onReceive(c: Context?, intent: Intent?) {
                  currentTimeZone = TimeZone.getDefault()
              }
          }
          context.registerReceiver(
              receiver,
              IntentFilter(Intent.ACTION_TIMEZONE_CHANGED),
          )
          onDispose { context.unregisterReceiver(receiver) }
      }

      LazyColumn {
          items(timeZoneIds, key = { it }) { id ->
              TimeZoneListItem(
                  timeZoneId = id,
                  currentTimeZone = currentTimeZone,
              )
          }
      }
  }
  ```

  <br />

### 5. Guard optional effects and match effect type to work

Never spawn `LaunchedEffect` or `DisposableEffect` unconditionally if optional
callbacks or trigger handlers are `null`. Guard the effect with `if (callback !=
null)` to avoid allocating unnecessary coroutine jobs and snapshot effect
tracking structures. Furthermore, ensure the effect API matches the execution
type:

- **Synchronous Callbacks (`() -> Unit`)** : Use `SideEffect` or keyed `SideEffect(onInit)` rather than launching a coroutine job.
- **Suspending Callbacks (`suspend () -> Unit`)** : Use
  `LaunchedEffect(onInit)` guarded by `if (onInit != null)`.

- **Bad (Spawns empty coroutine and effect nodes when callback is null or
  synchronous)**:


  ```kotlin
  // Avoid: Launches coroutine for synchronous work, runs even if null
  LaunchedEffect(onInit) {
      onInit?.invoke()
  }
  ```

  <br />

- **Optimized (Synchronous work uses SideEffect; guarded if optional)**:


  ```kotlin
  if (onInit != null) {
      SideEffect(onInit) {
          onInit()
      }
  }
  ```

  <br />

### 6. Prevent stale captures with `rememberUpdatedState`

If a long-running, periodic, or looping coroutine inside `LaunchedEffect`
references a Composable parameter or callback, use `rememberUpdatedState` to
ensure it always uses the latest value without restarting the effect:

- **Bad (Stale capture if onTick parameter changes during loop lifetime)**:


  ```kotlin
  @Composable
  fun PeriodicTicker(intervalMs: Long, onTick: () -> Unit) {
      LaunchedEffect(intervalMs) {
          while (isActive) {
              delay(intervalMs)
              onTick() // Stale reference if onTick callback instance changes!
          }
      }
  }
  ```

  <br />

- **Optimized (rememberUpdatedState provides latest callback without
  cancelling timer loop)**:


  ```kotlin
  @Composable
  fun PeriodicTicker(intervalMs: Long, onTick: () -> Unit) {
      val currentOnTick by rememberUpdatedState(onTick)
      LaunchedEffect(intervalMs) {
          while (isActive) {
              delay(intervalMs)
              currentOnTick()
          }
      }
  }
  ```

  <br />

### 7. Replace legacy timers with coroutine delays

Avoid using legacy Android handlers like `Handler.postDelayed` or `TimerTask` in
UI code. Use `LaunchedEffect` combined with `delay()` for clean, lifecycle-aware
timing that is automatically cancelled when the Composable leaves the
composition.

- **Bad (Legacy Handler risks memory leak and out-of-lifecycle execution)**:


  ```kotlin
  DisposableEffect(Unit) {
      val handler = Handler(Looper.getMainLooper())
      val runnable = Runnable { showBanner = false }
      handler.postDelayed(runnable, 3000L)
      onDispose { handler.removeCallbacks(runnable) }
  }
  ```

  <br />

- **Optimized (Clean, lifecycle-aware coroutine delay)**:


  ```kotlin
  LaunchedEffect(Unit) {
      delay(3000L)
      showBanner = false
  }
  ```

  <br />

### 8. Combine related `LaunchedEffect`s (reduce number of effects)

Every `LaunchedEffect` attached to a composable allocates slot table records,
registers coroutine launcher machinery, and requires cancellation tracking
during teardown.

When multiple asynchronous tasks share the same trigger key (such as `Unit` on
initial screen load), combine them into a single `LaunchedEffect` block with
nested `launch { ... }` calls instead of declaring multiple standalone
`LaunchedEffect`s.

- **Bad (Multiple separate LaunchedEffects incur redundant effect overhead)**:


  ```kotlin
  @Composable
  fun ShoppingCartScreen(viewModel: CartViewModel) {
      // Avoid: 3 LaunchedEffects allocate 3 effect nodes and launchers
      LaunchedEffect(Unit) { viewModel.loadCart() }
      LaunchedEffect(Unit) { viewModel.loadPaymentMethods() }
      LaunchedEffect(Unit) { viewModel.loadDeliveryAddresses() }

      CartContent()
  }
  ```

  <br />

- **Optimized (Single LaunchedEffect manages multiple concurrent
  coroutines)**:


  ```kotlin
  @Composable
  fun ShoppingCartScreen(viewModel: CartViewModel) {
      // Optimized: 1 LaunchedEffect node launches concurrent jobs
      LaunchedEffect(Unit) {
          launch { viewModel.loadCart() }
          launch { viewModel.loadPaymentMethods() }
          launch { viewModel.loadDeliveryAddresses() }
      }

      CartContent()
  }
  ```

  <br />

### 9. Effect execution ordering and teardown costs

Understanding the execution lifecycle and performance costs of Compose effects
is critical when choosing between them:

#### A. Execution order relative to frame rendering

Compose executes effects in a strictly defined order across frame phases:

1. **`DisposableEffect`**: Executes first, immediately after composition changes are applied, in the exact order declared in the composable.
2. **`SideEffect`** : Executes second, after all `DisposableEffect`s have run, before layout and drawing occur.
3. **Layout \& Draw**: The UI hierarchy measures, places, and draws pixels onto the screen.
4. **`LaunchedEffect`** : Executes **after Draw at the end of the frame**, scheduled on the coroutine dispatcher.

> **Warning:**
> If your code implicitly relies on `LaunchedEffect` running after the frame has
> rendered (e.g., waiting for layout bounds to be drawn), migrating to
> `SideEffect` will execute *before* Layout and Draw. Always verify execution
> order dependencies when refactoring effects.

#### B. Effect allocation and teardown costs

Effects have substantially different overheads:

- **`SideEffect`**: Cheapest effect; executes synchronously on every successful composition pass without lifecycle registration or coroutine allocation.
- **`DisposableEffect`** : Moderate overhead; registers callbacks and runs `onDispose` synchronously upon key change or exit from composition.
- **`LaunchedEffect`**: Highest overhead; requires allocating a coroutine launcher, launching a new coroutine job on the composition dispatcher, and canceling or tearing down the coroutine job when keys change or the composable exits.

Because canceling a `LaunchedEffect` incurs coroutine job cancellation and
teardown overhead, avoid cycling `LaunchedEffect` keys frequently during
animations or fast scrolling. For synchronous keyed triggers without teardown,
always prefer keyed `SideEffect(keys)` over `LaunchedEffect(keys)`.

## 3. Side effects API selection guide

Choose the correct Side Effect API based on the execution context and lifecycle
requirements:

| Use Case | Recommended API | Example |
|---|---|---|
| **Coroutine tied to Composable lifecycle** | `LaunchedEffect(keys)` | Triggering network calls or animations when an ID changes |
| **Synchronous cleanup on exit** | `DisposableEffect(keys)` | Registering and unregistering lifecycle listeners or observers |
| **Launch coroutine from UI callback** | `rememberCoroutineScope()` | Triggering a scroll animation on button click or swipe |
| **Convert Compose State into Flow** | `snapshotFlow { state }` | Debouncing search queries derived from text field state |
| **Synchronous keyed triggers** | `SideEffect` or `SideEffect(keys)` | Updating external imperatively managed views or non-Compose state |
| **Viewport visibility or impression tracking** | `Modifier.onVisibilityChanged` | Sending analytics or : logging impressions when an item enters the viewport \| |